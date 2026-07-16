
# ADR-0017 — Org namespace + permission cascade

## Status

Accepted, 2026-05-24. Guides
plans/multi-tenant-orgs-and-teams.md.
Builds on
[ADR-0016 (multi-tenant repo hosting)](0016-multi-tenant-repo-hosting.md).

## Context

ADR-0016 shipped per-repo isolation across `/repos/{repo}/...`
URLs. A team of N users sharing M repos has to grant each user
on each repo individually (N × M grants). For a 20-user team
with 50 repos, that's 1000 grants — operationally untenable.

The hosted-git competitors (GitHub, GitLab) solve this with an
**org** (or "group") namespace layer above the repo: grant a
user once at the org level, they implicitly have the granted
role on every repo in the org.

Adding this layer pre-1.0 commits us to:

- A URL shape: where do orgs live in the path?
- A permission model: how do org grants compose with per-repo
  grants?
- A storage model: where on disk do org-owned repos live?
- A scope-resolution model: how does the front-end (or
  arbitrary client) discover what scope a given URL represents?

## Options

The relevant choices and tradeoffs:

| Option | URL shape | Permission cascade | On-disk layout |
|---|---|---|---|
| **A — Single namespace** (no orgs) | `/repos/{repo}` | Per-repo only | `<store>/repos/{repo}/db/` |
| **B — GitHub-style flat 2-level** | `/{owner}/{repo}` | Owner = user OR org | `<store>/{type}/{owner}/repos/{repo}/db/` |
| **C — Explicit prefix** (this ADR) | `/repos/{repo}` (personal) + `/orgs/{org}/repos/{repo}` | Org grants are floors; per-repo grants override | `<store>/repos/{repo}/db/` + `<store>/orgs/{org}/repos/{repo}/db/` |
| **D — Nested groups** (GitLab-style) | `/groups/.../groups/.../repos/{repo}` | Subgroup inherits from parent | Recursive directory tree |

## Decision

Option **C — Explicit prefix with floor-semantic cascade.**

11 sub-decisions lock the shape:

**D-1 — `/orgs/{org}` is the new URL namespace; `/repos/{repo}`
remains the personal namespace.** Both shapes coexist
indefinitely. Personal repos look exactly as they did before;
org-owned repos add the `/orgs/{org}` prefix.

*Why not B (GitHub-style):* the path `/{owner}/{repo}` requires
a reserved-name list (everything that could be a top-level path
becomes a forbidden username/org name: `/sparql`, `/sync`,
`/auth`, `/repos`, `/orgs`, `/admin`, ...). The explicit prefix
sidesteps this entirely.

**D-2 — An org cannot be deleted while it owns repos.** Returns
409 `org_not_empty`. Operator must delete or transfer the org's
repos first.

*Why:* avoids orphaned data + ambiguous storage cleanup ordering.

**D-3 — Org-level grants are floors, not exact roles.**
Granting a user WRITER at the org means "at least WRITER on
every repo in the org". Per-repo grants can UPGRADE (e.g. to
REPO_ADMIN on a single sensitive repo) but cannot downgrade
below the org floor.

*Why not exact roles:* downgrade semantics confuse users
("granted writer on org, granted reader on repo: am I writer or
reader on that repo?"). Floor model is GitHub's and is what
users expect.

*Effective resolution:* `eff(u, org, repo) = max(org.grant(u,
org).asRepoRole(), repo.grant(u, org/repo))`. See
`EffectivePermissions.java`.

**D-4 — Personal repos and org-owned repos with the same
short-name are independent.** `biopharma/alpha` and personal
`alpha` are separate repos with separate storage and separate
permissions. They never collide.

**D-5 — Per-repo RocksDB at
`<store>/orgs/{org}/repos/{repo}/db/`.** Same per-repo
isolation properties as personal repos. Each DB has its own
WAL, memtables, chunk store, dictionary. No cross-org chunk
dedup (same C-1 finding from ADR-0016's principal-engineer
review: per-repo dicts → per-repo TermIds).

**D-6 — Org-level audit is a separate stream from repo-level.**
`org_create`, `org_delete`, `org_grant`, `org_revoke` events
flow through the same `AdminAuditLog` as repo events, but with
distinct event-type names so audit consumers can split.

**D-7 — `ORG_ADMIN` is a new top-level role; existing
`READER`/`WRITER`/`REPO_ADMIN` unchanged.** The 4-level
`OrgRole` ladder: READER < WRITER < REPO_ADMIN < ORG_ADMIN.
`ORG_ADMIN`'s extra power is org-scoped (create/delete repos in
the org, manage org-level permissions); at the per-repo level
it saturates to `REPO_ADMIN` via `OrgRole.asRepoRole()`.

**D-8 — Org-owned repo storage-key is the composite
`{org}/{repo}`.** RepoMetadata.name = composite; metadataStore
keys + RepoRegistry repoId values are composite. RepoMetadata
carries an `org` field so the composite can be split for
display. This is internal; controllers strip the prefix for
external display (the API returns `{name: "alpha", org:
"biopharma"}` not `{name: "biopharma/alpha"}`).

**D-9 — SPA encodes the (org, repo) scope as `<org>|<repo>` in
selector option values.** Single grouped `<select>` with
`<optgroup>` for personal + each visible org. The `|` separator
is rejected by `RepoNameValidator`, so collision-free.

**D-10 — URL is the source of truth for active scope.**
`RepoService` reconciles `currentOrg` + `currentRepo` from the
URL on every `NavigationEnd`. localStorage persistence is for
convenience across new tabs but URL always wins at construction.

**D-11 — Schema version on RepoMetadata bumped 1 → 2 to add the
`org` field.** Pre-v2 records are NOT readable (per CLAUDE.md
no-BC rule). Operators upgrading from pre-v2 binaries blow away
their data; this is fine pre-1.0.

## Consequences

**Operationally:**
- A 20-user team sharing 50 repos needs 20 org-level grants
  rather than 1000 individual grants. 50× reduction.
- A new repo created in an org automatically inherits the
  org-level grants without operator action.

**Storage:**
- Each org-owned repo gets its own RocksDB at
  `<store>/orgs/{org}/repos/{repo}/db/`. Same per-tenant cost
  story as ADR-0016 (per-repo dicts → no cross-tenant chunk
  dedup; acceptable for the scale we're targeting).

**Existing personal-namespace paths are unchanged.** The
`/repos/{repo}/...` URLs continue to behave exactly as before.

**Schema bump (v1 → v2) means existing dev installations need
to blow away their data on upgrade.** Pre-1.0; CLAUDE.md
explicitly contemplates this.

**Known follow-ons:**
- **SparqlController + SyncController @RequestMapping
  extensions.** Today the controllers only mount under
  `/sparql/*` + `/repos/{repo}/sparql/*`. To make
  `/orgs/{org}/repos/{repo}/sparql/*` actually serve data, the
  controllers need the new path prefix added (parallel to
  Phase 2 Step 19 of ADR-0016). The auth-gate at that URL
  shape IS wired (Phase 2 Step 10 of this plan); only the data
  plane lags.
- **DELETE
  `/orgs/{org}/repos/{repo}` routing collision** with
  RepoController's `/repos/{repo}` DELETE. Spring's
  PathPattern picks the shorter match. Fix needs a renamed
  path-var or consolidated handler.
- **Web hooks / status checks on org-scoped repos.**
  Out of scope for this plan; reuse the same hook surface from
  the personal namespace when it lands.
- **Org-level audit stream filtering** in the SPA admin UI
  (currently the audit log shows all events; future work could
  scope to org_admin views).

## Cross-links

- [ADR-0013 (user accounts)](0013-user-accounts-and-authenticated-staging.md)
- [ADR-0014 (auth graph gates)](0014-auth-graph-write-read-gates.md)
- [ADR-0015 (auth backend choice)](0015-auth-backend-choice.md)
- [ADR-0016 (multi-tenant repo hosting)](0016-multi-tenant-repo-hosting.md)
- plans/multi-tenant-orgs-and-teams.md
