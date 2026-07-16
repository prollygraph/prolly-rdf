
# ADR-0012: IA audit pass before visual audit

## Status

Accepted, 2026-05-21. Guides `plans/query-update-import-split.md`.

## Context

The project ships a recurring **visual-audit ritual** — see
`prolly-rdf4j-e2e/tests/visual-audit.spec.ts` (the screenshot harness) and
the `F-1`..`F-N` grading pattern that closed Phase 4 of
`plans/provenance-history-axis.md`.
The ritual evaluates *execution quality*: token usage, contrast, motion,
typography, dark-theme parity, hover/active states. Each surface gets a
numeric grade and a punch-list of finishes.

The 2026-05-21 split of `/query` into `/query` + `/update` + `/import`
(this ADR's guiding plan) exposed a gap. The combined `/query` page had
been **graded green at 8.5-9.5/10** across multiple prior visual audits.
It was well-executed visually: the `.mode-tab` toggle was tasteful,
spacing legal, dark/light parity solid. The audit had nothing bad to
say. Then the user opened the running app cold and asked:

> "Query and Update should be 2 different link in the nav bar? better
> user flows?"

That question was correct, and the visual audit had no language to
surface it. The toggle conflated three intents (read / typed-write /
bulk-write) behind one page, but each *screenshot* of the toggle looked
fine in isolation. The defect lived at a layer the audit didn't probe:
**information architecture** — the mapping from top-level surfaces to
user intents.

Three reinforcing reasons the visual audit can't catch this class:

1. **Implementer bias.** The audit is run by the same person who built
   the surface. Once you've committed to a mental model, you don't see
   it as one option among several — you see it as how the app works.
2. **Static snapshots can't see flows.** "These are different intents"
   is a multi-screen, multi-action observation: find toggle → click →
   notice editor text didn't carry → reorient. A single screenshot of
   the toggle in idle state shows none of that friction.
3. **The rubric has no IA category.** F-1..F-N graded tokens, contrast,
   motion, dark-theme parity. There was no checkpoint reading "does each
   top-level surface map to exactly one user intent?", so nothing in the
   rubric could fire on multiplexing.

The cost of missing this for ~3 prior audit passes was real but bounded:
the split is a routine refactor (24 plan steps, ~600 lines moved, lockstep
spec migration — all done in one /loop run). The cost of *not* fixing
the audit ritual would be ongoing — the next IA-shaped problem (a
dashboard that conflates monitoring + admin, an /api page that mixes
docs + interactive try-out, a /sync surface that hides three transports
behind one tab) would similarly grade green and ship.

## Options

| Option | Catches IA defects | Process cost | Implementer-bias resistance | Tooling needed |
|---|---|---|---|---|
| **A** — Status quo: visual audit only | No | None | None | None |
| **B** — Add an "IA" column to the existing visual rubric | Partially | Negligible | Low (same grader, same inputs) | None |
| **C** — Two-pass audit: IA pass first (page-list inputs, no screenshots), then visual pass | Yes, structurally | Modest (one extra pass per audit) | Medium (different inputs force different questions) | Light: a matrix template |
| **D** — Fresh-eyes external review on every audit (UX consultant / different teammate) | Yes, highest signal | High (depends on availability) | Highest | None per-audit; recruitment cost |

**B fails on the bias front.** The same person looking at the same
screenshots can't easily ask a fundamentally different question by
adding a column — the inputs (screenshots) and the prior (this surface
exists) are unchanged.

**D is the gold standard but doesn't scale.** Fresh-eyes review IS the
critique that landed the /query split — the user looking at the app
cold IS the external reviewer. But making that a hard prerequisite on
every audit pass would either gate releases on availability or quietly
degrade into theater. Reserve it for major surface launches.

**C threads the needle.** The IA pass uses different inputs than the
visual pass — a *route map* and an *intent list*, not screenshots —
which structurally invites different questions. The pass produces a
matrix (surface × intent), and any cell where one surface owns >1
intent is flagged for an explicit decision: split, accept the
multiplexing with rationale, or surface the question to the user.

## Decision

**Adopt Option C.** The audit ritual becomes two passes, in this order:

### D-1: IA pass (first; no screenshots)

For each top-level page in `app.routes.ts`:

1. List the **surfaces** the page exposes (e.g., editor, demo picker,
   commit panel, dropzone, format strip).
2. List the **user intents** each surface serves (e.g., "compose a
   read query", "compose a write", "upload a file").
3. Build a matrix:

   | Page | Surface | Intent(s) |
   |---|---|---|
   | /update | editor | compose typed write |
   | /update | demo picker | discover update patterns |
   | /update | commit panel | execute write |
   | /update | staging panel | queue writes |

4. **Flag any surface row with >1 intent.** For each flagged row,
   the audit's output is one of: (a) split into a new top-level
   surface, (b) accept the multiplexing with an explicit rationale,
   (c) surface to the user / stakeholder for a decision.

5. The matrix lands in the plan's audit phase as a deliverable —
   committed to the plan file, not just discussed in conversation.

### D-2: Visual pass (second; on screenshots)

Existing ritual unchanged. F-1..F-N grading, token sweep,
dark-theme parity, motion. Visual pass should not start until D-1
is signed off — there's no point grading execution quality on a
surface that's about to be restructured.

### D-3: Trigger conditions

- **Every plan that lands a new page or restructures an existing
  page** runs both passes in its final audit phase (the pattern
  `provenance-history-axis.md` Phase 4 established).
- **Pure visual polish plans** (e.g., a future `frontend-design-pass.md`
  iteration that's only tweaking tokens) MAY skip the IA pass if all
  flagged surfaces in the prior IA matrix still hold.
- **Cross-plan audits** (a quarterly sweep across the whole app) run
  both passes against every route in `app.routes.ts`.

### D-4: Bias mitigation within the IA pass

The pass is most valuable when **the grader is not the implementer of
the surface being graded**. When that's not possible (small team /
solo work, the realistic prolly-port case), the mitigation is to do
the matrix *before* opening the running app — derive intents from
the route map + user stories first, then test the matrix against the
implementation, not the other way around. Confirms what's there only
after committing to what *should* be there.

## Consequences

**Positive.**

- Catches the class of defect the recent /query split exposed.
  Future surfaces that conflate intents (a `/sync` page that mixes
  three transports, a `/dashboard` that combines admin + monitoring,
  an `/api` page that hosts docs + interactive try-out) get surfaced
  *before* they're built into the visual baseline.
- The IA matrix is **persistent documentation**. Future readers see
  the intent map of the app at a glance — useful for onboarding and
  for the newcomer-docs anatomy walks.
- Lowers the bar to surface IA problems. Today saying "I think this
  page is doing too much" feels imprecise; with the matrix, the same
  observation becomes "row X has 3 intents — propose splitting".

**Negative.**

- **Audit time doubles** in the small. Each plan's audit phase grows
  from one pass to two. Modest cost (the IA pass is mostly enumeration,
  not grading), but real.
- **Implementer bias is mitigated, not eliminated.** Solo work still
  risks the "I built this, it's obviously one intent" trap. D-4
  helps but doesn't fully solve it. The honest mitigation is to
  ship plans as drafts and invite cold-open critique before grading
  visuals.
- **The matrix's value depends on naming discipline.** "Edit a triple"
  and "compose a write" are the same intent named differently —
  inconsistency across audits would let multiplexing hide. Future
  audits should reference prior matrices when picking intent labels.

**Neutral.**

- The IA pass adds a deliverable (the matrix), not a tool. No new
  test harness, no new script. The discipline lives in plan files
  and `visual-audit.spec.ts` comments.
- The pre-existing F-1..F-N visual rubric is unchanged. This ADR
  bolts an earlier pass onto the front of the existing ritual; it
  doesn't alter what visual grading covers.

## Follow-up / future work

- **First retroactive matrix:** Add an IA matrix to
  `plans/query-update-import-split.md` Phase 6.5 (the deferred visual
  audit) — the split now serves as the worked example for what the
  matrix looks like after a successful flag-and-split.
- **Extend `e2e-conventions` skill** (or write a new `ia-audit` skill)
  to encode the matrix template + the flag-row rule. The skill makes
  it easy to invoke before any plan's Phase-N audit.
- **Plan-template update:** the `new-plan` skill's template should
  include an "IA matrix" sub-section under any audit phase, with the
  empty matrix shape pre-filled.
- **Newcomer-docs entry:** an explainer doc on "what the IA audit
  is, when it catches what" would help future contributors recognize
  the class — when reviewing PRs, they should be asking "does this
  add a new surface? has the matrix been updated?"
- **Possible future ADR:** if the matrix discipline grows complex
  enough to need its own tooling (a generated route × surface ×
  intent JSON, a CI check that flags new multi-intent rows), promote
  it to its own decision.

## Open questions

- **Q1.** Should the IA matrix be a separate file (e.g.,
  `docs/ia-matrix.md`) checked into the repo as the canonical
  intent map, or should each plan re-derive its own matrix in its
  audit phase? Argument for canonical: one source of truth, easier
  to spot drift. Argument for per-plan: less coordination cost,
  matrix evolves with the app. **Lean: per-plan for now, promote
  to canonical if drift becomes a problem.**
- **Q2.** Does the IA pass have a minimum cadence outside of plan
  audits? E.g., a quarterly cross-app sweep regardless of whether
  any plan is actively in audit phase. **Lean: no minimum for now,
  rely on plan audits to keep the matrix fresh.**
- **Q3.** How does this interact with the recent token-sweep
  discipline (the `token-sweep` skill)? Token sweeps are
  surface-execution work — they should run inside the visual pass,
  not interrupt the IA pass. Worth documenting in the skill's
  SKILL.md if confusion emerges.
