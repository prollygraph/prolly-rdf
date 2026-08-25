
# Conformance frontier — the categorized known-failures baseline

Step 26 of `prolly-rdf4j/plans/prolly-rdf4j-test-strategy.md` (the plan lives in the private monorepo's work tracker)
(invariant **S-11**). This is the managed view of every W3C / RDF4J-contract conformance test the suite
**accepts as a known failure** — each classified, each with a roadmap step or an explicit out-of-scope
ruling. The point is that the frontier is *visible and governed*, not hidden: a gap may only shrink
(failures get fixed and removed) and may never grow silently (the size gate below + git review).

Three categories:

- **architectural** — the gap follows from a deliberate design choice (the off-heap value model, the
  fixed-width tuple). Closing it would be a format/architecture project, not a bug fix. Usually
  out-of-scope, or behind its own ADR.
- **encoding-format** — the on-disk/wire encoding has no slot for the distinction the test needs. A
  format-version follow-up could close it; pre-1.0 the format evolves freely, so this is a real but
  deferred fix.
- **unimplemented** — a genuine feature gap with no architectural blocker. A candidate for a real fix; a
  test (not a feature epic) usually closes it.

## SPARQL 1.1 *query* — baselined in `known-failures/sparql11-query.txt` (1)

Baseline captured 2026-05-15 at **171/176**; SHRUNK 2026-06-12 to 174/176 (ADR-0043, the term-faithful
campaign); **SHRUNK 2026-08-25 to 175/176** when the conformance round 2 fixed `pp35` (see *Fixed*
below). The *update* suite stays **90/90**. The one remaining entry is not a prolly gap at all:

| Test (`mf:name`) | Category | Roadmap / ruling | Rationale |
|---|---|---|---|
| `constructwhere04 - CONSTRUCT WHERE` | **engine-independent** | ruled out (revisit only if upstream moves — `FrontierEngineIndependenceTest` re-verifies every build) | The manifest declares NO data; passing requires the engine to dereference the query's own `FROM <data.ttl>` document IRI. RDF4J's MemoryStore fails byte-for-byte identically under this harness; dereferencing is implementation-defined per SPARQL 1.1 and an SSRF-shaped non-feature inside a store by deliberate ruling. |

**Fixed 2026-08-25 by conformance round 2 (`QUERY_MAX` 2→1):**

| Test (`mf:name`) | What fixed it |
|---|---|
| `(pp35) Named Graph 2` | Upstream evaluates property paths under an unbound `GRAPH ?g` with graph-blind global dedup (`ZeroLengthPathIteration` keys vertices, `PathIteration` keys `(start,end)` pairs — both without the graph), so a term in several named graphs kept rows only for the first graph the scan surfaced; memory store passes by insertion-order luck, this store's content-addressed order lost the rows. `ProllyDefaultEvaluationStrategy` now walks zero-length vertices per `(vertex, graph)` and decomposes both-ends-unbound `ArbitraryLengthPath`s per named graph. Pinned by `ZeroLengthPathNamedGraphTest`. |

**Fixed 2026-06-12 by the term-faithful campaign (ADR-0043), removed from the baseline + `QUERY_MAX` 5→2:**

| Test (`mf:name`) | What fixed it |
|---|---|
| `TZ()` / `TIMEZONE()` | Verbatim lexical temporal storage (Step 6): the old fixed-width encoding had no tz-absent slot and coerced a tz-less `xsd:dateTime` to UTC, so `TZ()`/`TIMEZONE()` returned `"Z"`/`PT0S` instead of `""`/unbound. The verbatim lexical form preserves the tz-absent distinction. |
| `tsv03 - TSV Result Format` | The Dictionary-backed custom-datatype write path (Step 6a): its data carries a `http://example.org/myCustomDatatype` literal that used to throw `"unsupported datatype …"` on load; it now stores verbatim via `DictionaryTermEncoder`. (The 2026-06-11 re-measure correctly attributed it to the custom datatype, not `xsd:negativeInteger` — and that custom path is now wired.) |

## SPARQL 1.1 *update* — `known-failures/sparql11-update.txt` (0)

Clean — **90/90** passing, empty baseline. The default-graph context-isolation fixes (project history,
2026-05-15) closed this suite entirely; the empty baseline is itself a ratchet (any new update failure
breaks the build).

## RDF4J store / connection contract suites (documented gaps)

These are the gap **classes** the `RDFStoreTest` / `RepositoryConnectionTest` contract suites surface
(enumerated in the plan's *What's there already*). They are categorized here for completeness; the
SPARQL baselines above are the file-backed, gated ones.

| Gap | Category | Roadmap / ruling | Rationale |
|---|---|---|---|
| `Statement` serialization (`NotSerializableException`) | architectural | out-of-scope (don't make off-heap segments `Serializable`); a separate serialization adapter if ever needed | Values are backed by off-heap `MemorySegment`, which is not `java.io.Serializable`; the contract suite's Java-serialization round-trip can't apply. |
| Literals > 64 KiB | architectural | out-of-scope without a blob layer | The tuple offset layout caps a value's length at a `uint16`; large literals need a separate blob/overflow layer — a format project, behind an ADR. |
| Ill-typed literal lexical forms (`NumberFormatException`) | ~~encoding-format~~ **FIXED 2026-06-12** | Fixed by term-faithful storage (ADR-0043): typed literals store the verbatim lexical bytes with no eager parse, so an ill-typed form (`"abc"^^xsd:int`, odd-length `xsd:hexBinary`, …) round-trips as a valid RDF term instead of throwing. | The codec used to eagerly parse a typed literal's lexical form and threw on store. |
| Timezone-absent `xsd:dateTime` / `xsd:date` | ~~encoding-format~~ **FIXED 2026-06-12** | Fixed by verbatim lexical temporal storage (ADR-0043 Step 6) — a tz-less temporal round-trips exactly, no UTC coercion. The same fix un-baselined `TZ()`/`TIMEZONE()` above. | A tz-less temporal was normalized to UTC on store; the distinction was lost. |
| Pre-set binding on a `FILTER`-only variable → 0 rows | ~~bug~~ **FIXED 2026-06-11** | Fixed: inline the initial bindings via `BindingAssignerOptimizer` in both sails' `evaluateInternal` (guarded on non-empty bindings → the common empty-bindings path is byte-for-byte unchanged). `testQueryBindings` un-baselined on both sails. | A pre-set binding on a variable appearing *only in a `FILTER`* (not the basic graph pattern), fed to the low-level `SailConnection.evaluate(rawTupleExpr, dataset, bindings, …)`, was dropped → 0 rows (`RDFStoreTest.testQueryBindings:696`, both sails). **Root cause:** both sails called `strategy.evaluate(expr, bindings)` *without* RDF4J's binding-inlining `optimize()` step that the stock `SailSourceConnection` runs, so a binding absent from the basic graph pattern never reached the algebra. Pinned by `PresetBindingCharacterizationTest` (both the high-level and low-level paths). |

## The ratchet + the shrink-only gate

- **Ratchet (existing):** each baselined SPARQL test is skipped via `SPARQLComplianceTest.addIgnoredTest`;
  a test *not* on the baseline that starts failing is not skipped, so it fails the build. New failures
  cannot appear silently — they must be fixed or deliberately baselined.
- **Drift capture (existing):** `ComplianceFailureListener` writes the live failed-test set to
  `target/compliance-failures.txt`, the raw material for diffing against the baseline.
- **Shrink-only gate (Step 26):** `KnownFailuresBaselineTest` caps each baseline at a pinned maximum
  (query ≤ 5, update ≤ 0). The baseline may shrink freely (delete a line when a failure is fixed); it can
  only **grow** by deliberately raising the pinned cap — a reviewed edit, never silent.
- **Must-shrink gate (follow-ons Step 2, 2026-06-11):** `MustShrinkBaselineTest` forces a *fixed* failure
  *out* of the baseline. It runs each baselined query test **un-skipped** and asserts it **still fails**; a
  baselined test that now *passes* (a fixed failure left behind) — or a name that matches no approved
  manifest test (drift) — fails the gate, naming the offender. It executes only the handful of baselined
  tests, catches their throws internally (so it never pollutes `compliance-failures.txt`), and runs in the
  gated module (`-Dprolly.compliance.skip=false`), off the green per-build path. (The SPARQL-update baseline
  is empty, so must-shrink there is vacuous — covered by the `UPDATE_MAX=0` cap.)

When you fix a known failure: delete its line from the `known-failures/*.txt` baseline **and** its row
here, and lower the pinned cap in `KnownFailuresBaselineTest` to match. The cap going down is the visible
record that the frontier shrank.

## SPARQL 1.0 (DAWG) *query* — baselined in `known-failures/sparql10-query.txt` (8) — NEW 2026-08-25

Wired by the gap-wiring round at **228/236 evaluated** (upstream additionally ignores six
RDF-1.1-incompatible tests). All eight baselined entries are the `dataset-*` family: their datasets
live in the query's own FROM/FROM NAMED clauses (no manifest data), the same engine-independent
family as `constructwhere04` — RDF4J's MemoryStore fails all eight identically.
`Sparql10DatasetEngineIndependenceTest` re-verifies the parity every build; the cap is
`KnownFailuresBaselineTest.QUERY10_MAX`.

## The gap-wiring round (2026-08-25) — what it found and fixed

Wiring every applicable RDF4J testsuite class that existed in the dependency but was never
subclassed surfaced FOUR real defects, all fixed the same round:

1. **Wrong `QueryEvaluationMode`.** `AbstractSail` defaults to STRICT and stock connections
   propagate it; our `evaluateInternal` never set it, silently evaluating in STANDARD. Fixed by
   honoring the sail default (one line). Caught by `CascadeValueExceptionTest` + W3C
   `date-3`/`open-cmp-01/02`.
2. **Missing optimizer pipeline.** We ran only a targeted BindingAssigner; stock stores run the
   standard pipeline. Skipping it evaluated algebra shapes no stock store executes raw — and
   `Join(GRAPH ?g {..}, {..} UNION {..})` drops the union's rows in that raw shape (W3C
   `join-combo-1/-2`). Fixed by running `strategy.optimize(...)` with uniform-cost statistics,
   exactly as `SailSourceConnection` does.
3. **No sail-level change events.** `SailChangedListener`s never heard commits. Fixed:
   per-transaction add/remove flags fire a `DefaultSailChangedEvent` on commit. Caught by
   `RDFNotifyingStoreTest`.
4. **Notifications weren't change-accurate.** Connection listeners heard no-op re-adds and
   phantom removes. Fixed: presence-probed notifications (probe runs only when a listener is
   registered — the listener-less hot path is untouched).

It also caught **two stale `@Disabled` baselines** in the store contract suite (`testTimeZoneRoundTrip`,
`testInvalidDateTime` — both actually fixed by ADR-0043 back in June; the `@Disabled` set has no
must-shrink gate, unlike the file-backed baselines) — both re-enabled and passing.

Newly wired, all green: `SPARQL11SyntaxComplianceTest` (160 parser tests), `SPARQL10QueryComplianceTest`
(228), `SparqlDatasetTest`, `SparqlSetBindingTest`, `SparqlAggregatesTest`, `SparqlOrderByTest`,
`SparqlRegexTest`, `GraphQueryResultTest`, `TupleQueryResultTest`, `CascadeValueExceptionTest`,
`SPARQLUpdateTest`, `SailInterruptTest`, `RDFNotifyingStoreTest`, `RDFStarSupportTest` (2 green,
8 `@Disabled` on the star write-path gap), `SPARQL12QueryComplianceTest` (0 tests generated by
upstream 5.1.4 — wired so coverage arrives with the upgrade).

**New frontier rows from the round:**

| Gap | Category | Roadmap / ruling |
|---|---|---|
| ~~RDF-star triple terms in the write path~~ | **FIXED 2026-08-25 (round 3)** | `DictionaryTermEncoder` routes `Triple` values through `TermCodec.encodeQuotedTriple` (recursive component interning, canonical asserted tag — one tag is load-bearing for content addressing), and `SailConnectionTripleSource` implements `RDFStarTripleSource` (statement-driven triple-term enumeration) so SPARQL-star `TripleRef` patterns see native terms instead of the reification fallback. `ProllyRdfStarSupportTest` 10/10; `testAddTripleContext` re-enabled on both store-contract suites (the context-rejection guard now actually runs). Residual: a dedicated triple-term index if SPARQL-star over very large stores ever matters (enumeration is one statement scan per `TripleRef`). |
| Per-row update realization | architectural (parked) | `AbstractSailConnection` batches DELETE-then-INSERT realization, so change-accurate listeners emit one event per NET change; `RDFNotifyingStoreTest.testUpdateQuery2` pins SailSourceConnection's per-row interleaved trace. Semantics correct, cardinality differs. |
| Cross-connection visibility | architectural (documented cluster) | Snapshot-per-connection isolation; two more faces surfaced (`SPARQLUpdateTest.testAutoCommitHandling`, `RDFNotifyingStoreTest.testUpdateQuery`'s final assert), `@Disabled` with the cluster rationale. |
| `EvaluationStrategyTest` / optimistic-isolation suites | not wireable | Require `BaseSailConfig`/config-factory machinery this sail does not expose; revisit if a config layer lands. |
| Cardinality-aware `EvaluationStatistics` | performance (parked) | The pipeline runs with uniform costs; `TermStats` could feed real cardinalities into join reordering. |
