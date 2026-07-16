
# ADR-0061: Bulk load writer — a separate MVCC writer that builds once and merges at commit

## Status

Accepted, 2026-06-14 — **Q2 resolved: build** (Phase 2 directed). Guides
`plans/prolly-bulk-load.md` Phase 2. The design's load-bearing premise —
a build-once store's data tree equals an incrementally-built one — is pinned by
`BulkLoadHistoryIndependenceTest` (build-once vs batched yields byte-identical dict + index roots), so the
writer can swap or merge its built root and land a result identical to the normal path. **Q1 (merge-conflict
policy) and Q3 (entry-point shape) remain open** and are decided as the build reaches them; Q1's working
default is *fail* (D-6).

## Context

Loading a large RDF dataset into the versioned store is slow. A 2026-06-13 layered probe attributed the
wall precisely (`plans/prolly-bulk-load.md` Step 4h): it is the **per-commit prolly-tree spine re-walk** —
each `conn.commit()` re-reads the existing tree to merge the batch's edits, and those reads dominate (3.5M
`Get`s for 3M statements, 88% of read time; `db.get` latency grows 9× as the store fills). The cost is the
**read count**, which is set by the number of commits.

Two facts make the fix non-obvious:

1. **Read count and encode cost pull in opposite directions on batch size.** Fewer/bigger commits → fewer
   spine re-walks (commit cheaper) — measured 100k→500k batch ≈ 2.8×, read-opt + 500k = 3.9× the stock
   wall. But the per-transaction `Value→TermId` encode memo (`termCacheTx`, a bounded Caffeine cache) is
   reset per `begin()`; bigger batches hold *more* distinct terms live, so it **evicts** and re-encodes. The
   build-once extreme (single transaction) makes this stark: commit collapses to 8.67 s (`rd=1`, zero
   re-walk) **but encode balloons to 114 s**, so single-tx (150 s) *loses* to the 500k batch (84 s). The
   balanced optimum today is the ~250–500k batch — **shipped as option 1** (`prolly.rdf4j.import.batch-size`
   default 50k→250k + a per-request `?batchSize=` override).

2. **The commit path is single-writer.** `ProllySail.writeLock` is a `Semaphore(1)`; writers serialize and
   each forks from the previous committed root ("first to commit wins; a later connection must re-begin … no
   compare-and-set rebase yet"). Reads, by contrast, are **lock-free MVCC** — the four roots publish as one
   immutable `Snapshot` (`publishedSnapshot`), forked by reference. A batched bulk load through the normal
   path cycles the lock + re-forks per batch.

**Why now / the strategic position.** Option 1 captures most of the practical win cheaply, so the question
is whether the *full* bulk writer is worth building — and if so, its shape. The deciding insight: prolly's
differentiator is **versioning + structural merge** (`MergeEngine`). A bulk writer that builds in isolation
off a forked snapshot and **merges at commit** leverages exactly that — it lets a long-running bulk load run
concurrently with live writes and reconcile them, which a flat/unversioned store cannot do. So the decision
is how to build the bulk-load path to (a) recover the read-count win *without* the encode penalty, (b) not
block live writers, and (c) use the merge superpower — versus simply relying on option 1's bigger batches.

## Options

| Option | Throughput (read-count + encode) | Blocks live writers? | Memory bounded? | Uses merge? | Complexity |
|---|---|---|---|---|---|
| **A** — Bigger batches only (option 1, shipped) | ~3.9× at 500k; couples commit-read-count vs encode-eviction → can't reach build-once | per batch (brief, cycled) | yes (spill) | no | none (knob) |
| **B** — Single-tx build-once on the normal path | commit minimal (`rd=1`) but encode evicts → *slower* (measured 150 s vs 84 s); | for the whole load (holds the lock) | only with bounded `termCacheTx`; not the dict-build | no | low |
| **C** — Separate MVCC bulk writer *(chosen)* | build-once read-count win **+** streaming dict (no encode eviction) → best of both | **no** — builds off the lock; lock taken only at the final swap | yes (spill + streaming dict) | **yes** (swap or 3-way merge) | high |
| **D** — Load into flatsail, then convert | fastest raw load (65k/s) | n/a | yes | no | medium, but throws away versioning during load + needs a convert pass |

## Decision

**Option C — a separate MVCC bulk writer.** It is the only option that recovers the read-count win
*without* the encode penalty *and* keeps concurrency *and* uses prolly's merge — the deciding tradeoff is
that B (build-once) loses the encode race and blocks writers, while A (batches) can't reach build-once;
only C decouples all three. Sub-decisions:

- **D-1 — Fork the published `Snapshot` once** as the MVCC base (lock-free). The whole load reads a
  consistent base; no per-batch re-fork.
- **D-2 — Build each tree once from spilled sorted edits.** Accumulate all edits into `SpillableSortedBuffer`s
  (reuse Phase 1.5's external sort), then run `TreeMutator` once per index permutation + namespaces + stats
  over the sorted stream — one build, not N per-batch rebuilds (the read-count win, total when the base is
  empty).
- **D-3 — Streaming external dictionary build (the load-bearing piece).** Replace the per-transaction
  `termCacheTx` (which evicts over a whole file → the encode penalty that sinks option B) with a sorted
  external dictionary build over the *distinct* terms: sort the terms, assign ids, build the `Dictionary`
  tree from the sorted stream. This is what makes build-once *beat* batched rather than lose to it.
  *Feasibility confirmed 2026-06-14:* `TermId` is **content-addressed** — `encode` derives
  `TermId.ofNatural(hash(term))`, **not** a sequential counter — so a sorted/streaming build assigns each term
  the *same* id as the normal path, order-independent (the earlier "sequential id" belief was wrong). The one
  subtlety is the rare hash-slot **collision**: the salt-walk gives the natural slot to the *first-encoded* of
  two colliding terms, so the streaming build must resolve collisions in an order matching the normal
  (statement-order) path to stay byte-identical — to be pinned by extending the history-independence oracle to
  a collision-bearing corpus before D-3 ships.
  ***MEASURED 2026-06-14 — D-3's external sort is NOT needed for the common case; a config knob suffices.*** The
  single-tx encode wall (114 s at 3M) was the dictionary buffer's per-term dedup `get` going `O(runs)` once the
  `SpillableSortedBuffer` **spilled** — not `termCacheTx`, not the build. Keeping *just the dict buffer in-heap*
  (`-Dprolly.tx.dict.spill.bytes=<high>`; the index buffers are insert-only so they spill harmlessly) drops
  encode **114 s → 8.05 s** and, with read-opt + build-once, lands **3M in 45.5 s (~66k stmts/s, faster than
  flatsail)** at ~5 GiB heap — **7.1× the stock wall, by config, no dictionary rewrite.** So the bulk path's
  speed is `read-opt + dict-in-heap + single-tx`. The external-sort build is **reframed to a future
  optimization** for vocabularies whose *distinct-term set* exceeds heap (the dict-in-heap working set scales
  with distinct terms, not statements); reachable scales (NCIt 10.8M) fit.
  ***RETRACTED 2026-06-13 at scale — "reachable scales (NCIt 10.8M) fit" was a 3M extrapolation, and 10.8M
  refutes it.*** Single-tx 10.8M **OOMs the Java heap** at `-Xmx5g` (RSS 5.6 GiB, under the cgroup cap → a
  heap OOM, not a kill). A single transaction holds the dict-in-heap + encode state live for *every distinct
  term in the whole file*; that working set is **O(graph) on the single-tx path** and tips over an order of
  magnitude sooner than the statement-count framing implied (the 3M run already pinned heap at 5,104 MB).
  Forcing the *index* buffers to spill (64 MiB) does **not** save it — the overflow is the dictionary/encode,
  in the buffering phase *before any flush* (`sst=0` at OOM). Spilling the *dictionary* too bounds the heap
  but reintroduces the O(runs) encode wall (bounded-but-slow). So **D-3's external sorted dictionary build is
  needed at ~10M, not 500M** — it is the component that gets presort *bounded and fast*, and it is no longer a
  future optimization. The `dict-in-heap + single-tx` config is the fast build-once path only to ~3–5M; past
  that the external dictionary is the genuinely-new piece the throughput cliff demands. Measured throughput
  contrast (batched spine-walk vs single-tx presort): 48k→33k→**9.5k** vs 62k→66k→**OOM** stmts/s at
  1M/3M/10.8M. Build-log: `blog/build-log-the-cliff-was-the-batching.md`.
- **D-4 — Build entirely off `writeLock`.** D-1…D-3 run with **no** `Semaphore` held, so live writers keep
  committing concurrently for the whole (long) build. This is the concurrency win the normal path can't give.
- **D-5 — Commit via swap-or-merge.** At the end, acquire `writeLock` *briefly*: if `publishedSnapshot` still
  equals the forked base (no concurrent writer), advance the roots directly (one `publishSnapshot`); if it
  advanced, run a **3-way structural `MergeEngine` merge** (base, bulk-built, current) and publish the result.
  Release. The lock is held for the swap/merge only — microseconds, not the load.
- **D-6 — Conflict policy: FAIL by default** (see Q1). A merge conflict means the bulk load and a concurrent
  writer wrote the *same* key differently. Bulk loads are insert-heavy (new subjects), so this is pathological;
  silently last-writer-winning a whole bulk load is dangerous, so the safe default is to fail the bulk commit
  with a clear error (operator retries / resolves). The retry is cheap because the build is already done — only
  the merge re-runs against the newer base.
- **D-7 — Dedicated entry point, not the Sail commit path** (Goal #1; see Q3). A bulk-load mode (CLI
  subcommand and/or a `?bulk=true` route that dispatches to the `BulkWriter`), so normal interactive writes
  are untouched and the memory/throughput trade is opt-in.

## Consequences

- **Positive.** Recovers the full throughput win (build once) *without* the encode penalty (streaming dict),
  *without* blocking live writers (off-lock build), *and* with merge reconciliation — the combination no
  single existing path offers. It is also the design where versioning *earns its keep*: a long bulk load
  reconciled with live traffic via cheap structural merge is something a flat store structurally cannot do.
- **Negative / cost.** A substantial new component. The **streaming external dictionary build (D-3) is the
  hardest part** — sorting terms, id assignment, building the `Dictionary` tree from sorted distinct terms,
  and the bytes↔`TermId` mapping — and it is also the part without which C degenerates to B (loses). The
  **merge-at-commit (D-5/D-6)** adds a conflict path + its policy. **Garbage-collection interaction:** the bulk writer's new
  chunks must be reachable before any concurrent garbage collection sweeps them — the existing flush-window
  discipline (`bugs/gc-concurrent-write-flush-window`) applies and must be pinned by a test.
- **Neutral / punted.** Whether to build it at all is Q2 — option 1's 250k batch + `?batchSize=` override may
  already be "good enough" for a deployment's bulk needs; the build-once measurement says C only wins once D-3
  exists, which is real work. Build C only when a deployment is *proven* to need more than batched throughput.

## Follow-up / future work

- If accepted (Q2 = build), a Phase 2 plan: the `BulkWriter` orchestration (D-1/D-2/D-4/D-5), the streaming
  external dictionary build (D-3), the conflict path (D-6), the entry point (D-7), and a garbage-collection-reachability test
  for the bulk writer's chunks.
- A correctness oracle (already in the plan's Goal 4): a bulk-loaded store must have the **same root hash** as
  the same data loaded through the normal path — history-independence pins that the bulk writer builds the
  identical tree.

## Open questions

- **Q1 — Merge-at-commit conflict policy.** D-6 proposes *fail* (safe default). Alternatives: *retry-rebase*
  (re-merge against the newer base automatically, bounded retries) or *last-writer-wins* (dangerous for a bulk
  load). Insert-heavy loads rarely conflict, so fail-then-retry is low-cost — but the policy is a contract a
  client depends on.
- **Q2 — Build it, or is option 1 enough?** The build-once measurement shows C only beats batched *with* the
  streaming dictionary (D-3), which is significant work. Decide on evidence that a real deployment needs more
  than the shipped 250k batch + `?batchSize=` override — not on a benchmark.
- **Q3 — Entry-point shape.** CLI subcommand (`prolly bulk-load`), an HTTP `?bulk=true` route to the
  `BulkWriter`, or both? The CLI fits the "operator one-shot" model; the HTTP route fits the existing import UX.
