
# ADR-0001: Per-triple Provenance via Sidecar Prolly Tree

## Status

Accepted, backfilled 2026-06-23 (predated the `## Status` convention) — the sidecar `ProvenanceIndex` is implemented (opt-in).

| Status   | Implementing (F.1 in progress, 2026-05-13)               |
|----------|----------------------------------------------------------|
| Decision | **Approach 2** — sidecar prolly tree, opt-in via Sail flag |
| Iter     | F (sub-iters F.1 → F.7)                                  |
| Authors  | prolly-rdf4j team                                        |

> **Goal:** when a SPARQL SELECT returns a row, the UI can show where that row's
> triple came from — *"first added at commit `6b1f3c7d…` on Wed, 13 May 2026
> 04:30 GMT"*. Hover-card UX, O(1) server lookup, surviving merges and snapshots.

This is the killer demo feature of a versioned RDF store. Without it,
version control is abstract; with it, every result row is a doorway
into time. It's also a four-day slice with non-trivial implications
for write throughput, on-disk format, and cross-language compatibility
with Dolt's Go port — hence this plan.

## TL;DR — recommendation

**Approach 2: Sidecar Provenance Index.** A new prolly tree
(`provenance`) keyed by `(s,p,o,c) → firstSeenParentHash`, maintained
alongside the existing four quad indexes. Bit-level compatibility with
Dolt preserved (we don't touch SPOC/POSC/OSPC/CSPO bytes). O(1) lookup
at query time. Pays ~30–40% commit-time overhead and ~25% more disk
when enabled; opt-in via Sail flag for high-ingest deployments.

Split into 7 sub-iters (F.1 → F.7), 4–5 dev days end-to-end. Lazy
migration (existing stores stay empty-provenance until rebuild is
explicitly invoked).

---

## 1. The four candidates I considered

| Approach | Bit-compat with Dolt | Write cost | Read cost | Migration | Verdict |
|---|---|---|---|---|---|
| **1. SPOC value extension** — add `firstCommit` to each SPOC entry | ❌ breaks | +5% | O(1) | needs full rebuild | rejected: breaks the v0.2.0 bit-compat goal |
| **2. Sidecar prolly tree** | ✓ preserved | +30–40% | O(1) | optional rebuild | **recommended** |
| **3. Log scan on demand** | ✓ | 0% | O(commits × bindings) | none | rejected: 50-commit store = seconds per hover |
| **4. Background external index** | ✓ | 0% | O(1) | always rebuilding | rejected: eventually-consistent semantics + extra moving part |

### Why approach 2 wins on tradeoffs

- **Bit-compat is non-negotiable.** Project memory pins v0.2.0 to "Java
  port targeting bit-level compatibility with Dolt's Go port". Approach
  1 (mutating SPOC value bytes) would force a coordinated upstream
  change in Dolt or a divergent fork. Sidecar leaves SPOC untouched.
  > **Update 2026-06-26 — this load-bearing rationale is now MOOT.** Dolt
  > bit-compat went **optional/deferred** on 2026-05-29 (the port owns its
  > own deterministic format; see CLAUDE.md "Pre-1.0 — no backwards-compat
  > code" + `cross-lang/BITCOMPAT_FINDINGS.md`). So "Approach 1 breaks
  > bit-compat" is no longer a *disqualifier*. Approach 2 still wins on the
  > surviving merits (O(1) reads, opt-in zero-cost, schema-evolvability, and
  > SPOC-untouched simplicity), so the **decision stands** — only this one
  > supporting argument retired. (Retract-in-place per calibrated honesty.)
- **O(1) at query time** is the UX bar. Hovers cannot wait on snapshot
  reads. Approach 3 fails this immediately.
- **Opt-in.** Deployments that don't want provenance (e.g., a
  high-throughput append-only pipeline) pay zero — neither write nor
  storage cost. Flip the flag and the Sail simply doesn't create the
  sidecar.
- **Schema-evolvable.** The provenance value today is a parent commit
  hash; tomorrow it can grow to `{firstSeenParent, author, mergeCount}`
  without touching the data path.

## 2. Recommended design

### 2.1 Storage shape

New prolly tree `provenance`, written through a new
`com.earasoft.prolly.rdf4j.index.ProvenanceIndex` class:

```
key:   SpocKey { s, p, o, c }   (4 × TermId = 32 bytes — same as the existing SPOC)
value: byte[20] firstSeenParentHash
                                — the RootMetaTree hash of the *parent* commit at
                                  the moment this triple was added. Empty
                                  (zero-length) when added in the genesis commit.
```

Why the *parent* hash and not "this commit's" hash:

- A commit's own RootMetaTree hash is determined by its data, which now
  includes the provenance root, which would include this commit's
  hash → circular.
- Recording the parent gives us a stable handle: at lookup time, the
  server resolves "first appeared after commit X" by walking the
  CommitLog forward to find X's successor. That's the commit the UI
  shows.
- For the genesis commit, `firstSeenParentHash` is the empty byte
  array; the resolver returns the first entry in the CommitLog.

### 2.2 RootMetaTree extension

`RootMetaTree.NAME_PROVENANCE = "provenance"` joins the existing entry list
(`dict`, `spoc`, `posc`, `ospc`, `cspo`, `namespaces`, `stats`).
Provenance-enabled Sails always emit this entry; provenance-disabled
Sails never do. No defensive reader path — we're pre-1.0 and don't
maintain backward compat (see [[feedback-no-backwards-compat]]).
Existing on-disk stores need either a fresh store-dir or a one-shot
backfill (see §3, F.7).

**Hash impact:** adding the entry changes the RootMetaTree hash for any
provenance-enabled commit, which is the desired behavior (commit ids
should reflect the data they cover, including provenance).

> **Refined 2026-06-26 by [ADR-0071](0071-commit-identity-includes-parents.md).**
> The premise "commit id = the tree hash" is no longer true: a commit's **id**
> is now `hash(metaTreeHash ‖ parent-ids ‖ author ‖ message)` (the tree hash is
> a *separate* field, the tree address). So provenance still perturbs the id —
> but *transitively*, via the `metaTreeHash` term (a provenance-enabled commit
> has a different tree → a different id), not because the id *is* the tree. The
> claim "ids should reflect the data they cover" survives and is in fact
> **broadened**: the id now covers the tree **plus** parents + author + message,
> so two commits with the same provenance-bearing tree but different parents no
> longer collide (the bug ADR-0071 fixed). This §2.2 hash-impact note is
> therefore harmless under either provenance setting — exactly ADR-0071's promise.

### 2.3 Sail integration

```java
public class ProllySail {
    private volatile StaticMap provenanceRoot;  // analog to dictRoot, indexRoots[…]
    public Optional<StaticMap> provenanceRoot() { ... }
    void advanceProvenanceRoot(StaticMap next) { ... }
}

public class ProllySailConnection {
    private ProvenanceIndex provIdxTx;  // forked at startTransaction, like dictTx
}
```

#### Commit pipeline (delta only)

```
addStatement(s, p, o, c):
  ... existing 4-index inserts ...
  // Provenance write: idempotent — keeps the older entry if the triple
  // was already recorded in a prior commit. Records the CURRENT head
  // hash as the "parent", so the resolver maps to the *next* commit.
  if (provIdxTx != null) {
      provIdxTx.putIfAbsent(spocKey, sail.currentCommitHash());
  }

commitInternal:
  ... existing dict / indexes / namespaces / stats commits ...
  if (provIdxTx != null) {
      sail.advanceProvenanceRoot(provIdxTx.commit());
  }
  ... persistMetaTreeIfConfigured (now writes provenance root too) ...
```

#### Remove path

`removeStatementsInternal` does **not** delete provenance entries. The
triple was historically true; that fact is permanent. When the triple
is re-added later, the existing provenance record stands. Trade-off:
slightly larger sidecar over time (entries for deleted triples).
Mitigation: a separate `compactProvenance()` admin op (deferred).

### 2.4 Read endpoint

```
GET /sparql/provenance?s=<term>&p=<term>&o=<term>[&c=<term>]
POST /sparql/provenance       — body: { triples: [{s,p,o,c?}, ...] }   (batch)

Response:
{
  "results": [
    {
      "triple": { subject: {...}, predicate: {...}, object: {...} },
      "firstCommit":  "6b1f3c7d…",          // null if unknown
      "firstDatetime": "Wed, 13 May 2026 ..."
    },
    ...
  ]
}
```

Implementation:

1. Parse term inputs (same encoding the Dictionary uses — IRI / literal / bnode).
2. Resolve each term to a `TermId` via `dictTx.encode(...)`. If any term
   is unknown to the dictionary, return `firstCommit = null`.
3. Build `SpocKey { s, p, o, c }` (c defaults to default-graph TermId).
4. Look up in `provenanceRoot`. Empty → return null.
5. Take the `parentHash` value, resolve via `CommitLog.findSuccessor`
   (new utility): walk entries from oldest to newest, find the entry
   whose `parents` contains `parentHash`. That's the commit where the
   triple first appeared.
6. Special case: `parentHash == empty` → first entry in CommitLog.

Batch endpoint reuses the same machinery — one Sail snapshot, N
provenance lookups, single response.

### 2.5 UI integration

#### Where provenance is shown

- Query result table on `/query` — only when the query's projection
  contains all three of `?s ?p ?o` (heuristic: result vars include
  `s`, `p`, `o`). Otherwise the row doesn't correspond to a single
  triple and provenance is meaningless.
- Compare page diff results (already triple-shaped) — secondary, not
  needed for v1.

#### Interaction

- Each result row gets a small `(i)` icon at the row end.
- Hover or click triggers a fetch to `/sparql/provenance` (debounced,
  client-side LRU-cached by `(s,p,o)`).
- Popover shows: commit hash (short), relative datetime (`2 hours
  ago`), and a "View at this commit" link to `/query?commit=…`.
- For batch hydration: when the result page renders, fire a single
  POST with all rows; populate cache pre-emptively. Avoids hover lag.

#### Empty / degraded states

- Query shape isn't triple-pattern → no `(i)` icon at all; never
  promise provenance you can't deliver.
- Store has no provenance index (older deployment) → `(i)` icons stay
  greyed with tooltip "provenance not enabled on this store".

## 3. Sub-iter plan (7 slices)

Each slice is independently shippable and testable. Sequencing matters:
F.1 → F.2 → F.3 must land before F.4; F.5 depends on F.4; F.6 and F.7
can be done in either order after F.4.

| # | Slice | LOC | Effort |
|---|---|---|---|
| F.1 | `ProvenanceIndex` class + unit tests (no Sail wiring) | ~150 + 100 | half day |
| F.2 | RootMetaTree extension (`NAME_PROVENANCE`) + backward-compat tests | ~50 + 30 | half day |
| F.3 | Sail / Connection wiring: write path at commit time | ~100 + 80 | full day |
| F.4 | `/sparql/provenance` GET + POST endpoints + tests | ~150 + 120 | full day |
| F.5 | UI: `(i)` hover + popover + batch hydration + LRU cache | ~250 | full day |
| F.6 | Merge correctness: "older commit wins" in `MergeEngine` | ~80 + 60 | half day |
| F.7 | `POST /sparql/provenance/rebuild` — backfill scanner for legacy stores | ~150 + 80 | half-to-full day |

Total ≈ 4–5 dev days.

## 4. Production tradeoffs (the honest list)

### 4.1 Write throughput

Every `addStatement` now writes to a fifth tree. Expect **+30–40%
commit time** under provenance-enabled mode, primarily in
TreeMutator's hashing path.

Mitigations available:

- **Defer-and-batch**: gather adds in an in-memory `Set<SpocKey>` and
  write them to ProvenanceIndex once at `commitInternal` time. One
  flush instead of N.
- **Skip overhead for re-adds**: idempotent insert checks the existing
  provenance root before writing. Fast for read-heavy commits that
  also touch a few existing triples.
- **Bulk-load fast path**: `/sparql/load` can disable provenance for
  the duration of a single load if the user opts in (header
  `X-Prolly-Skip-Provenance: true`). Loads the data fast; loses
  provenance for that batch.

### 4.2 Storage

Each triple costs ~52 bytes in the sidecar (32-byte key + 20-byte
value). A 100M-triple store → **~5.2 GB extra**. Significant but
proportional. The sidecar compresses well (most provenance hashes
appear thousands of times — RocksDB block compression handles this).

### 4.3 Merge semantics

`MergeEngine` today does set-union over the four indexes. For
provenance the union rule is **min-by-commit-timestamp**: if A and B
both have an entry for the same triple, keep the one whose parent
hash points to the older commit.

Edge case: A added the triple, B never had it. Merge B → A is a no-op
for provenance (A's record stands). The reverse: merge A → B is the
case the union rule actually fires on.

Pathological case: A *removed* the triple before B re-added it. RDF
set semantics treat this as "the triple is present at the merge
result". Provenance now points to B's add-commit, even though A had
the triple earlier. **Decision**: this is acceptable. Provenance
tracks "currently visible since" not "ever existed since". Users
who want full audit history need event-log semantics (out of scope
for v1).

### 4.4 Snapshot reads

Querying at historical commit `X` should only see provenance for
commits `≤ X`. The Sail naturally satisfies this because opening at
`X` restores `provenanceRoot` from `X`'s RootMetaTree, which only contains
entries committed by then. Server code requires no special handling.

### 4.5 Cross-language compatibility

The sidecar is **opt-in**: a Sail without provenance produces
MetaTrees that lack the `NAME_PROVENANCE` entry. Dolt's Go port and
prolly-rdf4j-without-provenance produce byte-identical MetaTrees.

When provenance is enabled, the RootMetaTree has one extra entry; the
SPOC/POSC/OSPC/CSPO bytes are still byte-identical to Dolt's output.
The provenance tree itself is a vanilla prolly tree, so a Dolt-side
Go provenance writer could read it once one exists. This is the one
back-compat-style invariant we *do* hold — Dolt's index bytes must
not change ([[feedback-no-backwards-compat]] § exception clause).

### 4.6 Concurrent writers (Phase 4 CAS-rebase)

Currently single-writer. Phase 4 introduces concurrent commits with
CAS-rebase semantics. Provenance interaction:

- Two writers A and B both add the same triple in parallel branches.
- A commits first. B's CAS fails; B rebases on top of A.
- On rebase, B detects A already has a provenance entry for the
  triple and **drops** its own provenance write — A's earlier add
  wins.
- This is the "older parent commit wins" rule applied at rebase
  time instead of merge time.

### 4.7 Memory pressure

`provIdxTx` is in-memory until commit. For a bulk load of 10M triples
in one transaction, that's ~520 MB of pending provenance entries. To
cap:

- Flush ProvenanceIndex partials every N triples within a long
  transaction (sub-commits — not user-visible, just an internal
  spill-to-disk).
- Or: a transaction-level limit beyond which provenance is dropped
  with a warning header.

### 4.8 Privacy / leakage

Provenance timestamps reveal *when* data was added. For
compliance-sensitive workloads (e.g., financial trade timestamps),
this can leak business intelligence even if the data itself is
sanitized.

Mitigation: a Sail-level config `prolly.rdf4j.provenance-granularity`
that rounds the recorded datetime to day / hour / second. Default
second-granularity; ops can opt in to coarser bucketing.

### 4.9 Garbage collection

If a deployment ever GCs old commits (not v2.0 but eventually),
provenance entries pointing to deleted commits become orphans. Two
options:

- **Eager**: on GC, walk provenance and re-point orphans to the
  oldest surviving commit. Slow.
- **Lazy**: leave orphans alone; the read endpoint returns "commit no
  longer available; first seen ≤ <oldest-surviving-commit>".
  Acceptable as long as the message is clear.

## 5. Resolved questions (locked in for F.1)

These were open at plan time; we're proceeding with the answers below
unless someone explicitly overrides. Anything still in doubt is filed
as a sub-iter or a follow-up.

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Default opt-in or opt-out? | **Opt-in** via `prolly.rdf4j.provenance.enabled=true`. Default off. | Existing deployments don't pay the throughput cost without consent. UI shows greyed `(i)` icons with a "How to enable" link when disabled. |
| 2 | CONSTRUCT / DESCRIBE provenance? | **Skip** for v1. UI suppresses the `(i)` icon. | Constructed triples don't correspond 1:1 to a stored triple; provenance is undefined for them. |
| 3 | Join-row provenance? | **F.5 best-effort**: when all three of `?s ?p ?o` are bound to a known stored triple, show its provenance. Multi-triple joins → skip until F.8. | Single-triple SELECTs cover the demo case; row-level join provenance needs per-binding lineage tracking inside the optimizer (a separate slice). |
| 4 | Provenance via SPARQL result column or separate endpoint? | **Separate endpoint** (`/sparql/provenance`). | Cleaner data-vs-metadata separation. Doesn't perturb SPARQL JSON results. Easier to cache and batch. |
| 5 | Lifecycle of the flag? | **Sail constructor flag**, immutable for the JVM's lifetime. Lazy migration: new commits record provenance, older triples remain `unknown` until `POST /sparql/provenance/rebuild`. | Avoids partial-state pitfalls of runtime toggling. Backfill is an explicit operator action with progress reporting. |

> **Divergence note 2026-06-26 (Q1).** The ADR locks provenance **default off
> (opt-in)**, but the **booted server defaults it ON**: the `rebuild-jar` skill
> boots with provenance enabled (`--no-provenance` is the opt-*out*), and the
> single-tenant production wiring follows. So "Default off" is true of the bare
> `ProllySail`/embedder constructor but **not** of the deployed jar. This is
> intentional (the killer-demo feature ships on by default) — flagged here so a
> reader doesn't trust "default off" operationally. It is also why commit-identity
> soundness had to hold under **both** settings (ADR-0071): the product runs the
> on-path, the fuzz/embedder tests ran the off-path, and the bug lived only off.

> Stakeholder note: any of these can be overridden later by amending
> this ADR. The decision history (which ones changed, why) lives in
> git, not in the file body — leave older versions in the file only
> when they materially help understand the current decision.

## 6. Test plan

Unit (per slice):

- `ProvenanceIndexTest`: put+get round-trip; put-twice keeps original;
  scan order is stable; empty index returns Optional.empty.
- `MetaTreeProvenanceTest`: old MetaTrees deserialize fine; new ones
  round-trip with the provenance entry.
- `ProllySailProvenanceWriteTest`: 3 commits, each adds 1 triple,
  verify each triple's provenance points to the correct parent hash.
- `MergeEngineProvenanceTest`: A and B both add T; merge keeps the
  older one.

Integration (HTTP):

- `ProvenanceEndpointTests` (server module):
  - GET unknown triple → null.
  - GET known triple → expected commit hash + datetime.
  - POST batch → response order matches request order.
  - Snapshot read at older commit only returns provenance for triples
    that existed by that point.
  - Querying at HEAD shows the latest provenance state.

E2E (UI):

- Playwright (eventually): load FOAF sample → run `SELECT ?s ?p ?o`
  → hover an `(i)` icon → popover shows correct relative time.

Performance (one-shot, not CI):

- Bulk-load 1M triples with and without provenance. Measure commit
  time delta. Acceptance: < 50% slowdown.

## 7. Rollout

1. **F.1 → F.4 land in main**; provenance is opt-in via the Sail
   flag. Default off. No user-visible behavior change for existing
   deployments.
2. **F.5 lands**; UI gains the `(i)` icon. Tooltip says "provenance
   not enabled" when the Sail isn't configured for it.
3. **F.6 lands**; merge becomes provenance-aware (older-wins).
4. **F.7 lands**; `POST /sparql/provenance/rebuild` lets ops opt an
   existing store into provenance by walking history. No silent
   migration — the operator runs the endpoint, gets progress
   reporting, and decides when to flip the flag.
5. **Document the trade**: `docs/getting-started.md` gains a
   provenance section explaining the opt-in, the cost, and the
   rebuild path.

## 8. What this isn't trying to solve (deferred)

- **Author tracking** — "who" added a triple. Out of scope; needs an
  auth model first.
- **Modification history** — "when did this triple's object change?".
  RDF triples are immutable; objects don't change without a
  delete+add, which is two separate events. Punt to event-log
  semantics later.
- **Triple-level diff annotations** in CONSTRUCT results.
- **GC for orphan provenance** after commit pruning. We don't prune
  commits in v2.0.

## 9. "Blame" — open design axes

Follow-up axes the index could grow along once iter F is fully landed.
Capturing the decisions here so they don't get lost in the between-iter
shuffle.

1. **Naming.** "Provenance" is the right academic term — RDF has a
   whole PROV-O ontology around it — but **"blame"** is what
   developers reach for ("git blame", "blame this line"). The UI can
   surface it as **"Blame this triple"** / "Who added this?" while
   the server-side index keeps the technical name. Same data, two
   vocabularies for two audiences. Cheap rename, ship in F.5.

2. **Scope of the record.** Today the index stores only the
   parent-commit hash at first appearance. A richer "blame record"
   could include the **commit message** and **author** (once auth
   lands) at first-seen time — denormalized for fast display, costs
   roughly an extra ~50 bytes per entry but avoids the CommitLog
   walk on every hover. Defer until F.7 (the rebuild endpoint) is in
   place so we can backfill safely.

3. **Granularity.** Per-triple today. A coarser **per-subject**
   variant ("who first asserted anything about `:Alice`?") would be
   a smaller index and answers a common question more cheaply. The
   two can coexist:
   - **Per-triple** for forensic detail (e.g., "which commit added
     this exact (s, p, o)?").
   - **Per-subject** for cheap "show me the first appearance of this
     resource" overviews.

   Two sidecar trees, two `NAME_PROVENANCE_*` entries in the
   RootMetaTree, same opt-in flag gating both.

4. **History depth.** This index records first-seen-only. The full
   event chain (every INSERT and DELETE per triple, traversable as a
   `git log -- <triple>`) is the subject of [ADR-0003](0003-per-triple-event-log.md).
   Both indexes can coexist — first-seen for the cheap common case,
   event-log for forensic-detail workloads. Opt-in separately.

5. **Scope: repo-local via genesis-hash tagging.** The current
   `SpocKey` design is **accidentally repo-local**: each `TermId` is
   a function of `(encoded-term, salt, dictionary-state)` and the
   dictionary is per-repo, so the same RDF triple has different
   `SpocKey` bytes in different repos. Provenance leaves therefore
   differ across repos under CAS and don't accidentally collide in a
   shared chunk store.

   That accident disappears the moment we move to a stable cross-repo
   key (canonical-triple-hash) — which is the natural choice if "who
   has this fact across our fleet?" ever becomes a feature. To
   preserve repo-scoping in that future world, **tag every provenance
   entry with the repo's genesis-commit hash**:

   ```
   SpocKey → { parentCommit: byte[32], repoId: byte[32] }
   ```

   Properties of this design:

   - **Same triple in different repos → different leaf values → different
     chunks.** CAS dedup at the metadata layer is broken by construction.
   - **Cross-repo lookup remains possible**: a federated query layer can
     scan across stores by raw key, and the per-repo provenance is
     disambiguated by `repoId` in the value.
   - **Data-layer dedup is preserved**: SPOC/POSC/OSPC/CSPO chunks don't
     carry `repoId`, so identical data still shares storage.
   - **Forks share provenance** (because they share the genesis hash up
     to the fork point — semantically correct: both repos observed the
     pre-fork facts).
   - **Cost**: 32 bytes per entry. Roughly 1.8× the existing ~40-byte
     entry size; cheap relative to the ambiguity it removes.
   - **Migration**: a missing `repoId` in a legacy value means
     "unscoped / pre-this-iter." Readers tolerate; new writes always
     carry the id.

   **What this doesn't solve**: data-layer leakage in multi-tenant
   chunk stores (need tenant-encryption above CAS or per-tenant
   NodeStores), read-side access control (auth-layer concern), and
   trust/authority semantics (PROV-O territory, different feature).

The blame-vs-provenance UI rename ships in F.5 cheaply (label only —
no on-disk change). The denormalized record (axis 2), per-subject
index (axis 3), and genesis-hash tagging (axis 5) are load-bearing
on-disk schema changes — gate on F.7's rebuild path having landed so
we can backfill safely. Axis 4 lives in its own ADR.

---

*Plan version 1. Update on alignment with open questions in §5.*
