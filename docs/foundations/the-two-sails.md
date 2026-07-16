---
tags:
  - architecture
  - storage
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/the-two-sails.md; links + citations adapted to this repo's layout -->

# The two Sails: versioned `ProllySail` and flat `RocksDbFlatSail`

*Why this repo ships two RDF4J Sails, why they share one codec module but diverge in two others, and how the storage substrate alone explains which one wins which query.*

> **What you'll learn** — that `prolly-port` has *two* RDF4J Sails (one versioned,
> one not) and why that isn't redundancy; what they genuinely **share**
> (`prolly-codec`) versus where they **diverge** (the key codec and the
> dictionary); the two root causes of that divergence — the storage engine's
> **comparator** and the **versioning model**; and the measured performance
> sweet-spots that fall straight out of those choices (and which one to reach for).
>
> _Reading time: ~10 minutes._

> **Prerequisites** —
> [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) (content-addressing; why key *order*
> shapes the tree), [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) (`Tuple`, the
> `Int64` column), [the-termid-ordering-trap](the-termid-ordering-trap.md)
> (what a `TermId` is). Module layout: [the module table](../../README.md#modules).

## Why there are two

`ProllySail` (in `prolly-rdf4j`) is the project's **versioned** RDF store: every
commit is a content-addressed prolly-tree root, so you get git-like branches,
commits, and time-travel over your triples. That machinery isn't free — and a
lot of RDF workloads don't need history at all. So the repo also ships
`RocksDbFlatSail` (in `prolly-flatsail`): the **same** RDF4J Sail API, but
keys live as plain sorted bytes in RocksDB with **no versioning**. It exists to
be a fast, simple baseline where you'd otherwise reach for an off-the-shelf
triplestore.

They are not two implementations of the same thing fighting for the same job.
They sit at different points on a deliberate trade: *versioned-and-history-aware*
vs *flat-and-fast-for-point-work*. The rest of this doc is about how one design
axis — **where the keys are stored, and whether the store is versioned** —
predicts everything else, including the divergent codecs.

## What they share: `prolly-codec`

The module `prolly-codec` was split out precisely so the flat Sail could reuse
the substrate-agnostic primitives **without dragging in the versioned engine**.
Both Sails share:

- `TermId` — the 64-bit id an RDF term encodes to
  (`prolly-codec/.../term/TermId.java`); `TermId.ZERO` is the default-graph
  sentinel in *both* Sails.
- `SpocKey` / `QuadOrder` — the four-column permutation key model (SPOC, POSC,
  OSPC, CSPO) in `prolly-codec/.../index/SpocKey.java`.
- The order-preserving *value-encode* half (turning a `Value` into sortable
  bytes).

That shared layer is genuinely common. Everything below it diverges — and the
divergence is forced, not arbitrary.

## Divergence #1 — the key codec (driven by the comparator)

The two stores compare keys with completely different machinery, so they need
different key bytes.

- **`ProllySail`** keeps keys in a **prolly-tree `StaticMap`**, compared by
  `TupleDescriptor.compare` over the Dolt **`Tuple`** format (a value region plus
  an offset table and a count footer; fields compared by their declared type).
  A `SpocKey` *is* a `Tuple`.
- **`RocksDbFlatSail`** keeps keys in **RocksDB**, compared by its raw
  **bytewise** comparator. So `FlatKeyCodec` produces a fixed **32-byte key —
  four 8-byte `TermId` columns, big-endian**. Big-endian is deliberate, and the
  class says why:

  > *"`TermId` orders by `Long.compareUnsigned` … the big-endian bytes of an
  > unsigned 64-bit value sort lexicographically in exactly that order. So
  > RocksDB's natural byte-key ordering matches `TermId` ordering, and a byte
  > prefix of a key is a column prefix in `TermId` space."*
  > — `FlatKeyCodec`

No `Tuple` framing: RocksDB doesn't need an offset table because it never
interprets the key — it just compares bytes. ProllySail's tree *does* interpret
fields (per-type compare), so it carries the framing.

## Divergence #2 — the dictionary (driven by versioning)

- **`ProllySail`'s `Dictionary`** is **prolly-tree-backed**: its root threads
  through every commit (so a term added in a commit belongs to that snapshot),
  and TermIds are **hash-derived** — the *same term always maps to the same id*,
  across snapshots and repos. Content-addressing and dedup require that
  determinism. (The cost: hash-derived ids don't sort semantically — see
  [the-termid-ordering-trap](the-termid-ordering-trap.md).)
- **`FlatDictionary`** is just **RocksDB column families** (`dict-fwd`/`dict-rev`),
  and TermIds are assigned **sequentially from 1**. Denser, simpler — and there's
  no cross-snapshot determinism to preserve because the store is unversioned.

And the term codec splits for a sharp, documented reason:

> *"Why not reuse the versioned `TermCodec`? Its encode half lives in
> `prolly-codec`, but its decode half … lives in `prolly-rdf4j`'s tree-coupled
> `ProllyValue`/`PrefixTable`. Reusing it would drag the versioned engine into
> the flat Sail — exactly what the `prolly-codec` split set out to avoid."*
> — `FlatTermCodec`

So `FlatTermCodec` owns a simple, fully reversible length-prefixed encoding
instead. **The codecs differ exactly where the substrate or versioning forces
them to, and nowhere else.**

## The performance sweet-spots (the trade, measured)

Those two design choices *are* the performance profile. The third column is
**RDF4J's `NativeStore`** — a mature mmap'd-B-tree RDF store — as the reference.
Indicative benchmark numbers (lower = faster; directional):

| operation | ProllySail | flatsail | rdf4j-native | winner |
|---|---:|---:|---:|---|
| ingest 5k (ms, *synthetic*) | **58** | 89 | 131 | ProllySail † |
| point lookup (µs) | 46 | 7.2 | **2.8** | native |
| predicate scan (µs) | **971** | 3,398 | 42,823 | ProllySail (native 44× slower) |
| full scan (µs) | **1,969** | 6,782 | 45,785 | ProllySail |
| **acyclic** join 10k (ms) | 386 | 100 | **68** | native |
| **cyclic** triangle@380 (ms) | **41** | 85.8 | — | ProllySail (native not measured) |

> **† Synthetic ingest misleads — and the gap grows with scale.** That ingest row is *synthetic*
> dense-core data (a tiny vocabulary). On the **real NCIt ontology** the ranking flips and worsens with
> size: at 100k statements ProllySail is ~1.85× slower, ~2.2× more memory, ~3.4× larger on disk than
> native; and the **whole file (10.8M triples) flatsail + native load in ~3 min (`StreamingNcitIngest`)
> while ProllySail can't single-pass-load it in 10 min** (its per-commit prolly-tree rebuild is
> super-linear — it needs a bulk-load path). Real ontologies' thousands of distinct IRIs/literals + the
> four permutation indexes + content-addressed framing — all hidden by the synthetic toy where ProllySail
> "won." Full numbers: the [NCIt dataset card](../benchmarks/ncit.md) *(the full campaign plan stays in the private work tracker)*.
> Lesson: trust the *real-data, scaled* benchmark.

> **The read rows, by contrast, hold on real data.** On a 500k real-NCIt sample (`NcitReadBenchmark`,
> **2026-05-31 run, pre-optimization defaults**), ProllySail still wins scans (`rdfs:label` scan 29×
> over native; full scan ~2× / ~5× over native / flatsail), and native still wins point lookups and
> the acyclic (subclass→label) join (~15× over ProllySail's bind-join). So real data *corrected* the
> ingest story but *confirmed* the read story — which axis it overturns depends on the operation.
> Full numbers: the [NCIt dataset card](../benchmarks/ncit.md) *(the full campaign plan stays in the private work tracker)*.
> **The 2026-06-13 optimized-default re-measure** (node cache + bind-join memo on) kept every
> ranking but moved the magnitudes: the predicate-scan win *widened* to ~54× (821 µs vs 44,218) and the full scan to ~13× (43 ms vs 561),
> and the probe losses *narrowed* — acyclic join ~15× → ~1.9× behind native (137 ms vs 74), point
> lookup to ~7.5× (36 µs vs 4.8).

> **And the thing only ProllySail can do — versioning — finally measured.** Every row above prices
> versioning as a *cost* (slower ingest, 3–4× disk); `NcitVersioningBenchmark` prices what it *buys* by
> committing successive "monthly releases" of real NCIt. The win is **cross-commit structural sharing**:
> per-commit cost is independent of how much history you've kept — the 6th commit is as cheap (~700 ms,
> ~6,500 new chunks) as the 1st, while flatsail and native retain *zero* history because they overwrite. The
> honest wart: an *individual* commit rewrites ≈ the whole tree — a churn touches ~all leaves across the four
> permutation indexes, and (measured by the `mode=clustered` knob) you can barely improve it: batching edits
> by subject localizes only SPOC, while POSC/OSPC/CSPO scatter the same triples by predicate/object/context,
> so 3 of 4 indexes get no benefit. (TermIds are hash-derived — `TermId.ofNatural` — so you can't localize by
> term choice either.) The per-commit price is intrinsically ≈ (touched-fraction) × (4 trees); what's *free*
> is keeping the history. This is the only benchmark where the other two Sails can't even enter the race.

The unifying rule: **the prolly tree turns many tiny RocksDB operations into a few
chunked ones** (the native binding crossed once per *chunk*, plus an in-heap node cache), so it
wins *bulk* work; flatsail's RocksDB-direct model wins *point* work but pays a native-binding
crossing **per key**. That was inferred for most of this project — then **measured**
with RocksDB `PerfContext` on the cyclic triangle (see
the RocksDB perf-instrumentation campaign, private monorepo work tracker):

> **The numbers** — RocksDB user-key comparisons per query: flatsail **213,176**
> (its RDF keys *are* RocksDB keys), ProllySail's bind-join **33,199** (RDF keys
> live in in-heap nodes; RocksDB only does per-chunk lookups, ~6.4× fewer), the
> [triejoin](the-leapfrog-triejoin.md) **84** (joins in-process — near-zero RocksDB
> boundary). The "per-chunk vs per-key" story, on the scoreboard.

> **Gotcha.** "flatsail is faster at joins" is a half-truth that bites: it holds
> for *acyclic* joins (100 ms vs 386 ms) and inverts for *cyclic* ones, where
> flatsail is the **slowest** engine (85.8 ms vs ProllySail's 41 ms — those 213k
> per-key comparisons). Always say *which* join shape.

> **On a *real* cyclic graph the triejoin's edge is categorical.** The synthetic numbers above
> hint at it; `RealGraphTriangleBench` on wiki-Vote (a real 103k-edge power-law voting graph)
> settles it. The cyclic triangle — all engines agree on 131,925 results — runs in **3.2 s on the
> ProllySail triejoin vs 10.4 s on RDF4J native (3.3×), 36.8 s on ProllySail's bind-join (11.5×),
> 46.4 s on flatsail (14.5×)**; in-memory RDF4J can't even `COUNT` it in 120 s. Real degree skew
> makes the bind-join's 2-path intermediate explode through hub nodes, while the worst-case-optimal join stays near the
> AGM bound. See the [wiki-Vote dataset card](../benchmarks/wiki-vote.md).

## vs RDF4J NativeStore — no engine is uniformly fastest

NativeStore is heavily optimized *for its sweet spots*, not everywhere. It wins
**point lookups** (2.8 µs — a direct B-tree seek vs ProllySail's content-addressed
tree descent) and **acyclic joins** (68 ms — those are just many point lookups).
But it **loses scans by 20–44×** and ingest, and it offers **no versioning**. So
"catch up to native" really means *close the point-access gap while keeping the
bulk + versioning wins native can't match*. ProllySail's point-lookup cost (46 µs)
is partly **fundamental** — the hash→chunk→deserialize indirection is the price of
content-addressing/versioning; flatsail (unversioned, RocksDB-direct) sits closest
to native on point access.

## When to reach for which

- **`ProllySail`** — you need versioning/branches/time-travel, *or* your workload
  is ingest-heavy, scan-heavy, or has cyclic/multi-way joins (graph motifs,
  fraud rings, dependency cycles). The default for "versioned SPARQL."
- **`RocksDbFlatSail`** — no history needed, lean RocksDB-backed, point-lookup- and
  acyclic-join-heavy; the closest of the two to NativeStore on point access.
- **RDF4J `NativeStore`** — point-access-dominated, no versioning, and you don't
  need fast scans. The reference an off-the-shelf deployment would use.

Neither prolly Sail is "the fast one." Each is fast at the shape its substrate
favors, and the codec divergence is just that shape made concrete in bytes.

## Why this is optimized

The substrate split is itself an optimization choice — each Sail trades a different
axis, and the numbers show where.

- **ProllySail writes a whole tree build as one RocksDB batch.** `RocksNodeStore`
  buffers a build's chunks in a thread-local `WriteBatch` and flushes with a single
  `db.write()` — one write-ahead log record + one memtable pass instead of N puts. Chunks are
  content-addressed, so identical content dedups to one key, and a write-populated
  LRU `NodeCache` keeps hot nodes parsed.
- **`RocksDbFlatSail` trades history for linear cost.** No per-commit Merkle
  rewrite, sequential ids, plain sorted indexes — so on the 10.8M-triple NCIt
  whole-file load it sustained **~61k statements/sec** while ProllySail **did not finish
  (>10 min)**: the structural-sharing machinery that makes ProllySail's *versioning*
  cheap is exactly what makes its *bulk ingest* expensive. The two Sails sit on
  opposite ends of that trade by design.
- **The read side flips.** ProllySail's in-heap prolly-tree nodes mean a join does
  per-*chunk* RocksDB lookups, not per-*key* — which is why it wins scans and cyclic
  joins while the flat Sail wins point lookups (the `PerfContext` counts in
  finding-bottlenecks *(private monorepo contributing doc)* settle this).

> **The wart — neither beats RDF4J `NativeStore` on point lookups**, and ProllySail
> can't bulk-load a multi-million-triple ontology in one pass (the per-commit rebuild
> craters; the bulk-load plan *(private monorepo work tracker)* is the fix). Pick the
> substrate that matches the workload's *dominant* shape.

## Where this lives

- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java` — the versioned Sail.
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/RocksDbFlatSail.java` — the unversioned Sail.
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatKeyCodec.java` — the 32-byte big-endian RocksDB key.
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatTermCodec.java` — the flat Sail's self-contained value codec (+ the "why not reuse" rationale).
- `prolly-flatsail/src/main/java/com/earasoft/prolly/flatsail/FlatDictionary.java` — RocksDB-backed, sequential-id dictionary.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermId.java` — the shared id type.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/SpocKey.java` — the shared 4-column permutation key.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/Dictionary.java` — ProllySail's versioned, hash-derived dictionary.

## Where to go next

- [the-termid-ordering-trap](the-termid-ordering-trap.md) — the sharp edge of ProllySail's hash-derived TermIds.
- [the-leapfrog-triejoin](the-leapfrog-triejoin.md) — the worst-case-optimal join that targets the cyclic-join case ProllySail wins.
- finding-bottlenecks *(private monorepo contributing doc)* — the tools (incl. the RocksDB `PerfContext` probe) that produced these comparison numbers.
- [the module table](../../README.md#modules) — where `prolly-codec`, `prolly-flatsail`, and `prolly-rdf4j` sit in the reactor.
