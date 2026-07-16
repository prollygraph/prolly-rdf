
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

## SPARQL 1.1 *query* — baselined in `known-failures/sparql11-query.txt` (2)

Baseline captured 2026-05-15 at **171/176**; **SHRUNK 2026-06-12 to 174/176** when the term-faithful
campaign (ADR-0043) fixed **three** of the five — `TZ()`, `TIMEZONE()`, and `tsv03` (see the *Fixed* note
below). The *update* suite stays **90/90**. The two genuine remaining failures are unimplemented features,
not encoding gaps:

| Test (`mf:name`) | Category | Roadmap / ruling | Rationale |
|---|---|---|---|
| `constructwhere04 - CONSTRUCT WHERE` | unimplemented | candidate fix: wire `FROM`-document resolution through the Sail | `CONSTRUCT WHERE` with a `FROM` `DatasetClause` — `FROM`-document resolution is not wired through the Sail. |
| `(pp35) Named Graph 2` | unimplemented | feature backlog: property paths across named graphs | Property-path evaluation across named graphs is not implemented. |

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
