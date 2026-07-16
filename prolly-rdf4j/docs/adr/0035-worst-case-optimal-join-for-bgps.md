
# ADR-0035: Worst-case-optimal join for basic graph patterns

## Status

Accepted, 2026-05-29. Guides `prolly-rdf/plans/multi-variable-leapfrog-triejoin.md` (Phase 6, Step 21). Sibling of [ADR-0033](0033-prolly-rdf-test-methodology.md) (test methodology); depends on [ADR-0034](0034-streaming-triejoin-index-permutations.md) (index permutations).

## Context

A basic graph pattern (BGP) with several variables — e.g. the triangle
`?x :e ?y . ?y :e ?z . ?z :e ?x` — is a multi-way join. The engine had only a
**single-variable star join** (`GraphPatternEngine.execute`: project each pattern
onto one shared variable, intersect). That cannot bind a full multi-variable
pattern, and the standard alternative — RDF4J's **left-deep bind-join** (the
`DefaultEvaluationStrategy` the `ProllySail` already delegates to) — is a binary
join plan: it materialises pairwise intermediates. On a cyclic query such a plan
is provably **Θ(N²)** in the worst case, even when the output is far smaller.

The known better answer is a **worst-case-optimal join (WCOJ)**: a leapfrog
triejoin binds variables hierarchically and intersects the participating
relations level by level, achieving the **AGM bound** — `O(N^1.5)` for the
triangle — with **no O(N²) intermediate**. The question this ADR settles: should
the port adopt a WCOJ for BGPs, in what form, and — given a real measurement —
should it become the default evaluator?

The deciding context is that this is a **pre-1.0 engine with a working,
optimised incumbent** (RDF4J in-memory evaluation). A new join is only worth
making the default if it is *both* correct *and* faster in practice — not merely
asymptotically better. Phase 4 measured exactly that.

## Options

| Option | Worst-case time (triangle) | Peak intermediate | Wall-time, measured | Index needs | Complexity |
|---|---|---|---|---|---|
| **A** — RDF4J bind-join only (status quo) | `Θ(N²)` | `Θ(N²)` | fast (optimised, in-memory) | none new | none |
| **B** — Leapfrog triejoin (this work) | `O(N^1.5)` | `O(N)` | **~80× slower** as built (per-query rebuild, alloc-heavy) | raw-IRI SPOC/POSC; graph-leading for true streaming | high |
| **C** — Free Join / hybrid (WCOJ + binary, factorised) | `O(N^1.5)` | `O(N)` | best-of-both (not built) | maintained multi-perm | very high |

Sub-axis that drives the index decision (per ADR-0034): a triejoin streams a
pattern only from an index whose **constants form a prefix and variables follow
the global order**. The constant graph column trails in `SPOC`/`POSC`, so neither
streams a constant-graph pattern — true streaming needs **graph-leading**
permutations (`CPSO`/`CPOS`).

## Decision

**D-1 — Build the leapfrog triejoin (Option B), proven correct against
differential oracles.** It is the only option that binds full multi-variable
patterns *and* handles cyclic queries (the star join cannot; the triangle has no
SPOC-consistent variable order). Correctness is gated by nested-loop /
brute-force oracles (`MultiVarTriejoinProperty`, `TriangleTriejoinProperty`) and,
end-to-end, by equality with RDF4J's own SPARQL evaluation over `MemoryStore`
**and** `ProllySail` (`TriejoinVsRdf4jAgreementTest`, `TriejoinDifferentialTest` —
as set **and** multiset).

**D-2 — The asymptotic wins are real and were measured, not asserted.**
Deterministic counters (`TriejoinScalingEvidence`, fitted log-log slopes): triangle
**work ≈ N^1.5** (slope 1.48) with **O(N) peak materialised intermediate** (slope
1.00) on a triangle-dense core; on the adversarial star, triejoin work ≈ N^0.98
while a binary plan's intermediate is **Θ(N²)** (slope 1.96). This is the
"no O(N²) intermediate" (space) win and the WCOJ (time) bound, independently
confirmed.

**D-3 — Do NOT make it the default evaluator yet; keep it standalone and
flag-gated-off.** The JMH/indicative wall-time (`TriejoinVsRdf4jBenchmark`) shows
the triejoin **as built is ~80× slower** than RDF4J `MemoryStore` at N=380 on the
dense core, and diverges with N. Three causes: it rebuilds the projected indexes
on every call (O(N) scan+sort), it is allocation-heavy (a `byte[]`/`TupleBuilder`
per seek, a `Cursor.clone()` per descend — no value types), and the dense core
isn't binary-adversarial so RDF4J's hash joins are fine. **Asymptotically better ≠
faster here.** Wiring it as the default would regress production BGPs. The
deciding tradeoff: a pre-1.0 engine should not replace a fast, correct incumbent
with a slower path on the strength of asymptotics alone.

**D-4 — The triejoin stays a validated, standalone, non-default engine; the
wall-time project is shelved.** The path to a real wall-time win would have been
ADR-0034 Option C (maintained `CPSO`/`CPOS` permutations, killing the per-query
rebuild) + constant-factor work (buffer reuse, value types when Valhalla lands).
**That path is declined (2026-05-29):** the operator judged Option C's standing
cost (4→6 indexes per write, ~50% more index storage) not worth its benefit, given
Step 16 showed the triejoin loses on wall-time regardless. So the leapfrog triejoin
is **research-complete and parked**: correct, asymptotically optimal, reachable via
`VersionedQuadStore.queryMulti`, but not on any hot path and not slated to become
competitive. Revisiting requires a new motivating workload, not just "speed up the
triejoin" (see ADR-0034 Update).

**D-5 — Live SPARQL wiring (route ≥2-variable BGPs through the triejoin) is
deferred indefinitely.** It needs a custom `EvaluationStrategy` bridging
`executeMulti` (raw-IRI indexes) to `ProllySail`'s dictionary-encoded `TermId`
index. With D-4 shelved (the wired path would still be slower than RDF4J), there is
no near-term reason to build the bridge; RDF4J's bind-join remains the production
evaluator.

## Consequences

- **Positive:** the engine now has a correct, cyclic-capable multi-variable join
  with proven worst-case-optimal work and linear intermediate; a reusable benchmark
  + counter harness; and an honest, measured picture of where it stands. The
  `VersionedQuadStore.queryMulti` raw-IRI entry point is live and oracle-tested.
- **Negative / deferred:** the triejoin is **not** in the production SPARQL path —
  RDF4J's bind-join still serves every query, so the `Θ(N²)`-on-cyclic worst case
  remains in practice until D-4/D-5 land. The standalone differential + agreement
  tests substitute for the W3C-through-the-Sail gate (ADR-0034 / plan Step 18)
  meanwhile.
- **Neutral:** the work is sequenced so the expensive, irreversible pieces
  (maintained on-disk permutations, a custom evaluation strategy) are taken only
  when a measurement says they pay off — consistent with the pre-1.0 "evolve the
  format once, with evidence" stance.

## Follow-up / future work

- **The wall-time project (D-4 + D-5) is shelved (2026-05-29)** — maintained
  permutations declined on cost (ADR-0034 Update). No follow-on planned; the
  triejoin stays parked as a validated standalone engine. The trigger to reopen is
  a *new* motivating workload (selective cyclic patterns at a scale where RDF4J's
  in-memory joins themselves break down), not "make the existing triejoin faster".
- **Free Join (hybrid WCOJ + binary):** the academic frontier if a WCOJ is ever
  revisited — a separate, later decision, not on any current track.

## Open questions

- **Q1** — Should the default, once competitive, be *cyclic-only* (route only BGPs
  where a binary plan is provably quadratic) rather than all ≥2-variable BGPs?
  Phase 4 suggests RDF4J stays competitive on acyclic/star shapes, so a
  shape-gated router may beat an all-or-nothing switch. Decide with the D-4
  benchmark in hand.
