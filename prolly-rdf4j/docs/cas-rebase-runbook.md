
# CAS-Rebase — Implementer's Runbook

Step-by-step execution plan for landing the Phase 4 CAS-rebase design from
[`cas-rebase.md`](cas-rebase.md). The design doc says *what & why*; this
doc says *in what order to type*.

> Read [`cas-rebase.md`](cas-rebase.md) first. This runbook references its
> decisions without re-justifying them.

## Pre-flight checks

Confirm before you start:

- [ ] All 542 prolly-rdf4j tests pass at HEAD: `mvn -pl prolly-rdf4j test`
- [ ] You have a clean branch off `master` named `phase4-cas-rebase`
- [ ] `Database.commit(branch, StaticMap, expectedParentHash, ...)` and
      `Database.rebase(MutableMap, StaticMap)` both exist in prolly-rdf and
      work — read `prolly-storage/src/main/java/com/earasoft/prolly/Database.java`
      to confirm the signatures haven't drifted since the audit
- [ ] You have ~11 person-days budgeted (per design doc estimate); don't
      start with less than 8 contiguous days available
- [ ] You can run a JMH benchmark on isolated hardware — performance validation
      at the end of the runbook needs noise-free measurements

## Step 1 — `Snapshot` class

**Goal:** capture the Sail's roots at forkTables() time so commit can use
them as the CAS `expected` argument.

**File:** `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/Snapshot.java` (new)

```java
package com.earasoft.prolly.rdf4j.sail;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable record of all Sail-level committed roots at one instant.
 * Captured by {@link ProllySailConnection#forkTables} and used at commit
 * time as the {@code expected} argument to CAS.
 */
public record Snapshot(
    StaticMap dictRoot,
    Map<QuadOrder, StaticMap> indexRoots,
    StaticMap namespacesRoot,
    StaticMap statsRoot
) {
    public Snapshot {
        // ⚠ Don't use Map.copyOf — it rejects null values. We use null
        // to mean "empty tree, no commits yet", so we need a defensive
        // copy that allows nulls.
        EnumMap<QuadOrder, StaticMap> copy = new EnumMap<>(QuadOrder.class);
        copy.putAll(indexRoots);
        indexRoots = Collections.unmodifiableMap(copy);
    }
}
```

**Caveat caught during dry-run:** the obvious `Map.copyOf(indexRoots)` throws
NPE because our null-as-empty-tree convention conflicts with `Map.copyOf`'s
null-key/null-value rejection. Use `EnumMap` + `Collections.unmodifiableMap`
instead.

**Tests:** add `SnapshotAndConflictTest`:
- `snapshot_holds_null_roots_for_empty_sail`
- `snapshot_index_roots_map_is_defensively_copied` (mutate caller's input → snapshot unchanged)
- `snapshot_index_roots_view_is_immutable` (put on the view → `UnsupportedOperationException`)
- `snapshot_record_equality`

**Run:** `mvn -pl prolly-rdf4j test -Dtest='SnapshotAndConflictTest'` — should pass.

---

## Step 2 — capture snapshot in `forkTables`

**File:** `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySailConnection.java`

Add field:
```java
private Snapshot forkSnapshot;
```

In `forkTables()`, at the end:
```java
this.forkSnapshot = new Snapshot(
    sail.dictRoot(),
    captureIndexRoots(),
    sail.namespacesRoot(),
    sail.statsRoot());
```

Helper:
```java
private Map<QuadOrder, StaticMap> captureIndexRoots() {
    Map<QuadOrder, StaticMap> m = new EnumMap<>(QuadOrder.class);
    for (QuadOrder order : QuadOrder.values()) m.put(order, sail.indexRoot(order));
    return m;
}
```

**Tests:** none new — existing 542 should still pass.

**Run:** `mvn -pl prolly-rdf4j test` — all 542 green.

---

## Step 3 — `SailConflictException`

**File:** `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/SailConflictException.java` (new)

```java
package com.earasoft.prolly.rdf4j.sail;

import org.eclipse.rdf4j.sail.SailException;

/**
 * Thrown when {@link ProllySailConnection#commitInternal} cannot land a
 * commit after the configured retry cap. Indicates either pathological
 * write contention or a bug in the rebase logic.
 *
 * <p>Callers can catch this specifically to decide whether to retry the
 * entire transaction at the application level.
 */
public class SailConflictException extends SailException {
    public SailConflictException(String msg) { super(msg); }
    public SailConflictException(String msg, Throwable cause) { super(msg, cause); }
}
```

**Tests:** trivial — verify it's catchable as `SailException`.

**Run:** `mvn -pl prolly-rdf4j compile`.

---

## Step 4 — Sail CAS accessor

**File:** `ProllySail.java`

Per the design doc's Option B (single-record CAS via Database.commit), this
step requires the meta-tree work to be done in Step 5 below. **Do Step 5 first**,
then return here.

After Step 5, add:
```java
/**
 * Atomically advance all roots if the Sail's current state matches the
 * snapshot. Returns true on success, false if another writer raced in.
 */
boolean tryAdvance(
    Snapshot expected,
    StaticMap newDictRoot,
    Map<QuadOrder, StaticMap> newIndexRoots,
    StaticMap newNamespacesRoot,
    StaticMap newStatsRoot) {
    // Implementation calls into Database.commit (Step 5)
    // ...
}
```

Tag the old `advanceX(StaticMap)` accessors as `@Deprecated` but keep them
for single-writer testing paths.

---

## Step 5 — Meta-tree commit-record

**Goal:** wrap all per-table roots into a single `StaticMap` so `Database.commit`'s
existing CAS semantics give us all-or-nothing atomicity.

**File:** `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/RootMetaTree.java` (new)

```java
package com.earasoft.prolly.rdf4j.sail;

import com.dolthub.prolly.*;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.term.Layouts;
import java.lang.foreign.MemorySegment;
import java.util.*;

/**
 * Meta-tree commit record. A single Prolly Map whose rows are
 * {@code (name : String, rootHash : Bytes32)} — naming each table's
 * StaticMap root. This is the value the Sail commits via
 * {@link com.earasoft.prolly.Database#commit} for atomic
 * multi-root advance.
 *
 * <p>Known names (must stay stable across versions):
 * <pre>
 *   "dict"          — Dictionary tree root
 *   "spoc"          — SPOC index root
 *   "posc"          — POSC index root
 *   "ospc"          — OSPC index root
 *   "cspo"          — CSPO index root
 *   "namespaces"    — SparqlNamespaces tree root
 *   "stats"         — TermStats tree root
 *   "prefixes"      — PrefixTable tree root
 * </pre>
 */
public final class RootMetaTree {
    // Schema: String key, Bytes32 value
    // Construction: take a Map<String, StaticMap>; build the meta-tree
    // Decode: take a StaticMap; return a Map<String, StaticMap>
    // ... implementation skeleton
}
```

Spec-level questions to resolve here:
- How to encode a "Bytes32 value" — use `Encoding.Hash128` from the engine core (`dolthub-java-port`),
  or `Encoding.Bytes` with raw bytes?
- How does the Sail load the meta-tree on construction (from `Database.head()`)?

These need a 1–2 hour design huddle before writing code. Capture decisions
in `cas-rebase.md` as edits.

**Run:** unit tests on RootMetaTree round-trip; meta-tree → JSON-dump for debug visibility.

---

## Step 6 — `Database.commit` integration

**File:** `ProllySail.java`

Replace `advanceDictRoot/indexRoot/namespacesRoot/statsRoot` calls in
`ProllySailConnection.commitInternal` with one call:

```java
boolean won = sail.tryAdvanceAll(
    forkSnapshot,
    newDictRoot, newIndexRoots, newNamespacesRoot, newStatsRoot);
```

where `tryAdvanceAll` builds a RootMetaTree from the new roots and calls:

```java
return database.commit(
    branchName,
    metaTreeRoot,
    expectedParentHash /* derived from forkSnapshot */,
    author, message);
```

`database` is a new `final Database database` field on `ProllySail`. `branchName`
is the sail's active branch (default `"main"`).

The `Database.commit` return is `boolean` — true on CAS success, false on race.
Plumb that through.

**Run:** `mvn -pl prolly-rdf4j compile`. Some existing tests will break here
because the old `advanceX` paths are dead. **Update existing tests** to use
the new path; aim to keep 542/542 green at this step (or document which
tests are intentionally dropped).

---

## Step 7 — Rebase methods on each table

Per design doc Path R2, expose `MutableMap` access (or a `rebase` method) on
each table.

**Files & methods to add:**

| File | New method |
|---|---|
| `Dictionary.java` | `void rebaseOnto(StaticMap newBase)` — see [Dictionary.rebase pseudocode](#dictionary-rebase-pseudocode) |
| `QuadIndex.java` | `void rebaseOnto(StaticMap newBase)` |
| `SpocIndex.java` | `void rebaseOnto(StaticMap newBase)` (called by QuadIndex's) |
| `SparqlNamespaces.java` | `void rebaseOnto(StaticMap newBase)` — replay set/remove map |
| `TermStats.java` | `void rebaseOnto(StaticMap newBase)` — replay delta map |

Each method:
1. Captures the current pending mutations (the `MutableMap.edits`).
2. Re-creates the internal `MutableMap` over the new base.
3. Replays the mutations against the new buffer.

### Dictionary rebase pseudocode

```java
public void rebaseOnto(StaticMap newBase) {
    // Snapshot current pending writes
    Map<MemorySegment, MemorySegment> oldEdits =
        new TreeMap<>(buffer.editsComparator());
    buffer.copyEditsTo(oldEdits);
    // Replace buffer with one rooted at newBase
    this.buffer = new MutableMap(newBase, store, keySchema, pool);
    // Replay (re-encode each term against the new dict — TermIds may differ)
    for (var e : oldEdits.entrySet()) {
        // Reverse-decode the encoded bytes, re-encode against newBase
        if (e.getValue() == null) continue;  // deletes are rare here
        encode(e.getValue());  // re-runs the salted-rehash on the new base
    }
}
```

**Important:** Dictionary rebase may produce *different TermIds* for the same
input bytes (extension-table escalation may differ). The connection's other
per-tx tables (indexes, stats) hold those old TermIds in their `MutableMap`s
and need a rewrite step too. This is the hardest part of the runbook.

Two implementation paths:
- **Easy:** force connections to start the tx over on rebase failure (drop
  all per-tx state, rebuild from scratch, replay app-level mutations). Punts
  on the hard problem; ergonomic for the caller.
- **Hard:** maintain a `Map<TermId-old → TermId-new>` after dict rebase and
  rewrite all index/stat buffer entries through it. More work, but lets
  the connection's commit succeed without re-running the application logic.

**Recommendation: start with Easy.** Document the constraint clearly: rebase
on dict-conflict drops all uncommitted writes and tells the application to
retry the transaction. Phase 5 (or 4.1) upgrades to Hard if benchmarks
demand it.

---

## Step 8 — Retry loop in `commitInternal`

**File:** `ProllySailConnection.java`

Replace the current linear commit with the retry loop from the design doc:

```java
@Override
protected void commitInternal() throws SailException {
    for (int attempt = 0; attempt < MAX_REBASES; attempt++) {
        try {
            if (tryCommit()) {
                sail.metrics().increment("sail.commit");
                return;
            }
            // Lost the race — rebase and retry.
            sail.metrics().increment("sail.commit.rebase");
            rebaseAll();
        } catch (DictRebaseConflict e) {
            // Hard path: dict rebase produced different TermIds and we chose
            // Easy. Bubble up so the application re-runs the tx.
            throw new SailConflictException(
                "transaction must be retried at application level: " + e.getMessage());
        }
    }
    sail.metrics().increment("sail.commit.conflict.exhausted");
    throw new SailConflictException(
        "could not commit after " + MAX_REBASES + " rebases");
}
```

`MAX_REBASES` constant: start with 3. Tune based on contention benchmarks.

**Run:** the existing 4 rollback tests should still pass (they don't trigger
rebase, but they exercise the new code path).

---

## Step 9 — Wire metrics

New counters:
```
sail.commit.rebase                        // rebase attempts during commit
sail.commit.conflict.exhausted            // CAS retries exhausted → SailConflictException
sail.rebase.dict.diff_termids             // dict rebase produced different TermIds (Easy path)
sail.rebase.{spoc|posc|ospc|cspo}.replay  // index rows replayed during rebase
sail.rebase.namespaces.replay
sail.rebase.stats.replay
```

New durations:
```
sail.commit.cas.attempt                   // wall time per attempt
sail.rebase.total                         // wall time of one full rebase
```

Each rebase method records its own counters as it walks the mutations.

**Test additions:**
- Counter increment on synthetic contention (use a `CountDownLatch` to force
  two threads to race their commits).
- Counter `sail.commit.conflict.exhausted == 1` after `MAX_REBASES + 1`
  forced rebase failures.

---

## Step 10 — Tests

Implement the 6-test suite from `cas-rebase.md`:

1. `concurrent_committers_both_win_via_rebase`
2. `concurrent_committers_same_statement_idempotent`
3. `concurrent_writer_loses_then_retries`
4. `pathological_contention_throws_after_max_rebases`
5. `rebase_preserves_my_buffered_mutations` (or, on Easy path: documents that
   uncommitted writes are lost on rebase)
6. `term_id_stability_across_rebase` (only meaningful on Hard path)

Test infrastructure:
- A `TestSailContention` harness that pins two writers + a controllable
  contention point (via `Phaser`).
- A `MetricsAssertions` helper that reads counters from a shared
  a `SimpleMeterRegistry` and asserts expected values.

**File:** `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/CasRebaseTest.java` (new).

**Run:** `mvn -pl prolly-rdf4j test -Dtest=CasRebaseTest` — all 6 should pass.
Then full sweep `mvn -pl prolly-rdf4j test`, target 548/548 (was 542 + 6 new).

---

## Step 11 — Performance validation

**Microbenchmark:** the rebase cost vs base commit cost.

```
java -jar prolly-rdf4j/target/benchmarks.jar 'CasRebaseBench'
```

Targets:
- Single-writer commit: ≤ 1.10× the pre-Phase-4 baseline (overhead from
  the CAS check + meta-tree build should be < 10%).
- 2-writer 50/50 contention: ≥ 0.85× single-writer throughput (after
  rebase overhead absorbed).
- 16-writer pathological contention: ≥ 25 commits/s/writer; throws
  `SailConflictException` < 5% of attempts at MAX_REBASES=3.

If any miss: tune (a) `MAX_REBASES`, (b) backoff (currently 0 — add
linear backoff if needed), (c) consider Hard rebase path.

**Record results** in a new `prolly-rdf4j/docs/perf-phase4.md` baseline doc.

---

## Step 12 — Documentation

After landing:

1. Update `cas-rebase.md` row in `docs/README.md`: status from `design` to
   `implemented (Phase 4)`.
2. Add the new metrics to `ARCHITECTURE.md` §8.4 — `sail.commit.rebase` etc.
3. Update `connection-isolation.md`'s "What this unlocks" section: cross
   out the future-tense language, replace with a back-reference to the
   completed Phase 4.
4. Write `docs/perf-phase4.md` with benchmark results, contention curves,
   and recommended `MAX_REBASES` for typical deployments.
5. Add a `CHANGELOG` entry (if the project has one yet — it doesn't, but
   start the convention).

---

## Rollout checklist

Before merging to master:

- [ ] All 12 steps complete; all tests green
- [ ] Performance validation in Step 11 passes targets
- [ ] CodeReview by a second engineer who has read `cas-rebase.md` end-to-end
- [ ] The `Easy` vs `Hard` rebase decision (Step 7) is captured in the doc
      so future maintainers know which mode is in production
- [ ] `cas-rebase.md` status row updated
- [ ] Architecture diagram in `ARCHITECTURE.md` regenerated if the
      Sail-vs-Connection boundary changes

After merge:

- [ ] Tag release `v2.X-phase4-cas-rebase`
- [ ] Annotate release notes with the new SailConflictException class
- [ ] Migration note for any production deployments that override
      `commitInternal` — they must now handle SailConflictException

---

## Risk hotspots

Areas most likely to bite during implementation. Pre-mortem these now.

| Risk | Mitigation |
|---|---|
| Step 5 meta-tree spec drift mid-implementation | Lock the schema in a 1-paragraph addendum to `cas-rebase.md` before writing RootMetaTree.java |
| Dictionary rebase yielding different TermIds breaks ALL per-tx state | Easy path documented as the Phase 4 contract; Hard path is a follow-up |
| `Database.commit`'s `expectedParentHash` semantics aren't what we expect | Add a test that probes Database.commit's race semantics in isolation before integrating |
| Performance regression on single-writer paths (most callers!) | Step 11 measures; if regression, add a fast-path single-writer mode that skips meta-tree build |
| Long-running readers retain old StaticMaps → unbounded chunk retention | Document in Step 12; add a `sail.snapshot.age.max` metric for observability |
| The 6 tests in Step 10 are flaky under load | Use deterministic `Phaser`-based contention; never rely on `Thread.sleep` for timing |

---

## When to abort

Stop the implementation and reconsider the design if:

- Step 5 meta-tree work exceeds 4 days (likely a spec drift; pause and
  re-spec)
- Step 7 Easy rebase doesn't compose cleanly with the existing per-tx
  table commit (forces Hard path earlier than planned; budget +5 days)
- Step 11 performance shows > 30% regression on single-writer paths
  (rethink the CAS architecture; maybe per-table CAS after all)

Abort signals are not failure — they're signals that the design doc
needs a Phase 4.1 revision before continuing.
