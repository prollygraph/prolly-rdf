
# ADR-0016: Multi-tenant repo hosting

## Status

Accepted, 2026-05-24. Guides
`plans/multi-tenant-repo-hosting.md`.
Composes with [ADR-0013](0013-user-accounts-and-authenticated-staging.md)
(global user identity + service accounts),
[ADR-0014](0014-auth-graph-write-read-gates.md) (the
controller-level gate pattern that generalizes from per-graph to
per-repo), and [ADR-0015](0015-auth-backend-choice.md) (per-repo
permission storage extends both backends).

## Context

prolly-rdf4j today hosts **one** repo per server process: one
`CommitLog`, one set of branches, one dictionary, one set of
indexes, all rooted at `<store-dir>/`. This shape served the
single-team / single-dataset deployments that drove the
pre-v1 codebase.

Operators want to host **N independent repos** in one server
process — the "GitHub of ontologies" shape, where one jar fronts
many isolated datasets, each with its own commit history, its own
branch namespace, and its own permission matrix. Standing up a
JVM per repo is operationally awful at the scale operators are
asking about (50+ repos per host).

A v1 design landed on 2026-05-23 (commit `ac22b78`, amended
2026-05-24 in `e18121f`) chose **"shared RocksDB + per-repo
column families + shared chunk store."** The structural claim
was: a single chunk store dedupes chunks across repos that
happen to write the same triples, so the shared DB pays for
itself in storage efficiency.

A principal-engineer review on 2026-05-24 surfaced a load-bearing
contradiction in that claim. The v1 plan also chose per-repo
**dictionaries** (so two repos with overlapping IRIs don't
serialize each other's writes through one dict-write lock). The
verification trace lives at
[`ProllySail.java:903-914`](../../../prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java):
the Sail calls `dict.findTermId(TermEncoder.encode(...))` for
each S/P/O, then stores `(sId, pId, oId, ctxId)` **term-ID
tuples** in the chunk. The chunk's content hash is derived from
those term IDs. Per-repo dictionaries produce per-repo term IDs;
the same triple encodes to different bytes in different repos;
the chunks hash differently; **no cross-repo dedup happens, ever**.

With the headline benefit gone, the shared-DB design kept all
the structural costs (one WAL serializing every write across
every tenant, a CF-count cliff at ~500 CFs ≈ 60 repos at 8 CFs
each, whole-DB backup, whole-DB crash recovery) and the only
upside on the wishlist was illusory.

The strategic position: **self-hosted multi-tenancy is the
dominant pre-1.0 deployment shape**. Regulator-facing deployments
of this codebase host one repo per dataset and want a single jar.
SaaS-scale multi-tenancy with subdomain routing is real but
later — a separate ADR when the shape lands. The decision here
optimizes for the self-hosted-multi-repo case where path-style
URLs and per-tenant isolation matter more than cross-tenant
deduplication.

## Options

Three storage shapes considered for the per-tenant data layer.
Routing is decoupled (D-1) and not a tradeoff axis here; the
table compares the storage backings.

| Option | Cross-repo chunk dedup | WAL contention | CF-count cliff | Per-repo backup | Crash blast radius | Idle memory floor | FD pressure |
|---|---|---|---|---|---|---|---|
| **A — One RocksDB per repo (chosen)** | None (matches per-repo dict reality) | None (independent WALs) | None (each DB has ~8 CFs flat) | Trivial (`tar repos/{repo}/`) | Per-repo | ~50MB/repo + LRU eviction for cold | Higher: ~30 FDs/repo |
| **B — Shared DB, per-repo CFs (v1; withdrawn)** | **Claimed yes; actually no** (per-repo dicts break dedup) | One WAL serializes all tenant writes | Cliff at ~500 CFs ≈ 60 repos | Whole-DB checkpoint + extract | Whole DB | Lower (one DB instance) | Lower |
| **C — Shared DB, repo-id-prefixed keys in one CF set** | False dedup (same reason as B); one write lock app-wide | Worst of three (single CF gets all writers) | None | Whole-DB | Whole DB | Lowest | Lowest |

Option B was the v1 choice; the **"chunk dedup" column** is the
load-bearing finding from the principal-engineer review. The
claim was the only structural reason to accept B's WAL +
CF-count + backup costs; with it withdrawn, B's column profile
is strictly worse than A's on operability while equal on the
benefits that remain.

Option C trades WAL contention even harder for a smaller
memory floor — wrong tradeoff for the deployments that
motivate this work, where tenants want isolation guarantees,
not maximum density per host.

## Decision

Eleven sub-decisions mirror the implementation plan's D-1..D-11
exactly. Numbering is stable; if a decision is later withdrawn
it's marked `(Withdrawn)` rather than renumbered.

**D-1. Path-style URL routing — `/repos/{repo}/sparql/...`.**
URLs carry the repo as a path segment, not a query param, and
not a subdomain. The deciding tradeoff: **operator-friendliness
without forcing DNS / TLS**. Path-style caches well per-URL,
shows the repo in access logs verbatim, and composes cleanly
with the existing `?commit=` / `?branch=` / `?selected=` query
params (the D-7 invariant from
`plans/provenance-history-axis.md` survives). Query-param
routing would collide with those existing params; subdomain
routing would force per-tenant DNS into the ops loop.

**D-2. One RocksDB instance per repo.** This is the central
architectural decision and the reason this ADR exists. Each
repo gets `<store-dir>/repos/{repo}/db/` as its own RocksDB
instance with ~8 CFs (chunk store, dictionary forward / reverse,
SPOC, POSC, OSPC, CSPO, NS). The deciding tradeoff: **honesty
about chunk dedup + per-repo blast radius**. Per the C-1 finding
above, cross-repo chunk dedup was never going to work with
per-repo dictionaries, so the shared-DB choice (v1) bought
nothing in exchange for serializing every tenant write through
one WAL. Per-repo DBs give independent WALs, no CF-count cliff
(each DB has 8 CFs flat, regardless of tenant count), trivial
per-repo backup (`tar repos/{repo}/`), per-repo crash recovery
(a torn WAL in repo A doesn't affect B), and trivial repo
deletion (close the DB + remove the directory; no CF-drop
dance, no orphaned chunks). Intra-repo dedup — the prolly
tree's normal behavior — is unchanged.

**D-3. Per-repo `commits.log` file** at
`<store-dir>/repos/{repo}/commits.log`. The append-only file
pattern stays intact per-tenant. Each repo's writer holds its
own `FileChannel`; sync pack semantics are unchanged. The
deciding tradeoff: **preserve the existing commits.log
discipline rather than re-invent it as a CF**. Filesystem-level
backup composes (the commits.log is inside the per-repo
directory tree that D-2 already isolates).

**D-4. Per-repo branch namespace.** `main` in repo A is
independent of `main` in repo B. Branch refs live in each
per-repo DB's `refs` CF (or sidecar file at
`<store-dir>/repos/{repo}/refs/`, depending on the
`RefsStore` implementation). The deciding tradeoff: **users
expect repo-scoped branches**. GitHub's mental model
(branches belong to a repo, not the host) is what operators
will reach for; cross-repo branch sharing has no operational
use case at this scale.

**D-5. Hybrid auth — global user identity + per-repo grants.**
User accounts (per [ADR-0013](0013-user-accounts-and-authenticated-staging.md))
stay global; permissions become per-repo. Roles:
`reader`, `writer`, `repo_admin` (per-repo) + global `admin`
that bypasses per-repo checks. The deciding tradeoff:
**identity portability vs permission flexibility**. Per-repo
user pools would force re-registration per repo; global roles
would mean every writer writes every repo. Hybrid mirrors
GitHub's model: one identity, capability matrix per repo.

**D-6. `default` repo alias for backwards compatibility.**
On first boot under multi-tenant code, an existing
single-tenant store auto-migrates to
`<store-dir>/repos/default/`. The legacy `/sparql/...` URL
prefix aliases to `/repos/default/sparql/...` via a Spring
`Filter`. `default` cannot be deleted (409
`cannot_delete_default_repo`); it can be renamed but renaming
severs the bare-route alias. The deciding tradeoff: **zero URL
churn for existing single-tenant deployments**. Operators opt
into multi-tenancy by creating additional repos; existing
clients see no change.

**D-7. Per-repo `isPublic` flag for anonymous-read.**
Replaces v1's special-case PUBLIC_READ_PATHS-extends-`default`
hack. `_repo_metadata` gains `isPublic: boolean` (default
`false` for new repos; auto-set `true` on `default` during
migration to preserve today's anonymous read on
`/sparql/{commits,branches,version}`). The deciding tradeoff:
**a per-tenant capability instead of a special-cased URL list**.
Renaming `default` to `legacy` preserves `isPublic: true` on
the renamed repo — anonymous reads keep working without the
URL-list maintenance burden.

**D-8. Hard cross-repo isolation; chunks NOT shared.**
Commits, refs, branches, dictionary entries, chunks, auth
grants — none cross repo boundaries. Each repo is a
self-contained RocksDB instance + `<store-dir>/repos/{repo}/`
directory + entry in the shared admin DB's `_repo_metadata`
CF. The deciding tradeoff: **a hard isolation guarantee is a
sellable contract; a soft one is a leak-shaped bug surface**.
Cross-repo SPARQL federation
(`FROM <repo:A> FROM <repo:B>`) is explicitly **not** in
scope — cross-link to a future `plans/cross-repo-federation.md`
if real demand lands.

**D-9. Repo creation is admin-only; creator auto-granted
`repo_admin`.** `POST /repos` requires global admin; the
creator auto-receives `repo_admin` on the new repo (otherwise
they can't manage what they just created). The deciding
tradeoff: **mirror GitHub's two-tier authorization (host
admin + repo admin) rather than re-invent it**. Service
accounts (per ADR-0013) can be auto-granted at creation time
or granted post-hoc; both paths run through the same
`RepoPermissions` interface.

**D-10. Lazy repo registration + LRU eviction.** `RepoRegistry`
opens per-repo RocksDB instances **on first access**, not on
boot. An LRU cache caps the warm set
(`prolly.rdf4j.repos.warm-set-size`, default 64). Cold repos
pay ~50-200ms open latency on first request, free until they
fall out of the LRU. The deciding tradeoff: **bounded resident
memory + O(1) boot time regardless of tenant count**. 1000
repos × 50MB instance overhead would be 50GB resident if all
open at boot; lazy opens limit the working set to actually-used
repos. LRU (not LFU) because the access pattern in real
deployments is bursty (a tenant's CI hits its repo repeatedly,
then quiet) — LRU handles bursts well.

**D-11. Required config: per-repo write-buffer + max-concurrent
ops.** Two operator-facing knobs surfaced explicitly:
- `prolly.rdf4j.repos.write-buffer-bytes` (default 8MB per CF
  per repo) — caps memtable allocation. 8 CFs × 8MB × 64 warm
  repos = 4GB memtable ceiling under the default LRU.
- `prolly.rdf4j.repos.max-concurrent-ops` (default 16) —
  per-repo `Semaphore` capacity. Bounds Tomcat thread-pool
  saturation by one slow repo; exhaustion returns 503 with
  `{error:"repo_busy", repo}`.

The deciding tradeoff: **make the memory + concurrency
contracts visible, not hidden tunables**. Both have safe
defaults; operators only override for unusual deployment
shapes. Surfaced in the Step 21 operator docs of the plan.

## Consequences

**Cost we accept:**
- **~50MB idle memory per warm repo.** RocksDB's baseline
  overhead (block cache + memtable + index/filter blocks) is
  per-instance. 100 active repos = ~5GB resident. Mitigated by
  D-10's LRU eviction; the warm-set ceiling is operator-tunable.
- **File-descriptor pressure.** Each per-repo DB opens dozens of
  SST files. 100 repos × ~30 FDs = ~3000 FDs from RocksDB
  alone. Operators bump `ulimit -n`; LRU caps the warm set;
  operator docs (Step 21 of the plan) name the math.
- **Open-on-first-access latency.** A cold repo's first
  request pays ~50-200ms to open its RocksDB instance. Bounded
  and one-time per warm interval; LRU keeps frequently-accessed
  repos warm.
- **No cross-repo chunk dedup.** A triple written to both repos
  A and B uses storage in both chunk stores. Honesty about a
  cost v1 silently failed to deliver; intra-repo dedup (the
  prolly tree's normal behavior) is unchanged.
- **Two-level lifecycle complexity.** `RepoRegistry` owns
  RocksDB instance lifecycle (open / close / quiesce-on-delete),
  not just `ProllySail` references. More moving parts than v1's
  shared-DB; the lifecycle is bounded and well-defined
  (Steps 1, 8, 12 of the plan).

**Operator-visible changes:**
- New URL surface: `/repos/{repo}/sparql/...`. Legacy
  `/sparql/...` continues to work via the `default`-repo alias
  (D-6).
- Five new config properties under `prolly.rdf4j.repos.*`
  (warm-set-size, write-buffer-bytes, max-concurrent-ops,
  quiesce-timeout-ms, multitenant.enabled).
- Existing single-tenant deployments auto-migrate to
  `<store-dir>/repos/default/` on first boot (per D-6).
  No URL churn for existing clients.

**Follow-on work explicitly punted to separate plans:**
- **SPA repo selector + per-repo nav** —
  `plans/multi-tenant-spa.md`. Phase 4 of the v1 implementation
  plan was cut in this revision; v1 ships REST-only. Operators
  drive multi-tenant via curl + the REST API until the SPA
  follow-on lands.
- **Per-repo quotas + per-repo backup endpoint** —
  `plans/multi-tenant-quota-and-backup.md`. Per-repo backup is
  trivially `tar repos/{repo}/` under D-2 — but the streaming
  REST surface + restore protocol is its own plan.
- **Per-repo metrics with top-N cardinality cap** —
  `plans/multi-tenant-metrics.md`.
- **Async long-running jobs** —
  `plans/long-running-jobs.md` (for backup / restore / rename
  + future chunk-GC).
- **Per-repo rename** —
  `plans/multi-tenant-repo-rename.md`.
- **Cross-repo SPARQL federation
  (`FROM <repo:A> FROM <repo:B>`)** —
  `plans/cross-repo-federation.md`. Only if real demand lands;
  reopens the A-8 isolation contract.

**Pre-flight dependencies (must land before Phase 0):**
- **`plans/sync-auth-migration.md`** (not yet drafted). Today
  `/sync/**` falls through to `anyRequest().authenticated()`
  with no role gate — any authenticated user can push / pull.
  Multi-tenant amplifies this gap; the standalone fix
  (explicit `requestMatchers("/sync/**").hasRole("WRITE")`)
  is independently shippable and should land first.
- **`plans/auth-graph-syncpack-filter.md`** (not yet drafted).
  `SyncPack` walks every chunk reachable from the
  `RootMetaTree`, including chunks backing the
  `<urn:prolly-rdf4j:auth/{users,pseudonyms}>` named graphs
  when `auth.backend=sparql`. A pull replicates user records +
  password hashes to the puller. Multi-tenant doesn't create
  this bug but amplifies the blast radius; land first if any
  production deployment runs the sparql backend.

**Reversibility:**
- Kill-switch property `prolly.rdf4j.multitenant.enabled=false`
  (D-26 of the plan; default `true`) boots the server in
  single-tenant compat mode. `RepoRegistry` is replaced by a
  stub that always resolves to a hardcoded `default` Sail
  from the `<store-dir>/repos/default/` layout; `/repos/**`
  endpoints return 503 `multi_tenant_disabled`; `/sparql/*`
  aliases still work. Operators rolling back from a v1
  deployment of this code flip the property + restart; data
  stays in `repos/default/` either way. No data loss.
- The on-disk layout (`<store-dir>/repos/{repo}/db/`) is
  forward-compatible with hypothetical future shapes (e.g.
  multi-region replication) because each repo's directory is
  fully self-contained.

**Open / deferred questions:**
- **Subdomain routing (D-1 Option C)** is the right answer at
  SaaS scale where TLS / DNS automation already exists.
  Re-evaluate in a future ADR if a SaaS deployment surfaces;
  the path-style URL surface from D-1 doesn't preclude
  adding subdomain routing later (the alias filter pattern
  generalizes).
- **Per-repo encryption keys** are a separate concern from
  the storage isolation decision here. Disk encryption today
  is filesystem-level; application-level per-repo keys are
  their own plan.

## Follow-up / future work

- **Implementation plan**:
  `plans/multi-tenant-repo-hosting.md`
  — 4 phases, 27 steps. Phase 0 (Steps 1-6): `RepoRegistry`
  + URL routing for `default` (no-op backwards compat).
  Phase 1 (Steps 7-14): admin CRUD + per-repo storage layout.
  Phase 2 (Steps 15-19): per-repo data isolation invariants.
  Phase 3 (Steps 20-27): per-repo auth + operational
  hardening + this ADR.
- **ADR-N+1 (when justified) — cross-repo SPARQL
  federation**. `FROM <repo:A> FROM <repo:B>` queries
  reopen the D-8 isolation contract. Draft only if real
  demand lands; the current position is that cross-repo
  federation is an A-8 violation pre-1.0.
- **ADR-N+2 (when justified) — subdomain routing for SaaS
  scale**. Re-evaluates D-1 Option C if a SaaS deployment
  surfaces with TLS / DNS automation in place. The current
  path-style surface composes forward.
- **ADR-N+3 (when justified) — per-repo encryption keys**.
  Separate concern from storage isolation; filesystem-level
  encryption suffices for v1.

## Open questions

- **Q1**: At what tenant count does the per-repo memory
  floor (D-10's ~50MB warm-set × 64 default LRU = ~3.2GB)
  start to bite? Real deployments will show; the LRU cap is
  operator-tunable. Re-evaluate after the first production
  multi-tenant deployment reports memory metrics.
- **Q2**: Does the per-repo `Semaphore` (D-11) need a
  fairness setting? Default `Semaphore` (unfair) maximizes
  throughput; under saturation an unlucky tenant could
  starve. Switch to `new Semaphore(n, true)` (fair) if
  starvation surfaces — backwards-compatible config change.
- **Q3**: Should `prolly user` CLI (deferred per ADR-0013)
  surface per-repo grants directly, or only the REST
  endpoints from D-5 / Step 23? Probably the latter — the
  CLI wraps `/auth/users` + `/repos/{repo}/permissions`
  endpoints which abstract the backend, matching ADR-0015's
  Q3 pattern.
