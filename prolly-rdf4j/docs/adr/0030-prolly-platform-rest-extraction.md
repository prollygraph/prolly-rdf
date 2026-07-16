
# ADR-0030 — prolly-platform-rest extraction

## Status

Accepted, 2026-05-26. Amended 2026-05-27 — see
[Amendment 2026-05-27](#amendment-2026-05-27--three-decisions-that-warrant-adr-level-discoverability)
at the bottom for three additional decisions (D-9 atomic
property-name migration, D-10a `kindLabel: String` for the
jobs dashboard, D-13 SPARQL auth backend stays in
`prolly-rdf4j-rest`) surfaced during a `/ultrathink` plan
review and judged ADR-worthy. Updates
[ADR-0004](0004-platform-extraction.md) — *"Extract `prolly-platform`;
let non-RDF faces plug in"* — by capturing the concrete shape of the
extraction now that the demand exists (`prolly-json-rest`,
`plans/prolly-json-rest-controllers.md`).
Anchors a new cross-cutting plan
`prolly-platform-rest/plans/extract-prolly-platform-rest.md`.
Blocks Plan 2 of prolly-json
(`plans/prolly-json-multitenant-wiring.md`)
— which gets slimmed in the same series of commits to assume
platform-rest exists.

## Context

`prolly-rdf4j-rest` has accumulated three distinct roles:

1. **RDF face** — SPARQL controllers, sail factories, named-graph
   config, streaming SSE, schema/instance editors.
2. **Platform** — multitenant routing, per-repo storage layout,
   user accounts, PATs, SSH keys, sessions, repo + org admin,
   permissions, audit log, security filter chain, CORS + SPA
   shell forwarding.
3. **Cross-cutting product** — merge-request review, webhook bus,
   sync controller.

Until 2026-05-26 the second role was invisible because no other
face needed it — `prolly-rdf4j-rest` was the only thing on the
classpath that wanted REST. Today `prolly-json-rest` ships
([commit `a1114c3`](https://github.com/mannyrivera2010/prolly-port/commit/a1114c3))
and immediately needs the same multitenant routing + same auth
+ same role gates that role (2) provides.

Without an extraction, `prolly-json-rest` would have to **depend
on `prolly-rdf4j-rest`** to get the platform — and inherit the
entire RDF surface (role 1) + the cross-cutting product (role 3)
as transitive dependencies. That's:

- **wasteful** — 85% dead weight for a JSON-only deployment;
- **dishonest** — the module name `prolly-rdf4j-rest` becomes a
  lie (it would be the platform that happens to also do RDF);
- **bait** for future modules — every future REST face would
  inherit the same lie.

[ADR-0004](0004-platform-extraction.md) proposed this extraction
in the abstract. This ADR makes it concrete.

## Options

| Option | Module shape | Cost | Defect mode |
|---|---|---|---|
| **A — Status quo: `prolly-json-rest` depends on `prolly-rdf4j-rest`** | Two REST modules; json depends on rdf4j | Zero | Module-name lie; deadweight transitive dep; locks the smell in for future modules; reinforces the god-module pattern |
| **B — Full platform-rest extraction (THIS ADR)** | Three peer REST modules + a new `prolly-platform` core; rdf4j-rest and json-rest are siblings under platform-rest | ~50–80 file moves + package renames; ~30–50 test moves; CLAUDE.md sweep | Risk of breaking the load-bearing auth filter chain during the move; mitigated by atomic phase commits + before-and-after auth integration test runs |
| **C — Duplicate the platform code in `prolly-json-rest`** | Two copies of multitenant + auth | Massive duplication | Two sources of truth; fixes don't propagate; bug-prone |
| **D — Extract incrementally — one class at a time over many sessions** | Same end state as B; gentler each step | Many small commits; midpoint states have weird module shapes | Hard to test intermediate states; reviewer cognitive load across N commits |

## Decision

**Option B — full extraction into `prolly-platform-rest` (Spring
layer) + `prolly-platform` (core).** Big-bang within each phase
but staged across phases (Phase 0–5 per
`prolly-platform-rest/plans/extract-prolly-platform-rest.md`).

Rationale:

1. **Sealed-build invariant.** Per the boundary-doc pattern
   established in 2026-05-26 (`prolly-json/boundary.md`,
   `prolly-json-rest/boundary.md`), each module should build
   green in isolation. `prolly-json-rest` cannot do that under
   Option A — it transitively pulls `prolly-rdf4j-rest`, RDF4J,
   the SPARQL controllers, the streaming SSE, etc. Option B is
   the only way to honor the invariant for the JSON face. The
   same invariant protects `prolly-rdf4j-rest` post-extraction —
   it can build green without `prolly-json-rest`.

2. **The lie compounds.** Pre-1.0 we can rename modules freely;
   post-1.0 each module name is a public commitment.
   Future faces would inherit Option A's smell. Extracting now is
   cheap; extracting later means renaming multiple modules'
   dependency declarations.

3. **The platform is a substrate, not just an RDF face.** The
   platform (multi-tenant, branched, authenticated, audit-logged
   JSON+RDF over HTTP) stands on its own. Calling it
   `prolly-rdf4j-rest` undersells it.

4. **Option D's midpoint is worse than Option B's risk.** A
   half-extracted state (some auth in rdf4j-rest, some in
   platform-rest) is genuinely confusing. Option B's atomic
   phase commits give a clean before/after at each step.

The shape:

```
                    ┌─────────────────────────┐
                    │   prolly-platform-rest   │
                    └────────────┬────────────┘
                                 │
                ┌────────────────┼────────────────┐
                ▼                                 ▼
   ┌──────────────────────┐        ┌────────────────────────┐
   │  prolly-rdf4j-rest   │        │   prolly-json-rest      │
   └──────────────────────┘        └────────────────────────┘

   prolly-platform-rest ──depends-on──> prolly-platform (core)
   prolly-rdf4j-rest    ──depends-on──> prolly-platform-rest
   prolly-json-rest     ──depends-on──> prolly-platform-rest
   prolly-server        ──depends-on──> all three REST modules
```

The reverse arrows are forbidden: `prolly-rdf4j-rest` never
depends on `prolly-json-rest` and vice versa.

## Consequences

### What goes into `prolly-platform` (core)

Non-Spring, non-HTTP — usable from CLI, sync engine, batch
processes:

- Role enums: `RepoRole`, `NamespaceRole`, `CollectionRole`
- `RepoRegistry`, `OrgRegistry` interfaces + LRU impl
- `RepoMetadata`, `OrgMetadata` records
- `RepoNameValidator`
- Permissions interfaces: `RepoPermissions`, `NamespacePermissions`,
  `CollectionPermissions`
- `EffectivePermissions.forSparql / forJson` cascade logic
- `User` domain model + interfaces (`UsersStore`, `TokensStore`,
  `KeysStore`)
- `AdminAuditLog` interface
- `PerRepoStorageFactory` interface (the per-repo
  storage-layout abstraction; RDF + JSON impls plug in)
- RocksDB-backed impls of the permissions / users / tokens /
  keys / audit log stores (they're storage-layer concerns, not
  HTTP)
- `HttpSignatureVerifier` from ADR-0019 (the algorithm; the
  controller wrapper stays in -rest)

### What goes into `prolly-platform-rest` (Spring)

HTTP-shaped, Spring-aware:

- `RepoRoutingInterceptor` + `MultiTenantWebMvcConfiguration`
- `PerRequestRepoResolver` (generic; faces extend)
- `RepoRoleAuthorizationManagers`
- `MultiTenantErrorAdvice` + the standard `ProblemDetail` mapping
- Auth controllers: `AuthUsersController`, `AuthTokensController`,
  `AuthKeysController`, `AuthSessionsController`,
  `AuthPasswordController`, `AuthMeController`
- `AuthSecurityConfiguration` (the `SecurityFilterChain` bean)
- `LoginRateLimitFilter`
- `TokenIdleCutoffSweeper` (`@Scheduled` bean)
- `RepoController` (`/repos` admin CRUD), `OrgController`,
  `OrgRepoController`
- `RepoPermissionsController`, `NamespacePermissionsController`,
  `CollectionPermissionsController` (the admin-grant surfaces)
- `WebShellConfiguration` (renamed from
  `SparqlCorsConfiguration` — CORS + SPA history-mode forwarding,
  generalised to accept face-contributed `SPA_ROUTES` per the
  next consequence)
- `PlatformAutoConfiguration` (the `@AutoConfiguration` umbrella
  + `META-INF/spring/AutoConfiguration.imports`)

### `SPA_ROUTES` becomes an extension point

Each face contributes its routes via a `WebShellRoutesContributor`
bean — platform-rest aggregates. Concretely:

```java
public interface WebShellRoutesContributor {
    Collection<String> routes();
}
```

- `prolly-rdf4j-rest` contributes the SPARQL/RDF SPA routes
  (`/query`, `/update`, `/import`, `/schema`, `/instances`,
  `/commits`, `/branches`, `/compare`, `/sync`, `/mrs`, etc.)
- `prolly-json-rest` contributes `/docs`, `/docs/**`
- Future faces contribute their own

This kills the chronic "SPA_ROUTES trap" CLAUDE.md flags — adding
a route in a face's plan now also adds the contributor entry in
the same module's source, not in a sibling module's
configuration. The pinned-membership test (currently
`SparqlCorsConfigurationTest`) moves to platform-rest as
`WebShellRoutesTest` and asserts the aggregated set instead.

### What stays in `prolly-rdf4j-rest`

The RDF face:

- `SparqlController`, `SparqlServerAutoConfiguration`,
  `ProllySailAutoConfiguration`
- Per-repo Sail resolver (the part of `PerRequestRepoResolver`
  that extracts `ProllySail` from a `Repository`)
- `PerRepoSailRegistry` (RDF-specific sail caching; the
  generic `RepoRegistry` is in core)
- Streaming SSE controllers (load/update)
- Schema editor + instance editor REST surfaces
- Sync controller (Git-style RDF push/pull) — for now; promote
  to platform-rest when `prolly-json-sync.md`
  ships and there are 2+ substrates to sync
- MR review controller — for now; promote when JSON MR review
  lands per ADR-0028
- Webhook bus + dispatcher + controllers — for now; promote when
  `prolly-json-webhooks.md`
  joins (the event vocabulary becomes multi-face)

### What stays in `prolly-json-rest`

Per the existing module:

- `DocsController`, `EnumerationController`, `DocsRestExceptionAdvice`
- `DocsRestAutoConfiguration` (+ `META-INF/spring` imports)
- `DocJson` helpers
- The JSON-specific resolver (`PerRequestDocStoreResolver`)
- JSON-shaped schema/index controllers (when those plans land)
- A `WebShellRoutesContributor` bean contributing `/docs`,
  `/docs/**`

### Package renaming

`com.earasoft.prolly.rdf4j.server.{multitenant,auth,...}` →
`com.earasoft.prolly.platform.{multitenant,auth,...}` for the
classes that move. Renaming touches every import in every test
that references the moved classes — mechanical but sweeping.

### Request-attribute keys

`RepoRoutingInterceptor` currently stashes
`prolly.rdf4j.repo` / `prolly.rdf4j.org` as request attributes
(per
Plan 2 D-2
explicit note). After extraction, these become
`prolly.platform.repo` / `prolly.platform.org` to match the
owning module. **One-time atomic rename in Phase 1 of the
extraction plan**; per-1.0 no-BC rule applies (no aliases).
Every reader in every module updates in the same commit.

### CLAUDE.md sweep

Many CLAUDE.md sections reference `prolly-rdf4j-rest`/...` paths:
the "SPA_ROUTES trap" note, the auth section, the multitenant
section, etc. Phase 5 of the extraction plan does the sweep
in one commit so the doc stays accurate.

### Test-suite atomicity

The single load-bearing invariant for each phase commit:
`AuthSecurityFilterChainIntegrationTest` (and the
`MultiTenant*Test` suite) **must run green before and after**.
The auth filter chain is order-sensitive; verifying the chain
end-to-end is what catches any silent break.

### What this unblocks

- prolly-json-rest's Plan 2 (multitenant wiring) can be slimmed
  down: most of its proposed work was already-existing platform
  infrastructure with a JSON-specific add-on; after extraction
  the plan is just the add-on.
- Future faces can plug in cleanly.
- The boundary-doc invariant (each module builds sealed) holds
  for every face.

### Risk + mitigation

**Risk 1: Auth chain breakage.** Mitigation: `AuthSecurityFilterChainIntegrationTest`
+ specific role-cascade tests pinned BEFORE the move; rerun
after every phase commit; if any fail, the phase commit reverts.

**Risk 2: Spring AutoConfig ordering.** Mitigation: explicit
`@AutoConfigureBefore` / `@AutoConfigureAfter` on the platform
auto-config + faces; Phase 0 sets the ordering and the rest of
the phases inherit.

**Risk 3: Missed CLAUDE.md + plan references.** Mitigation: a
final grep sweep in Phase 5 + a pinned `BoundaryReferenceTest`
that asserts no `prolly-rdf4j-rest.*` packages are referenced
by `prolly-json-rest` source (and vice versa).

### Migration

Zero data-plane migration. The SPA's URLs don't change. The
storage layout doesn't change. Auth tokens don't invalidate.
The only thing that changes is which jar a given controller
class ships in.

## Open questions for design partners

1. **`prolly-platform` core — does it need its own `boundary.md`?**
   Yes, by the precedent set 2026-05-26. Phase 0 adds it.
2. **Should `prolly-server`'s assembly explicitly enumerate
   the faces?** Currently it just depends on each `-rest`
   module. Post-extraction, an operator who only wants the
   JSON face can build a custom `prolly-server-json` that
   depends on `prolly-platform-rest` + `prolly-json-rest` only.
   That's a future operator-tooling concern; not part of this
   ADR.
3. **MR review for JSON — when it lands per ADR-0028, does
   the MR controller move to platform-rest at that point?**
   Yes. The trigger condition is "2+ face-types reviewable"
   — when the JSON pointer-shaped diff renders alongside the
   RDF triple-shaped diff in the same MR UI.
4. **Webhook bus promotion timing — when?** Same trigger:
   when JSON doc events join MR + RDF events, the dispatcher
   + bus become platform-level. Until then, leave in
   rdf4j-rest.

## Amendment 2026-05-27 — three decisions that warrant ADR-level discoverability

This ADR captured the macro decision (Option B — extract). A
2026-05-27 `/ultrathink` review of the implementing plan
(`plans/extract-prolly-platform-rest.md`)
surfaced 9 additional decisions (D-9 through D-17) that landed
in the plan's Decisions section. Three of those are
controversial enough — counter-arguments exist; the chosen
option wins on a non-obvious axis — that they deserve
ADR-level discoverability. Future readers should find them
here without spelunking the plan.

The other 6 (D-11 phase sub-division, D-12 autoconfig
ordering, D-14 booted-jar smoke, D-15 bom-rest disposition,
D-16 profiles unchanged, D-17 e2e suite) are operational
choices without meaningful alternatives. They stay in the plan.

### Amendment D-9 — Operator-config property-name migration: atomic, no aliases

**Situation.** The macro extraction renames request-attribute
keys (`prolly.rdf4j.repo` → `prolly.platform.repo`,
in-memory servlet attributes, ~7 hits in 5 files — that's
ADR-0030's existing scope). The `/ultrathink` review found a
separate concern: ~30 operator-visible config properties
(`prolly.rdf4j.bootstrap-admin-password`, `prolly.rdf4j.api-key`,
`prolly.rdf4j.auth.backend`, `prolly.rdf4j.repos.warm-set-size`,
`prolly.rdf4j.multitenant.enabled`, …) that also need migration.
These are operator-visible — YAML files, env vars, the
`rebuild-jar` skill, the e2e helpers, CLAUDE.md examples — and
renaming them breaks every operator deployment in a single
release.

| Option | Operator impact | Code consistency |
|---|---|---|
| A — Keep `prolly.rdf4j.*` forever | Zero | Lie compounds; `bootstrap-admin-password` carrying the rdf4j prefix is misleading |
| B — Aliases (both prefixes bind) | Zero | Violates Pre-1.0 no-BC rule; adds a defensive @ConfigurationProperties handler |
| **C — Atomic rename, no alias (CHOSEN)** | Operators update YAML/env in one coordinated release | Clean |
| D — Per-property phased rename | Multi-release operator coordination | Confusing intermediate state |

**Decision.** Option C. Rename all platform-owned property
prefixes atomically in Phase 2.5 of the plan; no aliases. Per
the Pre-1.0 no-BC rule (CLAUDE.md). Face-specific properties
(`prolly.rdf4j.webhooks.*`, `prolly.rdf4j.sail.*`, `prolly.json.*`,
`prolly.bom.*`) STAY on their face prefix.

**Rationale (the non-obvious axis).** Option B is the
operator-friendly default — keep both prefixes binding for one
release, deprecate the old. But it violates the Pre-1.0 no-BC
rule the project has held to throughout — the same rule that
deleted the `SingleToMultiTenantMigrator` 2026-05-24 because
"just in case" code with no caller is the worst possible state.
A defensive @ConfigurationProperties alias handler is exactly
that pattern. The project is at v0.2.0-BETA where every
operator is a developer expected to read release notes;
single-release migration is acceptable. Pinned by
`PropertyPrefixBindingTest` + `OldPropertyPrefixBindingNegativeTest`
(positive AND negative cases — the latter verifies the OLD
prefix does NOT bind, catching a future accidental
re-introduction of an alias).

**Consequences.** Every operator config file, every deployment
script, every CI fixture updates in the same release. CLAUDE.md
+ README + newcomer-docs + the `rebuild-jar` skill all sweep in
Phase 2.5 Step 23. Negative test pins the no-back-compat invariant.

### Amendment D-10a — `JobsController` kind discriminator: pluggable label, not extensible enum

**Situation.** The macro extraction moves `StreamRegistry` +
`JobsController` (`/api/jobs/*`) into `prolly-platform-rest` so
future faces (json doc imports, bom ingests) share one
long-running-operation surface. Current `StreamRegistry` has a
`Kind` enum with values `LOAD | UPDATE` — RDF-specific terms.

| Option | Type safety | Future-face cost |
|---|---|---|
| A — Keep `Kind` enum RDF-typed | Strong — the enum's values are the closed set | Future faces can't register their kinds without modifying platform |
| B — Sealed `Kind` with consumer-registered variants | Strong (still) | Adds SPI boilerplate; consumers must register `SealedKind.Variant` from their @Configuration |
| **C — `kindLabel: String` (CHOSEN)** | Weak — any String is accepted | Future faces register any label; rendering decides per-label |
| D — Per-face JobsController | None across faces | Duplicates `/api/jobs` infra per face |

**Decision.** Option C. The `Kind` enum becomes
`kindLabel: String` on `StreamRegistry.Entry`. Faces register
labels like `"sparql-update"`, `"sparql-load"`,
`"docs-import"`, `"bom-ingest"`.

**Rationale (the non-obvious axis).** Option B is the
type-safe purist default — sealed types with consumer-
registered variants. But the only consumer of the label is the
SPA's `/jobs` page rendering, which dispatches per-label, not
per-type. Type safety here is illusory — the rendering layer
already has to handle "unknown label" as a fallback, regardless
of how the label is typed at the registry. Sealed-variant SPI
adds infrastructure (consumer-registration mechanism, variant
discovery) without catching a real regression class. Option C
pays one field-type change; sealed (B) pays ongoing SPI cost.
Pinned by `JobsKindPluggabilityTest` — registers an arbitrary
`"totally-new-kind"` label and asserts the full registry →
controller → `GET /api/jobs` flow works.

**Consequences.** `StreamRegistry.Kind` enum is deleted.
`ProgressEmittingSailListener` registers
`"sparql-update"` / `"sparql-load"` labels by string. Future
faces register their own labels without coupling to platform.
SPA rendering branches per label with a fallback for
unknown — that fallback already exists for forward-compat.

### Amendment D-13 — SPARQL auth backend (`auth-on-sail`) stays in `prolly-rdf4j-rest`

**Situation.** The macro extraction moves the
`AuthBackend` interface + RocksDB impl + auth controllers to
platform. The SPARQL backend (`SparqlUsersStore`,
`SparqlPseudonymStore`, `AuthDataMigrator`, `OntologyLoader`,
`AuthGraphs`, `SparqlGraphRefExtractor`, `SparqlUserDetailsService`,
`RocksToSparqlMigrator`) stores users in RDF named graphs per
ADR-0015 Option B — a queryable-auth deployment shape. Where
does this code live post-extraction?

| Option | Generalizability | Module size |
|---|---|---|
| A — Move auth-on-sail to `prolly-platform-rest` under a feature flag | Doesn't generalize (SPARQL-shaped) | Platform-rest gains 8+ RDF-specific files |
| B — Own module `prolly-rdf4j-rest-auth-on-sail` | Cleanest separation | 7th module for 8 files — overkill |
| **C — Stay in `prolly-rdf4j-rest`, implement platform's interfaces (CHOSEN)** | None needed — face-specific | No new module |
| D — Delete auth-on-sail (only RocksDB backend supported) | N/A | Removes ADR-0015 Option B; breaks queryable-auth deployments |

**Decision.** Option C. `SparqlUsersStore` + the rest of the
auth-on-sail code STAY in `prolly-rdf4j-rest`, reorient against
platform's `UsersStore` + `PseudonymStore` interfaces. The
interface lives in platform; the impl lives where the storage
shape lives.

**Rationale (the non-obvious axis).** The intuitive shape is
"auth code goes with auth code" → Option A. But the SPARQL
backend literally stores users IN RDF graphs (`<urn:prolly-rdf4j:auth/users>`,
`<urn:prolly-rdf4j:auth/pseudonyms>`) using SPARQL UPDATE /
CONSTRUCT queries. That's not platform-shape — it's RDF-shape.
Moving it to platform-rest would force platform-rest to depend
on the RDF4J Sail libraries, polluting the platform's
dependency tree with RDF infrastructure that no other face
needs. Option B is right-shape (sealed per-backend module) but
premature (one impl, one module). The natural shape: platform
owns the INTERFACE, faces own face-specific implementations.
Same pattern as `SailHealthIndicator` (D-10b in the plan).
Pinned by `SparqlUsersStoreImplementsPlatformInterfaceTest` —
catches a future divergence where someone adds a method to
the platform interface and forgets the SPARQL impl.

**Consequences.** `prolly-rdf4j-rest` keeps a meaningful chunk
of auth code (~8 classes) post-extraction — that's fine; it's
RDF-shape, not platform-shape. ADR-0015 Option B
("queryable auth in SPARQL") remains a supported deployment
shape. When `prolly-json-rest` wants its own queryable-auth
backend, it ships a `DocUsersStore` implementing the same
platform interface; no platform changes needed.

### Net result

These three decisions are now ADR-discoverable. The other 14
plan decisions (D-1 through D-8, D-11, D-12, D-14, D-15, D-16,
D-17) stay in the plan — they're operational choices without
meaningful alternatives.
