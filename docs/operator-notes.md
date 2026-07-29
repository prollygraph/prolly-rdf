# Operator notes — running the RDF ring

Operator-facing notes for whoever owns the process that embeds this Sail:
what it puts on disk, where its memory goes, and how to reason about a workload
before blaming the store.

**This ring is a library, not a server.** You deploy your own application (or a
server built on this Sail); these notes describe what you inherit by embedding
it. The engine-level companion is the
[engine ring's operator notes](https://github.com/prollygraph/prolly-core/blob/main/docs/operator-notes.md);
the developer-facing map is [developer-skill-sets.md](developer-skill-sets.md).

## What a triple costs you on disk

Two facts dominate capacity planning, and both are design choices rather than
accidents:

- **Four index permutations.** The versioned Sail maintains SPOC, POSC, OSPC,
  and CSPO so that any triple pattern has a covering order. That is roughly
  4× the index work of a store that keeps two — which is exactly why a
  like-for-like comparison against a two-index engine should normalise by index
  count before drawing conclusions.
- **Terms are interned through a dictionary.** Triples store fixed-width term
  identifiers, not repeated strings, so a graph with long shared IRIs compresses
  well; a graph of unique literals does not.

History is stored by **structural sharing**: a commit writes only the chunks its
edits touched. Disk growth tracks churn, not the number of versions.

## Memory

Everything in the engine ring's memory section applies — heap is not the whole
story, and resident set is the number that matters. Two additions specific to
this ring:

- **Ingest is the heavy phase.** Bulk loading holds an in-transaction buffer
  proportional to the *transaction*, not to the dataset, so the operator lever is
  commit batch size. One enormous transaction is the reliable way to exhaust
  memory; batched commits bound it.
- **Query working set is dominated by results, not the store.** A projected
  query returning millions of rows materialises those rows. If a query kills the
  process, check the result cardinality before suspecting the storage layer — a
  `COUNT` wrapper around the same pattern will tell you in one run.

## Choosing between the two Sails

This ring ships both a **versioned** Sail and a **flat** one over the same
RocksDB. They are not competing implementations of one thing; they trade
different axes:

- Versioned: commits, branches, diff, merge, time-travel. Pays for it in write
  amplification and in the four indexes.
- Flat: no history, straightforward key-value writes, lower ingest cost.

If your workload never asks a historical question, the versioned Sail's cost buys
you nothing. That is an operational decision worth making deliberately rather
than by default.

## Query behaviour worth knowing

- **Cyclic patterns route to a worst-case-optimal join; acyclic ones do not.**
  A triangle-shaped pattern takes the leapfrog triejoin; star and single-pattern
  shapes stay on the standard bind-join, because that is faster for them. The
  routing is automatic, and there is a kill-switch property if you ever need to
  force the bind-join everywhere.
- **The advantage is asymptotic, not constant.** On small cyclic graphs a mature
  engine's constants can beat this one; the triejoin's win shows up as data
  scales. Benchmark at *your* size before concluding anything.

## Backup and upgrade

A cold copy of the store directory is a valid backup — chunks are immutable and
content-addressed, so there is no log-replay step to get wrong. Stop writes,
copy, resume.

The project is **pre-1.0 with no backwards-compatibility guarantees**: formats
are deterministic and internally consistent, and they change freely between
versions. The upgrade procedure is therefore back up, upgrade, verify, and keep
the backup until the verification passes. There are no migration shims and none
are planned before 1.0.

## Before you file a performance problem

Two habits that save time, learned here the hard way:

1. **Name the layer that binds.** CPU, memory, input/output, or coordination —
   an optimisation aimed at the wrong layer does nothing at best. Profile before
   attributing.
2. **Check the instrument.** Per-request authentication, an enormous result
   payload, or an offered load beyond capacity have each, in this project's own
   benchmarking, produced numbers that looked like storage findings and were not.
