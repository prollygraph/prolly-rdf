---
tags:
  - rdf
  - performance
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/the-leapfrog-join-contract.md; links + citations adapted to this repo's layout -->

# The leapfrog-join contract

*Why the worst-case-optimal join at the heart of basic graph pattern evaluation demands sorted inputs — and how two real bugs, both violations of that one precondition, hid for years behind passing example tests.*

> **What you'll learn** — what leapfrog-triejoin is and why prolly-rdf uses it
> to answer SPARQL basic graph patterns; the single precondition the whole
> algorithm rests on (every input iterator sorted by the join key); two real
> bugs that were each a violation of it — one in the join itself, one in a
> caller (both now fixed); and the testing lesson that surfaced both:
> generate the inputs, drive the *real* iterators, and a violated-but-unenforced
> precondition becomes a shrinkable counterexample.
>
> _Reading time: ~10 minutes._

> **Prerequisites** — [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) (sorted, content-
> addressed B-tree; ordered iteration), [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)
> (`Tuple` keys, the SPOC/POSC permutation indexes).

## Why it matters

A SPARQL basic graph pattern like `?x follows ?y . ?y worksAt ?z` is a
*join*. prolly-rdf evaluates the single-variable star-join form of it with a
**leapfrog triejoin** (`LeapfrogJoin`) — a worst-case-optimal join that,
given N sorted iterators, walks them in lockstep to emit exactly the values
present in *all* of them (their intersection). `GraphPatternEngine` projects
each pattern onto the join variable's column (an iterator over one of the SPOC /
POSC indexes) and leapfrog-joins those projections.

A worst-case-optimal join is the right algorithm here — but it buys its optimality with a strict
**precondition**, and that precondition is the whole story of this doc.

## The contract: every input must be sorted by the join key

Leapfrog triejoin keeps a ring of the N iterators **sorted by their current
head key**. Each step looks at the iterator with the smallest head (`p`) and the
one with the largest (its ring-neighbour); if they're equal, that key is in the
intersection; otherwise it `seek`s the smallest forward past the largest. The
smallest, now advanced, becomes the new largest — so the ring *rotation*
preserves the sorted order, round after round.

> **Key idea** — the ring comparison "least vs greatest" is only meaningful if
> the iterators are sorted by head key. The algorithm maintains that order as it
> runs, but it **cannot establish it**. Sorted input is a *precondition*, not an
> invariant the join creates.

Violate it and the join doesn't crash — it silently returns the *wrong set*.
That's the dangerous kind of bug: no exception, no log, just a query answer
that's quietly incomplete. Both bugs below are exactly this.

## Bug 1 — the join didn't sort its own iterators (fixed)

`LeapfrogJoin`'s constructor advanced each iterator to its first key… and then
started joining, **without sorting the iterators by that key**:

```java
// LeapfrogJoin constructor — BEFORE
for (var it : this.iterators) {
    if (!it.next()) { this.iterators.clear(); return; } // empty input → empty join
}
// (no sort — the ring is in arbitrary array order)
```

With heads `[0, 1, 0]` (three iterators), the ring compared `iter[0]=0` against
its neighbour `iter[2]=0`, found them equal, declared `0` a match — and never
consulted `iter[1]=1`. So `intersection({0},{1},{0})` came back as `{0}` instead
of `∅`. A value reported as present in all three iterators when one of them
never contained it.

The fix is the one missing line — establish the precondition at init:

```java
// AFTER — the ring must START sorted by head key; rotation then preserves it.
this.iterators.sort((a, b) ->
        descriptor.compare(new Tuple(a.key()), new Tuple(b.key())));
```

> **The bug, in one line.** A worst-case-optimal join assumed its inputs were
> sorted by key and never enforced it; the one input ordering that breaks the
> ring (`[0,1,0]`) is one a hand-written test would never think to try.

## Bug 2 — a caller feeds unsorted projections (fixed)

The second violation lives one layer up, in how `GraphPatternEngine` builds the
per-pattern iterators. For a pattern with an **unbound position *between* its
bound prefix and the join column** — e.g. `(s, ?w, ?j)`: subject bound, predicate
a wildcard, object the join variable — the engine scans the SPOC index with the
prefix `[s]` and projects the object column. But the object is *not* the next
field after `[s]`; the predicate sits between. So the projected objects come out
in `(predicate, object)` scan order — **not sorted by object**.

That unsorted stream is then handed to `LeapfrogJoin`, whose precondition it
violates, so a multi-pattern join silently drops matches:

```
quads: (e1,p0,e2), (e1,p1,e1), (e2,p1,e1)     BGP: (e1,?w,?j) . (e2,p1,?j)
correct ?j = {e1}                              engine returns ∅
```

Pattern `(e1,?w,?j)` matches both `e1`-subject quads, so its correct object set is
`{e1, e2}`. But scanning the SPOC index with prefix `[e1]` yields them in
`(predicate, object)` order — `(p0,e2)` then `(p1,e1)` — so the projection streams
`[e2, e1]`, **descending**, not sorted by object. `LeapfrogJoin` reads the leading
`e2` as the stream's minimum and seeks the other pattern (whose only value is
`e1 < e2`) past its end, so it reports an empty intersection — dropping the real
match `e1`.

> **The bug.** Templates where the join column immediately follows the bound
> prefix — `(?j,p,o)` (POSC: `[p,o]`→subject) and `(s,p,?j)` (SPOC: `[s,p]`→
> object) — project a *sorted* stream and work. Templates with an unbound gap —
> `(s,?w,?j)`, `(?j,p,?w)` — don't.

**How it was fixed** — unlike Bug 1, no *single* SPOC/POSC prefix scan can
produce a sorted projection across an unbound gap, so the two options were a
**sort buffer** (materialize + sort each pattern's projection) or **more index
permutations** (OSPC/SOPC/… so the join column is always contiguous). The chosen
fix is the sort buffer: `GraphPatternEngine` now wraps every per-pattern
projection in a `SortedProjection` that drains the `ProjectingIterator`, sorts +
dedups by the join key, and serves its own correct `next`/`seek`:

```java
// GraphPatternEngine.execute — every input now satisfies LeapfrogJoin's contract
iterators.add(new SortedProjection(createIteratorForPattern(pattern, joinVar), joinDesc));
```

It trades streaming for correctness — `O(m log m)` per pattern, bounded by the
match count you read anyway; the index-permutation route (streaming-optimal but
more storage + per-query rebuild) is recorded as the deferred alternative in
ADR-0033. `GraphPatternBgpProperty` now generates **all** pattern shapes
(including the gapped ones) and passes — the original counterexample green, 73
main-style semantic tests still green (was 78; the `MainMethodTests` suite
shrank when `VersionedQuadE2ETest` and siblings were retired — ADR-0037).

## The lesson: an unenforced precondition is a latent bug magnet

Both bugs are the same shape — code that *consumes* a sorted stream while
something *upstream* fails to guarantee the order — and both slipped past the
existing example tests for the same reason: the curated cases (`disjoint`,
`singleton`, `identical`) happened to feed inputs whose array order already
matched key order, or single patterns where the projection was trivially sorted.

What surfaced them was a discipline, not luck:

- **Generate the inputs, don't hand-pick them.** `LeapfrogJoinProperty` shrank
  to `[["0"],["1"],["0"]]` in seconds; `GraphPatternBgpProperty` shrank to a
  two-pattern basic graph pattern with one gapped template. Neither is a case a human writes by
  hand — both are exactly the tail the curated suite missed.
- **Drive the real collaborators, not a double.** The leapfrog property first
  failed against a hand-rolled `ListIter` test double — which could have been
  the double's bug. Re-running over *real* `StaticMap.iter()` iterators proved
  the fault was in `LeapfrogJoin` itself. A bug found only against a mock is not
  yet a bug found.
- **A precondition you don't test is a precondition you don't have.** "Inputs
  must be sorted" was true in the javadoc sense and false in the enforced sense.
  Pin it: sort at the boundary (Bug 1's fix), or assert the contract and fail
  loudly when a caller violates it.

> **The maxim.** When an algorithm's correctness rests on a precondition its
> inputs must satisfy, the highest-value test is the one that *generates* inputs
> until it finds the arrangement that violates the precondition — because that
> arrangement is real, your callers will eventually produce it, and nothing in
> the type system is stopping them.

## Where to go next

- [the-leapfrog-triejoin](the-leapfrog-triejoin.md) — the multi-variable
  generalization: binding several variables hierarchically (a trie of variables
  on top of this single-variable join), and the worst-case-optimal payoff on
  cyclic queries.
- [the-termid-ordering-trap](the-termid-ordering-trap.md) — the sibling
  "green at the top is not green all the way down" lesson: a sort-order defect
  an end-to-end test structurally cannot see.
- [the-untrusted-byte-boundary](the-untrusted-byte-boundary.md) — another
  example-tests-pass-but-the-generated-tail-is-the-bug story (a parser denial-of-service).
- the-test-landscape *(private monorepo contributing doc)* — where the
  property tier that found these sits among the other tiers.

## Where this lives

- `prolly-rdf/src/main/java/com/earasoft/prolly/indexing/LeapfrogJoin.java` — the worst-case-optimal join; the constructor's init-sort is Bug 1's fix.
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/GraphPatternEngine.java` — `createIteratorForPattern`: picks SPOC/POSC + projects the join column (Bug 2's unsorted-projection site); `execute` now wraps each in `SortedProjection`.
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/SortedProjection.java` — Bug 2's fix: sorts + dedups each projection by the join key before the leapfrog ring sees it.
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/GraphPatternEngine.java` — `execute`/`executeMulti`: builds the per-pattern projections and runs the join (the native `VersionedQuadStore` query entry point that drove it was retired — ADR-0037).
- `prolly-rdf/src/test/java/com/earasoft/prolly/indexing/LeapfrogJoinProperty.java` — the property that found + pins Bug 1.
- `prolly-rdf/src/test/java/com/earasoft/prolly/semantic/GraphPatternBgpProperty.java` — the basic graph pattern property; pinned over the sorted-projection shapes, flags Bug 2.
- `prolly-rdf/src/test/java/com/earasoft/prolly/indexing/LeapfrogJoinUnitTest.java` — the older deterministic edge cases (disjoint/singleton/empty/identical).
- `prolly-rdf/plans/prolly-rdf-test-strategy.md` *(private monorepo work tracker)* — Steps 12 + 14, where both findings are recorded.
