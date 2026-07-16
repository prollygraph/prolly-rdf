---
tags:
  - storage
  - rdf
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/anatomy/A1-a-scan.md; links adapted to this repo's layout -->

# Anatomy of a scan

*From `?s ?p ?o` to a stream of decoded RDF statements — and the 50× bug found
on the way.*

> **What you'll learn** — how `RocksDbFlatSail` answers a triple-pattern query:
> how it picks one of four indexes, turns the bound terms into a byte-prefix
> seek, decodes each key, and resolves the integer term IDs back into RDF
> values. Following that path also explains the project's dictionary-encoding
> design and a real performance defect that lived on it.
>
> _Reading time: ~10 minutes._
> _Prerequisites: [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md),
> [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)._

## 0 · The problem

A client runs the simplest SPARQL query there is — every statement in the
store:

```java
var query = conn.prepareTupleQuery("SELECT * WHERE { ?s ?p ?o }");
try (var result = query.evaluate()) { /* ... */ }
```

RDF4J turns each basic graph pattern into a call on the Sail:

```java
getStatementsInternal(Resource subj, IRI pred, Value obj,
                      boolean includeInferred, Resource... contexts)
```

Here every position is unbound (`subj`, `pred`, `obj` all `null`) — a full
scan. A pattern like `{ ?s rdf:type ?o }` would bind `pred`. Either way, the
job is the same: stream back the matching statements. Follow the unbound case;
the bound cases are the same path with a longer seek prefix.

## 1 · Picking the index

The flat Sail stores every quad **four times**, once per permutation index —
`SPOC`, `POSC`, `OSPC`, `CSPO`. Each is a RocksDB column family of fixed
**32-byte keys** (four 8-byte `TermId`s) with empty values. Four orderings mean
*any* pattern of bound terms can become a leading key prefix.

`FlatIndexSelector` picks the cheapest one:

```java
FlatIndexSelector.Choice choice = FlatIndexSelector.choose(s, p, o, singleContext);
byte[] prefix = FlatKeyCodec.prefix(choice.prefixTerms());
```

It evaluates all four orders and returns the index whose **leading physical
columns cover the longest unbroken run of bound terms** — that run becomes the
seek prefix.

> **Key idea** — the index is chosen so the bound terms are a *prefix* of the
> key. A bound subject+object scans `OSPC` (`[o, s, …]`); fully bound is an
> exact-key lookup; fully unbound — our case — yields a zero-length prefix and
> a full column-family scan.

## 2 · The range scan

The chosen column family is opened as a `RocksIterator`, wrapped to respect any
open transaction:

```java
private RocksIterator indexIterator(ColumnFamilyHandle cf) {
    RocksIterator base = store.db().newIterator(cf);
    return (txBatch != null) ? txBatch.newIteratorWithBase(cf, base) : base;
}
```

`newIteratorWithBase` overlays the transaction's uncommitted writes on the
committed RocksDB state — that is how a connection reads its own writes. The
iterator is handed to `FlatStatementIteration`, which decodes lazily, one row
per `next()`:

```java
if (prefix.length == 0) {
    iterator.seekToFirst();
} else {
    iterator.seek(prefix);
}
while (iterator.isValid()) {
    byte[] key = iterator.key();
    if (!startsWith(key, prefix)) {
        break;   // left the prefix range — scan done
    }
    iterator.next();
    // ... decode ...
}
```

> **Key idea** — the scan costs **O(P)**, where P is the number of keys under
> the chosen prefix — not O(N) over the whole store. A fully-bound pattern
> seeks straight to one key; an unbound one walks the lot. The `startsWith`
> check is the *only* stop condition: once a key leaves the prefix, the
> contiguous range is exhausted.

## 3 · Decoding a key

Each 32-byte key is four `TermId`s in *physical* order. `FlatKeyCodec.decode`
splits it; `role.col(...)` maps physical columns back to logical
subject/predicate/object/context — undoing the permutation:

```java
SpocKey physical = FlatKeyCodec.decode(key);
TermId s = role.col(physical, 0);
TermId p = role.col(physical, 1);
TermId o = role.col(physical, 2);
TermId c = role.col(physical, 3);
```

Any term bound in the pattern but *not* covered by the seek prefix is
re-checked here as a per-row filter (`subjectFilter`, `predicateFilter`, …), so
the result is exact even when the index only partially covered the pattern.

## 4 · Resolving terms back to values

At this point a matching row is four 8-byte integers. The client wants RDF
`Value`s — IRIs, literals, blank nodes. `buildStatement` resolves them through
the dictionary:

```java
TermId[] ids = defaultGraph
    ? new TermId[] {s, p, o}
    : new TermId[] {s, p, o, c};
Value[] values = dictionary.lookupAll(ids, txBatch);
```

`lookupAll` checks an in-memory cache first, then fetches every miss in **one**
RocksDB `multiGet`:

```java
List<byte[]> terms = db.multiGetAsList(cfs, keys);   // one JNI round-trip
```

The decoded values are validated (`required(...)` — a missing term means
index/dictionary corruption) and assembled into a `Statement`. That statement
is what `next()` returns.

## 5 · The bug that lived here

This last step is where the path once went badly wrong.

> **The bug** — the original `buildStatement` resolved each term with its own
> uncached `db.get`. A 20,000-triple full scan therefore issued ~60,000
> single-key RocksDB point reads — one Java Native Interface call each — to re-resolve the *same*
> few hundred predicate and vocabulary IRIs over and over. The full scan ran
> ~237 ms when it should have run in single digits: roughly **50× too slow**,
> and the slowness scaled with the result size.

The fix has two parts, both visible above:

1. **A bounded LRU cache.** `FlatDictionary` keeps a shared, 100,000-entry
   `TermId → Value` cache. It needs no invalidation — the dictionary is
   *append-only*, so a `TermId`'s term never changes:

   ```java
   private final Map<TermId, Value> termCache = Collections.synchronizedMap(
       new LinkedHashMap<TermId, Value>(1024, 0.75f, true) {
           @Override
           protected boolean removeEldestEntry(Map.Entry<TermId, Value> eldest) {
               return size() > MAX_CACHED_TERMS;
           }
       });
   ```

2. **Batched cold misses.** Whatever the cache misses, `lookupAll` fetches in a
   single `multiGet` — one Java Native Interface round-trip per *statement*, not per term.

A *warm* scan now resolves every term from memory and touches RocksDB zero
times for the dictionary. Measured result: the full scan went **236.8 ms →
6.3 ms (~37×)**; a predicate scan ~35×.

> **Trade-off** — the cache is bounded (100k entries) and shared across
> connections, so a pathologically term-diverse workload can still miss. That
> is deliberate: a bound on memory beats an unbounded cache, and the cold path
> is still one batched `multiGet`.

## Takeaways

- A scan is **index choice → prefix seek → per-row decode → term resolution**.
  The first three are O(P); the fourth is where the cost hid.
- Four permutation indexes exist so any bound-term pattern becomes a key
  *prefix* — that is what turns a query into a range scan.
- Keys store integer `TermId`s, not strings; the dictionary is the bridge back
  to RDF `Value`s, and it is on the hot path of *every* result row.
- "Correct but slow" is still a bug. The 50× defect passed every functional
  test — only a benchmark caught it. An append-only structure makes an
  invalidation-free cache safe; batching turns N point reads into one.

## Where this lives

- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/RocksDbFlatSailConnection.java`
  — `getStatementsInternal`, `FlatStatementIteration`, `buildStatement`
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatIndexSelector.java`
  — index selection
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatKeyCodec.java`
  — key encode/decode
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatDictionary.java`
  — the term cache and `lookupAll`
- Foundations assumed:
  [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md),
  [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)
- Continues in: [A2 · a term](A2-a-term.md) — how a `Value` becomes a `TermId`
  in the first place.
