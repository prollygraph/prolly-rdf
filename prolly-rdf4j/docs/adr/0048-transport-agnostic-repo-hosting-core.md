
# ADR-0048: A transport-agnostic repo-hosting core (faces as thin adapters)

## Status

Accepted, 2026-06-06. Guides `plans/grpc-versioning-service.md`.

## Context

The server now has **two faces** over the same multi-tenant data: the REST face
(`prolly-rdf4j-rest`) and the gRPC versioning face (`prolly-rdf4j-grpc`). The gRPC face's
remaining verbs — repo lifecycle (`CreateRepo`/`GetRepo`/`ListRepos`/`DeleteRepo`) and per-repo
routing — need the multi-tenant **repo-hosting** capability: create/list/delete a repo, and
resolve a repo id to its open `Sail`.

That capability already exists, but it lives in the **wrong place**. The verified module
dependency graph:

```
prolly-platform        RepoRegistry, LruRepoRegistry, RepoMetadataStore, RepoMetadata
   |          |
prolly-rdf4j  prolly-platform-rest (RepoRoutingInterceptor)
   |              PerRepoSail, PerRepoProllySailFactory, ProllySail, VersionedSail, TagStore
prolly-rdf4j-grpc     ProllyVersioningService          <- depends ONLY on prolly-rdf4j
   |
prolly-rdf4j-rest     RepoController, PerRepoSailRegistry, PerRequestSailResolver,
                      ProllySailAutoConfiguration      <- depends on prolly-rdf4j-grpc
```

The repo-hosting *glue* (`PerRepoSailRegistry`, `PerRequestSailResolver`, the `RepoController`
create/delete flow) sits in `prolly-rdf4j-rest`, which depends on `prolly-rdf4j-grpc`. So the
gRPC face sits **below** the REST face. The gRPC repo verbs therefore **cannot reach** the
hosting glue without a circular dependency. The narrow-port patch first considered (a
`RepoAdmin` port in gRPC, adapter in REST) avoids the cycle but leaves the *core lifecycle logic
inside one face*, transport-entangled, and **duplicates** it the day a second face needs it —
which is exactly now.

The deciding question is **where the repo-hosting capability lives**, not how to reach it from
gRPC. The capability is transport-agnostic (it knows nothing about HTTP or gRPC); it is currently
trapped in a transport face.

## Options

| Option | Dependency direction | Lifecycle logic duplicated across faces | Core testable without a transport | Churn to working code |
|---|---|---|---|---|
| **A** — `grpc -> rest` dependency | **Circular** (rest already -> grpc) — impossible | — | — | — |
| **B** — narrow `RepoAdmin` port in gRPC, adapter in REST | OK (gRPC -> port) | **Yes** — REST keeps its own create/delete flow; gRPC adapter re-expresses it | No — logic stays in the REST face | Low |
| **C** — extract a transport-agnostic `RepoHost` into `prolly-rdf4j` | Clean: both faces depend **down** onto it | **No** — one flow, both faces delegate | **Yes** — pure application service | Medium (slims `RepoController`; moves one class down) |

## Decision

**Option C — a transport-agnostic `RepoHost` application service in `prolly-rdf4j`**, below both
faces. The deciding tradeoff: B's lower churn buys *permanent duplication* of the lifecycle flow
across every face and keeps the core un-unit-testable; C pays a one-time refactor to put the
capability where the dependency graph says it belongs — a place both faces already depend on. This
is the ports-and-adapters shape the two-face reality demands.

- **D-1. `RepoHost` is the application core** (`prolly-rdf4j`): `Optional<RepoMetadata> get(id)`,
  `List<RepoMetadata> list(Principal)`, `RepoMetadata create(spec, Principal)`,
  `void delete(id, Principal)`, and `PerRepoSail resolve(id)` (the routing resolution). It composes
  the existing `RepoRegistry` + `RepoMetadataStore` (`prolly-platform`) with
  `PerRepoProllySailFactory` / the flat factory — *no new storage*, just the glue, moved to where
  both faces can reach it.
- **D-2. Resolution is polymorphic, not a special case.** A `SingleRepoHost` (one `PerRepoSail`;
  `resolve` ignores the id; lifecycle is unsupported — single-tenant has no repo CRUD) and a
  `MultiRepoHost` (registry-backed) both implement `RepoHost`. Faces depend on the **interface**.
  This *replaces* the `ProllyVersioningService`'s fixed-`ProllySail` field and the
  constructor-sail-as-fallback hack: a single-tenant deployment and the 36 in-process tests pass a
  `SingleRepoHost`; a multi-tenant server passes a `MultiRepoHost`. One code path.
- **D-3. `Principal` decouples identity from transport.** A small value object (username, isAdmin,
  a granted-role lookup) carries the caller into the core. Each face extracts it from its own auth
  (Spring Security for REST; gRPC `Metadata` for the gRPC face) and passes it in. The core performs
  visibility scoping + admin-gating against the `Principal` and never imports a transport or a
  security framework.
- **D-4. `VersionedSail` owns its time-travel.** Add `VersionedSail openSnapshotAt(byte[] commit)`
  so a face never assembles `ProllySail.openSnapshotAt(sail.store(), sail.pool(), …)` from concrete
  internals. The gRPC service then depends only on `VersionedSail` (via `RepoHost.resolve(id).versioning()`),
  not concrete `ProllySail` — closing the last concrete coupling (the `Snapshot`/`Diff` `store()`/`pool()` reach).
- **D-5. Move `PerRepoSailRegistry` down** from `prolly-rdf4j-rest` to `prolly-rdf4j` (verified:
  it is a plain concurrent-map wrapper with no framework imports), so `RepoHost` can live below
  both faces. The faces keep only their adapters: `RepoController` slims to delegation; the gRPC
  `RepoRoutingServerInterceptor` + repo verbs delegate; `ProllySailAutoConfiguration` constructs
  the `MultiRepoHost` bean both faces share.

## Consequences

- **Positive:** one lifecycle flow, not one per face; the core is unit-testable with zero transport;
  the dependency direction is correct (faces -> core, never face -> face); the gRPC service becomes a
  genuinely thin adapter; `SingleRepoHost`/`MultiRepoHost` unifies single- and multi-tenant into one
  path (deleting the fixed-sail + fallback special-casing).
- **Negative / cost:** a real refactor of working code — `RepoController` loses its create/delete
  body to `RepoHost` (re-verified by `RepoControllerTest` / `RepoControllerPermissionsTest` /
  `RepoRegistryBootstrapTest`), `PerRepoSailRegistry` changes package, and `ProllySailAutoConfiguration`
  re-wires beans. Mitigated by the existing REST test battery and an **incremental order** (below).
- **Neutral / punted:** `RepoHost` is *not* a network boundary — it is an in-process application
  service both faces call directly (ADR-0010's unary-vs-streaming + the in-process-design ethos still
  hold). The `Principal`'s permission lookup may itself be a small port over `RepoPermissions`; that
  refinement is left to the implementation.

## Follow-up / future work

**Incremental execution order** (each step green before the next; the working REST layer is touched
last):

1. ✅ **Done** (`6f547097`) — `VersionedSail.openSnapshotAt` (D-4) + refactored the gRPC
   `Snapshot`/`Diff` onto it; the service no longer reaches `ProllySail.store()/pool()`. 36/36.
2. ✅ **Done** (`fcd8ab20`) — `RepoHost` interface + `SingleRepoHost`; `ProllyVersioningService`
   resolves its working sail per call through a `RepoHost` (D-2). Fixed-sail field + fallback hack
   deleted. 36 + 8 + 2 green.
3. ✅ **Done** (`d8f83d1d`) — moved `PerRepoSailRegistry` down to `prolly-rdf4j` (D-5). 63
   multi-tenant REST tests green.
4. **`MultiRepoHost`** over the registry + `RepoMetadataStore` + `PerRepoSailRegistry` + the
   factories + `Path storeRoot` + `Optional<RepoPermissions>`/`Optional<AdminAuditLog>` (all reachable
   from `prolly-rdf4j` — verified). A `Principal` value object (username + isAdmin) carries identity
   (D-3). The lifecycle is a faithful port of `RepoController`: **create** = validate name →
   `createMutex` { duplicate-check → `PerRepoRocksDbFactory.open` + `PerRepoProllySailFactory.from` →
   tri-write `openSails.put` + `registry.register` + `metadataStore.put` → grant creator REPO_ADMIN }
   → audit; **get/list** = `metadataStore` + visibility (admin || public || grant); **delete** =
   protect `default` → quiesce → close bundle → wipe dir → `metadataStore.delete`; **resolve** =
   `registry.resolve` (the warm path that applies the node cache + bind-join flag).
5. **Couple `MultiRepoHost` to its verification:** `RepoController` delegates its create/get/list/delete
   bodies to `MultiRepoHost` (the existing `RepoControllerTest` / `RepoControllerPermissionsTest` /
   `OrgRepoControllerTest` battery becomes the gate — no redundant standalone RocksDB test). Then the
   gRPC repo verbs delegate to `RepoHost` + the `RepoRoutingServerInterceptor` supplies the per-call
   repo id **and** the `Principal` from the call `Context` (`currentRepoId()` starts reading it). Wire
   the shared bean in `ProllySailAutoConfiguration`; **booted-jar** smoke test. *(Steps 4 + 5 are one
   coupled increment — `MultiRepoHost` is verified by the RepoController delegation, not in isolation.)*

## Open questions

- **Q1.** Does the `Principal`'s permission check take a `RepoPermissions` port, or does the
  `Principal` carry a resolved role set? Lean: a port (the role can change between calls). Resolved
  during step 4.
