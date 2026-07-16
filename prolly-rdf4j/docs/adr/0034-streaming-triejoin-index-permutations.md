
# ADR-0034: Streaming triejoin index permutations

## Status

Accepted, 2026-05-29. Guides `prolly-rdf/plans/multi-variable-leapfrog-triejoin.md` (Phase 3, Step 11).

**Update 2026-05-29 — Option C declined (cost).** The Phase-4 gate on D-2 has
resolved *against* maintained graph-leading permutations: Step 16 measured the
triejoin ~80× slower than RDF4J even before considering write amplification, and
the operator judged the standing cost of Option C (4→6 indexes per write, ~50%
more index storage) not worth its only-over-B benefit. Option B (seek-scoped
projection) stands as the durable decision; Option C is **deferred indefinitely**,
not pending more data. Q1 (graph-column elision) likewise parked.

## Context

The multi-variable leapfrog triejoin (Phase 1–2 of the plan) binds a BGP's
variables depth-first, leapfrog-joining each pattern's current trie level. For
that to work, **each pattern must be presentable as a trie whose levels are the
pattern's variable columns, in the chosen global variable order**.

Today `LeapfrogTriejoin` achieves that for *every* pattern the same way: at query
time it **scans all of the SPOC index, filters by the pattern's constants, and
projects the variable columns (in global order) into a fresh sorted
`StaticMap`** — a "sort-materialized projection". This is correct and delivers
Gains 1 (multi-variable binding) and 2 (no O(N²) intermediate), and with Step 9
the join over those projected tries is seek-streaming (Gain 3). But the
projection build itself is **O(N) per pattern regardless of the pattern's
selectivity** — a pattern matching three rows still scans the whole store.

Phase 3 asks: can a pattern instead **stream directly off a maintained index**,
skipping the projection rebuild? Step 10 answered *when* with the
`IndexStreamability` classifier: an index permutation streams a pattern iff its
constant columns (bound positions **and the graph**) form a prefix and its
variable columns ascend in global-variable-order.

**The decisive finding from Step 10:** these patterns carry a *constant graph*
`c`, and both maintained permutations — `SPOC` and `POSC` — place `c` **last**.
A trailing constant fails the prefix rule, so **neither SPOC nor POSC can stream
any constant-graph pattern**. Direct streaming requires **graph-leading**
permutations. The triangle `?x→?y→?z→?x` under order `[x,y,z]` concretely needs
`CPSO` (for `(?x,?y)` and `(?y,?z)`) and `CPOS` (for the closing `(?z,?x)`). This
also corrects the plan step's original wording, which named `OSPC/SOPC/PSOC` —
all *graph-trailing*, hence useless for these patterns.

So the question is sharper than "add more permutations": it is **how do we make
selective patterns cheap to present as a trie, and is maintaining graph-leading
permutations on the write path worth its cost — and do we know that yet?**

## Options

The plan's two framings ("query-time rebuild" vs "maintained on the write path")
hide a third, cheaper middle: scope the rebuild to the matching rows by seeking
the constants on an *existing* index first.

| Option | Per-query cost (selective pattern) | Write amplification | On-disk storage | Streaming-capable | New code |
|---|---|---|---|---|---|
| **A** — Full-scan projection (today's fallback) | O(N) scan, every pattern | none | none (4 indexes) | no (rebuild each query) | none |
| **B** — Seek-scoped projection | O(matches + sort), seek the constant prefix on SPOC/POSC then project only that range | none | none (4 indexes) | no (still rebuilds, but tiny) | small (`LeapfrogTriejoin` constructor) |
| **C** — Maintained graph-leading permutations (`CPSO`,`CPOS`) | O(seek) — stream directly, no rebuild | +2 indexes per write (4→6) | ~+50% index bytes | yes | larger (write path + covering selection) |

"Query-time rebuild like POSC" from the step text collapses into **A** (POSC is
itself maintained; rebuilding a permutation at query time *is* the projection we
already do). The real spectrum is A → B → C by increasing investment.

## Decision

**D-1 — Adopt Option B now; gate Option C behind Phase 4 evidence.** Replace the
full-store scan in `LeapfrogTriejoin`'s projection with a **seek-scoped**
projection: use the constants to `atKey`-seek the relevant prefix on an existing
maintained index (SPOC when subject is bound, POSC when predicate is bound), and
project only the matching range. This kills the "scan the whole store for a
three-row pattern" waste at **zero write-amplification and zero new storage** —
the cost the maintained permutations would impose. The seek-scoped projection
still materializes (it sorts the matched rows into global variable order), but
over `O(matches)`, not `O(N)`.

**D-2 — Do not maintain graph-leading permutations until Phase 4 measures the
projection cost.** Option C realizes the *purest* streaming (O(seek), no
materialization at all), but its write-amplification (every write updates two
more prolly trees) and storage (~+50%) are real and permanent, while its benefit
over B is **only the residual sort cost of the matched rows**. Whether that
residual dominates query time is exactly what Phase 4's deterministic counters +
JMH wall-time are built to measure. Committing to C before that data is
speculative write-amp. The `IndexStreamability` classifier (Step 10) is already
in place, so when C is justified the wiring is mechanical.

**D-3 — If C is later adopted, the permutation set is `CPSO` + `CPOS`, not
`OSPC/SOPC/PSOC`.** Graph-leading is mandatory (per the Step-10 finding); these
two cover the triangle, the worst case. Additional graph-leading permutations are
added only as specific query shapes demand them, never speculatively.

The deciding tradeoff: **B captures most of the selectivity win for a fraction of
C's permanent cost, and defers the irreversible write-amp decision until there is
data to make it.** Pre-1.0, adding then removing a maintained on-disk index is a
format change we would rather make once, with evidence.

## Consequences

- **Positive:** selective patterns stop scanning the whole store — the projection
  build drops from O(N) to O(matches + sort). No write-path or on-disk change, so
  no migration and no write-throughput regression. The classifier built in Step 10
  is exercised by B's index-choice logic (SPOC vs POSC seek), so it stops being a
  no-op.
- **Negative / punted:** the pure-streaming O(seek) win (Option C) is *not*
  realized yet — patterns whose matched-row set is itself O(N) (e.g. `(?x e ?y)`
  with only the predicate bound, matching most of the store) still pay an O(N)
  sort under B. Those are precisely the patterns C would help, and they are
  deferred to a post-Phase-4 decision.
- **Neutral:** the graph column's trailing position in SPOC/POSC is now a
  documented, load-bearing fact (it is *why* B seeks SPOC/POSC for filtering but
  cannot stream them). Any future single-default-graph optimization that drops the
  graph column would change this analysis.

## Follow-up / future work

- ~~**Phase 4 benchmark gates Option C.**~~ **Resolved against (2026-05-29):** the
  benchmark showed the triejoin loses on wall-time regardless, and the maintained-
  index write-amp/storage cost was judged not worth it. Option C is shelved. If it
  is ever revisited, the trigger is a *different* motivation than "make the existing
  triejoin faster" (e.g. a query workload dominated by selective cyclic patterns at
  a scale where RDF4J's in-memory joins themselves break down) — and it still needs
  the constant-factor work, so it would not be Option C alone.
- **Step 12** wires per-pattern covering-index *selection* using the
  `IndexStreamability` classifier — under this ADR that means "pick SPOC vs POSC
  for the seek-scoped projection", and (if C lands) "stream off `CPSO`/`CPOS` when
  the classifier says they cover".

## Open questions

- **Q1** — Single-default-graph deployments (the common case) have a *constant*
  graph across the entire store, making the `c` column pure overhead in every
  index. Is a graph-column-elision mode (3-column `SPO`/`POS` indexes when only
  the default graph is used) worth it? It would make `SPO`/`POS` graph-free and
  thus directly streamable, sidestepping the need for graph-leading permutations
  entirely — but at the cost of a second on-disk layout. Defer until multi-graph
  usage patterns are clearer.
