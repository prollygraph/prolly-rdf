
# ADR-0008: Multi-Store Hosting via a Shared NodeStore

## Status

Proposed, backfilled 2026-06-23 (predated the `## Status` convention) — the multi-tenant model that shipped is per-repo stores (ADR-0016), not this shared-NodeStore design.

| Status   | Proposed                                                                |
|----------|-------------------------------------------------------------------------|
| Decision | **Model 3** — N logical stores, one commit DAG each, over one shared content-addressed `NodeStore`, managed by a new `SailManager` |
| Iter     | MS (sub-iters MS.1 → MS.8)                                              |
| Authors  | prolly-rdf4j team                                                       |

> **Goal:** host many independently-versioned ontologies / datasets in
> one prolly-rdf4j deployment — each with its own branches, commits, and
> merges — without paying N times the storage or running N databases.

## 1. The problem

A `ProllySail` is **one store = one RootMetaTree commit DAG**. A commit
snapshots the *entire* Sail atomically (see [`root-meta-tree.md`](../root-meta-tree.md)).
That is the right unit for "version this dataset" — but real consumers
host *many* independently-versioned units:

- A knowledge-graph platform (the motivating case: Mobi) manages
  hundreds or thousands of ontologies and vocabularies, each a
  `VersionedRDFRecord` wanting its **own** branches and merge history.
- A dataset hub ("GitHub for datasets", see `TODO_HUB`)
  hosts many datasets, each forkable and branchable on its own.

One Sail-wide DAG cannot express "branch ontology A without touching
B." So: how do we host many independently-versioned datasets in one
prolly-rdf4j deployment?

Note this question is distinct from "many ontologies as *data*" — a
`ProllySail` is a quad store (the CSPO index), so many ontologies as
named graphs in one Sail already works. What it does *not* give is
**per-ontology versioning**. This ADR is about that.

## 2. The options

| # | Model | Per-ontology branches | Cross-ontology query | Storage | RocksDB count |
|---|---|---|---|---|---|
| 1 | One `ProllySail`, ontologies = named graphs (one shared DAG) | ❌ store-wide commits only | ✅ one dataset | dedup *within* | 1 |
| 2 | One `ProllySail` per ontology, **separate** `NodeStore` each | ✅ native | federation only | ❌ no cross-dedup | **N** |
| **3** | **N logical stores over one shared `NodeStore`** | ✅ native | grouping or federation | ✅ **full cross-dedup** | **1** |

A *Model 1.5* — one RootMetaTree carrying per-ontology sub-tree entries
(`ontology:foo/spoc`, …) — is still one DAG: one commit covers every
ontology. It is a variant of Model 1, not a third path.

### Why 1 is insufficient

Fine when prolly is *only storage* and an application layer (e.g.
Mobi's catalog) does the per-ontology versioning — that is a legitimate
deployment, and it mirrors Mobi's single-system-repo model. But it does
not use prolly's *native* versioning per ontology, which is the whole
point of the question.

### Why 2 is wasteful

A RocksDB instance is not free: block cache, memtables, file handles,
background compaction threads, an exclusive directory lock. A thousand
ontologies = a thousand RocksDBs. And — decisively — two ontologies
that both import FOAF/SKOS/Dublin Core store those chunks **twice**.
Separate `NodeStore`s throw away content-addressing's headline benefit.

### Why 3 wins

The `NodeStore` is a content-addressed chunk pool: `hash → immutable
bytes`, idempotent puts. A `ProllySail` over it is just *a set of roots
(a RootMetaTree) plus sidecars*. Multiple `ProllySail`s can be built
over the **same `NodeStore` instance** — each with its own
`RootMetaTreeStore` / `CommitLog` / `RefsStore` (its own DAG, its own
branches), all sharing one physical chunk store. This is the model
content-addressing is built for, and the `TODO` already lists
**"Multi-Tenant Repository Support"** as delivered in `prolly-rdf`
(`Database`).

## 3. The decision

Add a **`SailManager`** at the rdf4j layer that hosts N **logical
stores** over one shared `NodeStore`. Each logical store is an
independent commit DAG; the chunk pool is shared.

### 3.1 Layering

```
SailManager ──opens──▶ ProllySail "ontology-A" ─┐ own RootMetaTreeStore
            ──opens──▶ ProllySail "ontology-B" ─┤ own CommitLog
            ──opens──▶ ProllySail "ontology-C" ─┘ own RefsStore
                  │
                  ▼
        one shared NodeStore (one RocksNodeStore)  ◀── one chunk pool
        one shared off-heap NodeCache
```

### 3.2 On-disk layout

```
<root>/
  chunks/                  ← the single shared RocksNodeStore
  stores/
    ontology-A/            ← one logical store = one sidecar directory
      root-head
      commits.log
      refs/main
    ontology-B/
      root-head
      commits.log
      refs/...
```

Creating a logical store = create a sidecar directory with an empty
`CommitLog`. Deleting one = remove its sidecar directory; its chunks
become unreferenced and are reclaimed by GC (§5.1).

### 3.3 `SailManager` responsibilities

- **Registry** — logical-store id → sidecar directory.
- **Lazy open** — open a `ProllySail` on first access. Cheap: restore
  from its RootMetaTree (~7 chunk reads). The heavy `RocksNodeStore`
  stays open and shared across all of them.
- **LRU eviction** — do not keep 10k Sails resident; evict idle ones.
  Evicting drops only that Sail's in-memory roots + arena; the shared
  chunk store and `NodeCache` are untouched.
- **Lifecycle** — owns the shared `NodeStore` + `NodeCache`; closes
  them last, after every resident Sail.
- **CRUD** — create / list / delete logical stores.

Build on `prolly-rdf`'s `Database` multi-tenant machinery where it
already provides the registry + reachability walker; `SailManager` is
the rdf4j-facing facade. MS.1 decides build-on vs build-new.

## 4. Dedup is near-total — including index trees

A subtle but load-bearing point. `TermId` is a **content hash of the
term** (`id = H64(tag || payload)`, ARCHITECTURE §4.1). So identical
triples in two different logical stores resolve to **identical
`TermId`s**, hence identical SPOC/POSC/OSPC/CSPO **keys**, hence
identical index chunks. Cross-store dedup therefore spans not just raw
value chunks but the **index trees themselves** — two stores holding
the same vocabulary share its index subtrees physically.

The one exception is the hash-collision case: [ADR-0001 §5](0001-provenance-index.md)
notes the *salt* chain on a collision depends on per-dictionary
insertion order, so collision-extension entries can diverge between
stores. That is rare (≪1 at 1B terms) — dedup is near-total, with
collision-extension chunks the only exception.

## 5. Consequences — the honest list

### 5.1 Garbage collection spans all tenants

A chunk is garbage only if unreachable from **every** logical store's
**every** ref and commit. GC's reachability walker must union roots
across all logical stores — not one Sail's. `prolly-rdf` has a
parallel reachability walker and lists multi-tenant support as done;
MS.4 verifies it is multi-root aware.

The sharper problem: GC is currently **stop-the-world** (a write lock
for the full mark+sweep — `TODO` engine note 3). On a shared pool, one
GC pause stalls **every** tenant at once. Mitigation: schedule GC
off-peak; the real fix is the concurrent/generational GC already noted
as future work. Open question §8.

### 5.2 Cross-ontology query — the central tension

Per-ontology DAGs mean per-ontology Sails, so a query spanning
ontologies A and B spans two Sails. There is no free lunch:

- **Group co-queried datasets into one logical store** as named graphs
  — they then share a DAG (losing independent branching *for that
  group*). Reframes "logical store" as a **query + versioning domain**,
  not necessarily one ontology.
- **A read-only union Sail/repository** over selected member stores —
  queries fan out, results merge. Writes have no obvious target, so the
  union is read-only.

Granularity is the operator's choice: fine-grained (one ontology per
store — maximal independent versioning, query isolation) vs. coarse (a
domain of related ontologies as named graphs in one store — shared DAG,
cheap intra-domain query). `SailManager` supports any granularity; the
docs (MS.8) give guidance.

### 5.3 Concurrency

Each `ProllySail` keeps its own single-writer lock, so different
ontologies commit concurrently. The shared `RocksNodeStore` is
thread-safe, and content-addressed puts are idempotent (same hash →
same bytes — concurrent puts of the same chunk are harmless). The
off-heap `NodeCache` is shared (one cache for the pool → better hit
rate, and the cache dedups too).

### 5.4 Isolation — cooperative multi-tenancy only

Through the Sail API a tenant sees only its own roots. But the chunk
pool is shared: a tenant that *knows* another's chunk hash could read
that chunk straight from the `NodeStore`, bypassing the Sail. So the
shared pool is safe for **cooperative** multi-tenancy (one organization,
many ontologies) but **not** for hostile tenants. Hostile multi-tenancy
needs per-tenant `NodeStore`s (back to Model 2) or encryption-above-CAS
— a separate ADR if that requirement appears. ([ADR-0001 §5](0001-provenance-index.md)
flagged the same chunk-layer leakage.)

### 5.5 Backup and fairness

- **Backup:** one chunk pool + the sidecar tree. Back up `chunks/`
  **first**, then `stores/` — sidecars point *into* chunks, so a
  sidecar referencing a not-yet-copied chunk is the failure mode.
  Chunks are append-only immutable, so a chunks-first copy is always
  consistent.
- **Fairness:** one noisy tenant can dominate the shared `NodeCache` or
  inflate the pool. Per-tenant cache partitions / storage quotas are
  punted (§8) but named.

## 6. Module placement

| Piece | Module |
|---|---|
| `SailManager` (registry, lazy-open, LRU, lifecycle) | `prolly-rdf4j` — `com.earasoft.prolly.rdf4j.sail` |
| Multi-root GC / reachability | `prolly-rdf` (`Database` / GC) — extended if not already multi-tenant |
| Per-store REST routing (`/stores/{id}/sparql`) + store CRUD endpoints | `prolly-rdf4j-rest` |

## 7. Implementation plan (sub-iters)

Sequence: MS.1 → MS.2 → MS.3 in order; MS.4 alongside MS.3; MS.5/MS.6
after MS.3; MS.7 throughout; MS.8 last.

| # | Slice | Module | Effort |
|---|---|---|---|
| MS.1 | Audit `prolly-rdf`'s `Database` / multi-tenant layer — does it provide a multi-store registry + multi-root reachability? Decide build-on vs build-new. | prolly-rdf4j | full day |
| MS.2 | Spec the on-disk layout (§3.2): shared `chunks/`, per-store `stores/<id>/{root-head,commits.log,refs/}`. | prolly-rdf4j | half day |
| MS.3 | `SailManager`: create/open/list/delete logical stores over one shared `NodeStore` + shared `NodeCache`; lazy-open with an LRU of resident `ProllySail`s. | prolly-rdf4j | 2 days |
| MS.4 | GC: confirm/extend the reachability walker to union roots across all logical stores; decide pool-wide GC scheduling (§5.1). | prolly-rdf / prolly-rdf4j | 1–2 days |
| MS.5 | Optional read-only union Sail over selected member stores for cross-ontology query (§5.2). | prolly-rdf4j | 1–2 days |
| MS.6 | `prolly-rdf4j-rest`: per-store routing `/stores/{id}/sparql`; store create/list/delete endpoints. | prolly-rdf4j-rest | 1–2 days |
| MS.7 | Tests (§ below). | both | 1–2 days |
| MS.8 | Docs: multi-store guide, granularity guidance (§5.2), the Mobi mapping (§9). | both | half day |

Total ≈ 9–12 dev days — phase-sized; could equally live as `plans/09-multi-store.md`.

### Tests (MS.7)

- **Dedup** — load identical data into two logical stores; assert the
  chunk count of the shared pool is ~1× not 2× (index trees included,
  §4); assert collision-extension chunks are the only divergence.
- **Independent versioning** — branch/commit ontology A; assert
  ontology B's refs and DAG are untouched.
- **GC across tenants** — a chunk reachable from store B is *not*
  collected when store A drops its only reference (§5.1).
- **Lazy-open / LRU** — opening store #(cache+1) evicts the LRU Sail;
  the shared `NodeStore` stays open; re-access re-opens correctly.
- **Teardown** — deleting a logical store removes its sidecars; its
  now-unreferenced chunks are reclaimed on the next GC.

## 8. Open questions

| # | Question | Recommendation |
|---|---|---|
| 1 | Build `SailManager` on `prolly-rdf`'s `Database` or a new registry? | MS.1 decides. Prefer reuse if `Database` gives the registry + multi-root GC. |
| 2 | Pool-wide GC scheduling — one stop-the-world stalls all tenants. | Interim: scheduled off-peak GC. Real fix: the concurrent/generational GC already on the roadmap. |
| 3 | Logical-store granularity — per ontology vs per query domain? | Support both; **default to grouping co-queried datasets** as named graphs in one store; document the branching-vs-query tradeoff (§5.2). |
| 4 | Hostile multi-tenancy? | Out of scope — shared pool is cooperative-only (§5.4). Per-tenant `NodeStore`s or CAS-layer encryption is a separate ADR if needed. |
| 5 | Per-tenant cache partitions / storage quotas? | Punt; revisit when a real noisy-neighbour problem appears (§5.5). |

## 9. Relationship to other ADRs

- **This is the substrate the Tier-2 Mobi integration needs.** Each
  Mobi `VersionedRDFRecord` (ontology/vocabulary) → one logical store
  over the shared `NodeStore`; Mobi `Branch` → prolly ref, Mobi
  `Commit` → RootMetaTree commit. The Mobi *system* repository (catalog
  metadata) stays separate — keep it on a local store for transaction
  atomicity.
- Composes with [ADR-0006](0006-commit-log-as-rdf.md) /
  [ADR-0007](0007-void-dataset-statistics-graph.md): each logical store
  exposes its own `urn:prolly:meta:*` virtual graphs.
- Clarifies [ADR-0001 §5](0001-provenance-index.md): `TermId` being a
  content hash is what makes cross-store index-tree dedup work (§4); the
  "repo-local" caveat there is the rare collision-salt case.

---

*Plan version 1. Ready for stakeholder review before MS.1.*
