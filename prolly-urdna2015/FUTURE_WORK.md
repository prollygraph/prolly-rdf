
# prolly-urdna2015 — Future Work

What's shipped, what's next, what blocks what. Living list — update
as items close.

---

## Shipped today (iter 1-5 + sub-iter 6a)

| Item | Iter | Status |
|---|---|---|
| `RdfCanonicalizer` SPI + `NoopCanonicalizer` + `NonCanonicalizableException` | 1 | done |
| `SimpleFirstDegreeCanonicalizer` — blank-node rename case | 2 | done |
| `CanonicalizingQuadStore` — commit-time wiring + time budget | 3 | done |
| `SecondDegreeCanonicalizer` — neighbour-distinguishable case | 4 | done |
| `CascadeCanonicalizer` — cheap-first composition | 5 | done |
| `IdentifierIssuer` — URDNA2015 stateful primitive | 6a | done |
| `UrdnaCanonicalizer` skeleton (phases 1, 2, 3, 5) | 6b | done |
| `UrdnaCanonicalizer` phase 4 single-level (cyclic resolves) | 6c | done |
| `UrdnaCanonicalizer` phase 4 with recursion | 6d | done |
| Cascade integration — `UrdnaCanonicalizer` at level 2 | 6g | done |

115 tests across 16 classes (measured 2026-06-26). See
[`README.md`](./README.md) for the class map. CascadeCanonicalizer.INSTANCE
now has three levels: first-degree → second-degree → URDNA2015.

---

## Sub-iterations remaining for full URDNA2015 (per implementation guide §11)

Each is a separate task — don't merge them; bugs land in seams.

### 6b / 6c / 6d — `UrdnaCanonicalizer` phases 1–5 incl. phase-4 recursion — DONE

Shipped (see the table above). The skeleton (phases 1, 2, 3, 5), the
single-level HashNDegreeQuads permutation loop, and the full recursive
phase-4 with permutation early termination all landed. `UrdnaCanonicalizer`
no longer throws "phase 4 not yet implemented".

> **The remaining gate is byte-exactness, NOT phase-4 completeness.**
> `UrdnaCanonicalizer.hashNDegreeQuads` uses an internal path encoding that
> diverges from the W3C spec's exact byte form (see implementation guide §6.1
> and §12 pitfall #4). On unambiguous graphs our output matches a reference
> impl; on deeply-symmetric graphs the canonical-name *assignment* may differ
> (valid, but not interoperable). Closing this is the **path-encoding-alignment
> plan** — until it lands, 6e cannot pass byte-exactly. See
> `plans/urdna2015-path-encoding-alignment.md` (in the private monorepo's work tracker).

### 6e — W3C test vector harness — HARNESS DONE, suite not vendored / not passing

- The runner exists: `W3cTestVectorRunnerTest` (a `@TestFactory`
  reading `src/test/resources/rdf-canon-tests/<name>-in.nq` +
  `<name>-urdna2015.nq` pairs). Today the directory holds only a
  README — the official W3C suite is **not vendored**, so only the
  three hand-crafted smoke cases run.
- Remaining: vendor (or fetch-at-test-time under the W3C Document
  License) the `w3c/rdf-canon` suite, then make every case pass.
- **Blocked on the path-encoding-alignment plan** — byte-exact pass
  is impossible until the path encoding matches the spec.
- **Test gate:** every W3C test case passes byte-exactly.

### 6f — Differential testing harness against jsonld-java

- Add `jsonld-java` as a `<scope>test</scope>` dependency.
- Random-graph generator (seeded RNG for reproducibility).
- Asserts our `UrdnaCanonicalizer.canonicalize(graph)` produces the
  same canonical form as `JsonLdProcessor.normalize(graph)`.
- **Test gate:** 10,000 random graphs, zero discrepancies.

### 6g — Cascade integration — DONE (acceptance still pending byte-exactness)

- `UrdnaCanonicalizer` is wired as the level-3 fallback in
  `CascadeCanonicalizer.INSTANCE` (first-degree → second-degree →
  URDNA2015); cyclic / symmetric cases resolve there instead of
  throwing. Cascade tests pass.
- Still open: `BENCHMARKS.md` JMH results; pointing
  `../prolly-audit/design/HASHING_CANONICALIZATION.md` at
  `UrdnaCanonicalizer` as the reference impl; and the implementation
  guide §14 "acceptance criteria for shipped" — the latter cannot be
  met until byte-exactness (6e) passes.

### Not-yet-started: production wiring

- Nothing in a running path constructs `CanonicalizingProllySail`
  today — it is referenced only inside this module (main + tests).
  A consumer (e.g. `prolly-rdf4j`'s per-repo Sail factory, gated by
  an operator property) must adopt it before canonicalize-at-commit
  is actually in effect anywhere.

---

## Beyond v1

These are not blockers for shipping the W3C algorithm; flagged for
future iterations.

### URDNA2015 performance tier — for the audit hot path

URDNA2015 is super-polynomial worst-case. For the audit graph
specifically (small reified clusters, typically <20 quads per
event), the algorithm should be a microseconds-level cost.
Benchmark on the audit-hot-path payload shape and tune.

### JCS / RDF-Dataset-Canonicalization-2 (post-RDFC-1.0)

The W3C is sketching a successor algorithm. When and if it
stabilises, plug it in behind the `RdfCanonicalizer` SPI as
`Rdfc2Canonicalizer.INSTANCE` and cascade after URDNA2015. Same
shape, different math.

### Streaming canonicalization

The whole-graph URDNA2015 model doesn't apply to streaming RDF
(SSE, Kafka, etc.). A streaming variant is research-grade; out of
scope for v1 but a possible v2 frontier.

### Reasoner-output-aware canonicalization

RDFS / OWL inference produces derived triples. A canonicalizer that
distinguishes asserted-vs-derived would let merge ignore derived
content (it re-emerges from inference post-merge). See
a private strategy note
§4 for the open questions.

### RDF-star canonicalization

RDF 1.2's quoted triples (`<<:s :p :o>>` as a subject) need
recursive canonicalization through the quote boundary. The W3C
spec for RDFC-1.0 doesn't fully address this; sketch the extension
when the spec stabilises.

### Adapter for `jsonld-java` as a third cascade level

If our from-scratch URDNA2015 hits an edge case the spec doesn't
fully nail down, ship a `JsonLdJavaAdapter` that wraps `jsonld-java`'s
URDNA2015 and slots in as level 3 (fallback). Optional dependency;
deployers who don't want JSON-LD on the classpath skip it.

### SHACL companion shapes

The OWL ontology in `../prolly-audit/src/main/resources/audit-ontology.ttl`
catches some categories of bugs (disjointness, type, enum). SHACL
shapes catch the rest (required-field presence, format validation,
closedness). Ship a companion `audit-shapes.ttl` and wire SHACL
validation into the canonicalize+commit path.

---

## Out of scope, by design

- Other graph-canonicalization algorithms (RGRDA, BCG canonical-form,
  etc.) — URDNA2015 is the W3C standard; that's the target.
- Reasoning. The canonicalizer does not run inferencing; that's a
  separate engine pass upstream of canonicalization.
- Query canonicalization (canonicalising SPARQL itself). Out of
  scope.
- Cryptographic accumulators / blockchain-style canonical forms.
  Different problem.

---

## How to pick up next

1. Read [`./THEORY_OF_OPERATION.md`](./THEORY_OF_OPERATION.md) to
   build intuition.
2. Read
   a private strategy note
   for the practical algorithm walkthrough.
3. Start sub-iter 6b. It's deliberately small — just phases 1, 2,
   3, 5 — to land before the algorithmically-hard phase 4.
4. Add a JUnit test for the cyclic pair input. It should throw
   `NonCanonicalizableException` with "phase 4 not yet implemented"
   diagnostic until 6c lands.
