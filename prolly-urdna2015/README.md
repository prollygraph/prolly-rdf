
# prolly-urdna2015

RDF dataset canonicalization for the prolly substrate. Houses the
`RdfCanonicalizer` SPI, the cascading family of canonicalizer
implementations (first-degree, second-degree, full URDNA2015 N-degree),
the `IdentifierIssuer` helper, and the `CanonicalizingProllySail`
commit-time integration with `prolly-rdf4j`'s `ProllySail`
(ADR-0037 D-5 retired the earlier native `CanonicalizingQuadStore`).

**Status:** v0.2.0-BETA. The cascade — first-degree → second-degree →
`UrdnaCanonicalizer` (phases 1–5, including phase-4 N-degree recursion)
— is implemented and tested (115 tests, all green). It is **not yet
byte-exact with the W3C RDFC-1.0 spec**: `UrdnaCanonicalizer` uses an
internal path encoding in `hashNDegreeQuads` that differs from the
spec's exact byte form, so deeply-symmetric graphs may get a
valid-but-different canonical labelling than a reference implementation.
The official W3C test suite is not vendored or run (only hand-crafted
smoke cases run), there is no differential test against `jsonld-java`,
and no production path wires `CanonicalizingProllySail` yet. See
[`FUTURE_WORK.md`](./FUTURE_WORK.md) for what's left.

---

## What you get today

```java
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.semantic.CanonicalizingProllySail;
import org.eclipse.rdf4j.repository.sail.SailRepository;

// Wrap any ProllySail; the wrapper canonicalizes added statements at commit.
ProllySail delegate = new ProllySail(/* nodeStore, bufferPool, roots, commitLog */);
SailRepository repo = new SailRepository(new CanonicalizingProllySail(delegate));
repo.init();

// Now commits canonicalize automatically through the normal RDF4J surface.
// Two connections that commit structurally-equivalent blank-node graphs
// (with parser-renamed labels) land byte-identical Prolly-tree contents;
// the substrate's three-way merge sees them as the same triples.
try (var conn = repo.getConnection()) {
    conn.begin();
    conn.add(/* statements with blank nodes */);
    conn.commit();
}
```

The default cascade tries
[`SimpleFirstDegreeCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/SimpleFirstDegreeCanonicalizer.java)
first (cheap; handles the blank-node-rename case), falls through to
[`SecondDegreeCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/SecondDegreeCanonicalizer.java)
on collision (handles the neighbour-distinguishable case), and
fails closed when neither resolves. A `200ms` time budget wraps the
whole thing; substrate refuses the commit on timeout.

---

## Class map

| Class                                                                                  | Role                                                                                  |
|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| [`RdfCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/RdfCanonicalizer.java) | The SPI. Implements take `List<QuadPattern>`, return canonicalized `List<QuadPattern>` or throw. |
| [`NonCanonicalizableException`](src/main/java/com/earasoft/prolly/semantic/canon/NonCanonicalizableException.java) | Fail-closed signal. Callers MUST NOT fall back to a non-canonical labelling. |
| [`IdentifierIssuer`](src/main/java/com/earasoft/prolly/semantic/canon/IdentifierIssuer.java) | Stable, deterministic canonical-name issuer. The only stateful piece. |
| [`NoopCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/NoopCanonicalizer.java) | Pass-through for blank-node-free input; throws on any blank node. |
| [`SimpleFirstDegreeCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/SimpleFirstDegreeCanonicalizer.java) | First-degree hash + collision detection. Handles blank-node rename. |
| [`SecondDegreeCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/SecondDegreeCanonicalizer.java) | h₁ + one round of neighbour-hash propagation. Handles neighbour-distinguishable cases. |
| [`UrdnaCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/UrdnaCanonicalizer.java) | Full URDNA2015 phases 1–5 incl. phase-4 N-degree recursion. **Path encoding not yet byte-exact with the W3C spec** (see FUTURE_WORK.md). |
| [`CascadeCanonicalizer`](src/main/java/com/earasoft/prolly/semantic/canon/CascadeCanonicalizer.java) | Tries cheaper canonicalizers first; escalates on collision (first-degree → second-degree → URDNA2015). |
| [`CanonicalizingProllySail`](src/main/java/com/earasoft/prolly/semantic/CanonicalizingProllySail.java) | Canonicalize-at-commit Sail wrapper, with time-budget enforcement. |

---

## Reading order

For an engineer picking up the implementation work:

1. a private strategy note — the practical W3C-algorithm guide.
2. [`./THEORY_OF_OPERATION.md`](./THEORY_OF_OPERATION.md) — the intuition behind why URDNA2015 works.
3. [`./FUTURE_WORK.md`](./FUTURE_WORK.md) — what's left to ship.
4. The existing canonicalizer source files (cascading impls, iter 1-5).

For a downstream consumer (e.g. `prolly-audit`, `prolly-fhir`):

1. This file's "What you get today" section.
2. The class map.
3. `RdfCanonicalizer` source for the contract.

---

## Strategic context

- a private whitepaper §3.1 — why canonicalization is non-negotiable for regulated data.
- a private strategy note — strategic positioning for the canonicalization work.
- a private strategy note — the four hard merge cases this module exists to handle.
- `../prolly-audit/design/HASHING_CANONICALIZATION.md` — how canonicalization fits the audit substrate.

---

## Dependency footprint

| Dep                | Why                                                              |
|--------------------|------------------------------------------------------------------|
| prolly-rdf      | `VersionedQuadStore`, `QuadPattern`, `Database`, `MergeEngine`. |
| dolthub-java-port (engine core) | Transitive via prolly-rdf.                                    |
| JUnit Jupiter      | Tests only.                                                      |

No JSON-LD libraries, no external canonicalizers. Apache 2.0 only.

---

## Test coverage

115 tests across 16 classes, all green as of v0.2.0-BETA
(measured 2026-06-26 via `mvn -pl prolly-urdna2015 -am test`):

```
IdentifierIssuerTest                       12 tests
UrdnaCanonicalizerTest                      13 tests
SimpleFirstDegreeCanonicalizerTest           8 tests
SecondDegreeCanonicalizerTest                8 tests
CascadeCanonicalizerTest                     8 tests
RdfCanonicalizerTest                         9 tests
NoopCanonicalizerTest                        9 tests
BnccPartitionerTest                          9 tests
BnccPartitionedCanonicalizerTest             8 tests
ParallelBnccCanonicalizerTest                6 tests
NonCanonicalizableExceptionTest              6 tests
CanonicalizationBudgetTest                   6 tests
CanonicalizerFuzzTest                        4 tests
BlankNodeRenameCanonicalizerTest             3 tests  (regression marker from iter 1)
CanonicalizingProllySailIntegrationTest      3 tests
W3cTestVectorRunnerTest                      3 tests  (hand-crafted smoke cases only;
                                                       W3C suite not vendored — see FUTURE_WORK.md)
```

Run with: `mvn -pl prolly-urdna2015 -am test`.

---

## License

Apache 2.0 (see `../LICENSE`). Schema and reference algorithms from
W3C are public-domain by W3C Document License.
