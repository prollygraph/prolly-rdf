
# Multi-Writer CAS-Rebase (Phase 4 design)

The Sail's commit protocol today is single-writer: one connection at a time
calls `advanceDictRoot` etc. and the volatile assignment is the synchronization
boundary. Two concurrent committers race; the loser silently overwrites the
winner's root. This doc specifies the Phase 4 protocol that makes commit
multi-writer-safe via optimistic compare-and-set + rebase.

> Prerequisite read: [`connection-isolation.md`](connection-isolation.md). This
> doc builds on the per-connection mutation-buffering pattern that landed in
> Phase 3 iter 23.

## What changes

```
Today (single-writer):                      Phase 4 (multi-writer):

connection.commitInternal()                 connection.commitInternal()
  └─ sail.advanceDictRoot(newDict)            └─ retry loop:
      └─ this.dictRoot = newDict                  ┌─ snapshot = forkSnapshot
                                                  │  newRoots = flush per-tx tables
                                                  │  if (sail.cas(snapshot, newRoots))
                                                  │    return  ← won the race
                                                  │  else
                                                  │    rebase per-tx tables onto
                                                  │      sail's current roots
                                                  └─ retry up to MAX_REBASES
```

Two structural changes:

1. **Sail accessors gain CAS variants.**
   `advanceDictRoot(StaticMap next)` → also expose
   `compareAndAdvanceDictRoot(StaticMap expected, StaticMap next) → boolean`.
   The boolean tells the caller whether the swap won the race.

2. **`commitInternal` becomes a retry loop.** Each iteration either wins all
   the CASes (and returns), or detects a stale snapshot on at least one table
   and rebases the buffered mutations onto the newer Sail roots.

## Snapshot capture

Today `forkTables()` captures Sail roots into per-tx tables but does not
remember the snapshot for later comparison. Phase 4 needs to remember:

```java
private static final class Snapshot {
    final StaticMap dictRoot;
    final Map<QuadOrder, StaticMap> indexRoots;
    final StaticMap namespacesRoot;
    final StaticMap statsRoot;
}
private Snapshot forkSnapshot;
```

Captured at the same moment as `forkTables()`. Used at commit-time as the
`expected` argument to each CAS.

## CAS granularity

Two design options:

| Option | Description | Pros | Cons |
|---|---|---|---|
| A. Per-table CAS | Each Sail-level root has its own volatile + CAS pair. Commit does N independent CASes. | Simple; each CAS uses Java's existing `AtomicReferenceFieldUpdater` or `VarHandle` | "Partial commit" anomaly: dict CAS wins, indexes CAS loses → caller sees inconsistent state mid-retry |
| B. Single-record CAS | All roots wrapped in a single `CommitRecord` value class; one CAS on the whole tuple via the underlying `Database.commit` (which already has `expectedParentHash`) | Atomicity; matches the Manifest semantics; reuses the rebase machinery in `Database` | Requires building a "meta-tree" or commit-record class; bigger refactor |

**Recommendation: B.** The prolly-rdf `Database.commit(branch, StaticMap next, byte[] expectedParentHash, ...)` already has CAS-on-commit-node semantics. Wrap the per-tx tables' commit roots into a single commit node and let `Database` do the atomic CAS.

This matches the meta-tree pattern flagged in `ARCHITECTURE.md` §8.5 (Pre-1.G):
the Sail's "current state" is a single commit hash; the commit node carries
all the table roots as named fields.

## Rebase

When CAS loses, the connection's per-tx tables are buffered on a stale base
(the snapshot's roots). To preserve the connection's mutations, we replay
them onto the new Sail roots:

```java
// Pseudocode for one table (Dictionary; same pattern for the others):
StaticMap newSailDictRoot = sail.dictRoot();          // post-race
List<Mutation> myMutations = dictTx.pendingDiff();    // not yet exposed; new API
Dictionary rebased = new Dictionary(store, pool, hashFn, newSailDictRoot);
for (Mutation m : myMutations) rebased.apply(m);
dictTx = rebased;
```

Two implementation paths:

### Path R1: replay encoded mutations

The connection records every `dictTx.encode(...)` call as a `(input bytes,
returned TermId)` pair. On rebase, replay each `encode(input bytes)` against
the new base. The new TermId may differ (if the new base already has the
term at a different slot — extension-table escalation in a different chain).
For the dict, replay is fine because TermIds are content-addressed: re-encode
gives a deterministic result.

For SPOC/POSC/OSPC/CSPO index mutations, replay = re-insert each
`SpocKey` into the rebased index. Since SpocKeys are TermId-based, and
TermIds are content-addressed, the same `(s,p,o,c)` always produces the
same `SpocKey`. Replay is byte-stable.

For TermStats, replay = re-apply each frequency delta.

For SparqlNamespaces, replay = re-apply each `set/remove` in order.

### Path R2: diff-merge via prolly's Database.rebase

Prolly-rdf's `Database.rebase(MutableMap pending, StaticMap newBase)` already
exists (per the Round-3 audit). It takes the pending buffer (the per-tx
table's `MutableMap.edits`) and re-applies the diffs against the new base.

This is the cleaner path for Phase 4. The connection's per-tx `Dictionary`
holds a `MutableMap` internally; expose it (or expose `flush` + `rebase`)
and let `Database.rebase` do the work.

**Recommendation: R2 for Dictionary + indexes + stats. R1 is reasonable for
SparqlNamespaces (small map; replay is trivial). R2 needs minor surgery on
each table to expose its internal `MutableMap` to the rebaser.

## Conflict resolution

CAS-rebase handles "writers don't conflict" cleanly. Real conflicts:

| Conflict | Detection | Resolution |
|---|---|---|
| Two writers add the same `(s,p,p,c)` | Both insert same key; idempotent | None; both wins (one becomes the canonical insert via TreeMutator dedup) |
| Two writers delete the same key | Both delete; idempotent | None |
| W1 deletes key X; W2 reads X then writes "updated X" | Detected at rebase: W2's buffered insert refers to a TermId W1 just removed. Rebase replays W2's insert successfully (insert is content-addressed; W1's delete only removed *that* row of the SPOC index) | None; the row is re-added by W2 |
| W1 commits N statements; W2 deletes "wildcard pattern matching N items" before W1 committed | W2 saw 0 matches at scan; W1's commit landed; W2's commit applies an empty delete-set against the new state — N statements remain | Acceptable semantics for SPARQL; mirrors PostgreSQL's snapshot-isolation behavior |
| W1 sets namespace `ex → A`; W2 sets namespace `ex → B` | Last-CAS-wins | None at the Sail level; document |
| Pathological infinite rebase | Repeated rebase failures (e.g., every retry hits new contention) | Cap at `MAX_REBASES = 3`; throw `SailConflictException` |

## Retry loop pseudocode

```java
@Override
protected void commitInternal() throws SailException {
    for (int attempt = 0; attempt < MAX_REBASES; attempt++) {
        Snapshot snap = forkSnapshot;
        StaticMap newDictRoot = dictTx.commit();
        Map<QuadOrder, StaticMap> newIndexRoots = new EnumMap<>(QuadOrder.class);
        for (var e : indexesTx.entrySet()) {
            newIndexRoots.put(e.getKey(), e.getValue().commit());
        }
        StaticMap newNsRoot    = namespacesTx.commit();
        StaticMap newStatsRoot = statsTx.commit();

        boolean won = sail.tryAdvance(snap, newDictRoot, newIndexRoots, newNsRoot, newStatsRoot);
        if (won) {
            metrics.increment("sail.commit");
            return;
        }
        metrics.increment("sail.commit.rebase");
        rebaseTablesAgainstCurrentSailRoots();
    }
    metrics.increment("sail.commit.conflict.exhausted");
    throw new SailConflictException(
        "could not commit after " + MAX_REBASES + " rebases — too much contention");
}
```

Backoff between retries (e.g., `Thread.sleep(attempt * 10ms)`) is a refinement
worth measuring; it spreads retries across writers when many are racing.

## Sail API delta

Add to `ProllySail`:

```java
/** Try to atomically advance all roots from the snapshot. Returns true on success.
 *  All-or-nothing — partial advance is not exposed to the caller. */
boolean tryAdvance(
    Snapshot expected,
    StaticMap newDictRoot,
    Map<QuadOrder, StaticMap> newIndexRoots,
    StaticMap newNamespacesRoot,
    StaticMap newStatsRoot);
```

Behind the scenes, this calls `Database.commit(branch, metaTreeRoot,
expectedParentHash, ...)` which performs the underlying CAS. Building the
meta-tree is straightforward: a single `StaticMap` mapping names
(`"dict"`, `"spoc"`, etc.) to their roots' content hashes.

Keep the per-table `advanceX` accessors as test-only / fast-path APIs for
single-writer scenarios.

## Removing the volatile fields

Once `Database.commit` is the source of truth for the meta-tree, the
`volatile StaticMap dictRoot` etc. fields on `ProllySail` become stale.
Replace with derived accessors:

```java
StaticMap dictRoot() {
    return loadFromMetaTree("dict");  // looked up from Database's current commit
}
```

This makes commit truly atomic at the storage layer rather than at the
JVM-volatile layer — survives JVM restart, multi-process access, etc.

## Test plan

| Test | What it verifies |
|---|---|
| `concurrent_committers_both_win_via_rebase` | Two connections add disjoint statements concurrently; both commits land after at most one rebase each. Final state has the union. |
| `concurrent_committers_same_statement_idempotent` | Both connections add the *same* statement; both commits land; only one row in the final SPOC. |
| `concurrent_writer_loses_then_retries` | Connection A reads → writes; connection B sneaks in a commit; A's commit fails CAS → rebases → wins. Verified via metric counters. |
| `pathological_contention_throws_after_max_rebases` | A test harness drives N writers in tight contention; expect `SailConflictException` from at least one if `MAX_REBASES` is exceeded. |
| `rebase_preserves_my_buffered_mutations` | Connection adds 10 statements; another connection commits unrelated rows; first connection commits — its 10 statements all land. |
| `term_id_stability_across_rebase` | Encode term, capture TermId, force a rebase, re-encode same term → same TermId (content-addressed → byte-stable). |

## Open questions

1. **Cross-process CAS.** Two JVMs sharing a RocksDB-backed `NodeStore` must
   coordinate via the persistent `Manifest`, not via JVM-volatile fields. The
   meta-tree approach (Path B above) gives this naturally because
   `Database.commit` is RocksDB-backed.
2. **Isolation level reporting.** RDF4J's `SailIsolationLevel.SERIALIZABLE`
   demands that committed transactions appear to execute in *some* serial
   order. CAS-rebase gives this for write-write conflicts; read-write
   conflicts (snapshot read + concurrent commit) need read-set tracking,
   which we don't have. v2.0 will report `SNAPSHOT_READ` even after Phase 4
   lands.
3. **Stats counter deltas.** Two connections each increment frequency for
   the same TermId. After CAS-rebase, both deltas should add. With the
   current `TermStats.commit()` that reads the committed value + delta,
   rebase needs to re-read post-rebase and re-apply the delta. Verify the
   arithmetic survives the retry loop.
4. **Long-running readers.** A read-only connection holds its snapshot for
   minutes. Concurrent writers commit, advancing the Sail's roots, but the
   read-only connection's `dictRoot` reference still points at the old
   StaticMap. The underlying chunks remain GC-rooted via the connection's
   `Dictionary` reference until the connection closes. **No correctness
   issue, but memory grows** until the slow reader closes. Document.

## Effort estimate

| Step | Effort |
|---|---|
| Add `Snapshot` class + capture in `forkTables` | 1 day |
| Path R2: expose `MutableMap` from each table | 2 days |
| Meta-tree commit-record + `Database.commit` integration | 3 days |
| Retry loop + `SailConflictException` | 1 day |
| Tests (6 above) | 3 days |
| Metric wiring (`sail.commit.rebase`, `sail.commit.conflict.exhausted`) | 0.5 day |
| Doc updates (this file → "implemented"; ARCHITECTURE.md isolation table) | 0.5 day |
| **Total** | **~11 person-days** |

Suitable for a single 2-week sprint.

## Why not now

Phase 3's per-connection isolation is enough to pass the RDF4J Sail
conformance suite for the most common patterns. CAS-rebase blocks on:

1. **A real workload to validate against.** Multi-writer correctness needs
   a benchmark or production trace; we don't have one yet.
2. **Multi-process scenarios.** `Database.commit` is the integration point;
   wiring it requires committing to the meta-tree-record format, which is
   spec-locking. Defer until we have a Phase 0-style sign-off on the
   commit-record schema (similar to the term-encoding spec from iter 0).
3. **Phase 4 also delivers SPARQL BGP evaluation.** Bundling CAS-rebase with
   BGP turns one sprint into "the multi-writer-and-queries release" with
   clearer scope and review boundaries.

When Phase 4 starts, this doc transitions from spec to implementation
guide.
