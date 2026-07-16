
# ADR-0056: RDF4J Sail test strategy

## Status

Accepted, 2026-06-11. Guides `prolly-rdf4j/plans/prolly-rdf4j-test-strategy.md`
(31 steps, Phases 0–8) and its follow-ons
`prolly-rdf4j-test-strategy-followons.md`.
Fourth in the family — `core-engine` → `prolly-rdf` → *this* (the Sail) → `prolly-json`.

## Context

`prolly-rdf4j` is the RDF4J **Sail** over the versioned prolly quad store. Unlike the lower layers, it
arrived with a *mature, example-based* suite — ~151 test files, six RDF4J abstract conformance suites
subclassed, the W3C SPARQL manifests run as a ratchet, no print-and-pass tests. So the problem was **not**
"make tests able to fail." It was the gap between *"passes our examples"* and *"provably equivalent to a
reference, and correct under interleavings and crashes"* — the invariants no single example pins: that the
Sail returns exactly what RDF4J's own `MemoryStore` does for any operation stream (S-2); that transactions,
isolation, and the single-writer lock hold under real concurrency (S-4); that a crash recovers to the last
durable commit (S-8); that peers converge (S-9); and that the W3C conformance frontier is *governed*, not
quietly drifting (S-11).

Two constraints made the strategy non-obvious. First, a versioned content-addressed Sail is a
**concurrency + durability** artifact, not just a query engine — so the assurance has to reach into
interleavings and crash boundaries, where example tests are blind. Second, mid-program the toolchain moved
to **Java 25** (class-file v69, 2026-06-05), and the JVM bytecode-instrumentation tools the concurrency /
mutation phases depend on were not all ready for it — which forced a decision about *where* each invariant
can actually be proven.

## Options

| Option | Assurance | What it catches | Cost / feasibility on this stack |
|---|---|---|---|
| **A** — Examples + W3C conformance only (status quo) | "passes our examples" | regressions in the cases someone thought to write | cheap; **blind** to reference-divergence, rare interleavings, crash boundaries |
| **B** — Layered: differential oracle vs `MemoryStore` as the spine + property / metamorphic / concurrency / deterministic-simulation layers + a managed conformance ratchet | "provably equivalent to a reference; correct under interleavings + crashes; frontier governed" | reference-divergence (shrunk to a minimal counterexample), lost updates, torn recovery, sync divergence, silent conformance drift | moderate; some slow suites (nightly tier); a few bytecode tools need toolchain-bump upkeep |
| **C** — Full formal / exhaustive model-checking of every path | highest in principle | everything the model encodes | infeasible here — the commit path hashes (`MessageDigest` livelocks model-checkers) and the Sail class graph defeats the instrumenting checker; would stall, not assure |

## Decision

Adopt **Option B**, the layered strategy. The sub-decisions:

- **D-1 — The differential oracle against RDF4J `MemoryStore` is the spine (S-2).** This is the one layer
  in the whole stack with a *correct, independent reference implementation already on the classpath*.
  Running identical generated operation/query streams against `ProllySail` and `MemoryStore` and asserting
  equality — shrinking any mismatch to a minimal counterexample — is the highest-leverage move available,
  and the other phases hang off it. Values are compared by a **normalized, kind-tagged key**, not
  cross-implementation `Statement.equals`, so a mismatch is a real divergence, not an `equals` quirk
  between two value models.
- **D-2 — jqwik generators are the shared substrate.** One generator for RDF values / quads / op-streams,
  built once (Phase 0), unblocks S-2, S-3, S-5, S-6, S-7 alike — and the same shape extends to sync
  op-streams and crash streams. Generators stay inside the *round-trippable* domain; the format gaps are
  Phase-8 negatives, not smuggled into the substrate.
- **D-3 — The three-layer verification taxonomy for concurrency + durability (S-4, S-8).** A correctness
  claim has three provable layers: the **theorem** (the mechanism — a happens-before chain, a
  mutual-exclusion argument), the **functional + stress** proof (the wiring — every site actually drives
  the mechanism, under a real-thread oracle), and the **adversarial scheduler** (both at once, under
  enumerated interleavings). Use the instrument that *fits*: Lincheck proves the `Database` compare-and-set
  linearizable where its class graph permits; jcstress proves the root-publication safe-publication pattern
  on a minimal faithful model; a real-thread oracle proves the `ProllySail` single-writer lock where the
  Sail class graph defeats Lincheck. The test that earns trust is "*can you write the happens-before
  chain?*"; the scheduler is the belt to the theorem's suspenders.
- **D-4 — Conformance is a managed ratchet, not a hiding place (S-11).** Every accepted W3C / contract
  failure is categorized — *architectural* / *encoding-format* / *unimplemented* — with a roadmap step or
  an explicit out-of-scope ruling (`docs/conformance-frontier.md`). The
  baseline may only **shrink** (a size-cap gate forbids silent growth); a non-baselined failure breaks the
  build. The frontier is visible and governed.
- **D-5 — Document the isolation contract the Sail *actually* offers; never advertise a fiction.** The
  `ProllySail` runtime is isolation-level-**independent**: every transaction forks a consistent snapshot of
  the committed roots and reads immutable trees, i.e. it always provides serializable-grade isolation
  regardless of the requested level. It advertises the full RDF4J `IsolationLevel` ladder and defaults to
  `SNAPSHOT` — and the test asserts *that* specific advertised contract, rather than pretending each level
  is independently enforced. (The Step-14 honesty correction: the inherited default advertised a level the
  Sail didn't list as supported.)
- **D-6 — Treat the Java-25 / instrumentation-tool disturbance as a design input, not a defeat.** The
  21→25 transition disturbed three instrumentation tools — but via **three different mechanisms, not one
  shared cause** (an earlier draft of this decision over-generalized them as "an ASM that predates v69";
  that is wrong and is corrected here): jcstress's annotation processor stopped emitting because **JDK 23+
  disabled implicit classpath annotation-processing** — fixed with `-proc:full` + an explicit processor
  path (Step 20), *not* an ASM issue, and the runtime works on v69; `pitest` 1.16.1's bundled **ASM cannot
  read class-file v69** — the one genuine v69/ASM break (Step 30; fixed 2026-06-11 by bumping `pitest` to
1.25.4, whose shaded ASM 9.9.1 knows v69 — overriding the plugin's ASM was futile, since pitest shades its
own); and Lincheck **cannot retransform the
  RDF4J Sail class graph** (Step 19) — which is *not* a blanket v69 issue at all, since it instruments the
  lean v69 `Database` graph fine (root cause undiagnosed — an ASM edge case on a complex RDF4J class, or a
  Lincheck transform bug). The unifying point is therefore a shared **lesson**, not a shared cause:
  instrumentation tools (bytecode rewriters, annotation processors, agents) are tightly coupled to JDK
  internals — the class-file version, the annotation-processing defaults, the agent/retransform rules — so
  a toolchain bump's blast radius concentrates there, along *more than one* axis. The strategy's response
  is principled: **prove each invariant at the layer where an instrument can attach** (the `Database` level
  for Lincheck, a minimal model for jcstress), and treat a dark gate as a **tracked follow-on**, never a
  silent hole — the discovery that a gate had silently stopped running is itself a finding the program
  records, not buries.

## Consequences

**Positive.** Reference-equivalence is provable, not asserted (the spine). Properties + metamorphic
relations cover the value/context/versioning/indexing invariants far past the examples. Concurrency and
durability are proven at the layer each instrument fits, with the mechanism discharged by argument where no
instrument can reach. The conformance frontier is categorized + ratcheted. The isolation contract is
honest. Every cardinal invariant S-1…S-11 has a pinning test of the right *kind* — the coverage matrix has
no empty cell.

**Negative / cost.** The W3C conformance suites + `pitest` are a **slow nightly tier**, off the per-build
path. The bytecode-instrumentation tools need **upkeep on every toolchain bump** — Java 25 broke three of
them, and the mutation gate went *dark* from 2026-06-05 until it was restored on 2026-06-11 by bumping
`pitest` 1.16.1 → 1.25.4 (a v69-aware shaded ASM; overriding the plugin's ASM was disproven — pitest
shades its own copy). The conformance baseline *can* accrue **stale entries** as fixes land, so it needs periodic refresh — but
the first such refresh (2026-06-11, follow-ons Step 1) found **none stale**: the expectation that the
derived-XSD-integer fix of 2026-05-22 had made `tsv03` stale was **refuted** by running the suite (tsv03
still fails — on a custom datatype, a misattributed cause now corrected, not `xsd:negativeInteger`). The
lesson held: presume stale only after a run, never from a related fix. The
differential oracle is only as strong as `MemoryStore` — a *shared* RDF4J value-model quirk would pass
both — mitigated, not eliminated, by the kind-tagged-key comparison. Coverage **percentages did not lift**
even as absolute coverage grew, because production code grew alongside the tests; the thresholds hold above
their floors but were not ratcheted up.

**Neutral.** The strategy is mirrored to the JSON document store (its own plan,
`prolly-json-test-strategy.md`), adapting the spine to an in-memory document model since no off-the-shelf
reference exists there. The coverage matrix carries three residual TODOs (tracked in the follow-ons plan),
which are enhancements, not empty cells.

## Follow-up / future work

- The follow-ons plan: conformance-baseline refresh (ran 2026-06-11 — the baseline was already current,
  **0 stale**), the *must-shrink* gate half, and the pre-set-bindings frontier note. (Its **`pitest` bump
  for Java 25** landed 2026-06-11, restoring the mutation gate and closing test-strategy Step 30. A planned
  **cross-language `TermCodec` parity** item was **withdrawn 2026-06-11** — S-3 is already met by golden
  vectors, and Go byte-parity for the term codec is out-of-scope with no Go referent: the cross-language
  fixture validates tree structure over a generic string corpus, and Dolt has no RDF term codec.)
- Next ADR trigger: if Dolt bit-compatibility is ever pursued, the differential spine gains a *second*
  reference (the Go implementation) and this strategy is revisited.

## Open questions

- **Q1** — When to ratchet the JaCoCo floors up. Deferred: the production code is still growing
  (pre-1.0), so the coverage percentage is a moving target; raise the floors once the Sail's main code
  stabilizes, not before (raising them now would fail on the growth-driven dip, D-6 / Step 30).
