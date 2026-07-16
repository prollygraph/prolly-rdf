
# prolly-flatsail

`RocksDbFlatSail` — an **unversioned** RDF4J `Sail` storing quads as plain
sorted RocksDB keys. The fast, simple sibling of the versioned `ProllySail`
(`prolly-rdf4j`): no Merkle tree, no history, branching, diff or time-travel —
for high-churn data that needs none of that.

## Design at a glance

- **Dictionary-encoded.** RDF terms ↔ 8-byte `TermId`s; each index key is a
  fixed **32-byte** 4×TermId permutation with an empty value.
- **Seven column families** in one RocksDB instance — `dict-fwd`, `dict-rev`,
  the four permutation indexes `spoc`/`posc`/`ospc`/`cspo`, and `ns`
  (namespaces).
- **Transactions** buffer mutations into a RocksDB `WriteBatchWithIndex`;
  `commit()` is one atomic `db.write` with the WAL on. The indexed batch gives
  a connection read-your-writes — see below.
- **Query** has no pushdown — RDF4J's `DefaultEvaluationStrategy` drives BGPs
  over `getStatements`, which selects the permutation index whose leading
  columns best cover the bound terms.
- **Single-writer.** A fair semaphore serializes write transactions; readers
  never take it, so reads stay fully concurrent.

## Usage

`RocksDbFlatSail` is an ordinary RDF4J `Sail` — wrap it in a `SailRepository`
and use the standard RDF4J API:

```java
import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

var repo = new SailRepository(new RocksDbFlatSail(Path.of("/data/flatsail")));
repo.init();
try (RepositoryConnection conn = repo.getConnection()) {
    conn.add(alice, knows, bob);                       // add quads
    var query = conn.prepareTupleQuery("SELECT * WHERE { ?s ?p ?o }");
    try (var result = query.evaluate()) { /* ... */ }  // SPARQL
}
repo.shutDown();   // closes the RocksDB instance the Sail owns
```

The Sail opens (and owns) its RocksDB directory on `init()` and closes it on
`shutDown()`. For a complete, runnable walk-through — load, count, SPARQL
`SELECT`, a join, a `GRAPH` clause — see
[`FlatSailDemo`](src/test/java/com/earasoft/prolly/flatsail/examples/FlatSailDemo.java).

## Status

three implementation phases (the plan lives in the private monorepo's work tracker)
are essentially complete: the Sail is a functional unversioned RDF4J store —
add/remove, transactions, range-scan reads, namespaces, `size`/`clear`/
`getContextIDs`, SPARQL via a `SailRepository`. It passes RDF4J's `RDFStoreTest`
SPI contract suite 38/40 (the two `@Disabled` cases are an RDF-star
`Triple`-as-context timing quirk and a pre-set-query-bindings issue that
`ProllySail` baselines identically), with crash-safety and concurrency tests
and a `SailComparisonBenchmark` variant.

## Complexity of operations

Let **N** = total quads in the store, **P** = quads under a scan's chosen key
prefix, **B** = entries buffered in the open transaction.

| Operation | Cost | Notes |
|---|---|---|
| `addStatement` | **O(1)** | intern ≤4 terms (dictionary point reads) + 4 index puts |
| `removeStatement` (s, p, o + explicit context) | **O(1)** | fully bound → direct 4-key delete, no scan |
| `removeStatement` (any wildcard) | **O(N)** | full SPOC scan + per-match 4-key delete |
| `getStatements(pattern)` | **O(P)** | index selection makes the bound terms a key prefix; exact quad → O(1), fully unbound → O(N) |
| `size()` | **O(N)** | counts every key in one index |
| `size(context)` | **O(P)** | counts the CSPO prefix for that context |
| `getContextIDs()` | **O(N)** | scans CSPO, de-dups the context column |
| `clear()` / `clear(context)` | **O(N)** | scan-and-delete — see below |
| `setNamespace` / `getNamespace` / `removeNamespace` | **O(1)** | a single `ns` key |
| `commit` | **O(B)** | one atomic `db.write` of the batch |

`clear()` is **O(N), not O(1)**. A RocksDB `deleteRange` would clear an index
in one O(1) range tombstone, but `WriteBatchWithIndex` (the indexed transaction
buffer that gives read-your-writes) **does not support `deleteRange`** — and an
indexed batch is the firmer requirement. So `clear()` falls back to a
scan-and-delete: enumerate the matching quads and delete each from all four
indexes. `clear()` is rare; this trade is deliberate.

**Read-path term cache.** Every statement a scan returns must resolve its
TermIds back to RDF Values. `FlatDictionary` keeps a bounded (100,000-entry)
`TermId → Value` LRU cache for this — the dictionary is append-only, so cached
entries never go stale. A *warm* scan resolves terms from memory; a *cold*
scan batches each statement's term resolutions into one RocksDB `multiGet`
(`FlatDictionary.lookupAll`) — one JNI round-trip per statement, not one per
term. The scan costs above are warm-cache figures. (Without the cache a scan
re-read the dictionary once per term per statement — measurably ~35× slower;
see the benchmark report.)

**Versus the siblings.** The flat Sail's writes are O(1) RocksDB LSM puts; the
versioned `ProllySail` instead rebuilds a content-addressed Merkle-tree path on
every commit (≈O(log N) node rebuilds + hashing) — that is the price of
history/branch/diff. RDF4J's `NativeStore` is also unversioned but B-tree-based
(O(log N) point operations). For measured numbers, run
`JmhRunner SailComparison` — the benchmark has `prolly`, `flatsail` and
`rdf4j-native` variants.

## Transactions and read-your-writes

A transaction buffers its mutations into a RocksDB **`WriteBatchWithIndex`** —
a write batch that also maintains an index over its own pending entries.
Because that index is queryable, every read on the connection merges the open
transaction's uncommitted writes over committed RocksDB state:

- **point reads** (`getNamespace`, the dictionary's `find`/`lookup`) go through
  `WriteBatchWithIndex.getFromBatchAndDB`;
- **scans** (`getStatements`, `size`, `getContextIDs`, `getNamespaces`, the
  remove/clear enumeration) go through `newIteratorWithBase`, which overlays
  the batch on a base RocksDB iterator.

So a connection always sees its own uncommitted writes — the read-your-writes
behaviour the RDF4J `SailConnection` contract requires. `commit()` applies the
batch atomically; `rollback()` discards it; outside a transaction, reads go
straight to committed state.

**Implementation note — `AbstractWriteBatch`.** `FlatDictionary.intern` takes
its batch parameter as `AbstractWriteBatch`, the common supertype of
`WriteBatch` and `WriteBatchWithIndex`. The connection passes the live
`WriteBatchWithIndex`; `FlatDictionary` unit tests keep passing a plain
`WriteBatch`. Widening to the supertype let the connection adopt the indexed
batch with no churn to the dictionary's tests.

## Not optimized for bulk ingest

This Sail does correct, transactional writes — it is **not** a bulk loader.
For large one-shot loads, expect modest throughput and watch heap use:

- **WAL stays on.** The project deliberately chose "WriteBatch, keep WAL"
  (durability-neutral). Real bulk loaders typically disable the WAL or bypass
  it entirely.
- **No SST bulk-load path.** The fast RocksDB bulk mechanism —
  `SstFileWriter` + `ingestExternalFile`, building sorted SST files offline and
  atomically linking them in — isn't implemented. The Sail only does normal
  transactional puts.
- **No bulk-tuned RocksDB options.** `RocksFlatStore` opens with default
  `ColumnFamilyOptions` — no large write buffers, no disabled auto-compaction
  during load, no `prepareForBulkLoad`.
- **One transaction = one in-memory `WriteBatchWithIndex`.** A single giant
  load transaction balloons heap, since the indexed batch isn't flushed until
  `commit()`. Bulk loading needs chunked commits.

A practical bulk load today: split the input into batches of a bounded size and
`commit()` each as its own transaction, keeping any one batch small.
