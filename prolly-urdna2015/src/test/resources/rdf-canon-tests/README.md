
# W3C RDFC-1.0 Test Vector Directory

This directory is read by
`W3cTestVectorRunner`
at test time. It is empty by default — populate it locally to run
the W3C test suite against `UrdnaCanonicalizer`.

## Populating the suite

```bash
git clone https://github.com/w3c/rdf-canon.git /tmp/rdf-canon
cp /tmp/rdf-canon/tests/*.nq .

# The W3C suite uses test-NNN-in.nq + test-NNN-urdna2015.nq naming.
# Verify the pairs are recognised:
ls *-in.nq | head -5
ls *-urdna2015.nq | head -5

# Run.
mvn -pl prolly-urdna2015 test -Dtest=W3cTestVectorRunner
```

## Test pair naming

The runner expects file pairs in this format:

```
<name>-in.nq          # input graph (N-Quads)
<name>-urdna2015.nq   # expected canonical output (N-Quads)
```

A test fails if `UrdnaCanonicalizer.canonicalize(input)` does not
produce byte-equal output to the expected (after both are serialised
through `NQuadsSerializer.serialize`, which sorts the lines).

## Encoding caveat — read first

`UrdnaCanonicalizer` uses an internal path encoding (in `hashNDegreeQuads`)
that differs from the W3C spec's exact byte form in a few places (see
`../../../a private strategy note`
§6.1 and §12 pitfall #4). For test cases where the canonical-name
assignment is unambiguous (simple structure, no deep symmetry), our
output matches W3C reference impls. For ambiguous cases, our output
may differ.

The fix is a path-encoding refactor to match the W3C spec byte-for-byte.
Tracked as future work — see [`FUTURE_WORK.md`](../../../../FUTURE_WORK.md).

## What runs when this directory is empty

The runner ships with hand-crafted smoke tests in
`W3cTestVectorRunner.builtInSmokeCases()`. Those exercise the same code
paths as the W3C suite on a much smaller scale. When this directory
is populated, both sets run.

## License note

The W3C suite is published under the [W3C Document License](https://www.w3.org/Consortium/Legal/2015/doc-license).
Don't check vendor copies of the suite into this repo without confirming
licensing compatibility — that's why this directory is empty by default.
