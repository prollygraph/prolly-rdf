
# ADR-0007: Expose Dataset Statistics as a Virtual VoID Graph

## Status

Proposed, backfilled 2026-06-23 (predated the `## Status` convention) — no VoID/DCAT statistics graph is implemented.

| Status   | Proposed                                                                  |
|----------|---------------------------------------------------------------------------|
| Decision | **Option A** — derived, read-only virtual `void:`/`dcat:` graph via the virtual-graph dispatcher |
| Iter     | VO (sub-iters VO.1 → VO.8)                                                |
| Authors  | prolly-rdf4j team                                                         |

> **Goal:** a SPARQL client — or a dataset-catalog tool — can ask what a
> prolly store *is* and how big it is, in a standard vocabulary:
> `SELECT ?n WHERE { GRAPH <urn:prolly:meta:void> { <urn:prolly:meta:dataset> void:triples ?n } }`

## 1. The problem

A prolly-rdf4j store maintains internal statistics that no external
consumer can see:

- **`TermStats`** — a per-`TermId` 64-bit frequency counter, maintained
  for the `IndexPlanner` to estimate triple-pattern selectivity (Phase
  3). It is incremented/decremented on the write path and merged
  additively under CAS-rebase.
- **Total triple/quad count** and per-named-graph counts — bookkeeping
  counters in the `stats` RootMetaTree sub-tree.

Two consequences of that invisibility:

- **A federated query planner — or any client doing its own
  optimization — is blind.** It cannot see prolly's cardinality
  estimates, so it must either issue expensive `COUNT` probes or guess.
  prolly already *computed* the numbers; there is no standard way to
  hand them over.
- **The store is not self-describing.** Federation, dataset catalogs,
  and Mobi-style platforms discover what a dataset is by consuming
  **VoID / DCAT** RDF. `TODO_HUB` explicitly lists
  *"VoID / DCAT / Croissant metadata auto-generated from the store"* as
  a wanted feature. A `urn:prolly:meta:void` graph **is** that feature —
  for free, as a projection of counters that already exist.

This is the [`urn:prolly:meta:*` virtual-graph family](0006-commit-log-as-rdf.md)
applied to a second internal structure. Constraints carry over from
[ADR-0006 §1](0006-commit-log-as-rdf.md): no new wire protocol, no
on-disk format change, no perturbation of existing query semantics.

## 2. The options

| # | Approach | Client code | Standard vocab | Verdict |
|---|---|---|---|---|
| **A** | **Derived virtual `void:` graph** synthesized from `TermStats` + the `stats` counters | none (plain SPARQL) | yes — VoID/DCAT | **recommended** |
| B | Materialize VoID triples into a stored graph | none | yes | rejected |
| C | Bespoke `GET /sparql/void` JSON endpoint | custom adapter | no | rejected |
| D | Compute VoID on demand via full `COUNT` queries | none | yes | rejected |

- **B rejected** — same regress as [ADR-0006 §2](0006-commit-log-as-rdf.md):
  VoID *describes the dataset*, so storing it inside the dataset is
  self-referential, and every stored triple would change the very counts
  the VoID graph reports. It must be a derived view.
- **C rejected** — a JSON endpoint reaches neither SPARQL clients nor
  VoID/DCAT catalog tooling; it defeats the standard-vocabulary point.
- **D rejected** — O(triples) per request; prolly already maintains the
  numbers incrementally, so re-counting is pure waste.

## 3. The decision

Add a **`urn:prolly:meta:void`** virtual graph: derived, read-only,
hidden, synthesized from `TermStats` and the `stats` counters, expressed
in W3C **VoID** (with **DCAT** + **Dublin Core** for the catalog
framing). It is a second `VirtualGraphProvider` registered in the same
`VirtualGraphSailWrapper` dispatcher that ADR-0006 introduces — all four
safety rules (derived-never-stored, hidden-graph / explicit-context-only,
read-only, `evaluate()`-routing) are inherited unchanged from
[ADR-0006 §3–§4](0006-commit-log-as-rdf.md).

Three things specific to this graph:

### 3.1 No ontology to ship

Unlike ADR-0006's bespoke `pcm:` vocabulary, **RDF4J already ships
`org.eclipse.rdf4j.model.vocabulary.VOID`, `DCAT`, and `DCTERMS`**. The
synthesizer reuses those IRI constants; there is no `.ttl` to publish.
The only prolly-specific term is the cross-graph link `pcm:atCommit`
(§3.3), reused from ADR-0006.

### 3.2 Exact totals, estimated partitions — labelled honestly

`TermStats` is a *planner estimate* — a per-term frequency that may
approximate, and may or may not be position-aware (VO.1 audits this).
The total count from the `stats` tree is a maintained counter and is
treated as exact. The decision: **emit both, and document that
`void:propertyPartition` / `void:classPartition` counts are planner
estimates, not guaranteed-exact.** VoID consumers tolerate approximate
partition statistics; a federated planner *wants* estimates. An exact
recompute is deferred (open question §9).

### 3.3 Snapshot-correct, and linked to the commit graph

Unlike the commit-log graph (which is empty on a bare snapshot Sail —
no `CommitLog` sidecar), the VoID graph **works on a snapshot**: a
`?commit=<hex>` snapshot restores its own `statsRoot` from that
commit's RootMetaTree, so `urn:prolly:meta:void` naturally reports the
dataset statistics *as of that commit*. To make the "as of when" explicit
and joinable, the dataset resource carries `pcm:atCommit` pointing into
[`urn:prolly:meta:commits`](0006-commit-log-as-rdf.md) and `dcterms:modified`
from the HEAD commit timestamp.

## 4. The VoID model

```turtle
@prefix void: <http://rdfs.org/ns/void#> .
@prefix dcat: <http://www.w3.org/ns/dcat#> .
@prefix dct:  <http://purl.org/dc/terms/> .
@prefix pcm:  <https://prolly.earasoft.com/ns/commit#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

<urn:prolly:meta:dataset> a void:Dataset , dcat:Dataset ;
    void:triples          1234567 ;
    void:distinctSubjects 89012 ;
    void:properties       142 ;
    void:distinctObjects  456789 ;
    void:classes          37 ;
    dct:modified          "2026-05-15T12:00:00Z"^^xsd:dateTime ;
    pcm:atCommit          <urn:prolly:commit:abc123…> ;          # ← links to ADR-0006 graph
    void:propertyPartition [ void:property <http://xmlns.com/foaf/0.1/knows> ;
                             void:triples 8123 ] ;               # estimate (TermStats)
    void:classPartition    [ void:class <http://xmlns.com/foaf/0.1/Person> ;
                             void:entities 5012 ] ;              # estimate
    void:subset            <urn:prolly:meta:dataset:graph:…> .   # per-named-graph

<urn:prolly:meta:dataset:graph:http%3A%2F%2Fex.org%2Fg1> a void:Dataset ;
    dct:identifier "http://ex.org/g1" ;
    void:triples   4096 .
```

Notes:

- Per-named-graph `void:subset` resources give a quad store something a
  triple-only VoID graph cannot — per-context sizes. This is directly
  useful to a Mobi-style consumer whose model is graph-per-record.
- Partition resources use **deterministic sub-IRIs**, never blank nodes
  (same rule as ADR-0006 §3.4 — bnode identity across queries is a trap).
- `void:sparqlEndpoint` is deliberately *omitted* by the Sail-level
  synthesizer — see open question §9.

## 5. `TermStats` → VoID partition mapping

This is the planner-statistics half of the goal. `TermStats` maps
`TermId → frequency`. The synthesizer turns those into partitions:

| `TermStats` entry | VoID |
|---|---|
| frequency of a predicate `TermId` | `void:propertyPartition` → `void:property` + `void:triples` |
| frequency of a class `TermId` (object of `rdf:type`) | `void:classPartition` → `void:class` + `void:entities` |
| count of distinct subject/predicate/object `TermId`s | `void:distinctSubjects` / `void:properties` / `void:distinctObjects` |

The exactness of this mapping depends on whether `TermStats` counts a
term *position-aware* (predicate-position occurrences) or as an
any-position total. **VO.1 audits this** and the mapping adjusts; if
`TermStats` is not position-aware, partition counts are emitted as the
coarser any-position frequency and §3.2's "estimate" labelling covers
the imprecision.

The payoff: a federated planner reads `void:propertyPartition` and gets
prolly's own selectivity numbers in the same vocabulary it would read
from any other VoID-publishing endpoint — no bespoke format, no `COUNT`
probes.

## 6. Module placement

Identical split to [ADR-0006 §5](0006-commit-log-as-rdf.md): the
`VoidStatsSynthesizer` + `VirtualGraphProvider` registration live in
`prolly-rdf4j` (`com.earasoft.prolly.rdf4j.sail`); `prolly-rdf4j-rest`
only adds a property and one wiring line. No `.ttl` resource (§3.1).

## 7. Implementation plan (sub-iters)

Sequence: VO.1 → VO.3 → VO.4 in order; VO.2 alongside VO.1; VO.5 after
VO.4; VO.6 after VO.4; VO.7 throughout; VO.8 last.

| # | Slice | Module | Effort |
|---|---|---|---|
| VO.1 | Audit `TermStats` counting semantics (position-aware?) and locate the maintained total/per-graph counters in the `stats` sub-tree. Lock the §5 mapping. | prolly-rdf4j | half day |
| VO.2 | Confirm RDF4J's `VOID` / `DCAT` / `DCTERMS` vocabulary constants cover every term in §4. No ontology ships (contrast ADR-0006 CL.1). | prolly-rdf4j | quick |
| VO.3 | `VoidStatsSynthesizer`: pure `(totalCount, TermStats, per-graph counts, headCommit) → List<Statement>`. Emits dataset totals, property/class partitions, per-graph `void:subset`, `dct:modified`, `pcm:atCommit`. | prolly-rdf4j | full day |
| VO.4 | Register as a `VirtualGraphProvider` for `<urn:prolly:meta:void>` in the `VirtualGraphSailWrapper` dispatcher. **Depends on ADR-0006** generalizing its wrapper into the dispatcher; if ADR-0006 ships a single-purpose wrapper first, generalize it here. | prolly-rdf4j | half day |
| VO.5 | Cache the synthesized graph keyed on `ProllySail.currentCommitHash()` — stats change only on commit, so the cache key is exact. | prolly-rdf4j | half day |
| VO.6 | `prolly-rdf4j-rest`: `voidGraphEnabled` property + `ProllySailAutoConfiguration` wiring. *Optional:* advertise the graph from the SPARQL 1.1 Service Description. | prolly-rdf4j-rest | half day |
| VO.7 | Test suite (§8). | both | full day |
| VO.8 | Docs: `getting-started.md` VoID section; note historical VoID via `?commit=` (§3.3). | both | half day |

Total ≈ 3.5–4.5 dev days — smaller than ADR-0006 (no custom ontology;
the dispatcher infrastructure is shared).

## 8. Test plan

Unit (prolly-rdf4j):

- `VoidStatsSynthesizerTest` — fixture stats → exact triple set; totals
  match; one `void:propertyPartition` + one `void:classPartition`
  present with deterministic sub-IRIs; per-graph `void:subset` emitted.
- `VoidGraphProviderTest` — `GRAPH <urn:prolly:meta:void> { … }`
  returns the stats; unrestricted `getStatements` excludes them (the
  no-leak invariant); write to the graph throws `SailException`.
- `VoidGraphCacheTest` — reused between commits; invalidated on commit.

Integration (prolly-rdf4j-rest):

- `VoidEndpointTest` — over HTTP, `void:triples` agrees with
  `conn.size()` (within the documented estimate tolerance for
  partitions; exact for the total).
- Snapshot Sail (`?commit=`) returns the VoID stats *of that commit*,
  not HEAD (§3.3) — the snapshot-correctness test.
- Feature flag off → empty graph, no error.

## 9. Open questions

| # | Question | Recommendation |
|---|---|---|
| 1 | Exact vs. estimated partition counts? | **Emit both; label partitions as estimates** (§3.2). Defer an exact `void:` recompute op until a consumer needs guaranteed-exact partitions. |
| 2 | Include `void:sparqlEndpoint`? | **Omit from the Sail-level synthesizer** — the Sail does not know its own public URL, and baking a host in makes the graph non-portable. If wanted, `prolly-rdf4j-rest` (which *does* know its endpoint) can inject it as a REST-layer augmentation. |
| 3 | Per-named-graph `void:subset` for stores with very many graphs? | Include by default; if graph count is large, gate the per-graph subsets behind a `verbose` flag (mirrors ADR-0006 §8.3 for table roots). |
| 4 | Default on or off? | **Default ON, opt-out** — same reasoning as [ADR-0006 §8.1](0006-commit-log-as-rdf.md): zero cost unless explicitly queried, invisible to scans. |

## 10. Relationship to other ADRs

- **Second member of the `urn:prolly:meta:*` family** started by
  [ADR-0006](0006-commit-log-as-rdf.md). Shares the
  `VirtualGraphSailWrapper` dispatcher, the four safety rules, and the
  hidden-graph semantics. ADR-0006 should land the dispatcher; this ADR
  is its second worked provider and validates that the dispatcher
  generalizes.
- `pcm:atCommit` links this graph to ADR-0006's commit graph, so a
  single query can ask "dataset size *and* the commit it reflects."
- Distinct from [ADR-0001](0001-provenance-index.md) /
  [ADR-0003](0003-per-triple-event-log.md): those are *per-triple*
  history (O(triples), query-driven synthesis); VoID stats are
  *dataset-level* aggregates (small, eager-cacheable) — the easy
  pattern, like the commit log.

---

*Plan version 1. Ready for stakeholder review before VO.1; gated on
ADR-0006's `VirtualGraphSailWrapper` dispatcher.*
