
# How Per-Connection Isolation Works

This document explains the Phase 3 iter 23 refactor that made the Sail
rollback-capable and multi-connection-safe.

## Why

Before iter 23, every `addStatement` / `setNamespace` / etc. mutated
**Sail-level** `MutableMap` buffers shared across all connections. Two
consequences:

1. **No rollback.** Once written, the only way to "undo" was to track
   inverse operations — a fragile pattern that can't handle deletes of
   non-existent keys, hash collisions, or wildcard removes correctly.
2. **No isolation.** A second connection's reads would see the first
   connection's uncommitted writes immediately. Two connections couldn't
   independently buffer "what-if" mutations.

The fix is the same pattern PostgreSQL / RocksDB / Git use: **the
authority holds the committed state; each session forks a working copy
that commits or rolls back**.

## The architecture

```
┌────────────────────────────────────────────────────────────────┐
│ ProllySail                                                     │
│                                                                │
│  Long-lived, read-only-from-the-connection's-perspective:      │
│  - NodeStore + BufferPool                                      │
│  - HashFunction                                                │
│  - PrefixTable (Sail-level for v2.0; promotion is rare)        │
│  - ProllyValueFactory (bound to the PrefixTable)               │
│  - MeterRegistry                                               │
│                                                                │
│  Mutable Sail state — the *committed* StaticMap roots:         │
│  - volatile StaticMap dictRoot                                 │
│  - Map<QuadOrder, StaticMap> indexRoots                        │
│  - volatile StaticMap namespacesRoot                           │
│  - volatile StaticMap statsRoot                                │
│                                                                │
│  Accessor pairs for connections:                               │
│  - dictRoot()        / advanceDictRoot(StaticMap next)         │
│  - indexRoot(order)  / advanceIndexRoot(order, next)           │
│  - namespacesRoot()  / advanceNamespacesRoot(next)             │
│  - statsRoot()       / advanceStatsRoot(next)                  │
└────────────────────────────────────────────────────────────────┘
                              │
                              │  fork at connection-construction +
                              │  on rollback
                              ▼
┌────────────────────────────────────────────────────────────────┐
│ ProllySailConnection (per-connection, owned exclusively)       │
│                                                                │
│  - Dictionary dictTx                                           │
│  - Map<QuadOrder, QuadIndex> indexesTx                         │
│  - IndexPlanner plannerTx                                      │
│  - SparqlNamespaces namespacesTx                               │
│  - TermStats statsTx                                           │
│  - DictionaryTermResolver resolverTx                           │
│  - Arena arena                                                 │
└────────────────────────────────────────────────────────────────┘
```

Each per-tx table is constructed with the Sail's *current* committed
StaticMap as its base. The table layers a `MutableMap` over that base
for buffered writes. Reads within the connection see committed-state +
the connection's own buffered writes (read-your-writes).

## Lifecycle

### Construction

`new ProllySailConnection(sail)` calls `forkTables()`, which:

```java
private void forkTables() {
    StaticMap dictRoot = sail.dictRoot();
    dictTx = (dictRoot == null)
        ? new Dictionary(store, pool, hashFn, MAX_SALT, metrics)
        : new Dictionary(store, pool, hashFn, dictRoot, metrics);
    // ... similarly for each table
    plannerTx   = new IndexPlanner(indexesTx, metrics, statsTx);
    resolverTx  = new DictionaryTermResolver(dictTx, sail.prefixes());
}
```

Each table either starts fresh (Sail has nothing committed yet) or
forks the existing committed root (the constructor that takes a
`StaticMap committed` parameter).

### `startTransactionInternal`

**No-op.** Tables are always live, forked at construction. RDF4J's
`AbstractSailConnection.autoStartTransaction()` semantics work because
the underlying buffers exist regardless of whether the caller has
explicitly called `begin()`.

### `addStatement(s, p, o, ctxs...)`

Writes to per-tx tables only:
1. `dictTx.encode(...)` returns a TermId (buffered insert if new).
2. `indexesTx.get(...).insert(s,p,o,c)` for each of the 4 indexes.
3. `statsTx.increment(termId)` for each position.

The Sail's `dictRoot` / `indexRoots` / `statsRoot` are **unchanged** —
nothing has been flushed yet.

### `commitInternal`

Each per-tx table flushes to a new StaticMap, and the Sail's volatile
root references are advanced atomically:

```java
sail.advanceDictRoot(dictTx.commit());
for (Map.Entry<QuadOrder, QuadIndex> e : indexesTx.entrySet()) {
    sail.advanceIndexRoot(e.getKey(), e.getValue().commit());
}
sail.advanceNamespacesRoot(namespacesTx.commit());
sail.advanceStatsRoot(statsTx.commit());
sail.prefixes().commit();  // Sail-level; participates in commit
```

After commit, the connection's per-tx tables still exist and are now
backed by the just-committed roots. Subsequent writes in the same
connection buffer on top of that fresh base.

Per-stage commit durations are recorded:
`sail.commit.{dict,indexes,prefixes,namespaces,stats,total}`.

### `rollbackInternal`

```java
forkTables();
sail.metrics().increment("sail.rollback");
```

Just re-forks. The Sail's committed roots are untouched (rollback never
called `advance*Root`), so the new per-tx tables are forked from the
same committed state as before — minus the buffered mutations that just
got discarded.

For v2.0 single-writer this is "clean rollback": no other connection
could have advanced the Sail roots while this connection was buffering,
so re-forking lands on the same base. Phase 4 multi-writer adds
CAS-rebase: rollback that detects an advanced Sail root will pick up
the newer state.

## Read path inside a transaction

`getStatements` consults `dictTx` / `plannerTx` / `resolverTx` — all
the per-tx tables. The dict's buffered inserts ARE visible to reads in
the same connection (read-your-writes). Other connections' uncommitted
inserts are NOT visible (snapshot isolation against the start-of-tx
Sail roots).

The `DictionaryTermResolver` returned to `ProllyStatement` is bound to
the connection's `dictTx`, so when an iteration materializes a TermId
to a `ProllyValue` it reads from the per-tx dict — picking up
in-transaction inserts.

## Sail-level vs per-tx — the split

| Table | Where it lives | Why |
|---|---|---|
| `NodeStore` / `BufferPool` | Sail-level | Shared underlying storage; per-connection wrappers all read/write the same chunks |
| `HashFunction` | Sail-level | Format invariant; same across connections |
| `PrefixTable` | Sail-level | Bootstrap entries + rare promotions; rollback isolation isn't worth the complexity |
| `ProllyValueFactory` | Sail-level | Bound to PrefixTable; values created here are heap-backed and don't tie to a connection arena |
| `MeterRegistry` (Micrometer) | Sail-level | Cross-connection observability |
| `Dictionary` | **Per-connection** | Term insert/encode/decode happens in-tx; rollback must drop unflushed terms |
| `QuadIndex × 4` | **Per-connection** | Quad inserts buffered; rollback drops them |
| `SparqlNamespaces` | **Per-connection** | `setNamespace` is rollback-safe |
| `TermStats` | **Per-connection** | Frequency increments in-tx; rollback drops them |
| `IndexPlanner` | **Per-connection** | Wraps per-tx indexes + per-tx stats |
| `DictionaryTermResolver` | **Per-connection** | Resolves via per-tx dict for read-your-writes |

`PrefixTable` lives Sail-level deliberately — IRI prefix promotion is
manifest-level metadata that shouldn't be transaction-scoped. If we
later need rollback isolation for prefix promotion, the refactor is
trivial (same pattern as Dictionary).

## Verification (4 rollback tests)

| Test | What it proves |
|---|---|
| `rollback_discards_writes` | `addStatement` + `rollback` → 0 rows visible from a new connection. The Sail's committed state was never advanced. |
| `rollback_after_commit_does_not_undo` | First commit lands; second tx rollback only drops second-tx's buffer. The first commit's row survives. Confirms `rollback` doesn't undo committed state. |
| `rollback_then_continue_in_new_tx` | Same connection: tx1.add → rollback; tx2.add → commit. The committed row from tx2 is visible; tx1's discarded row isn't. Confirms the connection itself is reusable across rollback. |
| `rollback_does_not_consume_term_ids_persistently` | After rollback, the Sail's `dictRoot` is still empty. No "ghost" TermIds got allocated to terms that don't exist. |

## What this unlocks (future iters)

See [`cas-rebase.md`](cas-rebase.md) for the full Phase 4 design. The
short version: the Sail now holds committed-state-only references plus
accessors. This is the foundation for **multi-writer CAS-rebase**:

```java
// On commit (multi-writer pseudocode for Phase 4):
StaticMap snapshotDictRoot = sail.dictRoot();  // captured at forkTables()
StaticMap newDictRoot = dictTx.commit();
if (sail.compareAndAdvanceDictRoot(snapshotDictRoot, newDictRoot)) {
    // Won the race — our commit landed.
} else {
    // Lost the race — rebase our diff onto the new Sail root and retry.
    StaticMap latest = sail.dictRoot();
    dictTx = dictTx.rebase(latest);
    // ... retry, up to N times, then surface SailException
}
```

`Database.rebase` already exists in prolly-rdf; wiring it requires the
Sail's accessors to evolve from `advanceX(next)` to
`compareAndAdvanceX(expected, next)`. Phase 4 work.
