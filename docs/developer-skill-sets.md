# Developer skill sets — what working on this ring actually asks of you

A map of the competencies this codebase exercises, module by module, so you can
find the part that matches what you already know — and see honestly what a given
area will demand before you start.

This is not a hiring bar. Nobody has all of it; the ring is deliberately layered
so that most contributions need one or two rows, not the whole table. It
complements [CONTRIBUTING.md](../CONTRIBUTING.md) (how to submit) and
[docs/README.md](README.md) (what to read); this answers *what will I need to
know, and how hard is it to pick up here*.

## The map

| Area | Where it lives | Skills it actually asks for | Ramp |
|---|---|---|---|
| **RDF & SPARQL semantics** | `prolly-rdf`, `prolly-rdf4j` | Triples/quads, named graphs, IRIs vs blank nodes vs literals, SPARQL 1.1 algebra, RDF 1.1 literal equality rules | Moderate — start with [rdf-in-five-minutes](foundations/rdf-in-five-minutes.md) |
| **The RDF4J Sail contract** | `prolly-rdf4j` (76 main / 242 test files) | Implementing someone else's SPI faithfully: transaction lifecycle, isolation levels, connection semantics, read-your-writes | Steep — the contract is large and the conformance suites are unforgiving |
| **Binary codecs & key design** | `prolly-codec` (14 main / 31 test) | Byte-level encoding, order-preserving keys, varints, fixed-offset layouts, why `Locale.ROOT` matters when a case-fold feeds a storage key | Moderate — small surface, high precision |
| **Content-addressed storage** | `prolly-rdf`, the engine ring | Merkle trees, structural sharing, copy-on-write, why identity-by-hash makes caching and diff cheap | Moderate — [the-chunk-store](the-chunk-store.md), [structural-sharing-and-churn](foundations/structural-sharing-and-churn.md) |
| **Query execution & joins** | `prolly-rdf4j` | Iterator/cursor pipelines, sorted-merge intersection, the worst-case-optimal leapfrog triejoin, cardinality intuition | Steep — [the-leapfrog-join-contract](foundations/the-leapfrog-join-contract.md) then [the-leapfrog-triejoin](foundations/the-leapfrog-triejoin.md) |
| **Graph canonicalization** | `prolly-urdna2015` (12 main / 18 test) | URDNA2015, blank-node labelling, hash-based isomorphism, why canonical form is a precondition for merge | Steep and self-contained — a good first deep dive if you like algorithms |
| **Concurrency** | `prolly-rdf4j` sail internals | Java memory model, single-writer designs, safe publication, `volatile` and happens-before — plus the humility to test rather than reason | Steep — [the-concurrency-model](foundations/the-concurrency-model.md); one jcstress harness exists |
| **Property-based & differential testing** | ring-wide (90 `*Property` files, 49 using jqwik) | Writing generators, finding the invariant worth stating, building an oracle that does **not** mirror the implementation | Moderate — the highest-leverage skill here |
| **Fuzzing** | untrusted-input boundaries (Jazzer, 3 files) | Thinking like a hostile input; recognising a parser boundary as a trust boundary | Moderate |
| **Mutation testing** | `-Pmutation` (pitest, 5 modules) | Reading surviving mutants as a statement about your *assertions*, not your coverage | Light to pick up, humbling to apply |
| **Performance measurement** | benches across the ring | JMH, paired A/B, naming the bottleneck layer before optimising, distrusting a clean result | Steep — the discipline is harder than the tooling |
| **Java 25 specifics** | ring-wide | Records, sealed types, pattern matching; the Foreign Function & Memory API for off-heap segments and arenas | Moderate; the memory API is the unfamiliar part for most |
| **Build & quality gates** | `pom.xml`, `build/` | Maven multi-module, BOM imports, spotless, license headers, dependency convergence | Light — but read [CONTRIBUTING.md](../CONTRIBUTING.md), `mvn test` green is *not* the gate |

## Where to start, by background

- **You know RDF, not storage.** Start in `prolly-rdf4j` behaviour: read
  [A4-a-sparql-query](anatomy/A4-a-sparql-query.md), then
  [A5-a-versioned-query](anatomy/A5-a-versioned-query.md). The compliance suites
  give immediate, unambiguous feedback on whether you got the semantics right.
- **You know storage engines, not RDF.** Start in `prolly-codec` and
  [the-chunk-store](the-chunk-store.md). Key design and encoding are familiar
  territory in unfamiliar clothes; you can be productive before you learn SPARQL.
- **You know neither, but you like tests.** Property-based testing is the best
  entry point in the repository: pick a class, write the invariant nobody wrote
  down yet, and see whether it holds. Several real bugs here were found exactly
  that way, by people learning the component while testing it.
- **You are here for the algorithms.** `prolly-urdna2015` is self-contained,
  well-specified by a W3C standard, and has a clear correctness oracle — the
  cleanest deep dive in the ring.

## The non-technical half

Two habits this codebase asks for more insistently than most, both documented in
[CONTRIBUTING.md](../CONTRIBUTING.md):

- **Ground claims, or mark them ungrounded.** A pull-request description that
  says "this is faster" without a measurement, or "this is safe" without the
  test that shows it, will be asked for the evidence. Saying "I think, but
  haven't verified" is always acceptable; asserting it is not.
- **A refuted hypothesis is a good result.** Several documents here record
  optimisations that were built, measured, and reverted. Reporting that your
  idea did not work — with the number that shows it — is treated as a
  contribution, not a failure.
