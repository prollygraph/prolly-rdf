
# ADR-0022 — Merge-path partial failure: recovery, not rollback

## Status

Accepted, 2026-05-25. Guides
plans/mr-state-on-commits.md
Phase 2 Step 9. Implementation shipped in commit
`86041e7`.

## Context

`MergeRequestController.merge` writes two distinct artifacts on
the happy path:

1. **MergeEngine commit** on the target branch — content-addressed,
   appended to the Sail's commit log; this is the actual data-level
   merge.
2. **MR state-transition record** — for the RocksDB-backed
   `MergeRequestStore` this is a single CF row; for the branch-
   backed store (`plans/mr-state-on-commits.md`) this is a new
   commit on the hidden `_meta/mrs/{id}` branch carrying
   `state=MERGED` + `mergedBy` + `mergedCommit` + `mergeStrategy`.

The two writes are **not in a single transaction**. Between them,
a process crash, RocksDB IO error, refs-store IO error, or
arbitrary RuntimeException can leave the system in an
intermediate state: **the merge has happened on target but the
MR record still says OPEN.**

Operationally this is confusing: the reviewer sees the MR open
in the SPA, but the target branch's HEAD already contains the
merge. Stale-state, not data loss — but it needs clear handling.

The plan's Step 9 text aspires to "both writes in the same
logical transaction (single sail flush). On failure of either,
the whole call rolls back." This ADR records why we did NOT
deliver that.

## Options

| Option | What it delivers | Cost | Defect mode |
|---|---|---|---|
| **A — True transactional atomicity** | Both writes succeed or neither lands. Single Sail commit holds the merge-commit triples AND the `_meta/mrs/{id}` state triples in one flush. | Restructure the merge call site so MergeEngine's commit is staged but not committed until the controller adds the MR state triples. Touches `MergeEngine`'s public API. Multi-week. | Significant new surface area on the engine the rest of the system doesn't use; future engine refactors gated by MR-controller knowledge. |
| **B — Compensation / rollback** | If MR-record write fails after MergeEngine, undo the MergeEngine commit. | Requires a new "revert this commit" primitive on `ProllySail`. Content-addressed semantics make this hostile: the commit's hash is now reachable from clients that already read the response. | Operators see "merge happened, then disappeared" — worse than the OPEN-but-merged state we're trying to avoid. |
| **C — Recovery-not-rollback (this ADR)** | Partial-failure window is documented + the error response carries the recovery instruction. Operator re-calls `POST /mrs/{id}/merge`; MergeEngine no-ops; `store.put(merged)` completes. | One try/catch in the controller. ~25 lines. | The OPEN-but-merged-on-target state is briefly visible if the failure window opens. Operator-driven recovery is required (not auto-retry). |
| **D — Ignore (status quo before Step 9)** | RuntimeException bubbles to Spring's default error handler. | Zero. | Operator sees a 500 with a stack trace + no recovery guidance. Easy to miss that the merge actually committed. |

## Decision

**Option C — recovery, not rollback.**

Rationale:

1. **Content-addressed commit semantics rule out clean rollback.**
   A merge commit reachable from the target branch's HEAD is a
   hash that clients may already have observed. Withdrawing it
   produces "merge happened then unhappened" — a worse experience
   than the OPEN-but-merged state we're trying to avoid.

2. **True atomicity has cost out of proportion to the failure
   rate.** Option A restructures `MergeEngine` for what is
   genuinely a rare partial-failure window (RocksDB IO error
   during a normal merge is uncommon). Operator-driven retry on
   a clear error message is adequate.

3. **MergeEngine's idempotency makes recovery cheap.** A second
   `POST /mrs/{id}/merge` call after a partial failure runs
   MergeEngine with the source already reachable from the target
   — that's a no-op result (no new commit). Only `store.put`
   re-runs, which is the failure point that needs to succeed.
   Recovery is one HTTP call, no operator-visible state-mangling
   needed.

Concretely, the controller now wraps `store.put(merged)` in
try/catch and responds with a structured 500:

```json
{
  "error": "mr_record_write_failed",
  "mergedCommit": "<hex>",
  "message": "Merge succeeded on target branch but the MR
    record could not be updated. The merge commit is at <hex>.
    Re-call POST /mrs/{id}/merge to fix the MR record; the
    merge itself will be detected as a no-op."
}
```

The `publishEventSafely` call for the `mr.merged` webhook event
is skipped on failure, so a recovery retry produces exactly one
webhook delivery, not two.

## Consequences

**Operator experience:**
- 500 responses on this failure mode now include a structured
  recovery instruction, not a bare stack trace
- ERROR-level log line names the merged commit hash + the
  recovery path so on-call has a clear next step
- A second merge call is the entire recovery procedure; no
  manual `git reset` analog required

**Engineering experience:**
- `MergeEngine`'s API stays narrow; no new "staged commit"
  primitive
- The MR controller's merge path is ~25 lines longer; the
  partial-failure branch is reachable only when `store.put`
  throws after MergeEngine succeeds (test fixture stops short
  of this scenario — code-review verification + the natural
  no-op idempotency in `mr-merge-roundtrip.spec.ts`'s retry
  flow cover the recovery path)

**Webhook semantics:**
- `mr.merged` fires AT-MOST-ONCE per logical merge. The
  partial-failure window doesn't emit a half-event; the
  recovery retry emits the single event after `store.put`
  finally succeeds

**Future flexibility:**
- If the storage layer ever grows a write-transaction primitive
  spanning multiple branches + multiple CFs, true atomicity
  (Option A) becomes cheaper to ship and this decision can be
  revisited
- If `mr_record_write_failed` shows up in production logs
  frequently enough to be a real reliability concern, an
  auto-retry sweeper (background job that re-runs `store.put`
  for any MR where target-branch-state and MR-record state
  disagree) is a smaller second ship than Option A would be

## What this ADR is NOT about

- **Conflict-during-merge:** that's the `MergeEngine.Conflict`
  path (`result.kind() == CONFLICT`) which is handled separately;
  the MR stays OPEN and surfaces conflict rows. Not a partial-
  failure scenario.
- **MR-state corruption from concurrent writers:** the controller
  serializes merges by repo (existing lock). Not addressed here.
- **Webhook delivery failures:** orthogonal — covered by
  ADR-0021's delivery contract (HMAC + retry + DLQ).

---

*v1 — written 2026-05-25. Revisit when production logs show
`mr_record_write_failed` more than once per quarter, or when a
storage-layer multi-write transaction primitive ships.*
