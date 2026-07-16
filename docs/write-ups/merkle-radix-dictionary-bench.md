# Merkle Radix dictionary vs the engine's dictionary — benchmarked

*2026-07-17 · Intel N150 (4× Gracemont), JDK 25, JMH 1.x, 3 forks × 5 iterations, in-memory
stores · `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/dictbench/`*

Could a **Deterministic Merkle Radix trie** (path-compressed, content-addressed nodes — the
structure proven out in Python in the engine repository's
`prolly-python-notebooks/merkle_radix_dictionary.ipynb`) serve as the term dictionary, and how
does it behave against the **real** implementation? The engine's dictionary
(`prolly-codec`'s `Dictionary`) is a different design point entirely: term → id is *computed*
(salted FNV-1a 64 hash with a collision probe), and a single prolly tree keyed by `Int64`
TermId stores id → term bytes.

## What was compared, and the one asymmetry to keep in view

Both arms consume **identical raw term bytes** (no TermCodec anywhere): a 50,000-term
NCIt-shaped corpus — 40k `…/obo/NCIT_C1xxxxx` URIs (one dominant namespace, dense sequential
ids), 2k GO terms, the usual RDFS/OWL property URIs, 8k label literals. Fresh structures per
invocation; lookups probe 1,000 uniformly sampled terms per op against pre-built structures.

**The asymmetry, named up front:** the current dictionary operates on its *production
representation* — serialized prolly chunks (`StaticMap` probes decode tuples from
`MemorySegment`s). The radix candidate operates on **live heap objects** (a `HashMap` of node
records) — the representation a production version would only have *after* deserializing its
pages. The lookup comparison therefore structurally favors the radix arm; a page-serialized
radix would pay decode costs this bench does not measure. The ingest comparison tilts the other
way for the same reason: the current arm's output is durable serialized bytes, the radix arm's
is objects (though it does pay ~55k SHA-256 node digests). These numbers answer *"is the
structure competitive?"* — not *"ship it."*

## Results (50k terms; 15 samples = 3 forks × 5; Welch's t on fork means)

| benchmark | mean ± err | vs current | verdict |
|---|---|---|---|
| `ingestCurrent` (encode ×50k + commit) | 74.6 ± 5.0 ms | — | — |
| `ingestRadixBatch` (sort + canonical build) | **40.4 ± 1.1 ms** | **−45.8%** | t = −30.4, CI excludes 0 — real |
| `ingestRadixInserts` (fold of path-copying inserts) | 173.3 ± 4.8 ms | **+132%** | t = +51.3 — real, and much worse |
| `lookupCurrent` (findTermId ×1k: hash + tree probe) | 1.17 ± 0.13 ms | — | — |
| `lookupRadix` (trie walk ×1k, heap objects) | **0.40 ± 0.01 ms** | **−66%** | t = −13.9 — real (see asymmetry) |
| `lookupRadixSerialized` (walk parsing every node from bytes) | **0.53 ± 0.03 ms** | **−52.7%** | t = −26.8 — real; resolves the asymmetry below |

Per-term: current lookup ≈ 1.17 µs (FNV hash + `StaticMap` descent with tuple decode); radix
walk ≈ 0.40 µs (heap pointer-chasing + byte compares).

## Serialized footprint + depth (`DictionaryFootprintReportTest`, same corpus)

| | bytes | chunks/nodes | vs 2.22 MB raw | mean lookup depth |
|---|---|---|---|---|
| current (chunk bytes written) | 3,108,604 | 660 chunks | **140%** | — |
| radix K=1 (one leaf per key) | 2,373,722 | 55,561 nodes | 107% | 8.5 |
| radix **K=64** (bucket leaves) | **1,158,942** | **5,561 nodes** | **52%** | 7.5 |

*(The first draft reported K=1 at 93% under a leaner leaf layout; the bucket-capable terminal
format — offset table per node — costs ~14 points at K=1. Annotated rather than silently
replaced.)*

The current tree pays TermId keys plus tuple framing *on top of* every term's bytes; the radix
pays 20-byte child addresses but stores every shared prefix once — on this namespace-skewed
corpus the radix is smaller *as serialized structure*. (The Python study's caveat still stands
one level up: a flat sorted blob + a block compressor is ~10× smaller than either — footprint
is not what justifies either structure.)

## Reducing depth: the bucket-leaf experiment (K = 64)

The depth question ("mean 8.5 hops — can it shrink?") got the classic answer implemented and
measured: **bucket leaves** — stop the trie once a subtree holds ≤ K entries and store them as
one sorted, binary-searched node (canonical: the ≤K rule is a function of the key set; fold ≡
batch re-pinned at K ∈ {1, 4, 64}, 180 shuffled trials). Same harness, `@Param` bucketSize,
3 forks, all Welch-checked:

| benchmark | K=1 | K=64 | verdict |
|---|---|---|---|
| `ingestRadixBatch` | 43.6 ms | **18.8 ms** | −56.8% (t = −200); **−74.6% vs the engine** (t = −410) — ~4× |
| `ingestRadixInserts` | 177.7 ms | **289.9 ms** | **+63.2% — WORSE** (t = +46.7) |
| `lookupRadix` (heap) | 0.43 | **0.30 ms** | −30% |
| `lookupRadixSerialized` | 0.54 | **0.40 ms** | −26%; **−60.2% vs the engine** (t = −20.0) |
| footprint | 107% of raw | **52%** | node count ÷10 — the 20-byte-address tax collapses |
| mean depth | 8.5 | 7.5 | **the one number that barely moved** |

Two findings worth more than the wins:

- **Depth was the wrong scent.** On digit-cascade keys (`NCIT_C1xxxxx`), fan-out is ~10, so
  collapsing the bottom saves only ~1 hop — yet lookups got 26–30% faster anyway. The gain came
  from *cheaper hops* (binary search over contiguous arrays, fewer dereferences), not fewer
  hops — the Adaptive-Radix-Tree lesson: optimize hop cost before hop count. Cutting depth
  itself on such keys needs wider branching (2-byte edges → fan-out ~100) or larger K.
- **The bucket reverses the streaming trade.** Every insert now rewrites and re-hashes a K-entry
  bucket — O(K) per insert beats the O(depth) it saved, so streaming got 63% worse while
  everything batch/read/storage improved. K is a knob that trades the write path against every
  other axis; the LSM-style batching layer is no longer optional for streams, it is the design.

## Closing the last axis: batched updates (insertAll)

The streaming loss had a known cause — per-insert path-copying re-hashes O(depth) nodes per
key — and a known cure: batch the mutations and hash each touched node once per batch (the
same amortization the engine's own MutableMap/commit performs). `insertAll` merges a sorted
batch group-recursively, keeping every untouched subtree's address without re-serializing it
(canonicity re-pinned: random chunkings ≡ batch build, 80 trials, K ∈ {1, 64}).

Update workload — 5,000 NEW terms applied to the pre-built 50k dictionary (3 forks, Welch):

| arm | K=1 | K=64 | vs engine |
|---|---|---|---|
| `updateCurrent` (5k encodes + commit on the committed base) | 19.9 ms | 19.4 ms | — |
| `updateRadixPerInsert` (the naive fold) | 27.9 ms | 21.1 ms | ≈ / worse |
| `updateRadixBuffered` (**insertAll**) | 7.8 ms | **1.92 ms** | **−90.1%** (t = −75.8) — ~10× |

With this, the K=64 radix leads the engine on **all four measured axes**: bulk build −74.6%,
batched update −90.1%, serialized lookup −60.2%, footprint 52% vs 140% of raw. The standing
caveats do not move: in-memory regime, leaner-than-flatbuffer framing, study scope.

## Chunk-distribution target 4 KiB: the page-packing layer

Radix nodes average ~208 bytes — far under a storage page — so the prolly chunk-size
discipline applies at the *packing* layer: pack the canonical depth-first node sequence into
content-defined pages (close when page ≥ 2 KiB and the node's own address masks to zero at
p = 1/8, force-close at 8 KiB). The partition is a pure function of the node sequence
(determinism pinned), and `DictionaryPagingReportTest` measures, on the 50k dictionary:

- **304 pages, mean 3,812 B** (target 4,096), p10 2,302 / p90 6,041, bounds [2,129, 8,495];
- **edit locality at the page level**: +100 terms → **4 new pages** (300 of 304 byte-identical
  to the old page set); +5,000 terms → 34 new pages, still 300 shared.

That is the full prolly payoff reproduced one layer up: page-sized blocks with a bounded
distribution, byte-identical across replicas, and edits that touch a handful of pages.

## Subtree addressing, implemented

The differentiator previously only asserted is now code with a pinned contract
(`subtreeAddress` / `entriesUnder`):

- **Resolve**: prefix → the minimal enclosing subtree's 20-byte address, O(depth) (~7 hops
  here); null when no key matches. Granularity honestly stated: a prefix ending inside a
  compressed node or bucket returns the minimal *enclosing* node; `entriesUnder` filters exact.
- **Enumerate**: all terms under a namespace in sorted order — pinned equal to filtered ground
  truth (2,000-term namespace).
- **Prune** — the Merkle payoff: after inserting terms into a *different* namespace, the queried
  namespace's subtree address is **byte-identical** (one address comparison proves every term
  under it unchanged — no enumeration, no diff walk); an in-namespace insert changes it. Pinned
  both directions.

This is the operation the hash-keyed design cannot express at any cost: with ids =
salted term hashes, a namespace's entries scatter uniformly across the id space, so "is this
namespace unchanged between versions?" is a full scan there, and one address compare here.

## The flatbuffer-framed arm: framing was most of the lookup win

`flatc` 23.5.26 (matching the engine's runtime exactly) generated a FlatBuffers table for the
radix node (`src/test/resources/dictbench/radix_node.fbs` → `fb/FbNode`), and
`FlatbufferPool` probes it with vtable-navigated accessors at every hop — the radix under the
**engine's own framing discipline**. Agreement with the other walks pinned; results (3 forks):

| lookup arm (K=64) | ms/1k | vs engine | verdict |
|---|---|---|---|
| engine (`findTermId`) | 0.989 | — | — |
| radix, hand-rolled serialized | 0.405 | −59% | the framing's share included |
| radix, **flatbuffer-framed** | **0.863** | **−12.8%** | t = −20.5 — the *structure's* share |
| (K=1 flatbuffer-framed) | 1.155 | +4.9% | **WITHIN NOISE** — nil |

**The retraction this buys** (ledger-logged): earlier rounds presented −60% on the all-axes
scoreboard with the framing residual *named* but the attribution still implied structural. The
leveled arm settles it — under identical framing the trie walk beats the engine's hash + tree
probe by ~13% at K=64 and not at all at K=1. Framing (fixed offsets vs vtables) was worth ~2×
by itself. Structure and framing are **independent axes**: nothing forces FlatBuffers on a new
component (the lean layout is a legitimate choice — and would equally speed the engine), but a
claim about the *structure* must be measured at equal framing.

Footprint under the same leveling — where the structural win *survives*:

| | hand layout | flatbuffer-framed |
|---|---|---|
| radix K=64 | 52% of raw | **61%** — still 2.3× under the engine's 140% |
| radix K=1 | 107% | 272% (!) — per-table overhead × 55k nodes; buckets amortize the framing tax |

**The construction arms, leveled too** (follow-up run — both pipelines end in a fully
flatbuffer-framed store; the radix's incremental variant frames only novel nodes):

| construction (vs engine same run) | K=1 | K=64 |
|---|---|---|
| build → framed store | 80.1 ms — **worse than the engine's 72.0** | **21.7 ms, −69.8%** (t = −40.3) |
| 5k-update → framed store | 28.9 ms — worse than 19.8 | **2.85 ms, −84.9%** (t = −80.4) |

The pattern locks in across all four axes: **bucket leaves are what make framing affordable**
— at K=1 the per-table encode of 55k nodes inverts every construction and footprint win; at
K=64 every win survives leveling. One hybrid nuance remains in these arms: node *addresses*
still hash the hand-layout bytes (a production fb-framed store would hash the fb bytes —
same digest count, slightly larger input; direction of error is against the radix by a
sliver).

## The reverse index (id → term): where content-defined chunking is the wrong tool

The forward radix needs a reverse companion (the engine's single hash-keyed tree serves both
directions; the radix design assigns **dense append-only ordinals**, which changes the chunking
question entirely). Three strategies raced over the same 50k corpus
(`ReverseStores` / `ReverseIndexBench` / `ReverseIndexReportTest`):

| strategy | build | lookup (1k ids) | chunks · mean B · min–max | append +5k: sealed chunks reused |
|---|---|---|---|---|
| fixed-count 64 (`id/C` locate) | 1.14 ms | **0.036 ms** | 782 · 3,100 · 904–3,592 | **781/781 — all** |
| byte-budget 4096 (fence locate) | 1.15 ms | 0.057 ms | 538 · 4,502 · **4,096–4,520** | **537/537 — all** |
| content-defined (production splitter) | **8.28 ms** | 0.055 ms | 502 · 4,825 · 619–8,092 | **501/501 — all** |
| engine `decode` (Int64 tree) | — | 0.925 ms | — | — |

The structural finding first: **an append-only ordinal log has no shift problem** — an entry's
position never moves, so *every* strategy is history-independent (rebuild → identical
addresses, pinned) and reuses *every* sealed chunk on append (pinned). Content-defined
chunking's entire premium — per-byte rolling-hash compute buying shift-healing — purchases
nothing here: **7.2× the build cost** (t = +13.6 vs fixed-count) for zero extra reuse and the
loosest size distribution. CDC earns its keep where content shifts (the sorted forward
key-space); on position-stable logs it is strictly dominated.

Choose by priority: fixed-count for the fastest locate (36 ns/id, O(1) divide + offset;
size-drift across entries is its cost), byte-budget for page-perfect geometry (4,096–4,520 B)
at a fence binary-search. Against the engine's reverse path both are 16–26× faster
(t = −44.6 for fixed-count) — with the standing fairness note that the engine's one tree
serves both directions, while the ordinal log is reverse-only and requires the id-assignment
discipline the radix design controls (hashed TermIds forbid it).

## The system view: composing forward + reverse — and the axis the engine wins back

Per-component tables hide a structural asymmetry: the engine's **one** tree serves both
directions (term bytes stored once; forward resolution is computed by hashing), while the radix
design needs the forward trie **plus** the reverse log — which stores every term's bytes a
second time (measured: 109.2% of raw, any strategy). Composing honestly (ledger-logged as a
narrowing of the earlier "every axis" phrasing):

| system axis (both directions) | engine (one tree) | radix system (fwd K=64 + byte-budget reverse) |
|---|---|---|
| build | 72.0 ms | 21.7 + 1.15 ≈ **22.9 ms, −68%** |
| 5k update | 18.9 ms | 2.85 + tail-append ≈ **3.0 ms, −84%** |
| forward lookup (framed) | 0.99 µs | **0.86 µs, −13%** |
| reverse lookup | 0.925 ms/1k | **0.057 ms/1k, −94%** |
| **footprint** | **140% of raw** | 61% + 109% = **170%** (161% hand-layout) — **the engine wins** |

Storing term bytes once and *hashing to find them* is the hash-keyed design's genuine
structural advantage, and it survives everything the radix wins elsewhere. The open design
lever, named and unmeasured: a reference-based reverse (id → forward-page + offset) would
deduplicate the bytes at the cost of coupling the two structures' layouts and update paths.

## Git operations: correctness pinned both ways — and the bench caught a real defect

The versioning motions (diff, identical-detection, replica convergence) are what the study's
capability claims rest on, so they got both correctness pins and timings
(`GitOpsCorrectnessTest` / `GitOpsBench`):

**Correctness, three ways.** The radix diff matches ground truth over 40 randomized edit
batches (additions + id updates, both bucket sizes — the trials exercise prefix splits and
bucket growth); identical roots diff empty with a root-level prune (0 nodes visited, 1
pruned — O(1)); two replicas ingesting the same 50k mapping along different histories
converge to equal roots. And the **cross-implementation pin**: for the same logical 5k-term
edit, the radix diff and the engine's real `DiffEngine` report the *identical* term set.

**The bench then caught a real algorithmic defect.** First timing run: radix diff **51.0 ms**
vs the engine's 3.1 ms — 16× *slower*, despite green correctness. Cause: the walk's
shape-mismatch fallback collected *both entire subtrees* whenever an edit had split a
compressed prefix node (the 5k `C9…` additions split the `…NCIT_C` prefix, so version A's and
B's nodes compared "unequal" at the 40k-entry subtree root). Correct — and catastrophically
coarse. The fix aligns unequal prefixes *virtually* (descend the longer prefix through the
other side's edges; full enumeration only where key sets are provably disjoint, where it IS
the answer). All four correctness pins stayed green through the rewrite; rerun:

| git operation (5k-edit, 50k base) | engine `DiffEngine` | radix diff | verdict |
|---|---|---|---|
| version diff | 3.37 ± 0.21 ms | **2.63 ± 0.04 ms** | −22% (t = −6.0) |
| identical roots | ≈ 10⁻⁴ ms | ≈ 10⁻⁵ ms | both O(1) prunes |

The lesson is the study's recurring one from the other side: a green correctness suite says
nothing about the *walk* being proportionate — "correct diff" and "git-fast diff" are separate
claims, and only the bench separates them.

**Three-way merge** completes the git suite. The radix merges via its two pruned diffs plus one
batched `insertAll` (conflict = both sides changed a key to different ids; deletions
out of scope, documented — terms are immortal in the measured workloads). Pinned: union
semantics against ground truth; **ours/theirs symmetry yields byte-identical merged roots**
(canonicity doing merge-commutativity for free); true conflicts detected with both sides
reported; same-change-on-both-sides is not a conflict; and the cross-implementation pin — on
the same disjoint 2.5k + 2.5k scenario over the 50k base, the radix merge and the engine's
real `MergeEngine` produce the **identical merged term set**, both conflict-free. Timed:

| three-way merge (2.5k + 2.5k over 50k) | ms | verdict |
|---|---|---|
| engine `MergeEngine` | 22.4 ± 1.0 | — |
| radix (diff + diff + insertAll) | **4.95 ± 0.17** | **−77.9%** (t = −28.9) |

## Real-world verification: the actual NCI Thesaurus, and the switch gate

Every number above used a *generated* NCIt-shaped corpus. `RealNcitCorpusReportTest` streams
the real `ncit.owl` (RDF/XML, ~811 MB; JAXP depth limits raised — real OWL nests past 100) and
re-runs the comparison on **300,000 distinct real terms** from 1.05M statements — real URI
shapes, real definition literals (mean term 41 B, max 1,940 B):

| real NCIt, 300k terms | current | radix K=64 |
|---|---|---|
| build (best-of-3, indicative — not JMH) | 782 ms | **66 ms (~11.8×)** |
| serialized footprint | 17.5 MB — **143% of raw** | **10.25 MB — 84% of raw** |
| mean lookup depth | — | 7.0 |

Two verdicts: the synthetic conclusions **transfer** (current's 143% matches the synthetic
140% almost exactly; the build gap *widens* on real data — longer, more prefix-shared terms),
and one number narrows honestly: the radix's 52% footprint was flattered by dense generated
ids — on real term diversity it is **84%** (still 1.7× under the engine). Correctness pinned
on the real corpus (3k random resolutions exact).

**The switch gate — the arithmetic that reframes everything** (derived from prior measured
numbers, order-of-magnitude): whole-system ingest of 50k quads measures ~1.74–1.91 s
(`IngestSplitterEliminationBench`), and the engine dictionary's share of that — ~55k term
encodes — is ~74 ms ≈ **4% of end-to-end ingest**. A dictionary 75–90% faster therefore moves
whole-system ingest by **≤ ~3.5%** — the same gate that rejected the chunker candidate (1.6%)
applies here at ~4%. A *switch* cannot be justified by ingest speed. What actually carries a
switch case: the footprint delta (84% vs 143% on real data), materialization-heavy read paths
(reverse lookups −94–96%), and the versioning operations (namespace-pruned diff/merge) — each
of which needs its own share-of-system measurement on real query workloads before it counts.

## Workload shapes: skew changes nothing, occurrence streams confirm, DISK FLIPS LOOKUPS

Items 1–2 of the program, measured (`WorkloadBench` / `DiskBench`, 3 forks, Welch-checked):

**Skewed lookups (Zipf 1.0 — rdf:type/label dominance)**: no verdict change. Both designs speed
up mildly on hot-path cache warmth (engine 1.10 → 0.98, radix-serialized 0.41 → 0.36 ms/1k);
the relative gap is unchanged (−62% uniform, −63% skewed).

**The occurrence-stream encode** — the realistic ingest shape every earlier arm ignored (the
dictionary is called per term OCCURRENCE: 150k occurrences over ~46k distinct, subjects ×10,
64 hot predicates, evolving hit/miss from empty; the radix runs its real pipeline —
per-occurrence lookup + pending overlay + `insertAll` per 1k-occurrence transaction):
**51.0 ms vs the engine's 153.5 ms (−66.8%, t = −27.4)**, skew-invariant. The construction
verdict survives the realistic workload shape.

**The disk regime — where the verdict flips.** RocksDB-backed lookups (memtables flushed;
warm-cache regime — no root means no OS-cache drop, named limitation):

| RocksDB-backed lookup (1k probes) | ms | vs engine |
|---|---|---|
| engine over its production `RocksNodeStore` | 5.93 ± 0.12 | — |
| radix, one RocksDB get per hop | 10.43 ± 0.24 | +76% — **loses** |
| radix, 4 KiB pages + in-memory directory | 10.99 ± 0.32 | **+85% (t = +73) — loses; paging didn't help** |

Depth finally bites: ~7 store roundtrips against the engine's 2–3 chunk reads (its 4 KiB tree
nodes ARE the right disk shape — fanout ~85, depth 2–3). DFS page-packing didn't rescue it:
lookup paths cross pages almost as often as nodes. The in-memory lookup wins (−13% leveled,
−60% lean-framed) are properties of the RAM regime. An honest switch case must therefore treat
the radix as an **in-memory/cached front** (or redesign its disk shape into chunk-sized
multi-hop nodes — at which point it converges toward the engine's own layout). This is the
study's clearest example of the regime rule: the same structure wins one memory hierarchy
level and loses the next.

**The remaining verification program** (open, in priority order):
1. ~~Real workload traces~~ — DONE above (skew: no change; occurrence streams: −66.8%).
2. ~~The disk regime~~ — DONE above (lookups FLIP: +85%; warm-cache limitation named;
   true cold-storage latency still unmeasured).
3. **Scale** — 300k terms is the largest run; production dictionaries reach 10⁷–10⁸ terms
   (depth, resident set with `MemSampler`, GC pressure of the pools).
4. **Concurrency** — readers during writes; everything here is single-threaded.
5. **Deletion/GC semantics** — term retirement is unimplemented in the radix study
   (diff detects removals; merge and stores don't apply them).
6. **End-to-end integration** — the dictionary swapped inside the real Sail ingest and query
   paths, measured with the whole-system benches; then the production-primitive parity gate.

## Reading the results honestly

- **The batch-build win (−46%) is the solid finding.** Sorting 50k keys + building the canonical
  trie + 55k SHA-256 digests is genuinely cheaper than 50k salted-hash encodes + prolly
  chunking. Bulk dictionary construction — the load path — is where the radix structure earns
  real ground.
- **The lookup win survives serialization (follow-up run, same session shape).** The
  serialized-probe experiment the first draft called for was run: a `SerializedPool` holding
  every node as bytes, with a walk that parses header/prefix/edges from the byte form at every
  hop (agreement with the object walk pinned on 500 random entries). Deserialization costs the
  radix **+25.5%** over its heap form (t = +9.9) — and it **still beats the engine by −52.7%**
  (0.53 vs 1.13 µs/term, t = −26.8). One asymmetry remains, named rather than resolved: the
  engine's nodes are **FlatBuffers-framed** (`FlatbufferNodeSerializer`, vtable indirection per
  field access) while the serialized radix uses a leaner hand-rolled fixed-offset layout — a
  flatbuffer-framed radix arm would level that last difference (blocked today: `flatc` is not
  installed on this host; the engine's generated classes are committed, and "flatc gen" is an
  open engine roadmap item). The same framing asymmetry cuts the footprint the other way: part
  of the engine's 140%-of-raw is flatbuffer table overhead a flatbuffer-framed radix would also
  pay.
- **The streaming loss (+132%) is structural.** Path-copying re-hashes O(depth) nodes per
  insert (mean key depth ≈ 8.5 on this corpus). The engine's design does one term hash and one
  buffered tree insert. A production radix would need buffered/batched mutation (accumulate,
  then rebuild subtrees) to compete on streams — the classic LSM move.
- **What the radix buys that no speed number shows**: term → id without a stored reverse
  index is *not* one of them — the engine computes ids by hashing, needing no forward
  structure at all. The radix's genuine differentiators remain the operational ones from the
  Python study: prefix-shared storage, per-subtree addressing, pruned dictionary diffs.
- **Correctness parity**: the Java twin carries the same load-bearing pin as the Python
  reference — fold-of-inserts in shuffled orders is byte-identical to the canonical batch build
  (`MerkleRadixDictionaryTest`, 60 randomized trials), plus prefix-of-key and update-isolation
  cases.

## Verdict

With its two design completions (bucket leaves K=64, batched `insertAll`) the structure
**leads the engine's dictionary on every measured axis at equal framing, with every
attribution now clean**: build −69.8% and batched updates −84.9% (fully framed end states),
lookup −12.8% (framing itself was worth a further ~2× — an independent choice equally open
to the engine), footprint 61% vs 140%, 4 KiB content-defined pages with 4-pages-per-100-edits
locality, and the versioning operations the hash-keyed design cannot express (subtree
addressing/pruning, implemented and pinned). The single load-bearing design element is the
bucket leaf: at K=1, framing costs invert every construction win. At the SYSTEM level (both
directions composed) the speed and capability wins survive but the engine wins footprint back
(140% vs 161–170% — it stores term bytes once and hashes to find them; see the system view).
No named residual remains unmeasured beyond the address-input sliver and the reference-based
reverse lever; promotion still runs through the production-primitive parity gate. It is
**not** a drop-in improvement: streaming ingest is 2.3× slower without a batching layer, the
lookup advantage — though it survives serialization at −52.7% — is still measured against a
leaner framing than the engine's flatbuffer nodes, and the engine's "compute the id" design
needs no forward lookup at all for its primary path. Status: **study-scope candidate**
(`dictbench/`, test tree) — promotion would require a flatbuffer-framed (or otherwise
framing-leveled) probe arm, a batching mutation layer, and the production-primitive parity
gate.

## Where this lives

- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/dictbench/MerkleRadixDictionary.java`
  — the Java twin (canonical build, path-copying insert, trie lookup, content addresses)
- `…/dictbench/MerkleRadixDictionaryTest.java` — the history-independence + correctness pins
- `…/dictbench/DictionaryBench.java` — the JMH arms · `…/DictionaryFootprintReportTest.java`
- `prolly-codec` `Dictionary` — the real implementation compared against
- Engine repo `prolly-python-notebooks/` — the Python reference + property suite + the NCIt
  measurements this Java study extends
