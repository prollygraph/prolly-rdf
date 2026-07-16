
# Scaling URDNA2015 to Gigabytes of Triples

Practical algorithms and engineering patterns for running W3C
RDFC-1.0 on real-world graphs that don't fit in cache, RAM, or
patience.

**Audience:** the engineer asking "I have a 1 B-triple graph;
will canonicalization take seconds, minutes, or weeks?"

**Companion docs:** [`THEORY_OF_OPERATION.md`](./THEORY_OF_OPERATION.md),
[`FUTURE_WORK.md`](./FUTURE_WORK.md), the implementation guide at
a private strategy note.

---

## 1. The honest baseline

URDNA2015 is **worst-case super-polynomial** because of the
permutation loop in HashNDegreeQuads. A graph with K
indistinguishable groups of N blank nodes each has up to (N!)^K
permutations to enumerate.

The good news: real-world graphs are almost never worst-case. The
algorithm is **near-linear in practice** because:

- Most blank nodes have unique first-degree hashes → phase 3 issues
  them in O(blanks) without entering phase 4.
- Phase 4 only fires for collision groups, which are rare and
  small in real data.
- The cascade pattern (cheap canonicalizer first, escalate on
  collision) keeps the common case at first-degree cost.

Empirical observation across the regulated-data verticals we
target: **for non-adversarial inputs, ≥99% of blank nodes resolve at
phase 3.** The cost of canonicalization is dominated by the
linear-time first-degree pass, not the algorithmically-expensive
phase 4.

---

## 2. Scale targets and what they imply

| Input size            | What works                                                                    | What you need                                                                |
|-----------------------|-------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| < 1 M quads           | Anything. Naive URDNA2015 finishes in seconds.                                | Nothing special.                                                             |
| 1 M – 100 M quads     | First-degree pass becomes the bottleneck.                                     | BNCC partitioning (§4), parallelism (§5), cascade (§3).                      |
| 100 M – 1 B quads     | RAM pressure, hash-table thrashing, GC.                                       | Streaming first-degree (§6), incremental canonicalization (§8), JMH-tune.    |
| > 1 B quads           | Phase-1 pass alone is minutes; full algorithm may need hours.                 | Out-of-core (§9), reconsider whether full URDNA2015 is actually required.    |

The right answer for "is URDNA2015 fast enough?" is **always
benchmarked on your data**. Numbers below are guidance, not
guarantees.

---

## 3. Strategy: cascade by canonicalizer cost (SHIPPED)

`CascadeCanonicalizer` tries the cheapest viable canonicalizer
first, escalates on collision. Wall-clock breakdown for a typical
1 M-quad regulated reference-data graph:

| Canonicalizer level   | When it resolves                                    | Per-graph cost      |
|-----------------------|-----------------------------------------------------|---------------------|
| Level 0: first-degree | No collisions (typical case)                        | O(quads + blanks)   |
| Level 1: second-degree | Distinguishable by single-hop neighbours           | + O(blanks²) worst  |
| Level 2: URDNA2015    | Deep symmetric structures                           | Algorithm-dependent |

Instrument with the existing level-callback to measure your
workload's distribution. Workloads where < 1% of commits escalate
beyond level 0 pay essentially first-degree cost on aggregate.

---

## 4. Strategy: blank-node-connected-component partitioning

**The single highest-leverage optimisation for large graphs.**

A blank-node-connected component (BNCC) is a maximal set of blank
nodes connected by direct or indirect blank-blank edges. Blank
nodes in different BNCCs **cannot affect each other's canonical
labels** — the canonicalization algorithm treats them independently.

Implications:

- Partition the graph into BNCCs upfront (union-find over blank
  edges, O(quads × α(blanks))).
- Each BNCC canonicalizes independently.
- BNCCs are embarrassingly parallel: dispatch to `ForkJoinPool`.
- Memory pressure drops: working set per worker = one BNCC, not
  the whole graph.

For real-world data this is dramatic: a 100 M-quad FHIR dump might
have 10 M BNCCs averaging 3-8 blank nodes each. Each BNCC's full
URDNA2015 runs in microseconds; total wall-clock = linear in BNCC
count, divided by parallelism.

**Implementation status:** not yet shipped. Tracked in
[`FUTURE_WORK.md`](./FUTURE_WORK.md) "beyond v1." Recommended for
iter 6h after the W3C-vector validation lands.

---

## 5. Strategy: parallelize across colliding groups

Inside one BNCC (or one un-partitioned graph), phase 4 processes
each colliding group independently. Run them in parallel:

```java
collidingGroups.parallelStream()
    .forEach(group -> processGroup(group, canonical, ...));
```

Caveats:

- `canonical` (the global IdentifierIssuer) is shared mutable
  state. Either synchronize (cheap because phase 4's contention is
  light) or, better, have each parallel worker accumulate into a
  thread-local temp issuer and merge into `canonical` sequentially
  after all parallel work completes.
- The hash-sorted merge order from §5 of the implementation guide
  must be preserved at the merge step. Don't let parallel workers
  bypass the sort.

**Implementation status:** not yet shipped. Easy add-on; defer until
benchmarks show single-thread phase 4 is the bottleneck.

---

## 6. Strategy: streaming first-degree hashing

Phase 1 doesn't need the whole graph in memory. Stream-friendly
algorithm:

```
state = HashMap<blankNode, IncrementalSha256>()
for q in quads_stream:
    if isBlank(q.s): state[q.s].update(canonicalize(q, target=q.s))
    if isBlank(q.o): state[q.o].update(canonicalize(q, target=q.o))
finalize:
    h1 = { blank → state[blank].digest() for blank in state }
```

Caveat: phase-1 hashing requires sorting the per-blank quad list
before SHA-256, which seems incompatible with streaming. Workaround:
collect a small buffer per blank (~1 KB typical neighbourhood),
sort+hash on close. Net: streaming with bounded per-blank memory.

This is **O(quads) time, O(blanks × neighbourhood-size) memory**.
For a 1 B-quad graph with 100 M blanks averaging 5 quads each,
that's ~5 GB of working memory — uncomfortable but feasible.
Smaller working sets via off-heap (`MemorySegment` from Project
Panama) put this in reach for commodity hardware.

**Implementation status:** not yet shipped. Required for > 100 M
quad workloads.

---

## 7. Strategy: hardware-accelerated SHA-256

Modern JVMs auto-detect Intel SHA-NI / ARM crypto extensions and
use them transparently via `java.security.MessageDigest`. No code
changes needed; verify at runtime:

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal \
    -version | grep UseSHA
```

Look for `UseSHA = true`, `UseSHA256Intrinsics = true`. Speedup vs
software SHA-256 is ~3-5× on AVX2-capable CPUs. For URDNA2015 this
matters because hashing dominates phase 1.

**Implementation status:** transparent — already in use whenever
the JVM supports it. Document the verification step in your
runbook.

---

## 8. Strategy: incremental canonicalization (substrate-integrated)

The substrate's content-addressed Prolly tree already does
incremental diff. Combined with BNCC partitioning, this enables:

```
on_commit(added_quads, deleted_quads):
    changed_bnccs = identify_affected_bnccs(added_quads + deleted_quads)
    for bncc in changed_bnccs:
        canonicalize_just_this_bncc(bncc)
    return new_commit
```

For an append-mostly workload (a typical audit log), most commits
add < 100 quads and touch < 10 BNCCs. The per-commit cost is
near-constant regardless of total dataset size.

**Implementation status:** not yet shipped. Requires substrate-side
work in `CanonicalizingQuadStore.commit()` to identify
"changed BNCCs since previous commit." Tracked in
[`FUTURE_WORK.md`](./FUTURE_WORK.md) "beyond v1."

---

## 9. Strategy: out-of-core for true gigantor graphs

For graphs larger than RAM:

- **Memory-mapped quad files via Project Panama's `MemorySegment`.**
  The substrate's existing Prolly tree already does this; the
  canonicalizer can read from the same mmap'd region.
- **External sort for phase 1's per-blank quad lists.** O(quads
  log quads / available-RAM).
- **Disk-backed `HashMap` for h1 storage.** RocksDB is already on
  the classpath; reuse it.

Wall-clock for a 10 B-quad graph (back of envelope): phase 1 at
500 MB/s read = 20 minutes minimum. Phase 4 trivial after
BNCC-partitioning. Total: ~1 hour for the full algorithm. For most
audit / KG workloads, this is run once at ingest, not interactively.

**Implementation status:** not yet shipped. Niche; only relevant for
the largest deployments.

---

## 10. Strategy: bail to a stronger external library

`jsonld-java` and `titanium-rdf-canon` have years of optimisation.
For deployments where in-tree URDNA2015 implementation effort
exceeds the value:

```java
RdfCanonicalizer adapter = new JsonLdJavaAdapter(jsonLdProcessor);
// Slot into the cascade as the highest level.
new CascadeCanonicalizer(List.of(
    SimpleFirstDegreeCanonicalizer.INSTANCE,
    SecondDegreeCanonicalizer.INSTANCE,
    UrdnaCanonicalizer.INSTANCE,           // our impl
    adapter                                // fallback to jsonld-java
));
```

Trade-off: adds ~1 MB of JSON-LD machinery to the classpath. For
production deployments that already do JSON-LD, free win. For
substrate-only deployments, dependency cost.

**Implementation status:** not built. Cleanly enabled by the SPI;
write the adapter only if measurements show our URDNA2015 has
correctness or performance gaps on your data.

---

## 11. Strategy: time budget + fail closed (SHIPPED)

`CanonicalizingQuadStore` wraps canonicalization in a wall-clock
deadline. Default 200 ms. On timeout: `NonCanonicalizableException`.
Substrate refuses the commit.

For GB-scale workloads, raise the budget:

```java
new CanonicalizingQuadStore(inner,
    CascadeCanonicalizer.INSTANCE,
    Duration.ofSeconds(30));    // gigantor-friendly
```

Critical: the canonicalizer **must check
`Thread.currentThread().isInterrupted()` in its inner loops** or
the timeout won't fire. See implementation guide §7. Our
canonicalizers do; if you write a custom one, replicate the
pattern.

---

## 12. What NOT to do

Three patterns that look like optimisations but break correctness:

1. **Caching canonical labels across commits.** Tempting because
   it would amortise URDNA2015 cost. But adding a single quad can
   reshuffle the canonical labels of distant blank nodes (via
   BNCC merges or hash collisions). Caches go stale silently;
   debugging is brutal. Don't.
2. **Skipping the algorithm on "small" graphs and hash-only on big
   ones.** Looks like a free lunch — small graphs are correct,
   big graphs go faster. In practice the threshold becomes a
   correctness bug: a graph that imports a sub-graph crosses the
   threshold and changes its canonical labels for reasons that
   look like a bug. Stick with the algorithm, scale it instead.
3. **Approximating the permutation enumeration with a heuristic.**
   The lex-smallest-path rule is correctness-critical; truncating
   the permutation search produces non-canonical output that's
   silently wrong on adversarial inputs.

---

## 13. Benchmarking — what to actually measure

The numbers above are estimates; your data is the source of truth.
Use the JMH harness in `BENCHMARKS.md` to measure:

| Metric                                | What it tells you                                                       |
|---------------------------------------|--------------------------------------------------------------------------|
| Wall-clock per 1 M-quad commit         | Is the substrate viable for your write rate?                            |
| Fraction resolved at cascade level 0  | How much of your traffic pays first-degree cost only?                   |
| Phase-4 group size distribution        | Are you hitting the super-polynomial wall, or staying in linear-land?   |
| Memory peak                            | Does your machine have enough RAM, or do you need out-of-core?          |
| GC pressure                            | Allocation hot path — can you eliminate with byte arrays vs Strings?    |

For the audit substrate specifically: measure canonicalization cost
as a fraction of total commit cost. A typical target is
**< 5% of commit time**. If you're spending more than that, the
substrate is canonicalization-bound and one of §3-§9 will help.

---

## 14. Quick decision matrix

| You have…                              | Start with…                                                              |
|----------------------------------------|---------------------------------------------------------------------------|
| < 1 M quads, blank-light               | Default `CanonicalizingQuadStore(inner)`. Move on.                       |
| 1-100 M quads, sometimes-blank          | + Profile to confirm > 99% resolve at cascade level 0.                  |
| 100 M – 1 B quads                      | + BNCC partitioning (§4). + Parallel phase 4 (§5). + Larger time budget. |
| > 1 B quads                            | + Streaming phase 1 (§6). + Off-heap memory (§9). + Reconsider scope.    |
| GB of mostly-blank-node data           | Check if you can re-shape the data to use named IRIs instead.            |
| Adversarial inputs (security context)  | Fail-closed time budget is the answer (§11). Don't try to be clever.     |

---

## 15. The pragmatic close

Most production deployments don't hit the algorithm's hard cases.
The cascade pattern + first-degree fast path gets you 99% of the
way at near-linear cost. The remaining 1% needs the full algorithm,
and for those cases you pay for it once at write time (canonicalize-
at-commit, whitepaper §3.1) — never at read time. Time-budget the
rare hard case; fail closed; alert the operator.

If you're considering URDNA2015 for a deployment where the audit
log is the substrate (which is most regulated deployments), the
typical commit is 5-50 quads — well within microseconds for the
full algorithm. The "gigabytes of triples" question really comes
up only when canonicalizing a snapshot of an existing large
dataset on first ingest. That's a batch operation; spend the hour
once, move on.

---

## 16. References

- W3C RDF Dataset Canonicalization 1.0 — https://www.w3.org/TR/rdf-canon/
- jsonld-java performance notes — https://github.com/jsonld-java/jsonld-java/blob/master/docs/optimization.md
- rdf-canonize-js benchmark suite — https://github.com/digitalbazaar/rdf-canonize/tree/main/test-suites
- Apache Jena performance tuning (general RDF, applicable patterns) — https://jena.apache.org/documentation/tdb/optimizer.html
- Project Panama / `MemorySegment` for off-heap large-data — https://openjdk.org/jeps/454
- Intel SHA-NI extensions — https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sha-extensions.html
