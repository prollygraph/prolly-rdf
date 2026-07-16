---
tags:
  - storage
  - performance
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/anatomy/A3-an-ingest.md; links adapted to this repo's layout -->

# Anatomy of an ingest

*From `conn.add(alice, knows, bob)` to four committed index keys.*

> **What you'll learn** — the write path of `RocksDbFlatSail`: how a
> transaction is buffered, how a statement's terms are interned, why one quad
> becomes *four* index keys, and what `commit` actually does.
>
> _Reading time: ~9 minutes._
> _Prerequisites: [A2 · a term](A2-a-term.md),
> [the-concurrency-model](../foundations/the-concurrency-model.md)._

## 0 · The problem

A client adds one statement inside a transaction:

```java
try (RepositoryConnection conn = repo.getConnection()) {
    conn.begin();
    conn.add(alice, knows, bob);     // alice, bob: IRI   knows: IRI
    conn.commit();
}
```

RDF4J routes the `add` to the Sail as `addStatementInternal(subj, pred, obj,
contexts...)`. By the end of `commit`, that one quad must be durably
queryable by *any* triple pattern. Follow it.

## 1 · Begin — buffering the transaction

`begin` reaches `startTransactionInternal`, which sets up two things:

```java
protected void startTransactionInternal() throws SailException {
    // overwriteKey=true so the in-batch index resolves a re-written key
    // to its latest value — required for correct merged reads.
    txBatch = new WriteBatchWithIndex(true);
    dictPending = new HashMap<>();
}
```

- `txBatch` — a RocksDB `WriteBatchWithIndex`: every mutation is staged here,
  not written to the store. Because the batch is *indexed*, the connection's
  own reads can see its uncommitted writes (read-your-writes — see
  [the concurrency model](../foundations/the-concurrency-model.md)).
- `dictPending` — the per-transaction intern cache from [A2](A2-a-term.md).

The single-writer gate is **not** taken here. It is acquired lazily on the
*first mutation* — `addStatementInternal` calls `acquireWriteLock()`, a no-op
on every later mutation of the same transaction. A read-only transaction never
takes the gate at all.

> **Key idea** — `begin` is cheap and lock-free. A transaction only becomes a
> *writer* — and only then contends for the gate — when it first mutates.

## 2 · Interning the terms

`addStatementInternal` turns the three (or four) RDF values into `TermId`s:

```java
acquireWriteLock();
WriteBatchWithIndex batch = requireTransaction();
TermId s = dictionary.intern(subj, batch, dictPending);
TermId p = dictionary.intern(pred, batch, dictPending);
TermId o = dictionary.intern(obj,  batch, dictPending);
```

That is the [A2](A2-a-term.md) path: each value is encoded, looked up, and
either resolved to its existing ID or assigned a new one — with the new
`dictionary` mappings staged into the *same* `batch`. The context is special:

```java
if (contexts.length == 0) {
    writeQuad(batch, s, p, o, TermId.ZERO);          // default graph
} else {
    for (Resource context : contexts) {
        TermId c = (context == null)
            ? TermId.ZERO                            // explicit default graph
            : dictionary.intern(context, batch, dictPending);
        writeQuad(batch, s, p, o, c);
    }
}
```

> **Gotcha** — the default graph is the `TermId.ZERO` sentinel, *not* a missing
> value. An empty `contexts` array and an explicit `null` context both mean the
> default graph; a non-null context is interned like any other term. Adding to
> N contexts writes the quad N times.

## 3 · One quad, four index keys

`writeQuad` is where the quad actually lands — and it lands four times:

```java
private void writeQuad(AbstractWriteBatch batch, TermId s, TermId p, TermId o, TermId c)
        throws RocksDBException {
    for (QuadOrder order : QuadOrder.values()) {
        batch.put(store.index(order),
                  FlatKeyCodec.encode(order, s, p, o, c),
                  EMPTY_VALUE);
    }
}
```

`QuadOrder.values()` is the four permutation orders — `SPOC`, `POSC`, `OSPC`,
`CSPO`. Each is its own RocksDB column family. `FlatKeyCodec.encode(order, …)`
lays the four `TermId`s out in that order's physical column sequence, producing
a 32-byte key; the value is **empty** — the key *is* the data.

Why pay to write the same quad four times? Because of [the scan](A1-a-scan.md).
A query binds some positions and leaves others free; an index can answer it as
a fast range scan only if the bound terms form a *key prefix*. Four orderings
guarantee that for *any* pattern of bound terms, some index has them leading.

> **Trade-off** — ingest does **4× the write work** and uses 4× the index
> space. That is bought back on the read side: every triple pattern becomes a
> prefix seek instead of a full scan. The flat Sail spends writes to make reads
> cheap.

Nothing has touched the store yet — all four `put`s went into `txBatch`.

## 4 · Commit

`commitInternal` flushes the whole batch in one shot:

```java
protected void commitInternal() throws SailException {
    WriteBatchWithIndex batch = requireTransaction();
    try (WriteOptions options = new WriteOptions()) {
        store.db().write(options, batch);   // atomic, WAL on
    } finally {
        discardTransaction();               // close batch, release the gate
    }
}
```

One `db.write` applies every staged mutation — the dictionary entries *and* all
four index keys for every quad in the transaction — **atomically**, with the
write-ahead log on for durability. A reader either sees the whole transaction
or none of it. `rollbackInternal` is the opposite and simpler: it just
`discardTransaction()`s — drops the buffered batch unapplied. Either way,
`discardTransaction` closes the batch and releases the single-writer gate.

> **Gotcha** — one transaction is one in-memory `WriteBatchWithIndex`. It is
> not flushed until `commit`, so a single giant load transaction balloons the
> heap. Bulk loads must chunk into many bounded transactions — the Sail is a
> correct transactional store, not a bulk loader.

## Takeaways

- `begin` is lock-free; a transaction acquires the single-writer gate only on
  its first mutation, and read-only transactions never do.
- A write buffers into a `WriteBatchWithIndex` — staged, queryable by the
  writer, invisible to others — until `commit`.
- Interning reuses the [A2](A2-a-term.md) path; the default graph is the
  `TermId.ZERO` sentinel.
- One quad is written to **four** permutation indexes as 32-byte keys with
  empty values — 4× write cost, traded for prefix-scan reads.
- `commit` is a single atomic `db.write`; `rollback` discards the batch. Big
  loads must be chunked.

## Where this lives

- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/RocksDbFlatSailConnection.java`
  — `startTransactionInternal`, `addStatementInternal`, `writeQuad`,
  `commitInternal`
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatKeyCodec.java`
  — permutation key encoding
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatDictionary.java`
  — `intern`
- Foundations assumed:
  [the-concurrency-model](../foundations/the-concurrency-model.md)
- Continues in: [A4 · a SPARQL query](A4-a-sparql-query.md) — querying what was
  just ingested.
