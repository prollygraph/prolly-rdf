
# ADR-0020 — Merge request review workflow

## Status

Accepted, 2026-05-25. Guides
plans/repo-review-merge-requests.md.
Partial implementation as of this writing — Phase 0 + Phase 1
Step 4 + Step 7 landed; Phase 1 Step 6 (merge engine wiring),
Phase 2 (SPA), and Phase 3 are follow-ons.

## Context

Versioned RDF without a review workflow means every write lands
on `main` immediately. Operators with WRITER on a repo can break
production with one bad `INSERT DATA`. GitHub's PR / GitLab's MR
flow is the standard answer: open a request that proposes
changes, gather review, merge once approved. We need the same
shape for prolly-rdf4j.

The interesting design question is what an "MR" IS in a
content-addressed triple-store world. GitHub PRs reference
commits in a feature branch; merging produces a merge commit (or
squash / rebase variant). The prolly merge engine already
supports those operations — we already use it from
`BranchesController.merge`. So MRs are an OVERLAY on top of
existing branches: an MR is "this source branch wants to land
on this target branch, with these reviews and comments attached".

## Options

| Option | State storage | Audit story | Complexity |
|---|---|---|---|
| **A — MR state on hidden `_meta/mrs/{id}` branches** (plan's D-1) | Every action is a commit on a hidden branch | Native — all state is queryable via the existing commit log + diff | High (writer-on-arbitrary-branch primitive doesn't exist yet) |
| **B — MR state in RocksDB CFs** (this ADR's first ship) | 4 CFs (mrs / counters / comments / reviews) keyed by `{repoId}\0{id}` | Separate from commit history; needs its own audit feed | Low (mirrors RepoMetadataStore pattern) |
| **C — MR state as triples in a special graph** | `<urn:prolly:mrs/{id}>` in the data Sail | Queryable via SPARQL across MR + data | Medium |

## Decision

**Phase 0 ships Option B** (CF-backed). The plan's D-1
specifies Option A as the long-term design, and we maintain the
same `MergeRequestStore` interface so a future migration is
transparent. The branch-backed variant is a follow-on plan
(`plans/mr-state-on-commits.md`, not yet drafted).

8 sub-decisions follow:

**D-1 — `MergeRequest` is a record with a 3-state lifecycle:**
`OPEN` → (`MERGED` ⊕ `CLOSED`). Merged MRs are terminal — they
cannot be closed or reopened. Closed MRs can be reopened. The
state machine is enforced at the controller AND by the record's
own constructor (a MERGED record must carry all merge fields).

**D-2 — Monotonic per-repo MR ids starting at 1.** Stored in a
dedicated `mrs_counters` CF with atomic per-repo increments via
a ConcurrentHashMap-keyed monitor. Composite repo keys (e.g.
`biopharma/genome` for org-owned repos per ADR-0017) get their
own counter.

**D-3 — Source branch is tracked as a REFERENCE.** Pushing more
commits to the source branch auto-updates the MR's commit
range; no frozen-tip semantics. Matches GitHub's auto-update PR
behavior; cheaper than re-syncing.

**D-4 — Three merge strategies: `merge`, `squash`, `rebase`.**
Stored on the MergeRequest record after merging. Same strategy
catalog as `git merge --no-ff` / `git merge --squash` / `git
rebase`.

**D-5 — Default required approvals = 1.** Currently a
controller constant; per-repo override
(`requiredApprovals` on `RepoMetadata`) is a Phase 1 Step 5
follow-on. Latest review per reviewer wins — a reviewer who
first requested changes and then approves resolves to APPROVED.

**D-6 — Comments are raw text on the wire; markdown is
client-side.** Server doesn't render HTML (XSS attack surface).
Comments support flat threading via `replyTo`.

**D-7 — Verdicts are `APPROVED` | `CHANGES_REQUESTED` |
`COMMENTED`.** A pending `CHANGES_REQUESTED` (latest review per
reviewer) blocks merge regardless of approval count.

**D-8 — Self-merge blocked by default.** The MR opener cannot
merge their own MR. (Plan's `allowSelfMerge` per-repo flag is a
Phase 1 Step 5 follow-on.)

## Consequences

**Operationally:**
- Operators with WRITER role can open MRs but cannot bypass
  review by self-merging.
- Reviewers can change their mind; the controller looks at the
  latest review per reviewer when computing the gate.
- Merged MRs are immutable in state — re-opening a merged MR
  would be a contradiction (the changes already landed).

**Storage:**
- 4 new CFs in the shared admin DB
  (`mrs` / `mrs_counters` / `mrs_comments` / `mrs_reviews`).
- Per-repo isolation via the `{repoId}\0{padded-id}` key prefix.
  Same MR id can exist in different repos without collision;
  composite org-owned repo keys (`biopharma/genome\0...`)
  isolate cleanly too.

**Known follow-ons (each warrants its own plan):**
- **Phase 1 Step 5** — `PATCH /repos/{repo}` exposing
  `requiredApprovals`, `allowSelfMerge`, `autoDeleteMergedBranches`.
- **Phase 1 Step 6** — Wire `MergeRequestController.merge` to
  the existing merge engine (`BranchesController.merge`).
  Today the state transitions to MERGED with a placeholder
  commit hash; the data-plane merge isn't actually executed.
- **Phase 1 Step 8** — `multi-tenant-mr-isolation.spec.ts`
  pinning cross-repo MR isolation against the booted jar.
- **Phase 2** — SPA pages: `/repos/{repo}/mrs` list,
  `/repos/{repo}/mrs/{id}` detail, `/repos/{repo}/mrs/new`
  form, topbar nav + open-MR badge.
- **Phase 3 Step 14** — webhooks on MR state transitions.
- **`plans/mr-state-on-commits.md`** (not drafted) — migrate
  from CF-backed storage to the plan's D-1 branch-backed
  vision (state as commits on hidden `_meta/mrs/{id}`
  branches). Interface stays stable; storage layer swaps.

## Cross-links

- [ADR-0017 (org namespace + permission cascade)](0017-org-namespace-and-permission-cascade.md)
- [ADR-0019 (PATs + SSH keys)](0019-personal-access-tokens-and-ssh-keys.md)
- plans/repo-review-merge-requests.md
