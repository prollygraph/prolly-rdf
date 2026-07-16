---
tags:
  - rdf
  - format
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/anatomy/A2-a-term.md; links adapted to this repo's layout -->

# Anatomy of a term

*From an RDF `Value` to an 8-byte `TermId` — how the dictionary makes quads
small.*

> **What you'll learn** — why both Sails store integer IDs instead of RDF
> strings, how a `Value` is encoded to bytes and *interned* into a `TermId`,
> and the two different ID schemes the unversioned and versioned Sails use.
>
> _Reading time: ~10 minutes._
> _Prerequisites: [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md),
> [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)._

## 0 · The problem

[A scan](A1-a-scan.md) ended on a question it deferred: a stored quad is four
8-byte integers, and the dictionary turns them back into RDF values. This doc
follows the *other* direction — how a value becomes one of those integers in
the first place:

```java
// inside a write transaction, for each term of an incoming quad
TermId id = dictionary.intern(alice, batch, pending);
```

`alice` is an RDF4J `Value` — here the IRI `http://example.org/alice`.
`intern` must return a `TermId`: the same one every time this exact value is
seen, a brand-new one the first time. Follow that call.

## 1 · Why a dictionary at all

An RDF store could index quads as raw strings. It would be slow and huge: IRIs
are long, they repeat constantly (every statement about Alice repeats her
IRI), and variable-length keys make index pages irregular.

So both Sails **dictionary-encode**. Each distinct RDF term is assigned an
8-byte `TermId` once; every index then stores `TermId`s, not strings. A quad
becomes a fixed **32-byte** key (4 × 8). The mapping itself — `Value ↔ TermId`
— *is* the dictionary.

> **Key idea** — interning is deduplication with a number. The store holds one
> copy of each term's bytes and refers to it everywhere by a fixed-width
> integer. Small keys, uniform index pages, cheap comparisons.

## 2 · Encoding the value to bytes

A `TermId` is keyed on the term's *bytes*, so the first job is to turn the
`Value` into a deterministic, reversible byte string. In the flat Sail that is
`FlatTermCodec.encode`:

```java
if (value instanceof IRI iri) {
    out.writeByte(KIND_IRI);              // 0x01
    out.write(utf8(iri.stringValue()));
} else if (value instanceof BNode bnode) {
    out.writeByte(KIND_BNODE);            // 0x02
    out.write(utf8(bnode.getID()));
} else if (value instanceof Literal literal) {
    out.writeByte(KIND_LITERAL);          // 0x03
    // length-prefixed language block, length-prefixed datatype block, then label
}
```

A **1-byte kind tag** followed by a payload. The encoding must be *exact*: two
values encode to the same bytes if and only if they are the same RDF term — so
a typed literal carries its datatype IRI and a language-tagged literal carries
its language, because `"30"^^xsd:integer` and `"30"^^xsd:string` are different
terms and must not collide.

> **The bug** — encoding RDF literals is full of edge cases that only a real
> corpus surfaces. The versioned `TermEncoder` once failed on
> `"INF"^^xsd:double`: it routed the label through `Double.parseDouble`, which
> rejects the XSD spelling `"INF"` (Java wants `"Infinity"`). Valid RDF that
> simply would not encode. The lesson: an encoder's job is the *serialization
> format's* grammar, not Java's — every datatype's lexical space has corners.

## 3 · Interning — look up, or assign

With the bytes in hand, `FlatDictionary.intern` resolves them to a `TermId`,
checking three places in order:

```java
public TermId intern(Value value, AbstractWriteBatch batch,
                     Map<ByteBuffer, TermId> pending) {
    byte[] term = FlatTermCodec.encode(value);
    ByteBuffer cacheKey = ByteBuffer.wrap(term);

    TermId staged = pending.get(cacheKey);          // (a) interned earlier this txn?
    if (staged != null) return staged;

    byte[] committed = db.get(rev, term);           // (b) already in the store?
    if (committed != null) {
        TermId tid = TermId.of(longFromBytes(committed));
        pending.put(cacheKey, tid);
        return tid;
    }

    long id = nextId.getAndIncrement();             // (c) brand new — assign one
    batch.put(fwd, bytesFromLong(id), term);        // id  -> term
    batch.put(rev, term, bytesFromLong(id));        // term -> id
    batch.put(fwd, NEXT_ID_KEY, bytesFromLong(nextId.get()));
    TermId tid = TermId.of(id);
    pending.put(cacheKey, tid);
    return tid;
}
```

- **(a) `pending`** — a per-transaction map of terms interned so far in *this*
  batch. Without it, the same new term appearing twice in one transaction would
  be assigned two IDs (the `rev` write is not yet committed, so step (b) can't
  see it).
- **(b) the `rev` column family** — `term → id`, the committed reverse
  dictionary. A point read; a hit means the term already has an ID.
- **(c) assign** — take the next counter value, and write *both* directions:
  `fwd` (`id → term`, for [the scan](A1-a-scan.md)'s `lookupAll`) and `rev`
  (`term → id`, for the next intern). The counter's new value is persisted too,
  so it survives a reopen.

> **Gotcha** — the dictionary is **append-only**. An assigned `TermId` is never
> reused or remapped. That is what makes the scan-side term cache safe to hold
> without invalidation — and it means a deleted-then-re-added term keeps its
> original ID.

## 4 · The `TermId` — two schemes

`TermId` is a `record` wrapping one `long` — 8 bytes, immutable, cheap:

```java
public record TermId(long value) implements Comparable<TermId> { ... }
```

But the two Sails *assign* that long differently:

- **`RocksDbFlatSail` — a sequential counter.** Exactly what you saw above:
  `nextId.getAndIncrement()`, IDs 1, 2, 3, … (`TermId.ZERO` is reserved as the
  default-graph sentinel). Dense, simple, requires the `rev` lookup to dedup.
- **`ProllySail` — a hash.** The versioned Sail derives the ID from a 64-bit
  hash of the term: `TermId.ofNatural(hash)` masks off the top bit, giving a
  *natural* ID. The top bit is the **extension flag** —
  `TermId.ofExtensionSlot(slot)` sets it for terms parked in an extension table
  (hash collisions, overflow). `compareTo` is unsigned, so all natural IDs
  sort before all extension IDs.

> **Trade-off** — a counter needs a reverse-lookup to dedup but yields dense,
> tiny IDs; a hash dedups for free (same term ⇒ same hash ⇒ same ID, no
> lookup) but must handle collisions via the extension band. The flat Sail
> optimizes for simple bulk writes; the versioned Sail for content-addressed
> determinism.

And the encoders differ to match: the versioned side uses `TermCodec` /
`TermEncoder` (a `[tag:u8][payload]` format whose tag byte is frozen on-disk
state), while the flat Sail owns the simpler, fully-reversible `FlatTermCodec`.
`FlatTermCodec` exists *separately* on purpose — `TermCodec`'s decode half is
coupled to the versioned tree, and reusing it would drag the whole versioned
engine into the unversioned Sail. Keeping them apart is what the `prolly-codec`
module split is for.

## Takeaways

- The dictionary trades long, repetitive RDF strings for fixed 8-byte
  `TermId`s — uniform 32-byte quad keys and cheap comparisons.
- Interning is *encode the value to exact bytes → look up or assign*; the
  `pending` map dedups within a transaction, the `rev` column family dedups against the
  store.
- A `TermId` is one immutable `long`; the dictionary is append-only, so IDs are
  permanent — which is what makes the read-side cache safe.
- The two Sails assign IDs differently — flat: a sequential counter; versioned:
  a hash with an extension band — and use different codecs, kept apart by the
  `prolly-codec` split.

## Where this lives

- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatDictionary.java`
  — `intern`, the counter, the `fwd`/`rev` column families
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatTermCodec.java`
  — the flat Sail's value→bytes codec
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermId.java`
  — the 8-byte ID, natural vs extension form
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermCodec.java`,
  `TermEncoder.java` — the versioned codec
- Foundations assumed:
  [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md),
  [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)
- Continues in: [A3 · an ingest](A3-an-ingest.md) — interning as part of a full
  `addStatement`.
