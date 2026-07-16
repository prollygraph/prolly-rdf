
# ADR-0014: Auth graph write + read gates

## Status

Accepted, 2026-05-22. Guides `plans/auth-on-sail.md`
Step 10 (cutover) — without these gates, the auth-on-sail migration
ships with three privilege-escalation footguns documented as CRITICAL
findings H-1, H-2, H-3 in
`plans/auth-on-sail-hardening.md`.

## Context

`plans/auth-on-sail.md` moved user accounts + pseudonym mappings from
RocksDB-backed stores into SPARQL-backed named graphs
(`<urn:prolly-rdf4j:auth/users>`, `<urn:prolly-rdf4j:auth/pseudonyms>`).
Migration is technically complete + tested. But the hardening matrix
(53 rows × 10 risk categories) surfaced three CRITICAL findings that
ship the system **with privilege-escalation vectors the previous
RocksDB-CF design didn't have**:

- **H-1**: SPARQL `DELETE WHERE { ?s ?p ?o }` (no `GRAPH` clause) is
  graph-blind per spec — it matches the union of ALL graphs including
  named ones. An admin running an unscoped delete wipes the user table.
  Named-graph placement DOES NOT enforce isolation on its own.

- **H-2**: Any authenticated user with `/sparql/update` access can
  `INSERT DATA { GRAPH <urn:prolly-rdf4j:auth/users> { ?fake a auth:UserAccount ; auth:isAdmin true ; ... } }`,
  granting themselves admin without going through the admin-only
  `/auth/users` endpoint. The data layer doesn't gate writes; the
  only gate today is the controller's `ROLE_ADMIN` check on
  `/auth/users` — which SPARQL Update bypasses.

- **H-3**: Any authenticated user with `/sparql/query` access can
  `SELECT ?h WHERE { GRAPH <urn:prolly-rdf4j:auth/users> { ?u auth:passwordHash ?h } }`
  and fetch every user's BCrypt hash. BCrypt cost-10 makes offline
  cracking non-trivial but a leak is still a leak — weak passwords
  fall.

A fourth, lesser concern:

- **H-4**: Auth graphs are listed in `/sparql/contexts` by default.
  Operators can SEE the auth subsystem exists. Not a security issue
  alone but pairs with H-2/H-3 — naming the trap makes it easier to
  walk into.

The previous RocksDB-CF design avoided all four by putting auth state
in a **different database technology** than SPARQL data — there was no
path through `/sparql/update` to reach it. The auth-on-sail design's
benefits (audit trail, queryability, unified backup, RDF-native) come
at the cost of these new write/read surfaces needing explicit gates.

This ADR decides which surfaces to gate + how strictly.

## Options

The decisions are mostly orthogonal — gates on write, read, and
contexts-listing can each be chosen independently. We evaluate each
axis on its own then pick the combination.

### Axis 1 — write-gate on auth graphs

| Option | Performance | Operator surprise | Bypass risk | Complexity |
|---|---|---|---|---|
| **A — Hard block**: `/sparql/update` rejects ANY mutation touching `<urn:prolly-rdf4j:auth/*>` graphs (regardless of role) | pre-parse check, negligible | high — even admins can't `/sparql/update` auth; must use `/auth/users` | none | low |
| **B — Admin-only**: `/sparql/update` allows mutations to auth graphs only when caller has `ROLE_ADMIN` | negligible | medium — admins keep ad-hoc tooling | low — only via admin-credential theft | low |
| **C — No gate**: rely on operator discipline + naming convention | none | none (status quo) | **HIGH — H-2 stays open** | none |

### Axis 2 — read-gate on auth graphs

| Option | Performance | Operator surprise | Bypass risk | Complexity |
|---|---|---|---|---|
| **D — Hard block**: `/sparql/query` rejects ANY query touching `<urn:prolly-rdf4j:auth/*>` | negligible | high — kills the "operator queries accounts via SPARQL" goal of auth-on-sail.md Goal #1 | none | low |
| **E — Admin-only**: queries against auth graphs allowed only for `ROLE_ADMIN` | negligible | medium — admins keep queryability | low | medium |
| **F — Redact `passwordHash`**: a SPARQL-rewriter elides `auth:passwordHash` triples unless `ROLE_ADMIN` | medium (per-query rewrite) | low — operators see most data | low | high |
| **G — No gate**: status quo | none | none | **HIGH — H-3 stays open** | none |

### Axis 3 — contexts-listing

| Option | Performance | Operator surprise | Bypass risk | Complexity |
|---|---|---|---|---|
| **H — Hide auth graphs from `/sparql/contexts` for non-admins** | negligible | none (it's the default expectation) | n/a (defense in depth) | low |
| **I — Show all** | none | none | none on its own | none |

## Decision

Combined: **A + E + H** — Hard write-block, admin-only reads, hidden
contexts for non-admins.

**D-1. Hard block on writes (Axis 1 → A).** `/sparql/update` parses
the SPARQL Update + rejects (HTTP 403 +
`{"error":"auth_graph_protected","message":"Auth graphs are read-only via the SPARQL endpoint; use /auth/users for account changes."}`)
if ANY operation in the statement touches an auth graph. The check
runs server-side, pre-execution, on the `SparqlController.update`
endpoint. Implementation:
   1. Parse the SPARQL Update.
   2. Walk the AST collecting graph IRIs from `INSERT`, `DELETE`,
      `WITH`, and `USING` clauses.
   3. If any IRI matches `<urn:prolly-rdf4j:auth/...>`, reject.
   4. **Unscoped operations** (`DELETE WHERE { ?s ?p ?o }` with no
      graph anchor) also reject — they're graph-blind by SPARQL spec
      and would hit auth graphs by union semantics.

The deciding tradeoff vs Option B (admin-only): **non-bypassable**.
Even if an admin's session is compromised, the attacker can't escalate
to permanent admin via SPARQL Update — the attack still needs a
separate `/auth/users` privilege escalation. Defense in depth.

The pain (admins lose ad-hoc auth edits via SPARQL Update) is
acceptable: the `/auth/users` endpoints already cover every legitimate
auth-state mutation. Admins who genuinely need raw SPARQL access for
the auth tables can attach to the JVM via a debugger — that's a
known-operator-action surface.

**D-2. Admin-only on reads (Axis 2 → E).** `/sparql/query` parses the
SPARQL Query + rejects (HTTP 403 + same JSON shape, error code
`auth_graph_protected`) if ANY graph pattern, `FROM`, or `FROM NAMED`
IRI matches the auth namespace AND the caller lacks `ROLE_ADMIN`.
Admins get full access. Implementation mirrors D-1's AST walk.

The deciding tradeoff vs Option F (redact `passwordHash`
specifically): **simpler + correct enough**. SPARQL-rewriter complexity
isn't justified — non-admin users mostly don't need to query the
user table at all. Goal #1 of auth-on-sail.md (operator queries
schema + accounts) is preserved for admins, who are the operators
the goal targets.

**D-3. Hide auth graphs from `/sparql/contexts` for non-admins (Axis 3
→ H).** The controller filters the context list by stripping
`<urn:prolly-rdf4j:auth/users>` + `<urn:prolly-rdf4j:auth/pseudonyms>`
IRIs when the caller lacks `ROLE_ADMIN`. The TBox graph
`<urn:prolly:auth/>` is NOT hidden — schemas are public documentation,
not secrets. Mirrors the staging-branch-hidden pattern from
`staging-hidden-trees.spec.ts`.

**D-4. Consistent error shape.** All three gates return HTTP 403 with
a JSON body of shape `{"error": "<code>", "message": "<human>"}`. The
error code `auth_graph_protected` extends the existing
`account_disabled` convention (Step 18 of `plans/user-accounts.md`)
so frontend interceptors can route on the `error` field.

**D-5. The gates live at the HTTP controller layer, not the data
layer.** `BootstrapAdminInitializer`, `OntologyLoader`, and the
`AuthController` write/read via `RepositoryConnection.add(...)` +
direct `SparqlUsersStore` calls — they bypass the gates because they
ARE the trust boundary. Code that runs SPARQL through
`/sparql/update` or `/sparql/query` is by definition
operator-driven; that's where the gate applies.

## Consequences

**Cost of fix**:
- A new `SparqlAuthGateInterceptor` (or equivalent) class that
  pre-parses SPARQL statements + rejects auth-graph mutations /
  non-admin reads.
- ~3-4 unit tests per gate (admin allowed / non-admin blocked /
  malformed-statement-rejected / hard-block-on-unscoped-delete).
  ~12 tests total.
- One small filter on the `/sparql/contexts` endpoint.
- 2-3 e2e tests for end-to-end verification against the booted jar.
- Documentation update in
  `newcomer-docs/foundations/auth-as-data.md`
  explaining the gate's contract + the rationale for the trade-off.

**Operator-visible changes**:
- An ad-hoc `INSERT DATA { GRAPH <urn:prolly-rdf4j:auth/users> { ... } }`
  from the SPARQL workbench now returns 403 with
  `auth_graph_protected`. Documented in the newcomer doc.
- An ad-hoc `SELECT * WHERE { GRAPH <urn:prolly-rdf4j:auth/users> { ... } }`
  works for admins, 403s for non-admins.
- `/sparql/contexts` returns N-2 graphs for non-admins (the auth
  USERS + PSEUDONYMS graphs are hidden; the TBox graph stays).
- `DELETE WHERE { ?s ?p ?o }` (unscoped) now returns 403 instead
  of silently wiping auth state. This is the H-1 fix; the change
  is observable.

**Cost we accept**:
- Admins lose the ability to bulk-edit auth via SPARQL Update. The
  `/auth/users` endpoints cover every legitimate mutation; the loss
  is bulk-create-from-CSV, which has no current user.
- Per-query AST walk adds ~100µs to `/sparql/query` + `/sparql/update`
  latency. Negligible vs the query execution itself (typical SPARQL
  query: 1-50 ms).

**Cost we DON'T accept**:
- We do NOT add an in-memory cache for `SparqlUsersStore.findUser`.
  The G-3 finding (every authenticated request hits the SPARQL store)
  is a perf concern but THIS ADR's gate runs on the workbench-facing
  endpoints, not on the session-cookie hot path. Cache decision is
  its own ADR if ever justified.

**Migration story**:
- This ADR's implementation runs in a single follow-up plan after
  `plans/auth-on-sail.md` Step 10 unblocks. No data migration; pure
  controller-layer code addition.

**Open hardening findings still untouched after this ADR**:
- E-3 (session-survives-delete-recreate): documented in hardening
  plan; the cascade exists in the DELETE controller; tightening to
  SessionStore-level is a separate ADR if needed.
- G-3 (every request hits SPARQL): documented; cache opportunity is
  separate.
- B-5 (per-row resumable migration): documented; v1's all-or-nothing
  migration is acceptable for single-instance deployments.

## Follow-up / future work

- **Implementation plan**:
  `plans/auth-graph-gates.md` — 6 steps:
  (1) `SparqlGraphRefExtractor` utility that walks SPARQL ASTs to
  collect referenced graph IRIs;
  (2) write-gate enforcement at `SparqlController.update`;
  (3) read-gate at `SparqlController.query`;
  (4) contexts-hide filter at `SparqlController.contexts`;
  (5) e2e tests against the booted jar (the 3 H-row findings from
  the hardening matrix flip from "documented vulnerability" to "fixed +
  pinned");
  (6) update `auth-as-data.md` + CLAUDE.md.
- **ADR-N+1 (when justified)**: configurable graph-prefix lists for
  multi-tenant deployments where each tenant has its own
  protected-graph namespace.
- **ADR-N+2 (when justified)**: per-tenant or per-org auth graphs.

## Open questions

- **Q1**: Should the write-gate also forbid reading the gates' own
  trigger paths via reflection / introspection endpoints? Probably no
  — the gates are not secrets, they're documented contracts.
- **Q2**: Do we expose a per-test-fixture bypass flag? Probably no —
  tests should exercise the production gate path. The
  `BootstrapAdminInitializer` bypass via direct `RepositoryConnection`
  is the only sanctioned exception, and it's a controlled API.
- **Q3**: Are there other named-graph IRIs that need similar gates? At
  v1, no — auth is the only sensitive named-graph namespace. Future
  per-tenant or per-org graphs would need their own ADR.
