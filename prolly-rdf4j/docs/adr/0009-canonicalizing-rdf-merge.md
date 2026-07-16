
# ADR-0009: Canonicalization-Aware 3-Way RDF Merge

## Status

Proposed, backfilled 2026-06-23 (predated the `## Status` convention) — the basic 3-way `MergeEngine` exists; this canonicalization-aware (BNCC) variant is not implemented.

| Status   | Proposed                                                                |
|----------|-------------------------------------------------------------------------|
| Decision | **Option B** — BNCC-partitioned merge: ground triples byte-merge; blank-node components merge atomically by canonical hash |
| Iter     | RM (sub-iters RM.1 → RM.10)                                             |
| Authors  | prolly-rdf4j team                                                       |

> **Goal:** a 3-way merge that does not silently corrupt RDF — two
> branches that independently assert the same blank-node-bearing fact
> merge to *one* copy, not two; concurrent contradictions surface as
> conflicts instead of producing an inconsistent graph.

## 1. The problem

The merge engine (`com.dolthub.prolly.MergeEngine`, and the rdf4j
`MergeEngine` that plan 8 rewires)
merges by **byte-set-union over quad bytes**. That is correct for the
relational rows it was ported from and **incorrect for RDF** — because a
blank node's identity is graph-local, so byte-identity ≠ logical
identity. Two branches independently asserting the same address
(`_:b0` vs `_:b7`) byte-merge into *two* addresses: the merge invents a
fact, silently.

The full problem analysis — blank-node renaming, structurally-equivalent
reified nodes, RDF-star recursion, the five silent failure modes — is
`RDF_MERGE_SEMANTICS.md`.
That document is the *scoping*; it ends with "we don't have an
implementation yet … anyone shipping versioned RDF eventually picks a
stance." **This ADR picks the stance.** It is the single largest unowned
engineering gap flagged in
`RDF_VERSIONED_STORAGE.md §8.1`.

Plan 8 does **not** address this — it makes merge *fast* (`DiffEngine`
per tree instead of an O(n) scan) but is the same byte-set-union, so it
is equally wrong on a blank-node merge.

## 2. The options

| # | Approach | Blank nodes | Cost | Verdict |
|---|---|---|---|---|
| A | Canonicalize whole graphs (URDNA2015), then byte-merge | correct *iff* labels align | O(whole graph), n! worst case on the whole graph | rejected — labels don't align across base/ours/theirs |
| **B** | **BNCC-partitioned merge** — ground triples byte-merge; blank-node components canonicalized and merged atomically by canonical hash | correct | URDNA2015 confined to small components | **recommended** |
| C | Skolemize blank nodes to IRIs at write time | not fixed | — | rejected — deterministic skolem = A in disguise; non-deterministic doesn't help merge |
| D | TerminusDB-style delta/patch encoding | correct | — | rejected — prolly is a snapshot/Merkle substrate, not patch-based; wrong architecture |

### Why A fails

`RDF_MERGE_SEMANTICS.md` work-item A literally proposes "canonicalize all
three sides, then byte-merge." The flaw: URDNA2015 canonical labels are a
function of the **whole graph**. base, ours, and theirs are *different*
graphs (that's why you're merging), so the same logical blank node gets
*different* canonical labels in each — the issued `c14n3` in base may be
`c14n5` in ours. Byte-merge of independently-canonicalized graphs still
under- and over-merges. And it runs URDNA2015's exponential worst case on
the *entire* graph.

### Why B wins

The fix is **scope**. `prolly-urdna2015` already ships a
`BnccPartitioner` — it splits a quad set into the **ground** triples (no
blank nodes) and the **blank-node-connected components (BNCCs)**. That
partition is the whole design:

- **Ground triples** — identity *is* byte-identity. They go straight
  through plan 8's structural byte-merge, unchanged and correct. In real
  RDF this is the large majority of triples → the fast path stays fast.
- **BNCC components** — each connected blank-node component is
  canonicalized **on its own** (not against the whole graph) and reduced
  to a **canonical hash**. A component is then treated as a single
  atomic value, matched across base/ours/theirs by that hash.

Confining canonicalization to one component at a time means the labels
*do* align (a component canonicalized in isolation is deterministic) and
the URDNA2015 worst case is bounded by the largest component — typically
tiny (an address, a reified statement), not the dataset.

## 3. The decision — the algorithm

ADR-0009 is a **front-end to the merge**: it produces a *merge-consistent
relabeling* so that plan 8's structural byte-merge becomes RDF-correct,
and detects conflicts along the way.

```
mergeRdf(base, ours, theirs):
  for each side ∈ {base, ours, theirs}:
      (ground, bnccs) = BnccPartitioner.partition(side)
      for each component ∈ bnccs:
          canon = canonicalize(component)          # prolly-urdna2015, per-component
          hash  = H(canon)                         # isomorphic components → same hash

  # --- ground triples: identity = bytes ---
  groundResult = plan8StructuralMerge(base.ground, ours.ground, theirs.ground)

  # --- blank-node components: identity = canonical hash ---
  for each component identity (by hash) across the three sides:
      classify base/ours/theirs presence → 3-way decision:
        - added on one side only            → keep
        - added on both sides (same hash)   → keep once   (fixes 4.1 / 4.2)
        - in base, unchanged one side, gone other  → delete (delete wins)
        - in base, changed differently on both     → COMPONENT CONFLICT
  emit the merged component set + conflicts
```

Two components with the same canonical hash are the **same fact** even
if their blank-node labels differ — so independently-asserted isomorphic
structures (the `_:b0` / `_:b7` address; two reification clusters for the
same statement) merge to one copy. That is the §1 corruption, fixed.

### 3.1 Merge granularity — the honest tradeoff

A BNCC component is merged **atomically**. If both branches edit the
*same* component in *different* ways, that is a **component-level
conflict** — the engine does not attempt to 3-way-merge *inside* a
blank-node component.

This is coarser than triple-level merge, and it is the deliberate
tradeoff: **correct beats clever.** Blank-node components are usually
small and self-contained (one address, one reified statement); the
common cases — each branch adds a *different* component, or only one
side touches a given component — auto-merge cleanly. Intra-component
3-way merge is deferred (open question §7).

### 3.2 Conflict detection — two tiers

- **Structural conflict** — a component changed differently on both
  sides (§3). Always detectable, no schema needed.
- **Functional-property conflict** — `:Alice :age 30` vs `:age 31` where
  `:age` admits one value. This needs a *schema signal*: a configured
  set of functional properties, or SHACL `sh:maxCount 1`. OWL reasoning
  to *derive* functionality is out of scope. Tier 2, gated on a
  configured constraint set (open question §7).

Set-union has **no** conflict surface at all; ADR-0009 adds one. The
rdf4j `MergeEngine.Conflict` record (reserved but never emitted today)
is extended with a `ComponentConflict` variant.

### 3.3 What falls out for free

- **Reification (RDF_MERGE_SEMANTICS §4.2)** — a bnode-headed
  reification cluster *is* a BNCC component; it is handled by §3 with no
  special-casing. Named-IRI `rdf:Statement` reification is ground —
  also already correct. So work-item B is mostly free of the BNCC
  decision.
- **Delete-wins, idempotent add** — standard git-like 3-way logic at
  both the ground-triple and component level.

## 4. Where it plugs in

ADR-0009 sits **in front of** plan 8's
structural merge, in the rdf4j `MergeEngine` (`com.earasoft.prolly.rdf4j.sail`).
It reuses, not reinvents, the `prolly-urdna2015` module: `BnccPartitioner`
for the ground/component split and an `RdfCanonicalizer` implementation
for per-component canonicalization. Ground triples are fed to plan 8
unchanged; the component path produces statement-level mutations that
join the merge commit.

Dependency: plan 8 (structural merge — the ground fast path), and the
`prolly-urdna2015` canonicalizer suite (already exists). Independent of
plan 9.

## 5. Consequences

- **`MergeEngine` is no longer set-union.** The honest "merge is
  incorrect for RDF" caveat in `RDF_VERSIONED_STORAGE.md §8.1` is
  retired *for the cases this ADR covers* — see §7 for what remains.
- **URDNA2015 worst case is bounded but not eliminated.** A pathological
  highly-symmetric blank-node component still triggers the exponential
  path — now confined to one component. RM.7 adds a per-component
  budget guard with a clean `SailException` (mirroring plan 4's
  canon-budget exhaustion handling), so a merge fails loudly rather
  than hanging.
- **Merge cost scales with the bnode-bearing subgraph**, not the
  dataset. Ground-only merges are exactly as fast as plan 8.
- **Component-granularity conflicts** (§3.1) — coarser than triple
  granularity; accepted.
- **Materialization.** The component path needs statement-level access
  to the changed bnode subgraph, not just tree bytes — so the merge
  decodes that slice. Bounded by the bnode subgraph size; ground
  triples never decode.

## 6. Implementation plan (sub-iters)

Sequence: RM.1 → RM.2 → RM.3 → RM.4 in order; RM.5–RM.7 after RM.4;
RM.8 gated on plan 6; RM.9 throughout; RM.10 last.

| # | Slice | Effort |
|---|---|---|
| RM.1 | Audit `prolly-urdna2015`: confirm `BnccPartitioner.partition` gives the ground/component split we need; confirm an `RdfCanonicalizer` impl yields a stable per-component canonical form. | half day |
| RM.2 | Ground/component partition in the rdf4j `MergeEngine`: split base/ours/theirs; route ground triples to plan 8's structural merge unchanged. | full day |
| RM.3 | Per-component canonical hash: each BNCC component → canonical serialization → content hash. Isomorphic components hash equal. | full day |
| RM.4 | Component-level 3-way merge: match components across the three sides by hash; apply delete-wins / idempotent-add / both-sides logic; emit merged components + structural conflicts. | 2 days |
| RM.5 | Reification test pass: verify bnode-headed reification clusters partition as single BNCC components and merge correctly (RDF_MERGE_SEMANTICS §4.2). | half day |
| RM.6 | Conflict representation: `ComponentConflict` variant on `MergeEngine.Conflict`; structural conflict surfacing. Tier-2 functional-property conflicts gated on a configured constraint set — `RM.6b`, optional. | full day |
| RM.7 | Per-component URDNA2015 budget guard → clean `SailException` on exhaustion. | half day |
| RM.8 | RDF-star recursion — canonicalize quoted triples containing blank nodes. **Gated on plan 6.** Defer if plan 6 is not done. | 1 day |
| RM.9 | Test suite (§ below). | 2 days |
| RM.10 | Docs: update `RDF_MERGE_SEMANTICS.md` "current stance" and `RDF_VERSIONED_STORAGE.md §8.1`; merge section in `getting-started.md`. | half day |

Total ≈ 8–10 dev days. Phase-sized — could equally be `plans/10-rdf-merge.md`.

## 7. Tests

The four hard cases from `RDF_MERGE_SEMANTICS.md §4` are the test spec:

- **`BlankNodeRenameMergeTest` (§4.1)** — both branches add an
  isomorphic blank-node structure with different labels → merge yields
  **one** copy. The ancestor-also-had-it variant.
- **`ReifiedStatementMergeTest` (§4.2)** — both branches reify the same
  statement → one reification cluster, not two; `COUNT(DISTINCT ?stmt)`
  returns 1.
- **`ComponentConflictTest`** — both branches edit the same component
  differently → a `ComponentConflict`, no silent corruption.
- **`FunctionalPropertyConflictTest`** — `:age 30` vs `:age 31` with
  `:age` in the configured functional-property set → conflict.
- **`GroundFastPathTest`** — a bnode-free merge takes the plan-8 path;
  assert no canonicalization runs (spy/counter) and the result equals
  today's structural merge.
- **`MergeBudgetTest`** — a pathological symmetric component → clean
  `SailException`, merge aborts, no partial commit.

## 8. Open questions

| # | Question | Recommendation |
|---|---|---|
| 1 | Intra-component 3-way merge? | **No** — component-atomic (§3.1). Correct over clever. Revisit only if real workloads show large, concurrently-edited components. |
| 2 | Functional-property conflict source? | A **configured functional-property set / SHACL `sh:maxCount 1`** (RM.6b). OWL reasoning to *derive* functionality is out of scope. |
| 3 | Asserted-vs-derived triples (RDF_MERGE_SEMANTICS §4.4 / work-item D)? | **Defer** — it is a reasoning concern, and reasoning is out of scope for prolly-rdf4j. Re-open if a reasoner integration lands. |
| 4 | RDF-star (§4.3)? | Gate RM.8 on plan 6; ship RM.1–RM.7 without it. |
| 5 | Canonicalize at commit-time or merge-time? | **Merge-time, per-component.** Commit-time whole-graph canonicalization (plans 4/7) does not give merge-aligned labels — the whole-graph-dependence problem (§2-A). |

## 9. Relationship to other ADRs / plans

- **Resolves** `RDF_MERGE_SEMANTICS.md` (the scoping doc) and retires the
  `RDF_VERSIONED_STORAGE.md §8.1` "incorrect for RDF" caveat for the
  blank-node / reification cases.
- **Depends on** plan 8 (the ground
  fast path) and the `prolly-urdna2015` module (`BnccPartitioner`,
  `RdfCanonicalizer`).
- **Layered, not conflicting,** with [ADR-0006](0006-commit-log-as-rdf.md)
  / [ADR-0007](0007-void-dataset-statistics-graph.md) /
  [ADR-0008](0008-multi-store-shared-nodestore.md) — those are read
  projections and substrate; this is write-path merge correctness.
- §6 can be lifted into `plans/10-rdf-merge.md` unchanged if the team
  prefers the `plans/` workflow.

---

*Plan version 1. Ready for stakeholder review before RM.1.*
