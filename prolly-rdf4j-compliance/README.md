# prolly-rdf4j-compliance — the W3C conformance suite

Runs Eclipse RDF4J's published conformance suites against `ProllySail`: SPARQL 1.1
query — 174 of 176, the two remaining classified in
[`docs/conformance-frontier.md`](docs/conformance-frontier.md) (also re-run with
triejoin-cardinality routing forced — the two engines must agree), SPARQL 1.1 update
(90/90, empty baseline), and the store/repository contract suites. Fixtures arrive at
test time via the `rdf4j-*-testsuite` Maven artifacts — none are embedded here.

The honest part is [`src/test/resources/known-failures/`](src/test/resources/known-failures/):
the baseline of accepted failures that the ratchet allows to **shrink but never grow**
(`KnownFailuresBaselineTest` + `MustShrinkBaselineTest`). A conformance claim you can't
falsify is marketing; this one fails the build if it regresses.
