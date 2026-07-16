
# ADR-0002: Server-side staging via hidden branch

## Status

Accepted, backfilled 2026-06-23 (predated the `## Status` convention) — server-side staging on a per-user hidden branch is implemented (`StagingController`).

| Status   | Proposed (long-term — current client-side staging in iter U/V ships first) |
|----------|----------------------------------------------------------------------------|
| Decision | **Staging = hidden branch per session**; commit-draft = squash-merge into target |
| Iter     | V.1 → V.6 (slice list at end)                                              |
| Date     | 2026-05-13                                                                 |
| Supersedes | none — extends client-side staging from iter U                           |

> **Goal:** durable, multi-tab / multi-device staging that reuses the
> existing branch + commit + merge infrastructure instead of inventing
> a new state surface. Each staged operation is a real commit; the
> user's "commit draft" action becomes a squash-merge from a hidden
> staging branch into the target branch.

## 1 · Why move off client-side staging

The iter U/V client-side `StagingService` ships first because it's
zero-risk and unblocks the UX. But it has structural ceilings that get
worse over time:

| Limitation | Bites at |
|---|---|
| State lives in one browser's localStorage | User loses work on machine switch / browser crash / "Clear data" |
| Cross-tab sync via `storage` event is best-effort | Tab B can race-write; quotas vary; private-browsing mode breaks it |
| No real commit history for staged ops | Can't `git revert` an individual stage entry |
| Squash logic is a string concat at commit time | Can't preview a structured diff before committing |
| No durable audit trail | Compliance contexts can't reconstruct who staged what when |
| Can't share a draft across users / sessions | "Hey can you review my pending changes" requires copy-paste — server-side branches enable cross-user review via `GET /sparql/staging?user=alice` (with appropriate auth) |
| Two users can't work in parallel on the same store | localStorage is per-machine; server-side per-user namespacing (`staging/<username>/…`) eliminates cross-user collisions by construction |
| Conflict detection between staging and the live branch is impossible | Stale stage entries silently misapply |

The deeper insight: **a staged operation is conceptually a commit
that hasn't been promoted yet**. Modeling it as such on the server
inherits everything our commit / branch / merge machinery already
does well.

## 2 · Decision

**Each editing session gets a dedicated, hidden staging branch.** Each
"Stage" click is a real commit on that branch. "Commit draft"
squash-merges the staging branch into the target branch, then resets
the staging branch to track the new target HEAD.

```
target (e.g., main):           A — B — C ──────── E  (E = squash commit)
                                       \         /
staging/<sessionId>:                    C' — D' /
                                        ↑    ↑
                                      Stage Stage
                                      click click
```

After the squash:
- `main` advances from C → E (one new commit with the net effect of C' and D')
- `staging/<sessionId>` resets to E (ready for the next draft)

## 3 · Branch naming — one staging branch per user

**Rule**: one staging branch per user. Name: **`staging/<username>`**.

```
refs/staging/alice    — Alice's draft
refs/staging/bob      — Bob's draft (separate namespace, never collides with Alice)
```

`<username>` is the authenticated principal (from JWT / session
cookie / however auth is wired). One user = one branch; no other
user's drafts can collide.

**Why one branch, not many**: the multi-session-per-user case
(parallel drafts in different windows / per-task) adds complexity
the first cohort doesn't need. Users with two tabs open share one
draft buffer — cross-tab sync is free because the server is the
source of truth. If a demand emerges for "multiple parallel drafts
per user" (or "one staging per target branch"), the name extends
to `staging/<username>/<draftName>` without a format break. Not
shipping it preemptively.

**Pre-auth fallback**: when no authenticated user is present, fall
back to `staging/_anon` for single-user dev. Production deployments
gate writes on auth.

**Multi-user guarantees by construction**:

| Scenario | Outcome under one-branch-per-user naming |
|---|---|
| Alice and Bob both stage at the same time | Their changes land on `staging/alice` and `staging/bob` — no contention |
| Alice opens two browser tabs | Both share `staging/alice` → cross-tab sync free; whichever tab clicks Commit promotes the shared draft |
| Alice opens one tab on laptop, one on desktop | Both still share `staging/alice` — the draft is the user's, not the device's. Edits visible immediately on the other device after a refresh |
| Alice's session expires mid-stage | Server returns 401; client surfaces a re-auth prompt without dropping local UI state — user re-authenticates and pushes the queued op |

**Validation**: `RefsStore.validateName` permits `[A-Za-z0-9_./-]`,
which accepts `staging/<username>` for any reasonable username.
Usernames containing `/` or whitespace are rejected at auth time,
before they reach the staging path.

**Listing semantics**: `/sparql/branches` filters out `staging/*` by
default so the branch picker isn't cluttered. The user can see their
own staging branch via `?include=staging` or by typing it directly in
the URL.

## 4 · Squash-merge implementation

The existing `MergeEngine.merge` does a three-way merge with set-union
semantics. Squash needs a related but distinct operation:

```
squash(source: staging, target: main):
  lca   = findLCA(commitLog, target.head, source.head)        // existing
  diff  = readAllTriples(source) - readAllTriples(lca)        // already in /sparql/diff
  apply = diff.added   ⇒ INSERT
  apply = diff.removed ⇒ DELETE
  commit the union of (target.head + apply) on target with message
  refsStore.put(staging-branch, new target HEAD)              // reset staging
```

Two key differences from the existing `merge`:
- **Resulting commit has ONE parent** (target's previous head), not two.
  Squash deliberately throws away the staging branch's intermediate
  history.
- **Staging branch resets** after the squash — `refs/staging/X` now
  points at the freshly-created target commit. The user's next Stage
  click starts a new draft from a clean baseline.

Server endpoint shape:

```http
POST /sparql/branches/{target}/squash-merge
Content-Type: application/json

{ "source": "staging/c3f8a712-...", "message": "Add Alice + Bob FOAF" }
```

Returns the new commit hash + counts (added / removed / no-op).

## 5 · API surface

| Method | Path | Purpose |
|---|---|---|
| `POST`   | `/sparql/staging` | Body = SPARQL Update. Commits to `staging/<clientId>` (extracted from `X-Prolly-Client-Id` header). |
| `GET`    | `/sparql/staging` | Returns this client's staging branch HEAD + commit list since last squash. |
| `POST`   | `/sparql/staging/commit` | Squash-merge into target. Body = `{ message?: string, target?: "main" }`. |
| `DELETE` | `/sparql/staging` | Abandon the draft — reset staging branch to target's HEAD. |
| `POST`   | `/sparql/staging/revert` | Body = `{ commitId: "<hex>" }`. Revert a single staged commit. Maps to: `/sparql/staging` with the inverse SPARQL Update. |

Internally these reuse `ProllySailConnection` + `RefsStore` +
`CommitLog` + `MergeEngine`. The only new code is the squash-merge
variant (one parent, source-branch reset).

## 6 · Garbage collection

Abandoned staging branches accumulate. Three triggers:

1. **TTL** — branches under `staging/` whose HEAD commit is older than
   N days (default 7) are pruned. Implementation: scheduled task in
   `ProllySail` that walks `refs/staging/*` and drops stale ones.
2. **On explicit `DELETE /sparql/staging`** — user abandons.
3. **On successful squash** — staging branch resets (not pruned) to
   the new target HEAD; the old commits become unreachable and get
   collected by the eventual NodeStore GC (out of scope for v2.0).

GC of orphan commit chunks themselves is a separate Phase 4 problem.
Pruning the *ref* is what we control here.

## 7 · Migration from client-side staging

The current `StagingService` exposes a small API:
`stage / discard / pluck / clear / combined / entries`. Server-side
implementation honors the same signatures, so the consumer components
(`StagingPanel`, `StagingBadge`, `QueryPage`) stay identical.

Feature flag: `prolly.rdf4j.staging.mode = client | server` (default
`client` for v2.0; flip to `server` once the endpoints land).

Migration UX: on the first session where the server flag is on, if the
client has localStorage staging entries, prompt: *"You have N changes
staged locally. Move them to durable server-side staging? (Yes / Keep
local)"*. Yes → replay each fragment as `POST /sparql/staging`. No →
keep both, surfacing both in the panel until user resolves.

## 8 · Conflict semantics

A staging branch can fall behind its target if other commits land on
`main` while a draft is in flight. The squash applies the *net diff*
since the LCA, so:

- **Triples staging adds that main also added** → no-op, set-union
  semantics make this safe
- **Triples staging deletes that main has since changed** → staging's
  DELETE matches by `(s, p, o)` so it removes whatever's currently
  there; the user may overshoot
- **Triples staging adds that main has since deleted** → re-added

Surface this in the UI: the "Commit draft" preview should show
"+ N added, - M removed (your draft is K commits behind main — review
or rebase)". Rebase is out of scope; surfacing the offset is the
minimum.

## 9 · Risks

| Risk | Mitigation |
|---|---|
| Multi-user collisions on the same staging branch | **Eliminated by construction** — `staging/<username>/...` namespacing means Alice and Bob can never share a branch unless they cooperate explicitly |
| Pre-auth `_anon` namespace shared across machines | Acceptable for dev-mode only; production gates writes on auth |
| User's session expires mid-stage | Server returns 401; client surfaces re-auth without dropping local draft state — user re-authenticates and pushes the queued ops |
| Stale `staging/*` branches confuse `/branches` listing | Filter `staging/*` from default branch list; expose via `?include=staging` |
| Squash-merge has subtly different semantics from regular merge | Implement as a distinct method (`MergeEngine.squashMerge`); reuse LCA + diff machinery; new tests |
| User reverts a staged commit but the local UI doesn't reflect it | Always GET `/sparql/staging` after a write; build the local list from server state, not local cache |
| Two browser tabs same user | Either share one staging branch (cross-tab sync free) or use separate sessionIds for parallel drafts — user's choice via the SPA's session-id strategy |
| GC accidentally prunes an in-progress draft | TTL based on HEAD commit datetime; user activity (any POST) updates HEAD; default 7 days is generous; the prune step also skips branches whose owner has any activity in the last 24 hours |

## 10 · Resolved + open questions

**Resolved decisions** (locked in; revisit only on explicit demand):

| # | Question | Decision | Reason |
|---|---|---|---|
| 1 | One staging branch per user vs many | **One: `staging/<username>`** | Simpler workflow; multi-session can be added as `staging/<user>/<draftName>` later without breaking format |
| 2 | Squash commit's parents | **One parent (strict squash)** | Convention; staging history isn't durable enough to be worth preserving |
| 3 | Per-stage commit message | **No — squash-only** | First cohort wants the *final* label, not granular ones; revisit if users ask |

**Still open**:

| # | Question |
|---|---|
| A | Cherry-pick from another user's staging to a target branch — auth + review-flow design |
| B | Push vs poll for cross-tab updates — WebSockets vs `GET /sparql/staging` on focus. v2.0: poll on focus |
| C | Schema-aware preview of the staged diff (per-class summaries instead of raw triple lists) |

## 11 · Slice plan

| # | Slice | Effort |
|---|---|---|
| W.1 | Server: minimal auth — extract username from a header (`X-Prolly-User`, JWT later); pre-auth fallback to `_anon` | half day |
| W.2 | Server: `POST /sparql/staging` commits to `staging/<username>` branch (reuses the existing /sparql/update path; auto-creates branch on first call) | half day |
| W.3 | Server: `MergeEngine.squashMerge(source, target)` — LCA + diff + one-parent commit + reset source ref to target HEAD | 1 day |
| W.4 | Server: `POST /sparql/staging/commit` endpoint wiring `squashMerge` (body: `{ message }`) | half day |
| W.5 | Server: `staging/*` filtering on `/branches` listing + `DELETE /sparql/staging` (resets to target HEAD without a squash) | half day |
| W.6 | Client: `StagingService` gains `mode: 'client' \| 'server'`; server mode posts to `/sparql/staging` instead of buffering locally; `entries()` reads from `GET /sparql/staging` (commit list) | 1 day |
| W.7 | Client: migration prompt on first server-mode session — replay localStorage fragments into the server staging branch, then clear local | half day |
| W.8 | Server: scheduled GC for stale `staging/*` branches (TTL 7d) | half day |

Total ~5 dev-days end-to-end.

## 12 · What this isn't trying to solve (deferred)

- **Multi-user shared drafts** — needs auth + sharing model.
- **Cherry-pick UI** — the data model supports it; UI is a future iter.
- **Rebase staging onto a moved target** — surface offset for now; full
  rebase requires conflict resolution which we don't have.
- **Schema-aware preview** — diffing rendered as added/removed triples
  works without it; better preview (per-class summaries) belongs in a
  separate iter once basic squash is solid.
