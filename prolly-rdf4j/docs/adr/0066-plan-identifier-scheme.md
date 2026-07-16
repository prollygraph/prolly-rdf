
# ADR-0066: Plan identifier scheme

## Status

Accepted, 2026-06-21. Guides `plans/stable-plan-ids.md` (Phase 0).
A dev-tooling decision (the repo's only ADR home), not a product/wire decision.

## Context

Plans are linked today **only by stem** (the filename without `.md`): `depends:` frontmatter lists
stems, `dev-scripts/plan_status.py` resolves dependencies via `index_by_stem`, the new branch↔plan join
in `dev-scripts/branch_status.py` keys by stem, and prose cross-references use relative paths. Every one
of those links **breaks on a rename or move** — the stem changes and the reference dangles. The
`stable-plan-ids` plan introduces an **immutable `id`** in each plan's frontmatter so links resolve by a
stable identity; this ADR decides the **format** of that id, because it is baked into every plan and
every link forever (high revisitation cost) — the one hard-to-reverse choice the rest of the plan hangs
on.

The deciding constraint is **concurrency**. This repo's working context is *multi-agent / multi-worktree*
development (the pain that motivated `branch-status-visibility`): plans can be created concurrently by
different agents. The existing `new-adr.sh` allocates its `NNNN` by `max(existing) + 1` — which **races**
under concurrent creation (two creators read the same max, both write `N+1`, collision). For ADRs that
is tolerable (humans serialize ADR creation); for plans allocated by agents in parallel it is not. So
the id must be allocatable **without coordination** (no shared counter).

## Options

| Option | unique under concurrent creation? | human-readable | sortable | example |
|---|---|---|---|---|
| **A** — sequential `PLAN-NNNN` | **no** — `max+1` needs a shared counter (races) | yes (ordered) | yes | `PLAN-0042` |
| **B** — date-prefix + short random suffix *(chosen)* | **yes** — no coordination (suffix disambiguates same-day) | mostly (dated) | yes (by date) | `PLAN-20260621-a3f2` |
| **C** — opaque random / base32 token | **yes** — no coordination | no | no | `PLAN-7f3a2c` |

## Decision

**Option B — `PLAN-<yyyymmdd>-<short>`** (a creation-date prefix plus a few-character random/base32
suffix). The deciding tradeoff: in a multi-agent context the id **must be coordination-free**, which
rules out A (the sequential counter race); among the coordination-free options, the date prefix keeps
the id **human-readable and chronologically sortable**, which B has and C's opaque token lacks. The
suffix exists only to disambiguate same-day creation (collision-resistant, not a security token).

- **D-1 — the id is immutable.** Frozen at creation; never re-derived from the (mutable) title/stem.
  Immutability *is* the feature — it is what makes a link survive a rename or move.
- **D-2 — resolve id-first, stem-fallback during transition.** `depends` and the tooling accept an id
  *or* a stem and resolve id-first, so ids roll in without a flag-day conversion; the fallback is
  removed once backfill + conversion complete.
- **D-3 — backfill uses the plan's real creation date when cheaply derivable.** A backfilled plan's
  date prefix is its first-commit date (`git log --diff-filter=A --follow`) when available, else the
  backfill-run date; the suffix disambiguates either way.

## Consequences

- **Positive.** Rename/move-safe linking (the requirement); no counter to race, so concurrent
  multi-agent plan creation is safe; ids remain readable + date-sortable; `archive_plan.py` becomes a
  move-only operation (the id, hence every link, is unchanged).
- **Cost / negative.** Longer + less tidy than ADR-style `NNNN`; the date is creation-date only (no
  semantic meaning beyond ordering). A one-time backfill must stamp every existing plan, and the
  transition carries a stem-fallback path until conversion is complete (then removed).
- **Divergence from ADR numbering — deliberate.** ADRs keep sequential `NNNN` (human-serialized, rare
  concurrent creation); plans do not get sequential ids precisely because they are agent-allocated in
  parallel. The two schemes differ for a reason, not by oversight.

## Follow-up / future work

- The implementation (frontmatter `id` field + validator, scaffold stamping, backfill, id-first
  resolution, the immutability/uniqueness guard, the branch-join's join-by-id upgrade) is
  `plans/stable-plan-ids.md` Phases 1–3.
- Prose `[[id]]` wikilinks for narrative plan-to-plan references are a deferred Phase 4 there (the
  structured links — `depends`, the branch-join — are the rename-fragile, high-value ones).
