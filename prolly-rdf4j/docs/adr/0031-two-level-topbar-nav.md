
# ADR-0031: Two level topbar nav

> Originally drafted as ADR-0030 on 2026-05-26; renumbered to 0031 on
> 2026-05-27 when [ADR-0030 — prolly-platform-rest extraction](0030-prolly-platform-rest-extraction.md)
> shipped to the same number ahead of this one being accepted. Plan
> references updated alongside.

## Status

Accepted, 2026-05-27. Guides `plans/two-level-nav.md`.

## Context

The 2026-05-26 user-flow audit graded `app.html`'s topbar at
**23 elements on one visual tier**. Three small audit rounds
added discoverability (g-shortcuts, dropdown links) but did
NOT address the underlying IA: a single nav band where the
"what am I operating on" controls (repo breadcrumb, role chip,
sign-in) compete for attention with feature affordances
(Query, Update, Import, …) and utility chips (Docs, Online,
Theme, ?). Audit Finding 3 named the gap; the picker-redesign
plan deliberately left it as out-of-scope.

The codebase ALREADY has the seam — `.topbar-tier1` and
`.topbar-tier2` CSS classes exist (added incrementally as
plans landed). But every element of the topbar still mounts
within one of those tiers without consistent rules for which
tier owns what. Currently:

- **Tier 1 today**: brand, repo-breadcrumb, repo-picker,
  role-chip, Docs, system-status-badge, ?, theme toggle,
  signed-in-as dropdown, sign-out (10 elements).
- **Tier 2 today**: branch-switcher ("View at"), 12 feature
  nav links (Query/Update/Import/…), MR badge, Admin link
  (~14 elements).

That's the *physical* tier split, but the *logical* mapping is
muddy. Branch-switcher is per-repo (Tier 2 is right), but it
sits alongside Tier 1's repo-breadcrumb visually, splitting
the "what scope am I in" question across two rows. The Admin
link is global but lives in Tier 2.

The decision is: **do we commit to a strict global vs scoped
IA, accept the muddy state, or pursue a different layout
entirely (sidebar)?**

## Options

| Option | Tier shape | Cost | When right |
|---|---|---|---|
| **A — Strict two-tier** (this ADR's pick) | Tier 1 = global (logo, breadcrumb, search, profile); Tier 2 = scoped (in-repo nav OR admin nav OR empty) | Refactor every element's tier assignment + add a tier-controller that swaps Tier 2 contents by URL | Web apps with ≤15 in-repo nav slots and well-defined scope boundaries — GitHub, DoltHub, GitLab dashboard view |
| **B — Sidebar nav** | Tier 1 stays + a left sidebar holds scoped nav | Existing horizontal-nav muscle memory breaks; mobile collapse is harder | Apps with 15+ in-repo links + deep nesting — Snowflake, GitLab project view |
| **C — Accept muddy state** | Status quo: two physical tiers without logical rules | Zero work; ongoing UX cost as new pages land | When the IA pain isn't worth the refactor cost — only if the product is in maintenance mode |
| **D — Single tier with aggressive grouping** | One tier, but elements visually grouped (chips, sections) | Less physical reshuffle but harder to make memorable hierarchy | Mobile-first products; not aligned with the desktop-first persona here |

Option A wins on alignment with the existing tier classes +
the persona (desktop-first ops engineers + developers) + the
nav size (12 in-repo links — well under the sidebar threshold).
B is the right answer if the in-repo link count grows past
~15-18, but premature now. C punts the IA debt; the audit has
already flagged it. D is the wrong persona.

## Decision

Adopt **Option A: strict two-tier topbar**. The split rules:

**D-1. Tier 1 is GLOBAL.** Always renders (when signed in).
  Contents: brand link, repo-breadcrumb (scope identity),
  role-chip (scope-context: what role do I have here?),
  command-palette trigger (search), open-mrs-badge,
  signed-in-as dropdown, sign-out. Utility chips (Docs,
  system status, theme toggle, ?) MOVE into the
  signed-in-as dropdown (already a menu).

  **Revision note (2026-05-27):** an earlier draft moved
  role-chip into the dropdown alongside Docs/status/theme/?.
  Reversed: role-chip is scope-context (paired with breadcrumb),
  not utility chrome. Tucking it behind a click harms
  glanceable "do I have write here?" feedback for non-admin
  ops engineers — the most failure-sensitive case. Keep it
  visible in Tier 1.

  **Revision note (2026-05-27):** open-mrs-badge moves OUT of
  the MRs page-nav link (where it lives today, nested inside
  the link text) and into Tier 1 as a top-level badge. The
  MRs link in Tier 2 stays as a plain link; the badge surfaces
  unread MR count from anywhere in the app, not just when MRs
  is rendered.

**D-2. Tier 2 is SCOPED.** Its contents change based on URL:
  - In-repo (`/`, `/repos/X/`, `/orgs/X/repos/Y/...`): the
    workbench links (Query, Update, Import, Download,
    History, MRs, Branches, Compare, Sync, Jobs, Editor,
    Instances) + the branch-switcher.
  - Admin (`/admin/*`): Repos | Users | Remotes | Jobs (the
    admin-menu from `plans/admin-users-index.md`).
  - Account (`/account/*`): Tokens | Keys | Sessions | Password.
  - `/login`, `/my-repos`, `/my-orgs`, `/orgs`, `/account-disabled`:
    HIDDEN (no scope to nav within).

**D-3. The version-ribbon sits BETWEEN Tier 1 and Tier 2.** It
  renders in all three current modes (live HEAD, snapshot, empty
  store) — NOT only when `?commit=` / `?branch=` is set, as an
  earlier draft of this ADR proposed. Rationale for the revision:
  the head-mode ribbon "Viewing main · last commit 30s ago · …"
  is a useful continuous live-HEAD indicator (it answers "am I
  current?"), and squelching it sacrifices that feedback for no
  gain. The visual hierarchy is preserved via mode-specific
  styling: head/empty modes render as a low-contrast band; snapshot
  mode renders as a prominent amber warning. The "between tiers"
  position is structural; the mode determines the visual weight.
  Three candidate positions considered:
  - **Above Tier 1** — visual top weight; conflicts with
    brand prominence; rejected.
  - **Below Tier 2** (current position) — easy to miss;
    Tier 2 reads as "the surface" and ribbon-as-banner
    sits below the nav, decoupled from the snapshot it
    indicates. Rejected.
  - **BETWEEN tiers** (ADR pick) — reads as "this scope
    indicator modifies the nav below". The strongest
    semantic position; matches the read-order of
    "what am I in? (Tier 1 breadcrumb) → at what state?
    (ribbon) → what actions? (Tier 2)".

  Cost: a new vertical-layout slot. Mitigation: the ribbon is
  the SAME component, just mounted in a new place. Risk: if
  the snapshot mode is the default state (it isn't — most
  users live at HEAD), the always-present ribbon would
  bloat. Today it's gated on `?commit=` / `?branch=` so it
  appears only when relevant.

**D-4. Mobile: Tier 2 collapses to a hamburger at ≤900px.**
  Tier 1 stays visible. The hamburger is keyboard- + tap-
  accessible. Desktop-first product, but the hamburger is
  cheap insurance.

**D-5. Tier 2 link set is data-driven, not hardcoded per
  page.** A `Tier2NavService` (new) reads the URL and
  returns the link set. Adding a new admin page = adding
  one entry to the service's admin-nav array, NOT
  conditionally editing `app.html`.

**D-6. Branch-switcher moves UNAMBIGUOUSLY into Tier 2.**
  It's a per-repo concern (which branch's HEAD am I
  reading?) and belongs alongside the in-repo nav. Today
  it visually lives at the LEFT of Tier 2, before the
  workbench links.

**D-7. The (now-unused) admin link from Tier 2 becomes the
  Tier 2 link-set itself when URL is `/admin/*`** — not a
  separate "Admin" affordance. URL drives nav, not the
  reverse.

**D-8. No new visual layout beyond what's already there.** No
  font changes, no color changes, no spacing redesign. THIS
  is pure IA reshuffle. Visual restyle is a separate ADR
  if/when it lands.

## Consequences

**Positive**:
- Audit Finding 3 closes. Each tier has a single concern;
  no more "is this global or scoped?" guessing.
- New pages get an obvious home: global → dropdown in Tier 1;
  scoped → entry in the relevant Tier 2 set; never a fresh
  top-level link.
- Mobile gets a clean collapse path (Tier 2 hamburger).
- Future sidebar option (B) is still reachable if in-repo
  nav grows — Tier 2 IS the sidebar candidate.

**Negative**:
- Migration cost: every Tier 1 element must be inventoried
  and reassigned. The plan estimates 5 phases / ~9 steps.
- Visual change for every user — muscle memory hit. Users
  who've memorized "Query is 3rd from left in the topbar"
  will see Query move (probably to leftmost of Tier 2
  workbench).
- Some chips that used to live on Tier 1 (Docs, status
  badge) move into the dropdown — less glanceable. We
  accept this; glanceable system-status was probably
  over-prominent for what it conveys.
- Behavior-spec contracts break: `topbar-tiers.spec.ts`
  asserts current membership; must be updated alongside.
  Other specs that rely on specific link positions in
  Tier 1 also break — but only a few do.

**Risks**:
- The "URL drives Tier 2" rule (D-5, D-7) means a route
  not registered in `Tier2NavService` renders an empty
  Tier 2. We mitigate by including a fallback "show empty"
  state with a hint to register the route.
- Snapshot-ribbon-between-tiers (D-3) is a new visual
  position. If users mis-read it as "part of Tier 1", the
  ADR may need to revisit.

**Reversibility**: medium. Reverting reverts every assignment
in `Tier2NavService` + restores `app.html` to its current
shape. ~half a day of work. Not "free", but not
disk-format level either.

## Resolved decisions (Q1-Q4 closed 2026-05-27)

**Q1 → D-9. Brand collapse at narrow widths is an implementation
  detail, not an ADR-blocking decision.** Tier 1 left-to-right at
  desktop reads: brand → breadcrumb → picker (modal trigger) →
  palette button → spacer → open-mrs badge → signed-in-as dropdown
  → sign-out. At ≤1024px the brand link collapses to a logo glyph;
  beyond that, the implementation may further collapse the
  palette-button label to an icon (see D-10). No ADR commitment
  beyond "narrow-width collapse is allowed in either direction".

**Q2 → D-10. Command palette is a visible Tier 1 button** with
  label "Search…" (icon + text). Collapses to icon-only at ≤1024px.
  Rationale: mouse-only users today have zero discoverability of
  Cmd/Ctrl+K. The button advertises the shortcut on the tooltip
  and surfaces it to muscle-memory builders. Footprint: ~120px at
  desktop, ~32px on collapse.

**Q3 → D-11. Admin/account Tier 2 returns `kind: 'hidden'` for
  users who can't access ANY entry in the set.** A non-admin who
  URL-types `/admin/repos` already gets server-side 403; the Tier 2
  service ALSO returns hidden in that case so the user doesn't see
  a phantom admin nav band. For mixed cases (e.g. an org-admin
  visiting `/admin/repos` — has some perms but not all), the
  Tier 2 service includes only the entries the user can reach;
  per-entry server-side gates stay as the authoritative check.

**Q4 → D-12. Keyboard tab order follows DOM order.** No explicit
  `tabindex` ordering. `<version-ribbon>` gets `tabindex="-1"` on
  its outer container so the ribbon is skipped during Tab
  navigation (it's a status indicator, not interactive — the only
  interactive thing inside is the "Return to HEAD" button in
  snapshot mode, which keeps its own tabindex).

## Follow-up / future work

- **Sidebar nav (Option B)** — revisit when in-repo nav crosses
  ~15-18 links. Today: 12 (Query, Update, Import, Download,
  History, MRs, Branches, Compare, Sync, Jobs, Editor,
  Instances).
- **Visual restyle ADR** — colors, spacing, font weights. NOT
  in this ADR's scope per D-8.
- **Topbar minimization on focus mode** (hide Tier 1 + Tier 2
  while editing in `/query`)? Adjacent ergonomic feature;
  defer.
- **Notifications surface** (Tier 1 might gain a `🔔` badge for
  MR mentions, job-complete pings, etc.). Cross-link the
  webhooks plan and the open-MRs badge precedent when
  drafting.
