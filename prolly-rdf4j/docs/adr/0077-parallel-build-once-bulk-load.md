---
id: ADR-20260904-a1b7
---

# ADR-0077: Parallel build-once bulk load — partition independence as the gate

## Status

**Proposed, 2026-09-04.** Extends [ADR-0061](0061-bulk-load-writer.md) (build-once bulk writer,
Phase 2 directed): ADR-0061 decided to *build once*; this record proposes to **build once in
parallel**, and identifies the invariant that makes it safe. No implementation is committed.

The load-bearing mechanism lives in **prolly-core**, not this repository:
`dolthub-java-port/src/main/java/com/dolthub/prolly/RollingHashSplitter.java` (checkout
`~/git/dolthub`). Only the bulk writer that drives it is ours.

## Context

Bulk ingest is the ceiling on corpus size, and it is measured, not assumed
(`docs/benchmarks/ncit.md`): the whole NCIt file (10.77M triples) loads in **~175 s on flatsail**
and ~167 s on rdf4j-native, while **ProllySail does not single-pass-load it in 10 minutes**.

Extrapolating flatsail's ~61,500 triples/s to a terabyte-scale corpus — *derived, not measured*: at
its measured ~64 bytes/triple a TB is ~15.7B triples, so a single-node load is **~3 days**. That is
for the *unversioned* store; ProllySail is the one that must actually do it.

ADR-0061 localized the wall (the per-commit spine re-walk) and directed a build-once writer, pinned
by `BulkLoadHistoryIndependenceTest` — build-once and batched yield byte-identical dict + index
roots. What ADR-0061 does not address is that build-once is still **single-threaded**: it removes
the re-walk but leaves one core building the whole tree.

### The opportunity, and why it is not trivial

A build-once pass over sorted input looks embarrassingly parallel: partition the key range, build
disjoint leaf runs concurrently, assemble upper levels bottom-up. Content-defined chunking is what
usually makes this safe, and the design already banks on that property —
`RollingHashSplitter.java:39` states that content-defined boundaries plus per-level salts are
"what lets two independently-built trees share chunks."

**But the boundary decision here is not purely content-local.** `rollingHashPattern` is:

```java
private int rollingHashPattern(int offset) {
    int shift = 15 - (offset >> 10);
    return (1 << shift) - 1;
}
```

`offset` is the distance since the *previous* boundary, and `reset()` zeroes both `offset` and the
`BuzHash`. So the split probability is tapered by phase — ~1/32768 per byte below 1 KiB, rising to a
forced split at `MAX_CHUNK_SIZE` (16 KiB). Two workers that start at arbitrary points are out of
phase and **will produce different chunk boundaries, hence a different root**. Naively partitioning
the input and concatenating the results is silently wrong.

### The property that rescues it

Convergence is nonetheless guaranteed once two streams agree *once*:

- At a shared boundary both call `reset()` — `offset = 0`, `bz.reset()` — at the same absolute
  position in the stream.
- The first boundary test is gated at `MIN_CHUNK_SIZE` (512), which exceeds `WINDOW_SIZE` (67).
- So by the time either stream tests a boundary again, both have consumed the same bytes into the
  same window and hold identical `offset` and identical hash state.

**Agreement at one boundary implies agreement at every subsequent boundary.** A worker can therefore
start reading Δ bytes *before* its assigned range, chunk forward, and discard everything up to the
first boundary at or after its range start — which is provably the boundary its left-hand neighbour
ends on. Δ of a few multiples of `MAX_CHUNK_SIZE` makes convergence essentially certain (the
probability of not resynchronizing decays geometrically), though it is not bounded in the worst
case.

## Options

| Option | Ingest wall (TB, derived) | Correctness risk | Work |
|---|---|---|---|
| **A** — build-once, single-threaded (ADR-0061 as directed) | ~3 days (flatsail rate; ProllySail worse) | none beyond ADR-0061 | none — already directed |
| **B** — partition input, build ranges independently, concatenate | ~3 days ÷ N | **unsound** — phase mismatch yields a different root, silently | low |
| **C** — **partition with overlap-and-resynchronize, gated by a partition-independence test** | ~3 days ÷ N | sound, given the gate | moderate |
| **D** — restructure the splitter to be phase-free (drop the `offset` taper) | ~3 days ÷ N | changes chunk-size distribution and every existing hash — a format break | high, and prolly-core's call |

## Decision

**Adopt Option C**, subject to its gate.

- **D-1 — Parallel build-once uses overlap-and-resynchronize.** Each worker begins Δ before its
  range and discards chunks until the first boundary at or after its range start. Δ is configurable
  and defaults to a small multiple of `MAX_CHUNK_SIZE`.

- **D-2 — The gate is a `BulkLoadPartitionIndependenceTest`,** the sibling of ADR-0061's
  `BulkLoadHistoryIndependenceTest`: **building from K disjoint ranges yields byte-identical dict and
  index roots to building from 1**, over a range of K, Δ and input distributions. Parallel bulk load
  does not ship without it green. This is the whole of the safety argument; Option B is exactly
  Option C without it.

- **D-3 — Option D is not pursued from this repository.** The `offset` taper is prolly-core's, it
  shapes the chunk-size distribution, and changing it invalidates every stored hash. If it is ever
  revisited it needs prolly-core's own ADR.

## Consequences

- **Positive.** Turns the TB ingest wall from a single-core wall into a scale-out job, on top of
  machinery ADR-0061 already directed. Nothing about the on-disk format changes: a parallel build
  produces the *same bytes* as a serial one, which is precisely what D-2 asserts.
- **Negative.** Δ is a heuristic, not a bound — resynchronization is overwhelmingly likely but not
  worst-case guaranteed, so D-2's test must cover adversarial inputs, and the writer needs a fallback
  when a worker fails to converge within its overlap.
- **Cost not removed.** Parallel build does not remove the external sort the input must arrive in;
  that becomes the next bottleneck and is not costed here.
- **Unverified.** The ~3 days figure is derived from flatsail's rate, not measured on ProllySail,
  which cannot yet complete the load that would produce it.
