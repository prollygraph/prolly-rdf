
# ADR-0033: prolly-rdf test methodology

## Status

Accepted, 2026-05-29. Guides `prolly-rdf/plans/prolly-rdf-test-strategy.md`.
Sibling of (and inherits) [ADR-0032](0032-engine-test-methodology.md), which
decided the same discipline for the prolly-tree *engine* (`prolly-port-core` +
`prolly-codec`).

## Context

`prolly-rdf` is the layer **above** the engine: it turns the content-addressed
prolly tree into a *versioned RDF store* — `Database` (commit/branch/merge/LCA/
blame/bisect), the dictionary + the SPOC/POSC permutation indexes,
`GraphPatternEngine` (basic-graph-pattern evaluation), `SyncEngine` (pull/push),
a garbage collector, and crash recovery over RocksDB. Its correctness invariants
are therefore **different** from the engine's I-1…I-8 — they are the versioning,
query, sync, and GC laws, named **R-1 … R-10** in the plan.

[ADR-0032](0032-engine-test-methodology.md) already decided *how* to test "every
aspect" of the engine — invariant-centric, multi-method (property-based testing +
differential oracles + fuzzing + Lincheck/jcstress + deterministic simulation),
with quality gates set from a measured baseline. The question here is narrow:
**does the prolly-rdf layer just inherit that discipline, or does it have
module-unique testing decisions worth recording?** It does — two of them (the
oracle shape and the cross-subsystem simulation), plus this layer's gate
baseline differs and its bit-compat exposure is nil. And the prior state was
genuinely bad: the legacy suite had **no JUnit `@Test` methods at all** — ~44
`*Test` classes ran via a reflective `main()`-driver where a clean return counted
as a pass, so the dominant failure mode was tests that *could not fail*.

## Options

| Option | Bug classes caught | Oracle | Setup cost | Assurance |
|---|---|---|---|---|
| **A** — Keep the `main()`-style example suite (status quo) | the ones already thought of; many tests structurally can't fail (0 throws → green) | none (self-asserting prints) | none | false — "all green" was silent about real bugs |
| **B** — Inherit ADR-0032's invariant-centric multi-method discipline, specialized to R-1…R-10 with an **in-memory history/relational differential oracle** + a **cross-subsystem DST** | versioning-algebra (PBT+shrink), index/query drift (oracle), corruption (fault inject), races (Lincheck), crash (DST), parser hostility (fuzz) | a `TreeMap`/relational model the engine is diffed against | medium — generators + harnesses (reuses ADR-0032's tooling + the `prolly-concurrency` module) | **checkable** — a matrix with no empty load-bearing cell; found 2 real bugs |
| **C** — End-to-end SPARQL/integration tests only | high-level regressions | the SPARQL spec | low | misses the seams — an index/join/LCA bug is untriggerable or unobservable through SPARQL (the "green at the top ≠ green all the way down" trap) |

## Decision

**Option B** — inherit ADR-0032's discipline, with these prolly-rdf-specific
sub-decisions:

- **D-1 — Invariants are R-1 … R-10, each an executable spec.** Versioning
  algebra, snapshot isolation, durability, GC safety, index/dictionary
  consistency, query correctness, sync convergence, integrity, storage contract,
  RDF facade. Every test cites the R it defends; the coverage matrix
  (`TESTING.md`) has a row per R with no empty load-bearing cell.
- **D-2 — Make the suite *able to fail* before adding more.** The Phase-0 fix:
  the `main()`-driver (`MainMethodTests`) treats clean-return as PASS, so a
  `main()` with 0 throws is green by construction. Repaired the dead-soft ones
  (`GCTest`/`ConcurrencyTest`/`DebugMutationTest`/`IntegrityTest`) to assert real
  invariants; an assertion that can't fail is worse than no test.
- **D-3 — The differential oracle is an in-memory history/relational model.**
  At this layer the reference is a `TreeMap` (or last-write-wins map / set /
  nested-loop join) that *replays the same operations*, and the engine is
  asserted equal to it. This is the prolly-rdf analogue of ADR-0032's
  `TreeMap`-as-sorted-map oracle, lifted to commits, merges, indexes, and BGP
  results. Generators live in `prolly-rdf/src/test/.../gen/RdfGenerators`.
- **D-4 — Drive the *real* collaborators, not doubles.** Properties run against
  the real `Database`/`StaticMap`/`VersionedQuadStore`/`SyncEngine` over real
  (in-memory or RocksDB) stores — never a hand-rolled mock. This was not
  cosmetic: the `LeapfrogJoin` bug first reproduced against a `ListIter` double
  (which could have been the double's fault) and was only *confirmed* by
  re-running over real `StaticMap.iter()` iterators.
- **D-5 — Cross-subsystem deterministic simulation (DST).** Durability/GC/sync
  are tested with `SplittableRandom`-seeded schedules (commit / crash+reopen /
  plant-orphan+GC) diffed against the oracle, so a failure replays bit-for-bit
  from its seed (`DeterministicSimulationTest`, `…FaultTest`,
  `GcReachabilitySimulationTest`). The full multi-actor program (commits+merges+
  GC+sync interleaved) is the deferred capstone.
- **D-6 — Concurrency lives in `prolly-concurrency`** (the shared Lincheck/
  jcstress module), not prolly-rdf test scope. `DatabaseCommitOccTest` proves the
  commit-OCC manifest-CAS linearizable (R-2, no lost update).
- **D-7 — Gates are set *just below a measured baseline*** (inherits ADR-0032
  D-8). Measured 2026-05-29: line 97.1% / branch 86.1% / instruction 96.2% →
  `jacoco:check` gates line ≥95 / branch ≥83 / instr ≥94 (bound to `verify`).
  PIT runs in the `-Pmutation` CI/background job (multi-hour vs the RocksDB +
  property + DST suites); its threshold is pinned from the first measured CI run,
  not guessed.
- **D-8 — Fuzz the untrusted-byte parsers** (inherits ADR-0032 D-5). The
  surfaces that consume bytes the layer didn't produce — `Commit.deserialize`,
  `RefsStore` branch names, `CommitLog` lines — get Jazzer `@FuzzTest` harnesses.
- **D-9 — Bit-compatibility is inherited-resolved, not re-opened.** Per ADR-0032
  D-3, Dolt byte-parity is *optional/deferred*; prolly-rdf adds no bit-compat
  surface of its own, so there is no open bit-compat question at this layer.

## Consequences

**Positive.** The discipline paid off immediately at this layer: property-based
testing with shrinking found **two real engine bugs** that the example suite had
missed for the project's lifetime — (1) `LeapfrogJoin` never sorted its iterators
by head key, so the worst-case-optimal join returned *false* intersection members
(**fixed**); (2) `GraphPatternEngine`'s star-join fed an *unsorted* projection to
LeapfrogJoin when a pattern had an unbound position between its bound prefix and
the join column, silently missing matches (**fixed** — a `SortedProjection`
wrapper sorts + dedups each projection; the streaming-optimal alternative of
covering index permutations is deferred, below). Both are recorded in
`newcomer-docs/foundations/the-leapfrog-join-contract.md`.
Coverage is now a legible R-1…R-10 matrix, and the suite *can fail*.

**Negative / costs.** Branch coverage (86%) trails the engine's (94%) — the
honest signal that this layer has more untested branches. New test deps (jqwik,
Jazzer in rdf4j) and generator/harness code. PIT is too slow for the default
build, so the mutation gate is CI-only.

**Neutral / punted.** Three advanced concurrency items are deferred with
rationale: the GC↔concurrent-write latch harness (R-4 boundary), the
deterministic-scheduler snapshot-isolation upgrade (R-2), and the jcstress JMM
publication test — each needs machinery (a mark/sweep seam, a yield-point
scheduler, the forked jcstress runner) beyond a single pass. The R-6
`GraphPatternEngine` bug is **fixed** (a `SortedProjection` sort+dedup wrapper);
its streaming-optimal alternative (covering index permutations per access
pattern) is a deferred *optimization*, not a correctness gap.

## Follow-up / future work

- Pin the `-Pmutation` threshold from the first CI run (D-7); capture the
  full-module mutation score.
- The deferred trio: GC↔write boundary (plan Step 22), deterministic-scheduler
  snapshot isolation (Step 24), jcstress publication (Step 25) — and the
  multi-actor DST capstone (Step 29).
- (Done — `GraphPatternEngine` unsorted-projection bug fixed via `SortedProjection`.)
  The streaming-optimal index-permutation variant (OSPC/SOPC/PSOC covering
  indexes) remains an optional performance follow-up.

## Open questions

All decided at write time. The one prior open question across the test-strategy
work — Dolt bit-compatibility — was resolved by ADR-0032 D-3 (optional/deferred)
and does not re-arise at this layer (D-9).
