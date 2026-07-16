
# Theory of Operation — How URDNA2015 Actually Works

The W3C spec describes the algorithm in pseudo-code. This doc
explains *why* the algorithm has the shape it does — the
intuitions, the inductive structure, the reason each phase exists.
Read this first, the spec second.

---

## 1. The problem in one paragraph

RDF lets you write triples about *anonymous* resources — blank nodes
— whose identity is scoped to the document. When two parsers read the
same N-Triples file they mint different blank-node labels. So the
*same RDF graph* serialises to *different byte sequences* depending
on who serialised it. A versioned store can't recognise structurally-
equivalent graphs as identical, can't merge them sensibly, and can't
diff them without false changes. URDNA2015 fixes this by computing a
deterministic labelling — same graph in, same labels out, regardless
of who minted the originals.

---

## 2. Why simple hashing isn't enough

The natural first attempt: hash each blank node's local
neighbourhood, use the hash as its canonical name. This works for
many cases (it's exactly what `SimpleFirstDegreeCanonicalizer`
does) but breaks on three patterns:

### 2.1 Cyclic blank-node pairs

```
_:b1 ex:knows _:b2 .
_:b2 ex:knows _:b1 .
```

`_:b1`'s neighbourhood: "knows some other blank node, known by some
other blank node." `_:b2`'s neighbourhood: identical. Two distinct
blank nodes, same hash.

You can't tell them apart by looking at their immediate
neighbourhoods. You'd need to look at *their neighbours' neighbours*.
But `_:b1`'s neighbour is `_:b2`, whose neighbour is `_:b1` again —
recursion all the way.

### 2.2 Symmetric subgraphs

```
_:b1 ex:knows ex:bob .
_:b1 ex:age 30 .
_:b2 ex:knows ex:bob .
_:b2 ex:age 30 .
```

Two genuinely-symmetric blank nodes. RDF semantically treats them
as the same — there's no test that distinguishes one from the other.
Any deterministic algorithm must assign them the same canonical name
(or fail).

### 2.3 Long chains of indistinguishable nodes

```
_:b1 ex:next _:b2 .
_:b2 ex:next _:b3 .
_:b3 ex:next ex:end .
_:b4 ex:next _:b5 .
_:b5 ex:next _:b6 .
_:b6 ex:next ex:end .
```

`_:b1` and `_:b4` are symmetric (both are "head of a 3-node chain
ending at ex:end"). `_:b2`/`_:b5` and `_:b3`/`_:b6` are symmetric
pairwise. Looking 1 hop ahead: identical. 2 hops: identical. 3 hops:
finally different — but only because the chain terminates.

These three patterns share one structural truth: **distinguishing
blank nodes requires propagating information through the graph
until something asymmetric is found, or proving no asymmetry exists.**

---

## 3. The URDNA2015 idea, in plain English

Pick a single blank node B. Pretend you've assigned it the canonical
name `_:c14n0`. Now look at all the blank nodes B touches — call
them B's *related* blank nodes. They get tentative canonical names
`_:b0`, `_:b1`, … in *some order*. Walking through B's neighbourhood
with those tentative names yields a *path string* — basically a
fingerprint of B's local view of the graph.

**Different choices of order produce different path strings.** The
question is: which order should we use?

URDNA2015's answer: **try every order; pick the one whose path
string is lexicographically smallest.** That's the canonical fingerprint
for B. Doing the same for every other blank node, and assigning the
final canonical names in order of those fingerprints, gives a
deterministic labelling.

This is the entire trick. Everything else is engineering.

---

## 4. Why permutations

Suppose `_:b1` has two related blank nodes `_:x` and `_:y` — both
indistinguishable so far (same first-degree hash). Should we assign
`_:b0` to `_:x` and `_:b1` to `_:y`, or the other way around?

Both are valid choices. They produce two different path strings.
Pick the one that's lex-smaller. The choice is deterministic in
*the path strings*, not in the input names.

For N indistinguishable related blank nodes, there are N!
permutations. URDNA2015 enumerates them.

This is the source of the algorithm's worst-case super-polynomial
behaviour: a graph with K indistinguishable groups of N related
blanks each has roughly (N!)^K permutations to try.

---

## 5. Why recursion

For each permutation, when you assign `_:b0` to `_:x`, you may need
to recursively explore `_:x`'s own neighbourhood to give *it* a
fingerprint. That recursive call returns the fingerprint for `_:x`,
which becomes part of `_:b1`'s path string.

Critically, **the recursion uses the same algorithm**. The same
"try every permutation, pick smallest path" rule applies at every
depth. This makes the canonical labels stable: same algorithm at
every level means same result.

The recursion terminates because each level issues at least one new
canonical name, shrinking the set of un-issued blank nodes. Worst
case: every blank node is mutually-related, so the recursion depth
equals the blank-node count.

---

## 6. Why the lex-smallest-path rule

Why pick the *smallest* path string? Why not largest, or median?

Two reasons:

1. **Determinism.** Any consistent choice rule produces deterministic
   output. Smallest happens to be cheap to evaluate (compare strings
   byte-by-byte; early-exit at first differing byte).
2. **Stability under input renames.** If we rename `_:b1 → _:b9999`
   in the input, the algorithm still picks the same canonical labels
   because:
   - First-degree hashes don't depend on input labels (they
     substitute placeholders).
   - The smallest-path-rule depends on the structure, not on input
     labels.
   - So same structural graph → same canonical output, regardless of
     how blank nodes were minted upstream.

This is the spec's correctness theorem: same RDF graph, same
canonical bytes.

---

## 7. The IdentifierIssuer's role

The recursive algorithm needs to *try* assigning names to blank
nodes without *committing* — if a permutation turns out to produce
a worse path, you back up and try the next permutation. The names
you assigned during that try need to be discarded.

`IdentifierIssuer.copy()` makes this safe:

```
for each permutation P of related blank nodes:
    issuerCopy ← issuer.copy()             # discardable scratch issuer
    for each blank node in P:
        issuerCopy.issue(blank)            # tentative assignment
        path += issuerCopy.nameOf(blank)
    # ... compute path, compare to chosenPath ...
# After loop: chosenIssuer is the issuer-state for the winning P
issuer.merge(chosenIssuer)                 # commit only the winner
```

The "merge" step (re-issuing winners' ids into the outer issuer)
keeps idempotency: re-issuing a name returns the same name, so
merging is effectively "ensure these ids are issued in the outer
issuer, in this order."

Without `copy()`, you'd need to rollback by tracking what to
unissue — much harder to get right.

---

## 8. Two-phase structure: cheap-first, expensive-as-needed

URDNA2015 starts with a Phase 1 that assigns canonical names to
every blank node *whose first-degree hash is already unique*. For
many graphs, that's every blank node — first-degree hashing alone is
all you need. The N-degree algorithm only fires for blank nodes
that genuinely collide on first-degree.

This is the same insight `CascadeCanonicalizer` codifies at the
library level: try the cheap algorithm first, escalate only on
collision.

For real-world RDF data, the cheap-first ratio is high. A typical
audit-event reified cluster has 5-8 blank-node-anchored events with
distinguishable timestamps; their first-degree hashes are all
distinct, and the N-degree disambiguation never runs. The expensive
case is a rare problem you pay for only when needed.

---

## 9. The path encoding — why every byte matters

The path string built during a permutation is a concatenation of:

- Canonical names already-issued for related blank nodes
- Or freshly-issued temp names from the local issuer
- With position markers (`p` = outgoing, `r` = incoming) telling
  the algorithm which side of the edge it was on
- And recursion-result markers (`<hash>`) for nested
  HashNDegreeQuads calls

The path encoding has to be unambiguous: two different orderings
must produce different path strings whenever they're structurally
different. The W3C spec specifies exact byte sequences for each
separator (single space, single `<`, single `>`, etc.).

This precision matters because path strings are compared
byte-by-byte. A different separator in your implementation means a
different ordering choice, which means different canonical names.
W3C test vectors check byte-exact output specifically to catch
this.

---

## 10. Why the algorithm is super-polynomial worst case

The N-degree disambiguation enumerates permutations of related
blank nodes. With K indistinguishable groups of N nodes each, the
permutation count is `(N!)^K`.

Real-world graphs almost never hit this — the typical case has
small K (most graphs have at most a handful of first-degree
collision groups) and small N (most groups have 2-4 related
blank nodes).

The pathological case: a fully-symmetric graph where every blank
node is indistinguishable from every other at every level. The
algorithm correctly terminates, but the cost is proportional to
the graph's automorphism group size. For research-grade adversarial
inputs (specific graph constructions), this is unbounded.

This is why **time budgets matter in production**: even a correct
implementation can be made to spin for hours on adversarial input.
Fail-closed is the answer; the substrate refuses the commit and the
operator investigates.

---

## 11. Why URDNA2015 won't go away

The algorithm has a few characteristics that make it durable:

1. **It's the W3C standard.** Other algorithms exist but lack
   adoption. Building on the standard means tooling, test vectors,
   and reference implementations are available.
2. **It's strictly more expressive than simpler approaches.** Any
   structurally-distinguishable graph is distinguished; only
   genuinely-symmetric pairs collapse to the same canonical label
   (which is *correct* RDF semantics, not a bug).
3. **It's a closed-form computation.** Given finite blank-node count,
   the algorithm always terminates with a deterministic result. No
   parameters to tune, no heuristics to second-guess.

The known successors (RDFC-2.0 drafts, etc.) are likely refinements
of URDNA2015's structure, not departures from it. Building
correctly-shaped infrastructure around URDNA2015 today positions
us to swap in successors with minimal churn — the SPI accepts any
`RdfCanonicalizer`.

---

## 12. Common conceptual confusions

### "Aren't blank nodes just IRIs with `_:` prefix?"

Syntactically yes; semantically no. RDF 1.1 §3.4 says blank nodes
have *no global identity*. The `_:foo` label is a parser
convenience — two parsers can use `_:foo` for different anonymous
resources, or different labels for the same resource. URDNA2015
treats blank-node labels as variables to be solved for, not as
fixed identifiers.

### "Why not just use UUID for every blank node?"

UUIDs are global identifiers — they survive across parses. That
*breaks* RDF blank-node semantics: two structurally-equivalent
graphs with UUID labels would be treated as different graphs.
Canonical labelling is the opposite of UUID assignment: it
preserves blank-node anonymity while making the *graph* identity
deterministic.

### "Why is the algorithm based on hashing? Why not graph isomorphism?"

Graph isomorphism is NP-intermediate in general (no known
polynomial algorithm, no known NP-hardness proof). URDNA2015
sidesteps that by combining hashing (fast, deterministic) with
permutation enumeration (expensive only on the symmetric cases
where isomorphism is actually hard). For most real-world graphs,
the hash-based shortcuts mean the algorithm runs in near-linear
time.

### "Couldn't a smart serialiser just produce the same labels every time?"

Only if it has access to the same graph. Two parties producing the
same data independently — two clients submitting different parses
of an N-Triples file, two services serialising the same in-memory
graph with different blank-node minting — would still produce
different labels. URDNA2015 is the only way to recover a stable
encoding without coordination.

---

## 13. Practical takeaways for implementers

- **Build first-degree first.** It's the cheap baseline; most graphs
  resolve there. Get it right before touching N-degree.
- **Cascade canonicalizers, don't replace.** Cheaper canonicalizers
  stay useful even after URDNA2015 lands; they're the fast path.
- **Test against W3C vectors byte-exactly.** Anything less and you
  haven't shipped URDNA2015; you've shipped a graph-isomorphism
  approximation that happens to pass your own tests.
- **Time budget is mandatory.** No production implementation runs
  the algorithm without an interrupt-cooperative timeout.
- **Trust the W3C standard.** Don't invent extensions; the algorithm
  is finicky and "small tweaks" silently break correctness.

---

## 14. Related reading

- W3C RDF Dataset Canonicalization 1.0 — https://www.w3.org/TR/rdf-canon/
  (the spec; this doc is its companion)
- Practical implementation guide — a private strategy note
- "Canonical Labelling of Graphs" (Babai et al.) — academic
  background on the broader graph-isomorphism problem URDNA2015
  sidesteps.
- "Signing RDF Graphs" (Carroll, 2003) — earlier work on stable
  RDF encoding that motivated the W3C standardisation.
- Strategic context — a private strategy note
