
# ADR-0046: Distributed architecture — a two-plane model (content replication + ref consensus)

## Status

Proposed, 2026-06-06. Records the intended distribution model and the reasoning that
chose it; **no implementation is committed yet**. When distribution is pursued, the phased
plan `plans/distributed-architecture.md`
sequences it (Phase 1 — async replicas + shard-by-repo — first).

## Context

The store is a **content-addressed, versioned RDF engine** (a prolly tree), today
single-node. Distribution — high availability, horizontal scale, geo-replication — is the
single largest capability multiplier, and the inter-node transport (the gRPC versioning
service) is being built now, so the **consistency model must be decided before the wire
protocol and storage contracts harden** (both are expensive to change once nodes depend on
them).

A content-addressed store distributes **fundamentally differently** from a general SQL
database, and that difference is the whole decision. The data splits into two planes:

- **Content plane** — the chunks/nodes of the tree. **Immutable, hash-addressed.** It can
  replicate with **no coordination**: any node serves any chunk it holds, and the receiver
  verifies it by re-hashing. No consensus, no conflicts, ever. This is nearly all of the
  bytes.
- **Ref plane** — the branch → commit-hash mapping. The **only** mutable state, and already
  a **compare-and-set on a 20-byte hash** (`RefsStore.compareAndSet` /
  `RemoteRepository.compareAndSetRef`). This is the only state that ever needs coordination.

What already exists to build on: a git-style sync engine (`SyncEngine`, `PackBuilder`,
`HttpRemoteRepository`, the `/sync/{refs,fetch,push,pull}` endpoints) that already does
content-addressed replication and ref compare-and-set between repositories; per-repo
isolation (a natural sharding unit); and the merge-request / merge engine (a ready-made
conflict-resolution model for divergent branches).

The question this record answers: **what consistency and replication model do we commit
to**, given that nearly all of the data needs no coordination at all?

## Options

| Option | Consistency | What consensus carries | Per-write cost | Complexity (order, intuition) | Content-addressing fit |
|---|---|---|---|---|---|
| **A** — generic shared-nothing (consensus on data ranges, à la Spanner/CockroachDB) | linearizable everywhere | full write-sets | every write pays consensus | very high (~150–300k) | **poor** — pushes immutable, already-replicable data through consensus |
| **B** — async git-style replication only | eventual (merge-resolved) | nothing | local write + async push | low (extends the sync engine) | excellent |
| **C** — **two-plane: content replicates freely + ref compare-and-set via consensus** | **linearizable on refs (opt-in per repo), eventual content** | a 20-byte ref hash | local chunk write + ref consensus | moderate, phased (~40–80k) | excellent |

## Decision

Adopt **Option C — the two-plane model** — with async replication and ref-consensus as two
*modes* over one shared content plane.

- **D-1 — The two-plane separation is the foundational contract.** Every distribution
  mechanism is defined in terms of (a) the **content plane** — immutable, hash-addressed
  chunks that replicate without coordination and are verified by hash — and (b) the **ref
  plane** — the mutable branch → commit-hash compare-and-set, the sole coordinated state.
  No mechanism may blur these.

- **D-2 — Consensus carries refs, never data; a ref advances only over durable content.**
  A distributed commit is two steps in order: **(1)** place the commit's chunk closure in
  the content plane, **durably stored to the content plane's durability quorum** (so it
  survives node loss and is fetchable), then **(2)** propose the ref compare-and-set to the
  consensus group. The invariant: *a ref may never point at a commit whose chunk closure is
  not yet durable.* Readers fetch missing chunks on demand from any holder (content is
  globally addressable); anti-entropy backfills replicas lazily. Consensus orders 20-byte
  ref updates — it is never on the data path.

- **D-3 — Two consistency modes, selectable per repo.**
  - `async` (default): the existing git-style replication — eventually consistent, divergent
    branches resolved by **merge** (the merge-request model). This is the **available /
    partition-tolerant** choice: for geo-replicas, edge/offline, read-scale, collaboration.
  - `consensus`: the ref compare-and-set runs through a consensus group — **linearizable**
    branch updates. This is the **consistent** choice: for a repo that is a system of
    record. A per-repo choice of where on the consistency/availability tradeoff to sit is
    unusually powerful, and is *enabled by* D-1 (the content plane is identical either way).

- **D-4 — Shard by repo; within-repo sharding is out of scope (a future ADR).** Repos are
  isolated, so the unit of distribution is the repo: consistent-hash repos onto
  nodes/groups. **Multi-tenancy is the sharding key** — near-free horizontal scale for the
  many-repos workload. Sharding a *single* repo's tree across nodes (and distributed query
  across shards) is a separate, much larger decision deferred to its own ADR.

- **D-5 — The consistency programming model is unchanged from single-node.** Ref-consensus
  is distributed **optimistic concurrency control**: a commit names its expected parent and
  succeeds iff the branch still points there — exactly today's `expectedParent`
  compare-and-set, now agreed by a quorum. Racing writers see one winner and one retry, as
  they do single-node. Callers learn no new consistency model.

- **D-6 — Reuse, don't reinvent: an existing Raft library and the existing transport.**
  Consensus uses a vetted JVM Raft implementation (e.g. Apache Ratis — ratified in a
  follow-on), not a hand-rolled protocol; the value is the two-plane architecture, not
  re-deriving consensus. Nodes talk over the **gRPC versioning service** already being
  built — no second inter-node protocol.

- **D-7 — Correctness is gated on deterministic simulation testing.** No distribution ships
  without simulation of partitions, node failure, and message reorder, extending the
  existing `prolly-dst-*` harness (plus the Lincheck / jcstress concurrency rigs). The
  two-plane split is what makes this tractable: content-plane replication is deterministic
  (hash equality), so the only genuinely hard property to simulate is the ref group's Raft
  safety.

## Consequences

**Positive:**
- **Strong consistency, cheaply.** Linearizable branch updates without data on the consensus
  path — consensus moves 20-byte hashes. This is the central payoff of content-addressing.
- **The content-addressing advantage is preserved**, not discarded (Option A's failing).
- **Available and consistent modes coexist** over one storage substrate, chosen per repo.
- **Multi-tenancy gives sharding for free**, and the existing sync engine + gRPC transport
  + merge engine are reused rather than rebuilt.
- **Bounded blast radius** — the expensive within-repo distributed-query problem is excluded
  by D-4, so Phases 1–3 deliver a strongly-consistent, geo-replicated store without it.

**Negative / cost:**
- **Two consistency regimes** to document and operate; operators must understand the per-repo
  available-vs-consistent choice.
- **The durable-content-before-ref invariant (D-2) is load-bearing and must be enforced** —
  a ref committed over chunks that are *not* durable is a dangling pointer / data loss. The
  write path must confirm content durability to quorum before the ref step.
- **Per-branch write throughput is serialized** through one ref group — but this *matches*
  the inherent serialization of a versioned branch (each commit's parent is the prior head),
  so it adds no bottleneck a single node didn't already have; parallelism comes from
  branches/repos = parallel groups.
- **No cross-repo transactions** — each repo is its own consistency domain (see Punted).
- **Deterministic simulation testing is a major, non-optional investment.**

**Punted (each a future decision):**
- Within-repo sharding + distributed query across shards (its own ADR; the large item).
- Cross-repo / cross-shard atomic commits (two-phase commit over ref groups) — only if a
  real need appears.
- The disaggregated content plane's backing — shared object storage vs peer replication
  (D-2 admits either; an operational choice, ratified later).

## Follow-up / future work

- **The phased plan** (`plans/distributed-architecture.md`):
  Phase 1 async replicas + shard-by-repo (high value, low cost, extends the sync engine) →
  Phase 2 disaggregated content plane → Phase 3 ref consensus → (deferred) Phase 4 within-repo
  sharding. A single-primary / async-follower step is a legitimate stepping stone to Phase 3
  (strong consistency without a quorum, at the cost of automatic failover).
- **A consensus-library selection ADR** (Apache Ratis vs alternatives).
- **A within-repo-sharding ADR** if a single tenant ever outgrows one node.
- **A deterministic-simulation-testing-for-distribution plan** (extends `prolly-dst-*`).

## Open questions

- **Q1** — Which Raft library, and one group per repo vs one group per shard-of-repos (group
  count vs failure-isolation tradeoff)?
- **Q2** — Content-plane durability mechanism: replicate chunks peer-to-peer to a replication
  factor, or back the plane with shared object storage (simpler durability, network cost on
  read-miss)? D-2 holds for either; the choice is operational.
- **Q3** — Switching a repo between `async` and `consensus` mode: is it online, and how is a
  divergent async history reconciled when promoting to consensus (presumably a forced merge
  to a single head before the ref group takes ownership)?
