---
id: ADR-20260904-c4e2
---

# ADR-0078: Commit write amplification — group commit before a persistent delta layer

## Status

**Proposed, 2026-09-04.** No implementation is committed. Depends for its evidence on
`NcitVersioningBenchmark`, which already measures the metric this record turns on.

## Context

`NcitVersioningBenchmark` established the good news and the bad news together. Cross-commit
structural sharing works: the N-th "monthly release" commit costs the same as the first (~700 ms,
~6,500 new chunks at 150k base), independent of accumulated history — the thing no other Sail can
offer. But an *individual* commit rewrites ≈ the whole touched tree, the per-commit price decomposes
as `dict(~8%) + 4 x permutation(~23% each)`, and **batching edits by subject recovers only ~8%**: it
localizes SPOC while POSC/OSPC/CSPO scatter the same triples by predicate, object and context.

Three facts close off the obvious escapes:

- **Term identifiers cannot be chosen for locality.** `TermId.ofNatural` is
  `new TermId(hash & NATURAL_MASK)` (`prolly-codec/.../term/TermId.java:69`) — hash-derived and
  uniformly distributed. That uniformity is load-bearing: it is what makes two independently-built
  trees share chunks. Locality and content-addressed determinism pull against each other here.
- **Within-commit parallelism is already harvested.** `ProllySailConnection.commitInternal` builds
  all seven trees concurrently (`COMMIT_CONCURRENCY`, ForkJoin common pool by default). There is no
  easy concurrency left inside a commit.
- **Bigger batches are already shipped.** `prolly.rdf4j.import.batch-size` (default raised 50k→250k,
  with a per-request `?batchSize=` override) is ADR-0061's option 1.

### The shape of the cost — derived, and the reason the fix is what it is

Not measured; stated so it can be checked against the benchmark below:

```
bytes_written  ~  (distinct chunks touched) x chunk_size x num_indexes
               ~  N_triples x tree_depth x ~6 KiB x 4          [sparse regime]
```

bounded above by rewriting the whole tree. Two regimes follow, with the crossover where touched
triples approach the leaf-chunk count:

| commit size (TB-scale corpus) | rewritten |
|---|---|
| ~1,000 triples | ~100 MB — each touch pays for its *own* leaf **and its own ancestors** |
| ~0.2% of the corpus | every leaf chunk is touched; ≈ whole tree x 4 |

**The asymmetry is the finding: amplification per triple is worst for the smallest commits**, because
a 1,000-triple commit rewrites ~1,000 separate upper-level chunks that a 1,000,000-triple commit
would have shared. Small frequent commits are therefore the hostile case, and the fix is not to make
writes cheaper — it is to **make the effective batch large while keeping the user-visible commit
small**.

### What already exists that the fix can stand on

The read path already merges an overlay over the trees, including tombstones.
`ProllySailStagingModelTest` pins it as a property over interleavings: the connection's
**per-transaction staging overlay** is the read-your-writes buffer that `addStatement` /
`removeStatement` write into and `commit` flushes, with a regime explicitly covering last-write-wins
and tombstone-then-re-add. Merge-on-read is built and property-tested; it is merely scoped to one
transaction and held in memory.

`CommitLog` and `RefsStore` are already file-backed.

## Options

| Option | Per-commit cost | Read cost | Preserves versioning story | Work |
|---|---|---|---|---|
| **A** — status quo (bigger batches) | O(scatter x depth x 4) | unchanged | yes | none — shipped |
| **B** — **group commit**: durable in `CommitLog`, fold into trees on a threshold | amortized over the group | unchanged | yes, if version identity is decoupled from tree materialization | low–moderate |
| **C** — **persistent multi-level delta layer** (extend the staging overlay); background compaction | O(delta) | +1 probe per level, bloom-bounded | yes — deltas are ordinary content-addressed chunks | high |
| **D** — drop 4 permutation indexes to 2 | halved | loses OSPC/CSPO seeks | yes | low |
| **E** — per-tree chunk-size taper (small chunks for churn-heavy indexes) | ~6x lower on retuned trees | worse scan locality on those trees | yes | moderate; taper is prolly-core's |
| **F** — locality-preserving TermIds (namespace in the high bits) | lower *only* for multi-vocabulary corpora | unchanged | yes — still deterministic | high; on-disk format migration |

## Decision

**Sequence the work behind measurement; do not build C first.**

- **D-1 — Measure before building.** `NcitVersioningBenchmark [base] [releases] [churn]
  [mode=scattered|clustered]` already reports **net new chunks written** — exactly this metric, with
  the churn-locality knob. Run it at increasing `base` to locate the sparse→dense crossover and
  confirm the per-touch constant derived above. The derived figures in this record are hypotheses
  until this runs.

- **D-2 — Group commit (Option B) is the first build.** Accept writes into the existing `CommitLog`,
  acknowledge on WAL durability, and fold into the trees on a size or time threshold. This
  **decouples version identity from tree materialization**: a commit gets its hash and its durability
  immediately, while its tree root is materialized lazily. It delivers small-frequent-commit
  semantics at large-batch cost for materially less work than Option C. The semantic question it
  turns on — whether every commit needs an individually *queryable* root, or only an identity plus
  the ability to reconstruct one — is answered per deployment, and for the release-cadence workloads
  this store targets the answer is the latter.

- **D-3 — Option C only if the measurement still demands it.** A persistent multi-level overlay is
  the general answer and it fits the architecture (deltas are content-addressed, every commit keeps
  an immutable root, structural sharing and time-travel are untouched, ADR-0046's two-plane model is
  unchanged). But it costs read amplification, compaction scheduling, and multi-level roots through
  `MergeEngine` and the diff path. Build it when D-1 shows group commit is insufficient, not before.

- **D-4 — Option D is rejected, and the coupling is the reason.** Halving the indexes halves
  amplification and simultaneously removes the multi-order seekable access the leapfrog triejoin
  requires — the join that survives distribution ([ADR-0079](0079-scaling-a-single-graph.md), D-3).
  Index count is a joint decision with the distributed query strategy and must not be taken here on
  write-cost grounds alone.

- **D-5 — Option E is applied asymmetrically or not at all.** Shrinking chunks everywhere would erode
  the measured scan advantage (29–54x over rdf4j-native), which is the headline read result. If taken,
  keep scan-heavy SPOC coarse and retune only the churn-heavy permutations. The taper is hard-coded in
  prolly-core's `RollingHashSplitter.rollingHashPattern`, so this needs that repository's agreement.

- **D-6 — Option F is parked.** It attacks the ~8% result directly and stays deterministic, but it
  fails on the flagship corpus: NCIt is effectively single-namespace, so every term lands in one
  cluster and nothing improves. It would help only multi-vocabulary graphs and costs a format
  migration.

## Consequences

- **Positive.** The cheapest option that addresses the actual complaint is also the first one built,
  and it reuses `CommitLog` rather than introducing a storage concept.
- **Negative.** Group commit weakens "every commit is instantly queryable at its own root" to "every
  commit is instantly durable and identified." That is a user-visible semantic change and must be
  stated in the API contract, not discovered.
- **Unresolved.** Whether the per-touch constant derived above survives measurement (D-1). If the
  crossover sits much lower than estimated, Option C moves up the queue.
- **Not addressed here.** The single-writer ceiling (`ProllySail.writeLock` is a `Semaphore(1, true)`)
  is orthogonal — it bounds concurrency, not amplification. See [ADR-0079](0079-scaling-a-single-graph.md) D-4.
