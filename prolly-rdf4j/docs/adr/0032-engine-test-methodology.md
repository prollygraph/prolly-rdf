
# ADR-0032: Engine test methodology

## Status

Accepted, 2026-05-29. Guides `plans/core-engine-test-strategy.md`.

## Context

The prolly-tree engine (`prolly-port-core` + `prolly-codec`) is the foundation
the whole stack sits on: a content-addressed, history-independent Merkle B-tree.
Its existing suite is *strong by example* — PIT measured **94% mutation / 97.3%
line / 94.3% branch** — yet the bugs that actually matter in a structure like
this are the ones example tests structurally miss: subtle **ordering/boundary**
cases (signed-vs-unsigned compare, empty-vs-null fields, chunk-boundary shifts),
**concurrency** races, and **crash/durability** edge cases. Several such bugs
*had* shipped (the reverse-iteration contract, the `TermId` signed/unsigned
index-order trap, a `decodeFloat64` boundary, a tuple null-field ambiguity).

The question is **not** "write more tests" — it's *how* to test "every single
aspect" so the claim is **checkable**, not aspirational. That requires a
methodology: a way to explore the input space (not just hand-picked points),
independent oracles (not the engine judging itself), tools that *prove the
absence* of race/crash classes (not merely make them rare), and gates that
catch the next regression. Deciding now, before adding ~dozens of tests, so
they're organized around invariants rather than accreting ad hoc.

## Options

| Option | Bug classes caught | Setup cost | Offline / fast default | Assurance |
|---|---|---|---|---|
| **A** — More example tests (status quo) | the ones you think to write | low | yes | reduces probability; misses ordering/race/crash classes; mutation score plateaus |
| **B** — Invariant-centric, multi-method (PBT + dual oracles + fuzzing + Lincheck/jcstress + DST, gated) | ordering/boundary (PBT+shrinking), semantic drift (oracle), parser hostility (fuzz), races (Lincheck/jcstress), crash (DST) | medium — new test deps + a generator lib + harnesses + one test-only module | default stays fast+offline; heavy tiers gated behind profiles/modules | **proves absence** for race/crash classes; explores the space; coverage becomes a checkable matrix |
| **C** — Heavyweight formal methods (model the engine in TLA+/Alloy) | spec-level design bugs | very high | n/a | highest on the *model* — but doesn't test the actual Java bytes / on-disk format; high maintenance |

## Decision

**Option B** — an invariant-centric, multi-method discipline. Nine sub-decisions:

- **D-1 — jqwik for property-based testing, not hand-rolled `SplittableRandom`
  loops.** Automatic *shrinking* (a failure reduces to a minimal counterexample)
  is the single highest-leverage upgrade for an ordering/boundary-bug-prone
  structure. Cost: a new test dep + a generator library.
- **D-2 — A shared domain generator library (`gen`) built first.** Tuples,
  key→value maps, every `Encoding`'s boundary values (±0.0, INF/NaN, `Long.MIN`,
  empty/huge strings, BCE dates), edit scripts, three-way scenarios — curated
  once so boundary cases are never re-forgotten.
- **D-3 — The reference oracle is `java.util.TreeMap`; Dolt/Go is a
  *characterization* oracle, not an authority.** `TreeMap` proves *semantic*
  correctness (I-2) in-process. **(Revised 2026-05-29:** byte-for-byte Dolt
  parity is **optional, not a goal** — the port is not byte-compatible with Dolt
  v2.0.3, a multi-layer divergence confirmed by the Layer-3 experiment; see
  `cross-lang/BITCOMPAT_FINDINGS.md`. So the engine's *own* deterministic format
  is the contract; the cross-language fixture pins the parity that *does* hold
  (Layers 0–2) and characterizes the frontier rather than asserting Go
  equality.**)** Never test the engine against itself for byte-level claims — a
  self-consistent bug passes.
- **D-4 — Cross-language parity is property-based behind a `-Pcross-lang`
  profile, kept off the default build.** The default build stays Go-free +
  offline (golden vectors give offline bit-parity *characterization*). **(With
  D-3's revision, the Go-parity property/boundary sweeps are deferred — they
  assert an optional goal.)**
- **D-5 — Jazzer (coverage-guided) for the deserializer, with a committed crash
  corpus.** Node/tuple parsing consumes adversarial bytes; a hostile chunk must
  never crash the JVM/hang/OOM/mis-parse. Every found crasher becomes a
  permanent regression seed.
- **D-6 — Lincheck for linearizability, jcstress for the memory model —
  distinct tools for distinct questions.** Lincheck: "is the concurrent API
  equivalent to *some* sequential execution?" jcstress: "does this lock-free /
  `MemorySegment` / publication access exhibit a JMM data race?" Stress tests
  answer *neither* rigorously — they make races rare, not absent.
- **D-7 — Deterministic Simulation Testing (DST) for durability** (FoundationDB/
  TigerBeetle style): one seed drives the whole schedule — keys, commits, where
  the crash/torn-write/bit-flip lands — so a failing seed reproduces bit-for-bit.
  Generalizes the two hard-coded crash modes of `CrashRecoveryAtomicityTest`.
- **D-8 — Quality gates are pinned numbers that fail the build, set *just below
  a measured baseline*** (not aspirational). A gate that fails on day one is
  noise; one pinned to reality catches the next regression. Baseline (Step 3):
  mutation 94% / line 97.3% / branch 94.3% → gates 90% / 95% / 92%. Generated
  `serial/**` bindings excluded.
- **D-9 — Invariants are executable specifications, named I-1…I-8, and every
  test cites the invariant it defends.** A test that maps to no invariant is
  testing an implementation detail (fragile) or found a missing invariant (name
  it). This makes "test every aspect" *checkable* — the coverage matrix
  (`TESTING.md`) has a row per invariant with no empty load-bearing cell.

## Consequences

**Positive.** The discipline already paid off: PBT shrinking surfaced the
reverse-iteration contract and the tiny-tree churn-bound subtlety; the codec
isolation suite found the `TermId` signed-index/unsigned-`compareTo` ordering
trap; Lincheck *proved* the content-addressed store and the `Database` commit
OCC linearizable (no lost update); DST proved durability + bit-flip detection
over seeded crash schedules. Coverage is now a legible matrix, not a vibe.

**Negative / costs.** New test-scope dependencies (jqwik; Lincheck + jcstress,
isolated in a dedicated `prolly-concurrency` test-only module to keep the
Kotlin/instrumentation toolchain out of production modules; Jazzer pending).
PIT now runs slower against the property suites, so the mutation gate belongs in
a background/CI job, not the default build. **Dolt byte-parity is unmet and
deliberately deferred** — the cross-language oracle is characterization-only
until/unless a future ADR rules parity a goal (a multi-layer, format-breaking
project).

**Neutral / punted.** Gates (mutation/coverage thresholds, CI profiles, JMH
guard) are decided (numbers above) but not yet wired as build-failing checks.
jcstress is wired but its first real test (the `indexRoots` publication smell)
is a follow-up. DST torn-write mode is deferred (crash-mid-write covers the same
invariant at the application boundary).

## Follow-up / future work

- Wire the D-8 gates (PIT/JaCoCo thresholds, `-Pfuzz`/`-Pconcurrency`/
  `-Pcross-lang` profiles, JMH regression guard) — plan Steps 27–30.
- The jcstress `indexRoots`-publication test — plan Step 22 / D-6.
- Jazzer deserializer fuzzing + corpus — plan Steps 4–5 / D-5.
- The sibling-layer methodologies reuse these decisions: `prolly-rdf`,
  `prolly-rdf4j` (differential vs RDF4J `MemoryStore`), and `prolly-rdf4j-rest`
  (authorization matrix + boot-smoke) have their own plans.

## Open questions

All decided at write time (the bit-compat goal — the one prior open question —
was resolved 2026-05-29: optional/deferred, see D-3).
