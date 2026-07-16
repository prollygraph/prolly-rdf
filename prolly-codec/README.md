# prolly-codec — RDF terms as order-preserving key bytes

The encoding layer between RDF and the prolly tree: tagged term encoding (IRIs,
literals, language strings, datatypes), parity-encoded int64s (big-endian + sign-flip so
byte order equals numeric order), and SPOC key composition. Everything the versioned
indexes sort by is defined here, which is why the invariants that a result-comparing
test suite cannot see — term identity, lexical fidelity, canonicalization, datatype
identity — are cataloged in [`../spec-compliance/`](../spec-compliance/README.md) with
each entry citing the test that pins it. NullAway-gated (`@Nullable` is the marked
exception); jqwik property suites included.
