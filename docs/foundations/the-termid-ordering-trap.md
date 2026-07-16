---
tags:
  - format
  - rdf
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/the-termid-ordering-trap.md; links + citations adapted to this repo's layout -->

# The TermId ordering trap

*How a 64-bit id with an unsigned "flag bit" got stored in a column the tree compares as a signed integer — and why two correct-looking pieces of code can sort the same bytes in opposite directions.*

> **What you'll learn** — what a `TermId` is and why its top bit is a *flag*,
> not a sign; how the SPOC index stores it in a column whose comparator is
> *signed*; why that makes the index's sort order disagree with
> `TermId.compareTo` for extension ids; exactly how far that blast radius
> reaches (and where it stops); and the general lesson — a *semantic* type
> mismatch (unsigned value in a signed column) that the type system can't see
> because both are "8 bytes".
>
> _Reading time: ~10 minutes._

> **Prerequisites** —
> [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) (content addressing, why key *order*
> shapes the tree), [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) (`Tuple`,
> the `Int64` column type, the optional Dolt bit-compatibility question).

## Why it matters

Almost every read in the RDF Sails is a lookup into one of four permutation
indexes — SPOC, POSC, OSPC, CSPO. Each index key is four `TermId`s (one per
quad position) packed into a `Tuple`. **The order those keys sort in *is* the
index** — it decides what a prefix scan returns, what order results come back,
and, because this is a prolly tree, *where the chunk boundaries fall and what
the root hash is*. So when the thing that defines "less than" for a `TermId`
disagrees with the thing that defines "less than" for an index column, that's
not a cosmetic wart — it's two different answers to the question the whole
storage layer is built around.

That is exactly what's here. It's latent, it *was* documented in two files
that contradicted each other (since reconciled — see below), and it was
surfaced by a property test that deliberately built a value the rest of the
system almost never produces.

## What a TermId is — the top bit is a flag, not a sign

A [`TermId`](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) is the 64-bit number the
dictionary assigns to an RDF term. Its top bit is reserved as the
**extension flag**:

```java
// TermId.java
public static final long EXTENSION_FLAG = 0x8000_0000_0000_0000L;
public static final long NATURAL_MASK   = 0x7FFF_FFFF_FFFF_FFFFL;
```

- **Top bit clear (natural id):** the low 63 bits are the hash of the term.
- **Top bit set (extension id):** the low 63 bits are an extension-table slot,
  handed out only when a *hash collision* would otherwise map two different
  terms to the same natural id.

Because the top bit is a *flag*, `TermId` defines its order as **unsigned**:

```java
// TermId.java
@Override
public int compareTo(TermId other) {
    return Long.compareUnsigned(this.value, other.value);  // UNSIGNED
}
```

Under unsigned compare, a natural id (top bit 0) is always smaller than an
extension id (top bit 1). So `TermId.compareTo` says: **natural before
extension.** That's the intended contract.

## Where it goes wrong — the column comparator is signed

A `SpocKey` packs its four `TermId`s into a `Tuple` whose descriptor types
every column as `Int64`:

```java
// SpocKey.java
public static final TupleDescriptor DESCRIPTOR = new TupleDescriptor(List.of(
    new Type(Encoding.Int64, false),   // ← Int64 column, nullable=false
    new Type(Encoding.Int64, false),
    new Type(Encoding.Int64, false),
    new Type(Encoding.Int64, false)));
```

(The one-argument `TupleDescriptor(List<Type>)` constructor defaults
`binaryParity` to `false`, so the descriptor takes the type-aware comparison
path below rather than a pure-byte one.)

The prolly tree compares keys through `TupleDescriptor.compare`, which delegates
per column to `TypeCodec.compareAt` — the offset-based fast path — and for an
`Int64` column that is:

```java
// TypeCodec.compareAt
case Int64 -> Long.compare(a.get(LE_I64, aStart), b.get(LE_I64, bStart));   // SIGNED
```

`Long.compare` is **signed**. And under signed compare an extension id — top
bit set — is a *negative* long. A negative long sorts **before** a positive
one. So the SPOC index says: **extension before natural** — the *opposite* of
`TermId.compareTo`.

Two pieces of code, both reasonable in isolation, sorting the identical 8 bytes
in opposite directions:

| For an extension id vs a natural id | Verdict |
|---|---|
| `TermId.compareTo` (unsigned) | natural **<** extension |
| SPOC index column (`Int64` → signed) | extension **<** natural |

> **The bug, in one line.** A value whose ordering semantics are *unsigned*
> was stored in a column whose comparator is *signed*. The type system saw
> only "an 8-byte integer" and had nothing to complain about.

The two source files once disagreed in their own javadoc. `SpocKey` has always
been honest:

> *"extension TermIds sort before natural ones (signed-negative < signed-positive)… worth documenting for range queries."*

`TermId`'s javadoc originally claimed the reverse — *"Natural ids therefore come
before extension ids **in the index**"* — true for `TermId.compareTo`, but false
for the SPOC index that actually stores them. That contradiction has since been
corrected (the remedy in the Gotcha below): `TermId`'s javadoc now carries an
explicit *"Caveat — the SPOC index does NOT use this order"* and points back to
this doc, so the two files now agree on the index's signed order.

## How far the blast radius reaches — and where it stops

This is the part that matters for deciding what to *do* about it. The
disagreement only bites when (a) an **extension id** exists — which requires a
hash collision — **and** (b) something depends on the two orders agreeing.
Walking the read paths:

- **Point lookups (the common case): unaffected.** "Find the triple `(s,p,o)`"
  builds the exact key bytes and seeks to them. Equality is byte-equality;
  signed-vs-unsigned never enters. ✅
- **Prefix scans ("all `?p ?o` for subject `S`"): correct set, possibly
  reordered.** The prefix column is matched by exact equality, so *which* rows
  come back is right. Only the *iteration order within* the prefix could differ
  from what `TermId.compareTo` implies — and only if extension ids appear in
  the scanned columns. ✅ for correctness, ⚠️ for order.
- **True range scans over a `TermId` interval: would diverge.** "All ids
  between X and Y" computed with `TermId.compareTo` would mis-predict the
  index's signed order across the natural/extension boundary. In practice RDF
  doesn't range-scan over hash-shaped ids, so this path is theoretical — but
  it's the one to remember if a future feature adds it. ⚠️
- **Tree shape / root hash: self-consistent, and provably so.** The comparator
  decides key order → chunk boundaries → root hash. *Every* comparator in the
  storage stack is signed `Long.compare`, and they agree with each other:
  `TypeCodec`'s `Int64` branch (the tree / `StaticMap`),
  `SpocIndex.FAST_KEY_COMPARATOR` (the Sail write buffer), and
  `Int64Key.COMPARATOR` (the dictionary). A key is therefore always *sought* in
  the same order it was *placed* — so no triple is ever lost or mis-found, with
  or without extension ids. ✅
- **`TermId.compareTo` itself: dead in production.** Here's the kicker — the
  *only* unsigned comparator, `TermId.compareTo`, has **zero callers** in main
  code (no direct call, no `TreeSet<TermId>`, no `.sorted()`). The two orders
  never actually meet at runtime. So this isn't a behavioral fault at all today;
  it's a **latent contract contradiction**. The real residual risk is future
  code: the first time someone reaches for the "natural" `TermId.compareTo` to
  compute a range bound or build a sorted set, they inherit the *unsigned* order
  and silently disagree with the signed index. 🧨 (A loaded gun on the table,
  not a fired one.)

> **Gotcha — why "just make `compareTo` signed" is not a one-liner.** Changing
> either comparator changes the *on-disk tree shape* for any store that
> contains an extension id. Per the project's pre-1.0 rule, that's a real
> format migration with a one-shot script — not a defensive reader, and not a
> quiet code tweak. The cheap, safe action is the opposite: correct the
> `TermId` javadoc's false claim and *pin both orders with a test* so the
> mismatch can't drift unnoticed. That's what was done.

## The lesson: physical type ≠ semantic type

The trap generalizes well beyond this one field:

> When a value's *meaning* carries an ordering (here: unsigned, because the top
> bit is a flag) but you store it in a slot typed only by its *physical shape*
> (here: a signed `Int64` column), the comparator silently follows the physical
> type. Nothing fails to compile. Nothing throws. The two orders agree for
> every value you're likely to test by hand — and disagree exactly on the rare
> value (an extension id) you'd never think to generate.

That last clause is why this surfaced where it did. Random `TermId` generation
produces natural ids (top bit clear → non-negative → signed == unsigned) almost
always, so a naive round-trip test passes forever. The isolation suite
(`SpocKeyTest`) catches it only because it *deliberately constructs* an
extension id and asserts the two orders against each other:

```java
// SpocKeyTest.extensionIdsExposeTheSignedVsUnsignedOrderingMismatch
TermId natural   = TermId.ofNatural(1L);        // 0x0000…0001  signed-positive
TermId extension = TermId.ofExtensionSlot(1L);  // 0x8000…0001  signed-NEGATIVE

assertTrue(natural.compareTo(extension) < 0);            // unsigned: natural first
assertTrue(indexCompare(extensionKey, naturalKey) < 0);  // signed:   extension first
// → the two DISAGREE, and the test pins both so neither drifts silently.
```

**The maxim:** to find a bug that only the *unusual* value triggers, you have
to *manufacture* the unusual value — uniform-random generation will sail right
past it. Pin the surprising behavior the moment you understand it, so a later
"cleanup" has to confront it on purpose.

## Why the SPARQL compliance suite passed anyway

The W3C SPARQL query + update compliance suites are green. So is every
integration test. If there's an ordering inconsistency in the index, *why didn't
the layer above catch it?* The honest answer has three parts, and together they
are the most useful thing in this doc — because they generalize to a whole class
of bug.

**1. The trigger never fires — the input distribution doesn't reach it.**
An extension id exists *only* after a 63-bit hash collision. The natural-id space
is 2⁶³; by the birthday bound a collision needs on the order of **2³¹·⁵ ≈ 3
billion distinct terms** before it's even probable. The W3C datasets hold dozens
to low hundreds of distinct terms. So *every* `TermId` in *every* compliance test
has its top bit clear — a natural id. And for top-bit-clear longs, **signed
compare and unsigned compare are bit-identical**. The two orders agree on every
value the suite ever produces. The bug's precondition is simply never met;
integration tests sample the realistic-but-small middle of the distribution, and
this defect lives in a tail nobody samples by hand.

**2. The effect is below the observability floor — it can't cross the SPARQL
interface.** Even *with* an extension id, SPARQL never exposes raw `TermId`
order. A query answer is set membership — "does `(s,p,o)` exist?" — resolved by
*exact-key* lookup, which is order-independent. Result *ordering* is either
unspecified (no `ORDER BY` → the SPARQL spec says the solution bag is unordered,
so the compliance harness compares result **sets**, not sequences) or recomputed
at the value layer by SPARQL's own term-ordering rules (`ORDER BY` decodes
TermIds back to RDF `Value`s and sorts *those* — it never consults index byte
order). The index's internal sort order is invisible through the SPARQL
interface. A test cannot assert on what the interface does not surface.

**3. There is, today, nothing to catch — the live system is consistent.** The
clincher (previous section): the mismatch is between `TermId.compareTo` and the
index comparators, but the index never calls `TermId.compareTo`, and nothing
else does either. Every comparator that actually runs is signed and mutually
consistent, so runtime behavior is correct. The defect is a *latent contract
contradiction*, not a *behavioral fault* — and behavioral tests can only catch
behavioral faults.

> **The meta-lesson — green at the top is not green all the way down.** A test
> catches a defect only when it can both **trigger** it (an input reaches the
> precondition) and **observe** it (the effect crosses the interface the test
> watches). At the SPARQL layer this defect is *untriggerable* (no collisions in
> small data) **and** *unobservable* (order doesn't cross the boundary) **and**
> presently non-behavioral (the contradicting method is dead). "All compliance
> tests pass" is therefore *silent* about it — not reassuring. Each invariant
> must be tested at the layer where it is both **reachable** and **observable**.
> The index-ordering invariant lives in the codec: reachable only by
> hand-constructing an extension id, observable only by comparing the two
> comparators directly. Which is exactly what the `prolly-codec` isolation test
> does — and why the test-strategy plan pushes property-based + isolation tests
> down to the seams, where they can manufacture the tail input and watch the
> internal contract that an end-to-end test structurally cannot.

## The takeaway

`TermId` orders unsigned; the SPOC index orders signed; for extension ids they
point opposite ways. The damage is bounded — equality-based lookups and the
*correctness* of prefix scans are untouched; only iteration order and a
hypothetical range scan diverge, and only when a hash collision has minted an
extension id. It stays a latent footgun rather than an active bug **because the
index is internally consistent with itself**. The right response wasn't to
"fix" a comparator (that's a root-hash-changing migration) but to make the
mismatch *loud*: contradictory docs corrected, both orders pinned by a test.

## Where to go next

- [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) — the `Tuple` + `Int64` column
  encoding these keys ride in, and why content-addressing makes tree shape
  load-bearing.
- [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) — why key *order* (not just key
  *bytes*) determines chunk boundaries and the root hash.
- [structural-sharing-and-churn](structural-sharing-and-churn.md) — the
  companion "the test model was wrong, not the engine" lesson, also surfaced by
  a property test shrinking to a tiny case.

## Where this lives

- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermId.java` — the id; `compareTo` is `Long.compareUnsigned`; the `EXTENSION_FLAG` top bit.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/SpocKey.java` — `DESCRIPTOR` types each column `Encoding.Int64`; its javadoc documents the signed sort.
- `prolly-core:dolthub-java-port/src/main/java/com/dolthub/prolly/TypeCodec.java` — `compareAt()` (the live per-column dispatch; `compare()` is the equivalent slice-based form): the `Int64 -> Long.compare` branch that makes the column comparator signed.
- `prolly-core:dolthub-java-port/src/main/java/com/dolthub/prolly/TupleDescriptor.java` — `compare()`: walks columns and delegates per-column to `TypeCodec.compareAt` (the offset-based fast path).
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/index/SpocIndex.java` — `FAST_KEY_COMPARATOR`: the Sail write-buffer comparator, signed `Long.compare` on all four columns (consistent with the tree).
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/Int64Key.java` — `COMPARATOR`: the dictionary's single-column comparator, also signed.
- `prolly-codec/src/test/java/com/earasoft/prolly/rdf4j/index/SpocKeyTest.java` — `extensionIdsExposeTheSignedVsUnsignedOrderingMismatch` pins both orders.
- `prolly-codec/src/test/java/com/earasoft/prolly/rdf4j/term/TermIdTest.java` — pins `compareTo` == `Long.compareUnsigned` and the natural-before-extension contract.
