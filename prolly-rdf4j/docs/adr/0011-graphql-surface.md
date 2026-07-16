
# ADR-0011: GraphQL surface, ontology-shape-driven

## Status

Accepted, 2026-05-20. Guides
`plans/graphql-shapes.md`
(18-step plan, Phases 0–5). The four user-visible motions in
§Context are the acceptance contract; D-1..D-12 below name the
choices. Three sub-questions (Q1–Q3, post-D-12) remain open but
are explicitly **non-blocking** — they resolve during Phases 1–2
as the actual data informs the right answer. The load-bearing
Q4 (subsumption reasoning at query time) is now **D-12**.

## Context

`prolly-rdf4j` already exposes a SPARQL Protocol surface
(`/sparql`, `/sparql/update`, `/sparql/diff`, `/sparql/commits`,
`/sparql/branches`) and a versioning model where every write lands
as a commit. The Schema page in `prolly-rdf4j-ui` already browses
RDFS/OWL classes + properties from the live store — the ontology
data the engine could derive a typed surface from is already
reachable.

The strategic position: **versioning + a typed query surface** is
what nothing off-the-shelf ships. A GraphQL `@atCommit(hex: …)`
directive that pins a query to a specific commit, plus a mutation
that compiles to SPARQL `INSERT DATA` and returns the new commit
hex, is the developer-facing UX no other RDF store offers. SPARQL
Protocol can't ergonomically deliver either: SPARQL queries don't
return commits as values, and the versioning headers live outside
the protocol.

### What "GraphQL + ontology shapes" means here

Four user-visible motions form the acceptance contract:

1. **Auto-derived schema.** Visit `/graphql`; the server's
   ontology becomes GraphQL types (`Person`, `Org`, …) with
   fields matching `rdfs:domain` / `rdfs:range` (or, preferably,
   SHACL property shapes).
2. **Typed query, SPARQL underneath.**
   `{ person(id: "alice") { name memberOf { name } } }` compiles
   to one or more SPARQL `SELECT`s, resolves the typed values, and
   returns the expected JSON shape.
3. **Versioned reads via directives.**
   `{ person(id: …) @atCommit(hex: "aabb…") { name } }` runs the
   query against a snapshot — same `SnapshotScope` machinery
   `SparqlController` uses today.
4. **Mutations land as commits.**
   `mutation { createPerson(input: {name: "Bob"}) { id, _commit } }`
   compiles to SPARQL `INSERT DATA`, fires through
   `/sparql/update`, and returns the new commit hex.

Subscriptions (typed change feeds bound to `CommitLog` appends)
are the obvious next step and are explicitly **parked**; v1 is
read + mutate.

## Options

The design space breaks into eleven sub-choices. Each has a
viable alternative that we explicitly considered and dropped; the
deciding factor for each is named below. Two are open at
ADR-acceptance time and need explicit answers before code lands
(Q1 + Q4, the "Open questions" below).

| # | Decision | Picked | Rejected | Deciding factor |
|---|---|---|---|---|
| **D-1** | Schema source | **SHACL primary, OWL derived as fallback** | OWL-only; SHACL-only | OWL is open-world ("what *is*"); SHACL is closed-world ("what data *must look like*") — the contract GraphQL needs. Where SHACL shapes exist, they're tighter and more honest types; where they don't, the existing OWL/RDFS Schema-page derivation gives a workable baseline. |
| **D-2** | Generation model | **Schema-first SDL with `@class` annotations + auto-derive baseline** | Pure auto-derive; pure schema-first | Auto-derive is zero-config and opaque; schema-first forces every team to author SDL. Hybrid lets the default just work and a power-user opt in to overrides (custom resolvers, computed fields, auth gates). Matches the existing "opinionated by default, override-able" pattern. |
| **D-3** | Module layout | **New `prolly-rdf4j-graphql` alongside `-rest` and `-grpc`** | Embed in `-rest` | A new transport-shaped surface deserves a module, the same way gRPC did. Spring auto-config wired through `-rest` so a single deployment serves SPARQL + sync + GraphQL all from one Boot app. |
| **D-4** | Library | **Spring GraphQL** (atop `graphql-java`) | DGS; HyperGraphQL | Spring GraphQL is the official Spring Boot integration; matches the existing module's framework choice. DGS brings its own opinionated runtime; HyperGraphQL is closer in spirit but unmaintained-ish. |
| **D-5** | Read-path compilation | **Per-field SPARQL first, query-AST collapse as a follow-on** | One-shot AST→SPARQL up-front | Per-field is correct, simple, N+1-prone. AST→SPARQL collapse is a perf win, not a correctness change. Don't optimize before there's a workload to optimize for. |
| **D-6** | Mutation path | **SPARQL Update + commit-through `/sparql/update`** | Direct mutation on the engine, bypassing SPARQL | Routing through `/sparql/update` reuses the existing API-key gate, commit message, audit log, write-admission interceptor. One write path, one audit trail. |
| **D-7** | Versioning surface | **Directives (`@atCommit`, `@onBranch`)** | Query params (`?commit=`, `?branch=`) | Directives compose into nested-field semantics; query params don't. Both can map to the same server-side `SnapshotScope`. |
| **D-8** | Authentication | **Reuse the existing API-key interceptor** | New auth model | Same property (`prolly.rdf4j.api-key`), same `gateAllMethods=true` semantics, same dev-mode parity. The auth surface stays in one place. |
| **D-9** | Resource bounds | **`MaxQueryComplexityInstrumentation` + `MaxQueryDepthInstrumentation` with conservative defaults + property overrides** | Run uncapped + rely on `SyncLimits`-style downstream caps | `SyncLimits` covers the sync surface; GraphQL has its own attack shape (a deeply-nested query against the entire graph). graphql-java's instrumentations are the standard mitigation. |
| **D-10** | Subscriptions | **Parked** | Ship in v1 | Needs a server-push transport story (the existing surfaces are request/response) + a per-class filter syntax. Out of scope; revisit after read+mutate is real. |
| **D-11** | Federation | **Parked** | Ship Apollo Federation directives | No present consumer asking for it. Adding federation directives commits to the Apollo schema-extension grammar; reversibility cost is high. |
| **D-12** | Subsumption reasoning at query time | **Honor reasoning** — `{ person(…) }` walks `rdfs:subClassOf*` and returns instances of every subclass too | Direct `rdf:type` only | Same Sail under both surfaces; an operator who moves a query from SPARQL to GraphQL and sees fewer rows files a support ticket. Decided up front because reversibility is one-way painful — see the dedicated section below. |

## Decision

Proceed with the twelve-choice combination above:
SHACL-primary + hybrid SDL + dedicated module + Spring GraphQL +
per-field-then-optimize + commit-through mutations + directive
versioning + existing API-key + complexity caps +
subsumption-aware resolvers. Subscriptions and federation parked.

The deciding factors that pin the overall direction:

- **The versioning + typed-mutation combo is the differentiator.**
  Any GraphQL adapter for RDF is interesting; one whose mutation
  response carries a commit hex and whose every read field can
  carry an `@atCommit` is unique. The design choices flow from
  preserving that.
- **One write path stays one write path.** D-6 (mutations through
  `/sparql/update`) keeps audit / API-key / write-admission /
  commit-message logic on a single codepath. Diverging it would
  double the security surface area.
- **Reversibility favors smaller commits up front.** Per-field
  resolvers (D-5), hybrid SDL (D-2), and the parked subscriptions
  (D-10) all leave headroom for future plans without committing
  to grammars that are hard to unship.
- **D-12 is the exception** — reversibility is *not* high for
  subsumption, so the choice gets pinned now with explicit
  rationale (next section) rather than left as a follow-on. The
  cost is: SDL emitter's class-resolution logic walks
  `rdfs:subClassOf*` from day one.

### Why D-12 honors reasoning

The recommendation that landed: **yes, honor reasoning** — the
operator's surprise from "GraphQL returns fewer results than
SPARQL on the same data" is a worse failure mode than the perf
cost of the subsumption traversal. Both surfaces sit on top of
the same Sail; clients moving a query from one to the other and
seeing different result counts is the kind of cross-surface
inconsistency that becomes a support ticket.

But this is breaking-change-on-the-wrong-answer: if we shipped
"no" (GraphQL ignores subsumption — `Person` returns only direct
instances) and flipped to "yes" later, every client query that
returned N rows would return N+k. There's no shim that makes
that silent — clients have to be told. The inverse is also bad:
ship "yes" and flip to "no" later means client queries that
returned N+k now return N, a regression that looks like data
loss.

Either choice is hard to walk back. Pinned now; this ADR
documents the rationale. Deprecation in the other direction
requires its own ADR + explicit client-migration window.

## Consequences

**Build.** Adds a Maven module + a Spring GraphQL dep. The
`prolly-rdf4j-rest` reactor module picks the new module up so a
single deployment serves all four surfaces (SPARQL, sync HTTP,
sync gRPC, GraphQL). Spring auto-config gated on
`prolly.rdf4j.graphql.enabled=true` — zero startup cost when off.

**Test surface.** A new set of integration tests covering the
four acceptance motions. Per-class end-to-end first (`foaf:Person`
through every motion), then widen. The existing W3C compliance
work doesn't touch this; the GraphQL surface gets its own matrix.

**Runtime.** N+1 SPARQL on every nested-field traversal until the
AST-collapse work lands. For the workloads the project targets
today (tens of MB of RDF), this is fine. A consumer hitting >GB
will feel it; the optimizer is the answer when it matters.
D-12's subsumption walk is one extra `rdfs:subClassOf*` lookup
per class-typed field; constant work per query, not per-row, so
it's noise next to the N+1.

**Security.** One new attack shape: deeply-nested or
high-complexity queries. Mitigated by `MaxQueryDepth`/`Complexity`
caps + the same API-key gate. The mutation path inherits the
existing write-side guards.

**Maintenance.** SHACL + OWL drift between the data and the
generated schema is a real cost. The SDL emitter regenerates on
boot — a deployed schema reflects the current ontology. A
property-only SHACL change becomes a GraphQL schema change with
no separate migration step.

**Compatibility.** v1 ships SHACL-primary. A store with only OWL
falls back; the fallback is best-effort and may yield looser
types. If a future plan switches the primary, it's a
schema-change for that store but not a breaking wire change
(GraphQL clients see new/stricter fields, not different ones).

## Open questions (non-blocking)

Three points still need explicit answers, but they can resolve
during Phases 1–2 as the actual ontology data informs the right
call. None of them gates a step; the SDL emitter (Step 3) has
everything it needs from D-1..D-12. The recommendation column is
the lean direction; treat as a default, not a commitment.

| # | Question | Recommendation | Why it's still open |
|---|---|---|---|
| **Q1** | Single graph vs named-graph routing — does a GraphQL query default to the default graph, the union, or take a `@inGraph(iri: …)` directive? | **Default-graph, with `@inGraph` directive for explicit override.** | Depends on how named graphs are actually used in target deployments. A union-by-default would surprise operators of multi-tenant stores. |
| **Q2** | Blank nodes — project as inline objects (no `id` field) or require Skolemization at read? | **SHACL shapes drive the choice** — if the shape names a blank-node target with no IRI, project inline; otherwise Skolemize. | Without SHACL coverage of all target classes, the decision becomes data-dependent at runtime. |
| **Q3** | Property paths (OWL `owl:Restriction` over property chains, SPARQL `+` / `/`) — projecting as single GraphQL fields is genuinely lossy. | **Parked** for v1; the SPARQL-pass-through escape hatch in the plan's "Future work" handles the cases that need it. | A real consumer might want first-class support. None has asked yet. |

The original Q4 (subsumption reasoning) is now **D-12** in the
Decisions section above — pinned because reversibility ran the
wrong direction for that one.

## Follow-up

- **ADR-0012 (when needed):** Subscriptions over WebSocket bound
  to `CommitLog`. Triggered when a real consumer asks for a typed
  change feed.
- **ADR-0013 (when needed):** Federation directives. Triggered
  when a multi-server supergraph deployment is in scope.
- **`plans/graphql-shapes.md`:** Step 1 (this ADR) is closed.
  Step 2 (module scaffold) is the next unchecked step; Q1–Q3
  resolve in-line during Phases 1–2.
