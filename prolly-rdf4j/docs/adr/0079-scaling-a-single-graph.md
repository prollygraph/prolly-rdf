---
id: ADR-20260904-9f56
---

# ADR-0079: Scaling a single graph beyond one node — caching before sharding

## Status

**Proposed, 2026-09-04.** This is the record [ADR-0046](0046-distributed-architecture-two-plane.md)
anticipated: its Phase 4 (within-repo sharding + distributed query) is deferred and "gets its own ADR
if a single tenant ever outgrows one node." No implementation is committed. ADR-0046's two-plane
contract is unchanged by everything below.

## Context

ADR-0046 shards **by repo**, which scales the number of graphs. It does nothing for one graph that
outgrows a node, and the phased plan defers that case entirely (Phase 4, Step 12).

The reflex is that "one huge graph" means Phase 4. That is the wrong first move, and the reason is
already written into the plan's **Phase 2 — Disaggregated content plane**, which is *not* deferred:
Step 5's `ContentStore` abstraction over the chunk plane, Step 6's fetch-on-miss, Step 7's
anti-entropy. The plan states the enabling property itself — *"content-addressing makes this coherent
with no cache-invalidation (chunks are immutable)."*

**With fetch-on-miss, a node serves a graph larger than its local disk.** The binding constraint
becomes **working set, not total size** — the disaggregated-storage-with-local-cache architecture,
where immutability removes the invalidation logic that is normally the hardest part. Phase 4 becomes
necessary only when the *working set* or the query CPU exceeds one node, which is a materially higher
threshold than total size.

### Sizing, if Phase 4 is reached — derived, not measured

Scattering chunks by hash would make a root→leaf descent cost O(depth) network round trips, which is
fatal for point lookups measured in single-digit microseconds. Geometry fixes it. Assuming a
terabyte corpus, one permutation index at ~23% of it (~230 GB), the splitter's tapered chunk sizes
(`MIN_CHUNK_SIZE` 512 B, `MAX_CHUNK_SIZE` 16 KiB, mostly 4–9 KiB, call it ~6 KiB) and ~120 entries
per internal node:

| level | chunks | size |
|---|---:|---:|
| leaves | ~38M | ~230 GB |
| L1 | ~317K | **~1.9 GB** |
| L2 | ~2.6K | **~16 MB** |
| L3 | ~22 | — |

**L2 and above is ~16 MB — replicate it on every node.** L1 is ~1.9 GB per index (~7.6 GB for four),
which any node can hold. Then a lookup costs **exactly one remote fetch**. The price is honest and
should be quoted rather than discovered: ~0.1–0.5 ms against 3–9 µs local, so ~50–100x on point
lookups. Scans are largely unaffected — leaves stream in bulk and prefetch.

### Which join survives a network — the measurement already exists

The RocksDB `PerfContext` campaign counted user-key comparisons on the cyclic triangle: flatsail
**213,176**, ProllySail's bind-join **33,199**, the leapfrog triejoin **84**. Read as *boundary
crossings*, that is also a prediction about distribution: a bind-join at ~33k remote seeks is not
viable over a network; a triejoin at 84 is unaffected. The corroborating wall-clock is on a real
power-law graph (`RealGraphTriangleBench`, wiki-Vote, 103k edges, all engines agreeing on 131,925
results): triejoin 3.2 s, rdf4j-native 10.4 s, ProllySail bind-join 36.8 s, flatsail 46.4 s.

The same data names the hazard: real degree skew is what makes the bind-join's intermediates explode
through hub nodes, and hub nodes are exactly what defeats hash partitioning.

## Options

| Option | Holds a TB graph | Point-lookup cost | Query CPU scales | Work |
|---|---|---|---|---|
| **A** — status quo (one node, local disk) | no — bounded by local disk | 3–9 µs | no | none |
| **B** — **Phase 2 disaggregated content plane + local cache** | yes, while *working set* fits | unchanged when warm; one fetch on miss | no | already planned, not deferred |
| **C** — Phase 4: shard the tree by key range, replicate the spine | yes, unbounded | ~0.1–0.5 ms (one hop) | yes | high — the generic distributed-database problem |
| **D** — shard by repo (ADR-0046 Phase 1) | **no** — does not split one graph | unchanged | across graphs only | already planned |

## Decision

- **D-1 — Option B is the answer to "one huge graph"; Option C is not.** Capacity for a single
  large graph is a **caching and bulk-build problem before it is a sharding problem**. Phase 2 is
  therefore on the critical path for large single tenants, and Phase 4 stays deferred until the
  *working set* or query CPU — not the corpus size — exceeds one node.

- **D-2 — If Phase 4 is reached, replicate the spine and shard the leaves.** Levels L2 and above are
  replicated to every node; L1 is cached per node; only leaves are partitioned. The design target is
  **one remote fetch per lookup**, and the ~50–100x point-lookup regression is an accepted, quoted
  cost of distribution rather than a regression to be discovered later.

- **D-3 — The distributed join is the leapfrog triejoin, and this constrains index count.** Its 84
  boundary crossings against the bind-join's 33,199 is what makes it viable over a network. The
  triejoin needs seekable sorted access in multiple variable orders, so it **requires the four
  permutation indexes**. Any proposal to cut them to two on write-cost grounds
  ([ADR-0078](0078-commit-write-amplification.md), Option D) is a joint decision with this one and is
  rejected there for this reason.

- **D-4 — The single-writer ceiling is broken by CAS-rebase, not by sharding.** `ProllySail.writeLock`
  is a `Semaphore(1, true)`, and the current contract is "first to commit wins; a later connection
  must re-begin … no compare-and-set rebase yet" (ADR-0061). Replication does not touch this. Have the
  losing writer **rebase its diff onto the new root** via the existing `MergeEngine`
  ([ADR-0009](0009-canonicalizing-rdf-merge.md)) instead of re-beginning; disjoint edits — the common
  case on a large graph — always succeed. This preserves the optimistic-concurrency contract the
  distribution plan already commits to keeping (`compareAndSetRef` semantics unchanged). **Confirm the
  workload needs concurrent writes before building it**: the versioning benchmark's regime is monthly
  releases, and for release-cadence publication a single writer is not a constraint at all.

- **D-5 — Skew is mitigated by heavy-hitter replication, and it is measurable now.** Power-law hubs
  belong on every shard; they are few in count and large in degree, so replicating their adjacency to
  all shards is cheap. wiki-Vote is already in the bench suite and is exactly this shape, so this can
  be measured rather than assumed.

## Consequences

- **Positive.** Redirects effort from the largest, most generic piece of work (Phase 4) to one already
  scheduled (Phase 2), and records the geometry and join choice so Phase 4 starts from a design rather
  than a blank page.
- **Negative.** Option B's guarantee is conditional on working-set locality. A genuinely uniform random
  access pattern over a TB graph degrades to one network fetch per lookup with no cache benefit, and
  nothing here rescues that case.
- **Unverified.** Every figure in the sizing table is derived from the splitter's constants and the
  measured 23%-per-permutation split, not measured on a corpus of that size — no such corpus has been
  loaded ([ADR-0077](0077-parallel-build-once-bulk-load.md) is what would make loading one feasible).
- **Ordering.** ADR-0077 (parallel bulk load) precedes all of this: a graph that cannot be loaded
  cannot be served, distributed or not.
