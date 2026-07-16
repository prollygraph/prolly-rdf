---
tags:
  - rdf
  - performance
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/the-leapfrog-triejoin.md; links + citations adapted to this repo's layout -->

# The leapfrog triejoin

*From intersecting one variable to binding many — how a worst-case-optimal join answers a whole basic graph pattern, and why that beats chaining pairwise joins on cyclic queries like the triangle.*

> **What you'll learn** — the gap between what prolly-rdf does today (a
> *single-variable* star join) and a full **leapfrog triejoin**; the two
> layers of the leapfrog triejoin — the single-variable *leapfrog join* you already have, and the
> *trie* of variables on top; the trie-iterator interface and the backtracking
> driver; the headline payoff — on the triangle query, chaining pairwise joins
> is `O(N²)` but leapfrog triejoin is `O(N^1.5)`, the worst-case-optimal (AGM) bound; how that
> splits into a *space* win and a *time* win that arrive separately; and how it
> all differs from RDF4J's native pairwise bind-joins (and why that only matters
> for cyclic queries). This is a **design/algorithm** doc. (Status correction,
> 2026-06-30: the multi-variable layer this doc frames as *planned* has since
> **landed** — `TrieIterator` and `LeapfrogTriejoin` shipped 2026-05-29, and the
> triejoin is wired into SPARQL evaluation, routed **on by default** per
> ADR-0065 (`prolly-rdf4j/docs/adr/0065-triejoin-routing-default-on.md`). The
> forward-looking "not yet built" framing in the body below predates that and is
> pending a fuller reframe.)
>
> _Reading time: ~12 minutes._

> **Prerequisites** —
> [the-leapfrog-join-contract](the-leapfrog-join-contract.md) (the
> single-variable leapfrog join + its sorted-input precondition — leapfrog triejoin is built
> on it), [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) (sorted, content-addressed
> tuples), [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) (the SPOC/POSC permutation
> indexes; `Tuple` keys).

## Why it matters

A SPARQL basic graph pattern is a multi-way join:

```
?x :follows ?y .
?y :worksAt  ?z .
```

The answer is every `(x,y,z)` where all three hold together. prolly-rdf's
`GraphPatternEngine` today does a **single-variable star join**: it picks *one*
join variable, intersects each pattern's projection on it
([the-leapfrog-join-contract](the-leapfrog-join-contract.md) covers that
intersection), and treats every other variable as a wildcard. That can answer
"`?x` that both follows *someone* and works *somewhere*" — but it cannot bind
`x`, `y`, and `z` *consistently*. And for a **cyclic** pattern it has no good
runtime. The cycle that matters:

```
?x :e ?y .   ?y :e ?z .   ?z :e ?x .     -- the triangle
```

Chaining pairwise joins — join the first two patterns, then join the result with
the third — materializes an intermediate of size up to `Θ(N²)` (think a star
graph: one hub with `N` spokes makes `N²` two-edge paths), even when the final
answer is tiny. leapfrog triejoin never builds that intermediate. That's the whole point.

## Two layers: leapfrog *join* vs leapfrog *trie*join

**Layer 1 — leapfrog join (one variable).** Given several *sorted* iterators over
the same variable's values, walk them in lockstep and emit the values present in
*all* of them — the intersection. This is the existing `LeapfrogJoin`. It binds
exactly one variable.

**Layer 2 — the trie (many variables).** Pick a **global variable order**, say
`x < y < z`. Treat each relation as a **trie** over its variables in that order:

```
relation R(x,y):           x = a            x = b
                          /     \          /     \
                       y=1      y=3      y=2      y=7      (the (x,y) pairs of R)
```

A relation exposes this trie through a **trie iterator** with two extra moves
beyond the usual cursor:

| Move | Meaning |
|---|---|
| `key()` / `next()` / `seek(v)` / `atEnd()` | iterate the **current level** (the values of the current variable) |
| `open()` | **descend**: fix the current value, move to the next variable's level (its children) |
| `up()` | **ascend**: back to the parent level |

The triejoin then binds variables **one at a time, depth-first**:

```
bind(i):                                   # bind variable v_i
  if i > n: emit (v1..vn); return
  LJ = leapfrog-join over { R.currentLevel : R is a relation containing v_i }
  while not LJ.atEnd():
      v_i = LJ.key()                       # a value in the intersection on v_i
      for each participating R: R.open()   # descend, fixing v_i
      bind(i + 1)                          # recurse to the next variable
      for each participating R: R.up()     # backtrack
      LJ.next()
```

So for `R(x,y), S(y,z), T(z,x)`: bind `x` (leapfrog `R` and `T` on `x`); for each
`x`, `open` them and bind `y` (leapfrog `R` and `S` on `y`); for each `y`, `open`
and bind `z` (leapfrog `S` and `T` on `z`); emit `(x,y,z)`; backtrack. A relation
only joins a variable it actually contains, and is `open`/`up`-ed only on the
rounds where its current level *is* that variable — the bookkeeping that lets
relations skip variables they don't mention.

> **Key idea** — the single-variable leapfrog join is the *inner loop*; the trie
> + the variable order is the *recursion*. Same intersection primitive, applied
> level by level down a shared variable order.

## The payoff: the triangle and the AGM bound

For the triangle on a graph with `N` edges, the number of triangles is at most
`N^1.5` (a case of the **AGM bound** — the tight worst-case output size of a
join). leapfrog triejoin runs in time `O(N^1.5)` — proportional to that bound, hence
*worst-case optimal*. A pairwise-join plan can spend `Θ(N²)` building a
two-edge-path intermediate it mostly throws away. That asymptotic gap — `N^1.5`
vs `N²` — is why worst-case-optimal join algorithms exist, and it shows up exactly on the cyclic
queries graph workloads care about (triangles, cliques, cycles).

### Two wins, not one — space *and* time

The "`O(N^1.5)`" headline blurs together two payoffs that actually arrive
separately, and it's worth pulling them apart:

- **No O(N²) intermediate — a *space* win.** A pairwise plan computes `R ⋈ S`
  and can hold up to `Θ(N²)` intermediate tuples before it even touches the third
  pattern. The triejoin emits each output tuple as the depth-first walk reaches a
  leaf, so its live memory is just the open levels on the current root-to-leaf
  path — `O(N · #vars)`, never the cross-product. This win needs **only the
  backtracking driver**: even a *materializing* trie (one that collects each
  level's distinct values — `≤ N` per level) keeps it, because it materializes
  one *level*, never the *product*.
- **`O(N^1.5)` runtime — a *time* win.** Stricter: it needs the leapfrog `seek`
  to be *sublinear* (a real tree seek that jumps past a value's subtree). A trie
  that scans a materialized level list per step is *correct* but can still do
  `Θ(N²)` **work**; worst-case-optimal *time* arrives only with the seek-based
  skip.

So the space blowup vanishes as soon as the join is multi-way; the time
optimality is the extra mile the sublinear seek buys. (The plan stages them in
exactly this order — the driver first, the seek-streaming trie second.)

## How this differs from RDF4J's native joins

RDF4J — the framework prolly-rdf plugs into as a Sail, and whose `MemoryStore`
is the test suite's join oracle — evaluates a basic graph pattern as a **left-deep pipeline of
binary bind-joins**: probe the first pattern's index; for each binding, substitute
it into the next pattern and probe again; reorder patterns by cardinality. It is
*solution-at-a-time* (a stream of `BindingSet`s), pairwise, and mature across all
of SPARQL.

| | RDF4J native | leapfrog triejoin |
|---|---|---|
| join shape | pairwise **binary** (left-deep bind-joins) | single **multi-way** |
| granularity | solution-at-a-time (row-wise) | variable-at-a-time (column-wise) |
| **triangle** | **Θ(N²)** — *no join order avoids it* | **O(N^1.5)** |
| acyclic (path/star) | already efficient, often **faster** (lower constants) | competitive; can lose on constants |
| scope | all of SPARQL | the basic graph pattern join core only |

The decisive line is **cyclic** queries: binary-join plans are *provably* not
worst-case-optimal, so *every* order RDF4J can pick does `Θ(N²)` work on the
triangle — a limitation of pairwise joining, not a missing optimization. For
**acyclic** queries (the common case) RDF4J's bind-joins are already good and
often beat worst-case-optimal join on constant factors — which is exactly why the plan keeps the
single-variable star fast path and routes only cyclic / multi-shared-variable
basic graph patterns to the triejoin.

> **Nuance** — RDF4J's default bind-join is *pipelined*: it streams rather than
> holding N² rows in memory, so against it the win is the `Θ(N²) → O(N^1.5)`
> **work** (time). The literal *materialized*-intermediate **space** blowup is
> what **hash-join** plans (and classic SQL engines) suffer — RDF4J uses those in
> places too.

> **Wiring caveat** — because prolly-rdf is an RDF4J Sail, RDF4J's own strategy
> drives the bind-join over `getStatements()` by default. A triejoin changes the
> runtime only once it is *wired into* the query path (a custom evaluation
> strategy / native basic graph pattern interception — the plan's SPARQL-wiring step).
> (Status correction, 2026-06-30: that wiring has **landed** — `TriejoinNode` +
> `TriejoinRoutingOptimizer` (`prolly-rdf4j/.../sail/eval/`) intercept cyclic
> basic graph patterns, and ADR-0065 made the routing **default-on**
> (`prolly.rdf4j.triejoin-enabled=true`, flipped 2026-06-21). prolly-rdf's
> native `executeMulti` remains, with RDF4J as the equal-results oracle.)

## How prolly-rdf would do it

The fit is unusually clean, because **a SPOC index is already a trie**: it's a
sorted map of `(s,p,o,c)` tuples, so its columns *are* trie levels. A
`TrieIterator` rides the existing `StaticMap` cursor (which already supports
`seek`): `open()` fixes the current column and enumerates the next; `next()`
advances to the next distinct value; a **constant** in the pattern (e.g. the
`:e` predicate) is just sought-to once and skipped — only *variable* columns are
enumeration levels. (Status correction, 2026-06-30: an earlier v1 *materialized*
each level's distinct values for correctness — securing the *space* win above.
The shipped `TrieIterator` is the **seek-streaming** version (Step 9): `next()`
skips a value's whole subtree by seeking to `[prefix, value + 0x00]` via a
sublinear `atKey` tree-seek — the *time*-win upgrade, already landed. It rides
the prolly-tree `Cursor` with no materialization.)

The sharp constraint — and the link back to a bug this engine already hit — is
**index-permutation selection**. A relation can stream as a trie in the global
variable order *only if* some index's column order, restricted to that pattern's
variables, matches it. SPOC gives subject-before-object; POSC gives
predicate-before-others. Arbitrary variable orders need more permutations
(`OSPC`, `SOPC`, `PSOC`, …) — the same "covering index per access pattern" idea
that was the streaming-optimal alternative deferred when
[the-leapfrog-join-contract](the-leapfrog-join-contract.md)'s Bug 2 was fixed
with a sort buffer. So multi-variable leapfrog triejoin is where that deferral comes due: a
pattern either has a covering index for the chosen order (stream it) or its trie
must be sort-materialized (correct, not streaming).

> **Gotcha** — the variable *order* never affects correctness (any order yields
> the same bindings), but it dominates performance, and which orders can *stream*
> depends on which index permutations exist. Order choice + index selection are
> the two tuning knobs.

> **Trade-off** — the current single-variable star join stays as the fast path
> for star queries (one shared variable); the triejoin is the general engine for
> two-or-more shared variables. Building it is a real upgrade, not a refactor —
> hence a plan *(private monorepo work tracker)*, not
> a patch.

> **leapfrog triejoin isn't the frontier.** It's the simplest *practical* worst-case-optimal join and it fits
> prolly's sorted indexes for free, which is why it's the *first* step. But pure
> leapfrog triejoin binds column-at-a-time even where a relation-at-a-time binary hash join is
> cheaper — so it can lose to binary joins on acyclic/star subqueries (exactly why
> we keep the star fast path). **Free Join** (SIGMOD 2023) unifies worst-case-optimal join + binary
> joins and picks the best mix; **generalized hypertree decomposition/tree-decomposition** plans apply worst-case-optimal join only
> to a query's cyclic core. The plan's "Prior art & more-efficient alternatives"
> section lays out that evolution path.

## Takeaways

- A basic graph pattern is a multi-way join; binding one variable (today's star join) is a
  special case, not the general answer.
- leapfrog triejoin = the single-variable leapfrog join (the inner loop) + a trie of variables
  bound depth-first in a global order (the recursion).
- A trie iterator adds `open`/`up` to the usual cursor; **a sorted prolly index
  is already a trie** over its columns.
- worst-case-optimal join matters on **cyclic** queries: the triangle is `O(N^1.5)` for leapfrog triejoin vs
  `O(N²)` for pairwise-join plans.
- **Two payoffs, not one:** *no O(N² intermediate)* is a **space** win that needs
  only the multi-way driver (a materializing trie keeps it); the `O(N^1.5)`
  **time** win needs the sublinear seek.
- **vs RDF4J:** identical bindings, but RDF4J's pairwise bind-joins do `Θ(N²)` on
  the triangle (no join order escapes it) where leapfrog triejoin is `O(N^1.5)` — and RDF4J
  still wins the acyclic common case on constant factors.
- The cost is **index permutations**: arbitrary variable orders stream only with
  covering indexes — the deferred half of the `SortedProjection` story.

## Where to go next

- the implementation plan *(private monorepo work tracker)* — the
  phased design: `TrieIterator`, the `LeapfrogTriejoin` driver, the triangle, the
  index permutations.
- [the-leapfrog-join-contract](the-leapfrog-join-contract.md) — the
  single-variable join this is built on, and the sorted-input precondition.

## Where this lives

These are the pieces this design builds on. (Status correction, 2026-06-30: the
`TrieIterator` / `LeapfrogTriejoin` are described above as planned, but both
**shipped 2026-05-29** and now live in `prolly-rdf/.../semantic/` — the two
entries marked **[landed]** below.):

- `prolly-rdf/src/main/java/com/earasoft/prolly/indexing/LeapfrogJoin.java` — the single-variable leapfrog join (leapfrog triejoin's inner loop; reused per trie level).
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/TrieIterator.java` — **[landed 2026-05-29]** the trie iterator (`open`/`up` over a prolly index's columns).
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/LeapfrogTriejoin.java` — **[landed 2026-05-29]** the multi-variable backtracking driver.
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/GraphPatternEngine.java` — the single-variable star join (`execute`) plus the multi-variable `executeMulti` (both shipped).
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/SortedProjection.java` — the single-variable sorted projection; its trie generalization is the sort-materialized fallback.
- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/SailTriejoinOnRealIndexesTest.java` — the convergence proof: the triejoin runs over ProllySail's real TermId SPOC/POSC indexes (ADR-0036; the native `VersionedQuadStore` raw-IRI store was retired per ADR-0037).
- `prolly-rdf/plans/multi-variable-leapfrog-triejoin.md` *(private monorepo work tracker)* — the implementation plan this doc teaches.
