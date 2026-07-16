
# ADR-0038: Streaming-seek execution model for the worst-case-optimal triejoin

## Status

Accepted, 2026-05-30. Guides `prolly-rdf/plans/triejoin-performance.md`.
Builds on [ADR-0035](0035-worst-case-optimal-join-for-bgps.md) (WCOJ for BGPs),
[ADR-0034](0034-streaming-triejoin-index-permutations.md) (which declined maintained
graph-leading permutations — "Option C" — on write-amp grounds), and
[ADR-0036](0036-unify-rdf-encoding-on-term-codec.md) (the Phase-2 finding that the triejoin's
wall-time loss is **structural**, not key width). This ADR records the execution-model decision
(D-1 of the plan); the plan records the phased *how*.

**Measurement update (2026-05-30, plan Phase-0 Step 2).** A profiled allocation split corrects the
priority this ADR was written under. The Context below treats per-query *projection* materialization
(`projectScoped` → `MutableMap.flush`) as the dominant cost — that was the pre-measurement estimate.
The measured split (triangle, dense core): projection is **3-5%** of allocation; the **descent
(`solve()`) is 95-97%** (~918 MB of ~944 MB at N=380) — `Cursor.clone`/`TupleBuilder` per seek,
`Tuple` wrappers per row, per-recursion `ArrayList`/`LeapfrogJoin`/`levelIterator`, per-row
`LinkedHashMap`. **Consequence:** the streaming-seek decision (D-1, below) **stands as a model
improvement and still removes the projection cost, but it is no longer the headline performance
lever** — the descent's per-operation allocation (the plan's Phases 2/3/4) is. The plan reprioritizes
accordingly (descent allocation first, projection streaming after). This ADR's *decision* is
unchanged; its *weight* in the perf story is corrected by the data.

## Context

The `LeapfrogTriejoin` (`prolly-rdf`) is correctness-proven and asymptotically worst-case-optimal,
but ADR-0036's re-benchmark measured it ~80× slower than RDF4J's `MemoryStore` on a triangle at
N=380 edges (≈591ms vs ≈7.4ms — 591413µs vs 7438µs, min-of-4), and the TermId conversion did not move that number. A hot-path audit
(2026-05-30) localized the cost: **the engine materializes a fresh sorted prolly tree per pattern
per query.** `LeapfrogTriejoin.projectScoped` (`LeapfrogTriejoin.java:259–283`) opens an
`InMemoryNodeStore` + `MutableMap`, scans the seek-scoped prefix range of a source permutation
index (`Cursor.atKey`, line 268), `put`s each projected row (line 277), and `mm.flush()` **builds a
new tree** (line 283). `solve()` then allocates a `LinkedHashMap` per result row into a fully
materialized `List<Map<String,byte[]>>` (`LeapfrogTriejoin.java:176–187`). Estimated split:
projection rebuild ~50–60%, sort/`SortedProjection` ~20–25%, per-row maps ~10–15% — none of it the
actual join.

**What the join actually needs**, per pattern, is a **sorted + deduped, seekable** iterator over the
pattern's projected columns *in the global variable order*. Two facts reframe the problem:

1. **A seek-scoped range of a maintained permutation index already is that iterator** — *iff* the
   pattern's variables appear in that index's physical column order (the join column, then the next
   variable, … are a contiguous suffix after the bound-constant prefix). The maintained SPOC / POSC /
   OSPC / CSPO trees are sorted, seekable prolly trees. Copying a range of one into a fresh tree is
   pure redundancy.
2. **The dedup the tree gave us is recoverable for free.** `MutableMap` deduped because it is a set;
   over a *sorted* stream, duplicate join-keys are adjacent, so a skip-equal-adjacent step dedups in
   O(1) per skip with **no buffer** — satisfying `LeapfrogJoin`'s sorted-deduped-input precondition
   (the ADR-0033 contract) without materializing.

So the design question is not "is streaming faster" (it obviously avoids the rebuild) but **when is
streaming applicable** — and that reduces to *variable-order alignment*: does some maintained
permutation order this pattern's variables compatibly with the global join order? Where yes, stream;
where no, the projected order is not a contiguous suffix of any maintained index and the engine must
re-sort (materialize). The predicate-bound cyclic triangle — the headline benchmark — is exactly the
case where the three patterns' variable orders cannot all align with the four maintained permutations,
so it is the prime suspect for needing the fallback (or new permutations).

## Options

| Option | Per-query work (per pattern) | Hot-path allocation | Write-amp (commit cost) | Shape coverage | Complexity / risk |
|---|---|---|---|---|---|
| **A** — materialize-per-query (status quo) | O(m log m) tree build | O(m) + O(rows) maps | none | universal | low |
| **B** — stream over maintained permutations, materialize-fallback (this ADR) | O(1)/emitted row where streamable; O(m log m) only on fallback | O(query depth) where streamable | none (rides existing four) | partial — fallback for non-aligned shapes | medium (positioned cursors, alignment logic, two paths) |
| **C** — full covering permutation set, always stream | O(1)/emitted row | O(query depth) | **high** — every extra permutation re-written on each commit | universal | medium (once indexes exist) — but ADR-0034 already weighed the write-amp |
| **D** — lazy / external sort of the projection | O(m log m) buffer | O(m) | none | universal | medium — a weaker A (avoids the *tree* build but not the sort/allocation) |

## Decision

**Adopt B: stream a cursor-backed iterator directly over a maintained permutation index whenever a
permutation orders the pattern's variables compatibly with the global join order; materialize
(Option A) only as the fallback for patterns no maintained permutation aligns.** This captures the
dominant win for the aligned majority at **zero new write-amp**, stays correct everywhere via the
fallback, and defers C's write-amp until measurement proves it necessary. Rejected A (the thing we
are fixing), C up front (its write-amp is the cost ADR-0034 declined — pay it only on evidence), and
D (it removes the tree build but not the O(m log m) + O(m) per pattern, so it leaves most of the gap).

**D-1 — Stream where variable-order-aligned; materialize-fallback elsewhere.** A pattern streams when
some maintained permutation's physical column order, after the pattern's bound-constant prefix, is a
contiguous prefix of the global variable order. Streaming dedups equal-adjacent join keys in O(1)
(no buffer). Otherwise the pattern falls back to `projectScoped`'s materialize-sort.

**D-2 — Per-pattern permutation selection across all four maintained orders.** Today `projectScoped`
chooses only between SPOC and POSC (`LeapfrogTriejoin.java:241–247`); selecting across SPOC/POSC/OSPC/
CSPO makes more patterns streamable with the indexes that already exist.

**D-3 — Pull-based streaming results.** Replace `List<Map<String,byte[]>>` with a pull cursor over the
leapfrog descent that fills a reusable variable-indexed row buffer (`byte[][]` / `long[]`), enabling
`LIMIT` short-circuit. The `List<Map>` form survives as a thin `drain()` for tests (internal, pre-1.0).

**D-4 — Additional permutations (Option C) are an evidence-gated escalation with their own ADR.**
ADR-0034's write-amp verdict stands until a Phase-1 measurement shows the materialize-fallback
dominates an important shape (the cyclic triangle is the suspect). Then, and only then, a successor
ADR re-costs CPSO/CPOS now that ProllySail already maintains four permutations.

**D-5 — TermId `long` fast-path in the streaming iterators.** When all participating columns are
`Encoding.Int64` (the production ProllySail case), the level iterators expose `long` keys and
`LeapfrogJoin` compares/seeks on `long` — no `Tuple`/`TupleBuilder` encode-decode. The generic
`byte[]` path remains for variable-length keys.

**D-6 — The differential oracle gates every change.** Result sets are invariant: every streaming /
fallback / fast-path change keeps `TermIdTriejoinProperty`, `GraphPatternBgpProperty`,
`SailTriejoinOnRealIndexesTest`, and `TriejoinWorkBoundProperty` green. `seekWork()` must not regress
(streaming must preserve the WCOJ work-bound, not just wall-time).

## Consequences

- **Positive.** Eliminates the dominant per-query materialization for aligned patterns; hot-path
  allocation drops from O(rows) to ~O(query depth); streaming enables `LIMIT`/early-exit; **no new
  write-amp** (rides the four maintained permutations); the engine's WCOJ asymptotic advantage starts
  manifesting at practical N because the constant factor falls. Streaming does **not** change the
  algorithm — it changes where the sorted, seekable iterators come from (the source index in place vs
  a per-query copy), so the work-bound is preserved by construction.
- **Negative / cost.** A real rewrite of the iterator-sourcing layer (positioned forward-seek,
  alignment detection, the stream/fallback branch) — more surface to test and a cursor-state
  correctness risk (mitigated by D-6's oracle). **Two execution paths coexist** (stream + fallback)
  until/unless C lands. Most consequentially: the headline triangle may *not* fully stream under B with
  only four permutations — so the marquee benchmark win could be gated on the Phase-5 / Option-C ADR.
  The plan is explicit that the acceptance metric is two-pronged (constant-factor cut **and** a
  demonstrated asymptotic crossover on a worst-case shape), precisely so success does not hinge on
  beating MemoryStore at small N where no disk-tree engine can win.
- **Neutral.** Result sets unchanged (D-6); variable ordering (`SelectivityVariableOrder`) unchanged —
  this ADR is about *how iterators are sourced*, not *what order variables bind* or *what the join
  computes. The single-variable star-join path (`SortedProjection`) is in scope for the same streaming
  treatment but is not on the critical multi-variable path.

## Follow-up / future work

- **Q1 — Does the predicate-bound cyclic triangle stream under B with the existing four permutations,
  or does it need Option C?** Answered by the Phase-1 measurement (plan Step 5/6). If it needs C, the
  triangle's marquee win is deferred to the Option-C ADR (D-4); the path/star shapes should still win
  under B alone.
- **Q2 — Is the materialize-fallback a permanent part of the engine, or a transitional state until a
  covering permutation set?** Decide after measuring fallback frequency on representative workloads —
  a rarely-hit fallback is cheap insurance; a frequently-hit one argues for C.
- The **flag-gated wiring into ProllySail evaluation + W3C correctness gate** (ADR-0035 D-5 / ADR-0036
  Phase-3 deferral) is the plan's final phase — orthogonal to this model decision but unblocked by it.
