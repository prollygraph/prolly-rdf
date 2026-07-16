<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/rdf-canonicalization.md; links + citations adapted to this repo's layout -->

# RDF canonicalization (URDNA2015 / RDFC-1.0)

Two RDF graphs can say *exactly the same thing* and yet be byte-for-byte
different. The culprit is the **blank node** — an anonymous resource whose
label (`_:b0`, `_:b7`, `_:genid42`) is graph-local and arbitrary. Rename
every blank node and the graph's *meaning* is unchanged, but its serialized
bytes — and therefore its content hash — change completely. For a substrate
that decides identity by hashing bytes (see
[`the-prolly-tree.md`](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)), that is a real problem: two
commits that assert the same fact would land at two different root hashes,
and a three-way merge over the raw bytes would keep *both* copies, silently
inventing a fact that neither branch ever stated twice.

**RDF Dataset Canonicalization** is the fix. It assigns blank nodes
*deterministic* labels derived purely from graph structure, so that any two
isomorphic graphs (same triples up to blank-node renaming) serialize
identically. The algorithm is **URDNA2015**, standardized by the W3C as
**RDFC-1.0** (<https://www.w3.org/TR/rdf-canon/>). The `prolly-urdna2015`
module is this project's implementation.

> **Key idea** — canonicalization is *stable blank-node labelling*. Once
> two isomorphic graphs share canonical labels, byte-identity equals
> logical identity again, and content addressing + merge "just work" on
> blank-node-bearing data.

## When a newcomer meets this

Two motivating jobs, both grounded in the substrate:

- **Deterministic graph hashing / isomorphism.** "Are these two graphs the
  same fact?" becomes "do their canonical forms hash equal?" — a single
  SHA-256 comparison instead of a graph-isomorphism search.
- **Canonicalization-aware merge.** A three-way merge that does not duplicate
  a blank-node fact when two branches assert it independently. This is the
  subject of [ADR-0009](../../prolly-rdf4j/docs/adr/0009-canonicalizing-rdf-merge.md)
  — and, importantly, it is **Proposed, not yet implemented** (more below).

## The algorithm, in the actual classes

The contract lives in `RdfCanonicalizer` (a service provider interface):
`List<QuadPattern> canonicalize(List<QuadPattern>)`. Its four contract
clauses are worth internalizing — determinism, idempotence,
equivalence-preservation, and **fail-closed**: an implementation that *cannot*
produce a canonical labelling must throw `NonCanonicalizableException`, never
return a best-effort guess.

Rather than one monolithic canonicalizer, the module ships a **cascade** of
increasing strength, cheapest first, wired in `CascadeCanonicalizer.INSTANCE`:

1. `SimpleFirstDegreeCanonicalizer` — hashes each blank node by its
   immediate (first-degree) quads. Resolves the common case where every
   blank node has a unique structural fingerprint. Fails closed on a
   collision.
2. `SecondDegreeCanonicalizer` — adds a neighbour pass to distinguish blank
   nodes that share a first-degree shape but differ one hop out.
3. `UrdnaCanonicalizer` — the full URDNA2015 / RDFC-1.0 algorithm, for
   cyclic and symmetric blank-node structures the cheaper levels cannot
   resolve.

`CascadeCanonicalizer.canonicalize` tries each level, catching
`NonCanonicalizableException` and falling through to the next; only the
graphs that genuinely need the expensive analysis pay for it. An optional
`IntConsumer` callback reports *which* level resolved a given input — useful
for measuring "what fraction of commits resolve at level 0?".

`UrdnaCanonicalizer` implements the spec's phases directly. Reading its
`canonicalize` method top to bottom:

- **Phase 1** — first-degree hashing of every blank node
  (`hashFirstDegreeQuads`): the target blank node is rewritten to the
  placeholder `_:_self`, every *other* blank node to `_:_other`, the
  touching quads are sorted, and the result is SHA-256-hashed.
- **Phase 2** — bucket blank nodes by that first-degree hash in sorted hash
  order (a `TreeMap`).
- **Phase 3** — a bucket with one blank node gets a canonical name
  immediately (`IdentifierIssuer`, prefix `c14n`); buckets where several
  blank nodes collide are held for phase 4.
- **Phase 4** — the N-degree disambiguation (`hashNDegreeQuads`): for each
  colliding group it explores permutations of related blank nodes, picking
  the lexicographically smallest path, then issues canonical names in that
  order.
- **Phase 5** — rewrite the input quads through the issued label map.

> **Gotcha** — the class-level Javadoc on `UrdnaCanonicalizer` still reads
> *"Phase 4 throws"*. That comment is **stale**: the method body now
> implements phase 4 (the permutation/`hashNDegreeQuads` machinery). Trust
> the code and the module `README.md`, not that header sentence.

## Scaling: the blank-node connected component

URDNA2015's worst case is super-polynomial in the number of mutually
symmetric blank nodes, so running it over a whole dataset is a denial-of-service
risk. `BnccPartitioner` confines it. Using union-find over *blank-to-blank*
edges, it splits a quad set into:

- **all-named quads** — subject and object are both named (no blank node).
  Identity *is* byte-identity; these never need canonicalization.
- **blank-node connected components** — each maximal cluster of blank nodes
  connected through shared quads, returned as its own quad list.

Because blank nodes in different components cannot influence each other's
labels, each component canonicalizes *independently*, and the expensive path
is bounded by the *largest component* (usually tiny — one address, one
reified statement) rather than the dataset. `BnccPartitionedCanonicalizer`
composes the partitioner with a per-component canonicalizer to exploit this.

## What is wired — and what is not (be honest)

This is the part a newcomer most needs stated plainly.

- **The module is implemented and tested.** The cascade
  (first-degree → second-degree → URDNA2015 with phase-4 N-degree handling)
  works; the module `README.md` reports 115 passing tests.
- **It is NOT byte-exact with the W3C RDFC-1.0 spec.** `hashNDegreeQuads`
  uses an internal path encoding that differs from the spec's exact byte
  form, so a deeply-symmetric graph may receive a *valid-but-different*
  canonical labelling than a reference implementation would. Equivalent
  graphs still canonicalize to *each other*; the divergence is from the
  reference bytes, not from internal consistency.
- **The W3C test suite is not vendored or run.** `W3cTestVectorRunnerTest`
  is a *harness* with hand-crafted smoke cases; it documents how to drop the
  official vectors into `src/test/resources/rdf-canon-tests/`, but they are
  not checked in (licensing / repo bloat). There is no differential test
  against an external implementation such as `jsonld-java`.
- **No production path wires `CanonicalizingProllySail` yet.** That class is
  a real `NotifyingSailWrapper` that canonicalizes *added* statements at
  `commit()` under a wall-clock budget (`CanonicalizationBudget`,
  fail-closed) — but a repository-wide search finds **no caller outside the
  module**. It is built and ready, not yet mounted in front of a live
  `ProllySail`.
- **The canonicalization-aware merge is a proposal.** ADR-0009 designs a
  blank-node-component-partitioned three-way merge (ground triples
  byte-merge; blank-node components match by canonical hash). Its status is
  **Proposed** — `BnccPartitioner` is *not* referenced by the rdf4j
  `MergeEngine` today, so the production merge is still plain byte-set-union
  and remains incorrect for independently-labelled blank-node facts. The
  module is the building block ADR-0009 will consume, not the finished merge.

> **Trade-off** — ADR-0009 deliberately merges a blank-node component
> *atomically*: if both branches edit the *same* component differently, that
> surfaces as a component-level conflict rather than a triple-level
> three-way merge. The ADR's stance is "correct beats clever" — small,
> self-contained components (addresses, reified statements) auto-merge; the
> rare large concurrently-edited component conflicts instead of corrupting.

There is also **no graph or commit signing** here — a repository search for
Ed25519 / signing in the canonicalization sources finds nothing. Canonical
form is a prerequisite for deterministic signing, but the module stops at
producing the canonical bytes.

## Where this lives

- The service provider interface + blank-node helper:
  [`prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/RdfCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/RdfCanonicalizer.java)
- The full URDNA2015 / RDFC-1.0 implementation:
  [`prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/UrdnaCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/UrdnaCanonicalizer.java)
- The cheaper cascade levels:
  [`SimpleFirstDegreeCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/SimpleFirstDegreeCanonicalizer.java),
  [`SecondDegreeCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/SecondDegreeCanonicalizer.java),
  [`CascadeCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/CascadeCanonicalizer.java)
- Canonical-name allocation:
  [`IdentifierIssuer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/IdentifierIssuer.java)
- The blank-node connected-component partitioner:
  [`BnccPartitioner.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/BnccPartitioner.java),
  [`BnccPartitionedCanonicalizer.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/BnccPartitionedCanonicalizer.java)
- The fail-closed contract type:
  [`NonCanonicalizableException.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/canon/NonCanonicalizableException.java)
- Commit-time integration (built, not yet wired into production):
  [`CanonicalizingProllySail.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/CanonicalizingProllySail.java),
  [`CanonicalizationBudget.java`](../../prolly-urdna2015/src/main/java/com/earasoft/prolly/semantic/CanonicalizationBudget.java)
- The W3C test harness (smoke cases only; official vectors not vendored):
  [`prolly-urdna2015/src/test/java/com/earasoft/prolly/semantic/canon/W3cTestVectorRunnerTest.java`](../../prolly-urdna2015/src/test/java/com/earasoft/prolly/semantic/canon/W3cTestVectorRunnerTest.java)
- The design decision for canonicalization-aware merge (Proposed):
  [`prolly-rdf4j/docs/adr/0009-canonicalizing-rdf-merge.md`](../../prolly-rdf4j/docs/adr/0009-canonicalizing-rdf-merge.md)
- Module overview + honest status:
  [`prolly-urdna2015/README.md`](../../prolly-urdna2015/README.md)
- Content addressing this builds on:
  [`the-prolly-tree.md`](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)
