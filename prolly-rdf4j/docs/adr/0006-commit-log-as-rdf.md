
# ADR-0006: Expose the Commit Log as a Virtual RDF Graph

## Status

Proposed, backfilled 2026-06-23 (predated the `## Status` convention) — no virtual commit-log RDF graph is implemented.

| Status   | Proposed                                                       |
|----------|----------------------------------------------------------------|
| Decision | **Option A** — derived, read-only virtual named graph via a `SailWrapper` |
| Iter     | CL (sub-iters CL.1 → CL.8)                                     |
| Authors  | prolly-rdf4j team                                              |

> **Goal:** a plain SPARQL client — with no knowledge of prolly's REST
> endpoints — can query the commit history:
> `SELECT ?c ?t ?msg WHERE { GRAPH <urn:prolly:meta:commits> { ?c a pcm:Commit ; prov:generatedAtTime ?t ; pcm:message ?msg } }`

## 1. The problem

prolly-rdf4j's versioning — the RootMetaTree commit DAG, [`CommitLog`](../../src/main/java/com/earasoft/prolly/rdf4j/sail/CommitLog.java),
[`RefsStore`](../../src/main/java/com/earasoft/prolly/rdf4j/sail/RefsStore.java) —
is reachable today only through prolly-rest's own REST surface
(`/sparql/commits`, `?commit=<hex>`, branch/merge endpoints). None of
that is part of the **SPARQL 1.1 Protocol**.

That matters because the realistic integration path for an external
consumer (the motivating case: Mobi connecting via RDF4J's
`SPARQLRepository`) speaks *only* the SPARQL protocol. A `SPARQLRepository`
client hits one query endpoint and one update endpoint; it has no verb
for "list commits", "read at commit X", or "show branches". So with the
store wired up the obvious way, **prolly's entire version history is
invisible** — the consumer sees a flat triple store and nothing else.

Constraints:

- **No new wire protocol.** The value of the SPARQL-protocol path is
  that it is zero-integration-code on the client side. A bespoke
  protocol throws that away (see [the gRPC/transport discussion] — out
  of scope here, but the same logic applies).
- **No on-disk format change.** v0.2.0 pins bit-compatibility with
  Dolt's Go port; the commit log is *already* persisted in sidecars.
  This feature must be pure read-side projection.
- **Must not perturb existing query semantics.** A consumer doing
  `getStatements(null,null,null)` or `SELECT * WHERE { ?s ?p ?o }`
  must get exactly today's result — no synthetic triples leaking into
  data scans or exports.

## 2. The options

| # | Approach | Client code | On-disk change | Time-travel | Verdict |
|---|---|---|---|---|---|
| **A** | **Derived virtual named graph via `SailWrapper`** — synthesize commit-log triples on read from the sidecars | none (plain SPARQL) | none | via `?commit=` URL pinning | **recommended** |
| B | Materialize commit-log triples into a real stored graph | none | yes — and see below | n/a | rejected |
| C | Keep it on bespoke REST; client adds a custom adapter | custom wrapper | none | custom | rejected — defeats the SPARQL-protocol path |
| D | RDF4J-server transaction/system protocol instead of generic SPARQL | RDF4J-specific | none | RDF4J-specific | rejected — re-couples to a non-standard protocol |

### Why B is rejected — the regress

Materializing the commit log as ordinary stored triples is
**self-referential and cannot terminate**. Writing the triples for
commit *N* is itself a write → which produces commit *N+1* → whose
triples must then be written → commit *N+2* … The commit log can only
ever be a *derived* view, computed from the sidecars (`commits.log`,
`refs/`, RootMetaTree chunks) that are already the source of truth.
This is the same reasoning ADR-0001 §2.1 used to record the *parent*
hash rather than the self hash.

### Why A wins

- **Zero client code, zero on-disk change.** A consumer queries a
  well-known graph IRI through the endpoint it already uses.
- **Right architectural layer.** The projection must intercept the
  RDF4J Sail SPI (`getStatements` / `evaluate`), so it sits *below*
  HTTP — usable by embedded `ProllySail` consumers too, not just
  prolly-rest. It belongs in `prolly-rdf4j`, not `prolly-rdf4j-rest`
  (see §5).
- **No format-version impact.** A `SailWrapper` adds no bytes to disk.

## 3. The decision

Implement a **`CommitMetaSailWrapper`** (an RDF4J `SailWrapper` over
`ProllySail`) that exposes one **derived, read-only, hidden** named
graph, `<urn:prolly:meta:commits>`, whose triples are synthesized from
`CommitLog` + `RefsStore` at read time.

Four design rules make this safe; each is load-bearing.

### 3.1 Derived, never stored

The graph is computed from the sidecars on demand. Nothing is written
to any prolly tree. Querying at HEAD reflects the live `CommitLog`;
querying a bare snapshot Sail (which has no sidecars — see
[`root-meta-tree.md`](../root-meta-tree.md)) yields an *empty* meta
graph, and that is correct and documented behavior.

### 3.2 Hidden-graph semantics — explicit context only

The synthetic triples are returned **iff the meta-graph IRI is named
explicitly** as the query context. They are excluded from:

- `getStatements(s,p,o)` with no context (the default-graph / all-graphs
  scan),
- `getStatements(s,p,o, <other graphs…>)` that doesn't name the meta IRI,
- `getContextIDs()`,
- a SPARQL `GRAPH ?g { … }` with a *variable* graph.

A variable graph never binds `<urn:prolly:meta:commits>`. Only a
constant `GRAPH <urn:prolly:meta:commits>` does. This guarantees
existing scans, `CONSTRUCT`-based exports, and round-trips are
**byte-for-byte unchanged** — the feature is invisible until asked for.

### 3.3 Read-only

`addStatement` / `removeStatements` / `clear` targeting the meta graph
throw a `SailException` with a clear message ("the commit-metadata
graph is read-only; use the versioning endpoints to branch/commit/merge").
Silent no-op is rejected — it would hide consumer bugs.

### 3.4 Vocabulary — PROV-O base + a small `pcm:` term set

Reuse W3C **PROV-O** for the interoperable spine; add a minimal
prolly-commit vocabulary `pcm:` (`https://prolly.earasoft.com/ns/commit#`)
for the content-addressing specifics PROV-O has no term for. Shipped as
an OWL ontology resource (`pcm.ttl`) so a consumer — e.g. Mobi, which
already runs a PROV-O-based `com.mobi.prov` subsystem — can load it.

```turtle
@prefix pcm:  <https://prolly.earasoft.com/ns/commit#> .
@prefix prov: <http://www.w3.org/ns/prov#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

# --- a commit (content-addressed snapshot) ---
<urn:prolly:commit:abc123…> a pcm:Commit , prov:Entity ;
    pcm:rootHash        "abc123…" ;
    prov:generatedAtTime "2026-05-15T12:00:00Z"^^xsd:dateTime ;
    pcm:message         "Add gene annotations" ;
    pcm:firstParent     <urn:prolly:commit:def456…> ;        # 0..1
    pcm:mergeParent     <urn:prolly:commit:0099aa…> ;        # 0..1, merge commits only
    prov:wasDerivedFrom <urn:prolly:commit:def456…> ,
                        <urn:prolly:commit:0099aa…> .         # materialized for non-reasoning clients

# --- a branch (ref) ---
<urn:prolly:branch:main> a pcm:Branch ;
    pcm:branchName "main" ;
    pcm:head <urn:prolly:commit:abc123…> .
```

Key vocabulary choices:

- **Instance IRIs are URNs** (`urn:prolly:commit:{hex}`,
  `urn:prolly:branch:{name}`), not HTTP IRIs. A commit's IRI is then
  **deployment-independent** — the same commit has the same IRI in
  every replica, in the spirit of content-addressing. Branch names are
  percent-encoded into the URN.
- `pcm:firstParent` / `pcm:mergeParent` are declared
  `rdfs:subPropertyOf prov:wasDerivedFrom`. The synthesizer **also
  materializes `prov:wasDerivedFrom`** explicitly, so clients that
  don't run a reasoner still get the PROV view. The first/merge split
  preserves the ordered-parents distinction `persistMetaTreeIfConfigured`
  records (first = authored-on, merge = source side).
- Per-table RootMetaTree roots (`dict`, `spoc`, …) are **omitted by
  default** — they are deep-inspection detail and would bloat the
  graph. A `verbose` flag (open question §8) can emit them under
  deterministic sub-IRIs `urn:prolly:commit:{hex}:table:{name}`; never
  blank nodes (bnode identity across queries is a trap).

## 4. The hard part — `evaluate()` routing

`ProllySailConnection` has a **custom `evaluate()`** that routes BGPs to
the prolly `GraphPatternEngine` and scans the trees directly. A naive
`SailWrapper` that overrides only `getStatements` would be bypassed:
a SPARQL query for the meta graph goes through `evaluate()`, hits the
BGP engine, finds nothing in the trees, and returns empty.

So the wrapper **must override `evaluate()` too**:

```
CommitMetaSailConnection.evaluate(tupleExpr, …):
  if referencesMetaGraph(tupleExpr):
      // Evaluate the whole query with a generic strategy backed by
      // THIS connection's getStatements (which synthesizes meta +
      // delegates real data). Slower, but the meta graph is tiny.
      return genericEvaluation(tupleExpr, …)
  else:
      return delegate.evaluate(tupleExpr, …)   // fast prolly BGP path, untouched
```

`referencesMetaGraph` scans the `TupleExpr` for a `StatementPattern`
whose context is a *constant* equal to `<urn:prolly:meta:commits>`.
Consequences accepted:

- Pure-data queries take the fast path unchanged — **zero regression**
  for the 99% case.
- A query that *joins* the meta graph against real data falls entirely
  to generic evaluation. Correct, just not leapfrog-optimized — fine,
  the meta graph has at most a few × commit-count triples.

This is the riskiest slice and gets its own sub-iter (CL.4). If
TupleExpr inspection proves fragile across RDF4J query shapes, the
fallback is to integrate the check directly into
`ProllySailConnection.evaluate` rather than wrap (open question §8).

## 5. Module placement

| Piece | Module |
|---|---|
| `CommitMetaSailWrapper` + `CommitMetaSailConnection` | `prolly-rdf4j` — `com.earasoft.prolly.rdf4j.sail` |
| `CommitGraphSynthesizer` (pure projection function) | `prolly-rdf4j` |
| `pcm.ttl` ontology + `Pcm` IRI-constants class | `prolly-rdf4j` — `src/main/resources` + `…/vocab` |
| Config flag on the Sail | `prolly-rdf4j` |
| Property + compose-the-wrapper wiring | `prolly-rdf4j-rest` — `ProllySailProperties`, `ProllySailAutoConfiguration` |
| *Optional:* content-negotiated Turtle on `/sparql/commits` | `prolly-rdf4j-rest` |

`prolly-rdf4j-rest`'s only job is one or two lines: read the property,
and if on, `new CommitMetaSailWrapper(prollySail)` before handing it to
the `SailRepository`. The mechanism is a Sail behavior; only the
*presentation convenience* (Turtle on the existing JSON endpoint) is
genuinely REST.

## 6. Implementation plan (sub-iters)

Each slice is independently testable. Sequence: CL.1 → CL.2 → CL.3 →
CL.4 must land in order; CL.5 depends on CL.3; CL.6 depends on CL.4;
CL.7 throughout; CL.8 last.

| # | Slice | Module | Effort |
|---|---|---|---|
| CL.1 | `pcm.ttl` ontology + `Pcm` IRI constants. Publish the namespace. | prolly-rdf4j | half day |
| CL.2 | `CommitGraphSynthesizer`: pure `(CommitLog, RefsStore, headHash) → List<Statement>`. No Sail wiring — unit-tested standalone against fixture logs. | prolly-rdf4j | full day |
| CL.3 | `CommitMetaSailWrapper` + `CommitMetaSailConnection`: `getStatements` interception for the meta context; hidden-graph exclusion from unrestricted scans + `getContextIDs`; read-only guard on write methods. | prolly-rdf4j | full day |
| CL.4 | `evaluate()` routing — `referencesMetaGraph(TupleExpr)` + generic-evaluation fallback. The hard slice (§4). | prolly-rdf4j | full day |
| CL.5 | Caching: synthesize lazily, cache the `List<Statement>` keyed on `ProllySail.currentCommitHash()`; rebuild only when HEAD advances. | prolly-rdf4j | half day |
| CL.6 | `prolly-rdf4j-rest` wiring: `ProllySailProperties.commitMetaGraphEnabled`, `ProllySailAutoConfiguration` composes the wrapper. | prolly-rdf4j-rest | half day |
| CL.7 | Test suite (§7). | both | full day |
| CL.8 | Docs: `getting-started.md` section, update `root-meta-tree.md` ("history is now SPARQL-queryable"), the SPARQL-client integration note. *Optional:* content-negotiated Turtle/JSON-LD on `/sparql/commits`. | both | half day |

Total ≈ 5–6 dev days.

## 7. Test plan

Unit (prolly-rdf4j):

- `CommitGraphSynthesizerTest` — fixture `CommitLog` (genesis, linear,
  a merge commit with two parents, multiple branches) → assert exact
  triple set; merge commit emits `pcm:firstParent` + `pcm:mergeParent`
  + both `prov:wasDerivedFrom`.
- `CommitMetaSailWrapperTest` —
  - `GRAPH <urn:prolly:meta:commits> { … }` returns the history;
  - unrestricted `getStatements(null,null,null)` does **not** include
    synthetic triples (the no-leak invariant, §3.2);
  - `getContextIDs()` excludes the meta IRI;
  - `GRAPH ?g { … }` never binds the meta graph;
  - write to the meta graph throws `SailException`.
- `CommitMetaEvaluateRoutingTest` — pure-data query takes the delegate
  path (assert via a spy/counter); meta-graph query routes to generic
  evaluation and returns correct rows; a meta⨝data join query is
  correct.
- `CommitMetaCacheTest` — second query after no commit reuses the
  cache; a new commit invalidates it.

Integration (prolly-rdf4j-rest):

- `CommitGraphEndpointTest` — over HTTP `/sparql`, a `SELECT` against
  the meta graph agrees with `/sparql/commits` JSON (parity).
- Snapshot Sail (`?commit=`) returns an empty meta graph (documented).
- Feature flag off → meta-graph query returns empty, no error.

## 8. Open questions

| # | Question | Recommendation |
|---|---|---|
| 1 | Default on or off? | **Default ON, opt-out.** Unlike ADR-0001/0003 (which are opt-in *because* they add write-path and storage cost), this feature has **zero cost unless the meta graph is explicitly queried** and is invisible to every existing scan (§3.2). The opt-in house style doesn't apply; defaulting on maximizes the integration value. Still expose an off switch for operators who object to a reserved IRI. |
| 2 | Wrapper vs. integrate into `ProllySailConnection`? | Start with the **`SailWrapper`** (clean separation, embedded-reusable). Fall back to in-`ProllySailConnection` integration only if the `evaluate()` TupleExpr inspection (CL.4) proves fragile. |
| 3 | Emit per-table RootMetaTree roots? | **Omit by default;** add a `verbose` flag later if a deep-inspection use case appears. Deterministic sub-IRIs, never bnodes. |
| 4 | Expose branch→commit *reachability* (which commits are "on" a branch)? | **No.** It is derivable by the client from the parent edges; materializing it means a graph walk per branch. Keep the projection a flat fact dump. |

## 9. Alternatives considered and rejected

### Per-commit *content* graphs for time-travel (rejected)

An earlier sketch proposed a second graph-IRI family,
`<urn:prolly:commit:{hex}>`, resolving to `ProllySail.openSnapshotAt(hex)`
so `GRAPH <urn:prolly:commit:hex> { ?s ?p ?o }` queries historical
state. **Rejected** — it is semantically lossy for a quad store. prolly
stores quads (the CSPO index); a snapshot has its *own* internal named
graphs. Projecting a snapshot's quads into a single graph IRI flattens
that graph structure — `GRAPH ?g` binds one graph, and "all graphs at
commit X" cannot be one graph IRI without losing the distinction.

**Supported time-travel for a SPARQL client instead:** pin the endpoint
URL — point the client's repository at `…/sparql?commit=<hex>`, giving
a read-only repository fixed at that commit *with its graph structure
intact*. It is static per repository, which is the honest tradeoff.

### Future work (not in scope)

- **Per-commit additions/deletions diff graphs.** `DiffEngine` could
  back `<urn:prolly:commit:hex:additions>` / `…:deletions>` — and that
  shape maps directly onto Mobi's own `Revision` model, so it is the
  natural seam for the Tier-2 `VersioningService` integration. Subject
  to the same quad-flattening caveat; gate on real demand.
- **Driving versioning over SPARQL.** This ADR makes history
  *readable*. Performing branch/commit/merge still goes through the
  REST endpoints; interpreting SPARQL Update against the meta graph as
  ref operations was considered and rejected as too clever/fragile.

## 10. Relationship to other ADRs / plans

- Complements [ADR-0001](0001-provenance-index.md) (per-triple
  provenance) and [ADR-0003](0003-per-triple-event-log.md): those
  expose *per-triple* history; this exposes the *commit-level* DAG.
  All three are read projections of the same versioned substrate.
- Independent of `plans/08-structural-merge.md`.
  If the team prefers the `plans/` workflow, §6 can be lifted into a
  `plans/09-commit-log-as-rdf.md` phase plan unchanged.

---

*Plan version 1. Ready for stakeholder review before CL.1.*
