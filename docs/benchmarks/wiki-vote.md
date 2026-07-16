<!-- provenance: exported 2026-07-26 from the private monorepo's test_ontologies_zips/wiki-vote.md; links + run instructions adapted to this repo -->

# `wiki-vote.zip` — wiki-Vote (dense cyclic graph)

| | |
|---|---|
| **Source** | [SNAP wiki-Vote](https://snap.stanford.edu/data/wiki-Vote.html) — Wikipedia adminship voting (till Jan 2008) |
| **Size** | 283 KB zip · **103,689** directed edges · 7,115 nodes · zip entry `wiki-Vote.txt` (`from\tto`, `#` comments) |
| **Shape** | dense **cyclic** · power-law in-degree (popular candidates) · single-predicate · ABox · directed |
| **Why it's here** | the **real** cyclic graph that finally exercises the LeapfrogTriejoin (WCOJ) — NCIt is acyclic and never could |

## What it tests

| Aspect | Benchmark | Engines |
|---|---|---|
| Cyclic triangle join (worst-case-optimal vs bind-join) | `RealGraphTriangleBench` (tool) | rdf4j-native / flatsail / prolly bind-join / **prolly triejoin** |

Each `from→to` line becomes a triple `<v_from> <urn:bench:edge> <v_to>`. The query is the canonical
cyclic triangle:

```sparql
SELECT ?x ?y ?z WHERE { ?x :edge ?y . ?y :edge ?z . ?z :edge ?x }
```

This is the join where a bind-join's intermediate — the 2-paths `?x→?y→?z`, sized `Σ_y indeg(y)·outdeg(y)`
— explodes through power-law **hub** nodes (popular admins with ~1000 in-edges), even though the final cycle
count is far smaller. A worst-case-optimal join (WCOJ) intersects all three patterns at once and stays near
the AGM bound, sidestepping the blow-up. wiki-Vote is the real-world stress test the synthetic 380-edge
triangle could only hint at.

## Performance findings

Directed triangle, all engines, min-of-3 indicative (`-Xmx6g`). **All finishing engines agree on 131,925
directed 3-cycles** — the correctness cross-check.

| engine | time | vs triejoin |
|---|---:|---:|
| **prolly triejoin** | **3,202 ms** | 1× (fastest) |
| rdf4j-native | 10,411 ms | 3.3× slower |
| prolly bind-join | 36,777 ms | 11.5× slower |
| flatsail (bind) | 46,432 ms | 14.5× slower |
| rdf4j-memory | **DNF** (couldn't `COUNT` in 120 s) | — |

**The headline: on real cyclic data the triejoin's advantage is *categorical*, not marginal.** On the
synthetic dense-core triangle the WCOJ won by only ~1.12× (the intermediate barely blew up); real power-law
degree skew is what makes it decisive — **3.3× faster than heavily-optimized RDF4J NativeStore, 11.5× faster
than ProllySail's own bind-join, 14.5× faster than flatsail.** flatsail is slowest because the intermediate
explosion compounds with its per-key RocksDB JNI crossing. This is the first real-data validation of the
triejoin work (built + wired + flag-gated, previously tested only on a generator) — and the answer to "is the
triejoin worth it?" is *yes, on the cyclic queries it's built for, when the data has real skew*.

> **Caveat — this is the triejoin's best case.** Acyclic / star / point / scan workloads do *not* benefit
> (the router only sends cyclic BGPs to the triejoin; everything else takes the normal path). See
> [`ncit.md`](ncit.md) for the acyclic-workload picture, where native wins joins. The triejoin is a
> *targeted* win, not a general one.

## Where the time goes (CPU flame graph)

`RealGraphTriangleFlame` profiles the same query per engine under JFR `jdk.ExecutionSample` (which is
**blind to native/JNI** — so it's complete for the pure-Java engines and Java-side-only for the others). The
**Java-CPU sample counts alone** explain the timings:

| engine | Java-CPU samples | top self-CPU frames |
|---|---:|---|
| prolly bind-join | **17,621** | `TupleDescriptor.compare` 25% + `Cursor.searchInNode` 18% — the exploded 2-path intermediate, all in pure-Java tree probes |
| rdf4j-native | 8,045 | B-tree walk in Java over mmap: `getPatternScore`, `RangeIterator.next`, `compareBTreeValues`, + ~17% node-cache LRU churn (`ConcurrentLinkedDeque`) |
| prolly triejoin | 3,772 | leapfrog seek: `compareFieldAt`, `Tuple.fieldEquals`, `LeapfrogJoin.next`, `searchInNode` — **plus ~30% MemorySegment access ceremony** (`dup`, alignment/bounds checks) |
| flatsail | **131** | almost nothing — 46 s of work, ~all of it inside **RocksDB JNI**, invisible to JFR |

Three mechanistic reads:
- **bind-join burns CPU, not IO.** It's the *most* Java-CPU of any engine; 43% is just compare + node-search.
  That's the AGM intermediate blow-up made concrete — millions of tuple comparisons over the exploded 2-paths.
- **flatsail's 131 samples on a 46 s query is the starkest confirmation of the "per-key in native" story.** A
  Java profiler can barely find flatsail because it isn't *in* Java — it's in RocksDB JNI (the 213k per-key
  comparisons measured separately by `PerfContext`). For a native-inclusive flame, use async-profiler.
- **The triejoin wins by doing the least work** (fewest samples). Its flame *looked* like it had a ~30%
  lever — Panama foreign-memory access ceremony (`MemorySegment.dup`, alignment + bounds checks). **Measured
  and refuted:** eliminating the largest piece (`LevelIterator.key()`'s `asSlice` → `getFieldSegment`,
  replaced with a slice-free `fieldRange` + copy-from-parent) dropped the `dup` frame 14.6% → 11.7% but left
  wall-time unchanged (3.2–3.4 s, within noise; correctness preserved — still 131,925). JFR self-CPU% is
  safepoint-biased and over-attributes to cheap hot leaves; and the cost is *spread* across many ~equal
  frames (`compare` 13%, `dup` 11%, `compareFieldAt` 6%, access ceremony, `searchInNode`), with the rest of
  `dup` coming from result materialization. **No single removable lever exists** — the triejoin is near its
  floor for this access pattern. (The slice-free `key()` was kept: correctness-neutral, removes a hot-path
  allocation, even if latency-neutral.)

Reproduce: `RealGraphTriangleFlame` (writes `target/flames/rtri-*.svg` + `index.html`); see the run command
below with `RealGraphTriangleFlame` in place of `RealGraphTriangleBench`.

### Native-inclusive flame (async-profiler) — flatsail's JNI half, made visible

JFR found only **131** Java samples for flatsail (above) because it's blind to native code. async-profiler
(`itimer` mode, native-aware) reveals the rest: **54% of flatsail's CPU is inside RocksDB native** —
`rocksdb::DBImpl::GetImpl`, `DBIter::FindNextUserEntryInternal`, `MemTable::Get`, plus the per-`Get` JNI tax
(`__tls_get_addr`, `FailIfCfHasTs`, `NewArenaWrappedDbIterator`, `malloc`). The bind-join's exploded
intermediate drives millions of RocksDB `Get`s, each crossing the JNI boundary — the "per-key in native"
story from a third angle (PerfContext counted 213k comparisons; JFR saw ~nothing; async-profiler shows 54%
native). RDF4J NativeStore shows **zero** RocksDB frames — it's its own mmap B-tree, not RocksDB. Flames:
`target/flames/{flatsail,native}-jni.html`. Run with
`-agentpath:$AP/libasyncProfiler.so=start,event=itimer,flamegraph,file=target/flames/flatsail-jni.html`
(`itimer`, not `cpu` — this host's `perf_event_paranoid=4` blocks perf).

## How to run

```bash
T="$PWD/target/benchtmp"; mkdir -p "$T"
CP="prolly-rdf4j/target/test-classes:prolly-rdf4j/target/classes:$(cat /tmp/vbench.cp)"
JVM="--enable-preview --enable-native-access=ALL-UNNAMED -Xmx6g -Djava.io.tmpdir=$T -Dgraph.zip=$PWD/test_ontologies_zips/wiki-vote.zip"

# one engine at a time (bind-joins are slow — run independently):
java $JVM -cp "$CP" com.earasoft.prolly.rdf4j.bench.RealGraphTriangleBench triejoin
java $JVM -cp "$CP" com.earasoft.prolly.rdf4j.bench.RealGraphTriangleBench native
# add "symmetrize" to raise the cycle count by adding reverse edges:
java $JVM -cp "$CP" com.earasoft.prolly.rdf4j.bench.RealGraphTriangleBench triejoin symmetrize
```

Triejoin design + wiring:
the triejoin-evaluation-wiring plan *(private monorepo work tracker)*. 
