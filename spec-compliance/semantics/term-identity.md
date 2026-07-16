
# Term-identity invariants

The port is content-addressed: a value's identity *is* its canonical bytes. That makes one
chain of equalities load-bearing, and it is the chain the result-comparison suites cannot
assert.

## The identity chain

For any two RDF terms `a` and `b`:

```
a, b spec-equal   ⇒   encode(a) == encode(b)        (byte identity)
                  ⇒   dict.termId(a) == dict.termId(b)   (one TermId)
                  ⇒   one row in every SPOC/POSC/OSPC/CSPO index
                  ⇒   logically-equal graphs share a root hash
```

Each arrow is "by construction" given the one before it — the dictionary keys on the
encoded bytes, the indexes key on `TermId` tuples (`SpocKey`), and the root hash is a
pure function of the index contents. So the **whole chain rests on the first arrow**:
the encoder must emit identical bytes for spec-equal terms. That first arrow is the
subject of [`canonicalization.md`](canonicalization.md); the consequences are below.

## `IDENT-1` — equal terms collapse to one stored triple

| Field | Value |
|---|---|
| **Spec** | RDF 1.1 Concepts §3: a graph is a **set** of triples; equal triples are not duplicated. Triple equality is component-wise term equality. |
| **Invariant** | Inserting `<s> <p> a` and `<s> <p> b` where `a` and `b` are spec-equal terms leaves the graph with **one** triple. |
| **Port behavior** | Holds *iff* `encode(a) == encode(b)` (the chain above). With `CANON-LANG-1` fixed, case-variant langStrings collapse; before the fix they did not (two `TermId`s, two index rows). |
| **Validated by** | Two instruments, both confound-free: (1) byte identity at the **codec** (`CANON-LANG-1`); (2) **content-address identity at the Sail** — `prolly-rdf4j/.../sail/LangTagContentAddressInvariantTest`. The latter builds two independent in-memory graphs (one per casing) and asserts all five data-bearing roots (dict + SPOC/POSC/OSPC/CSPO) are **byte-identical** — identical dict root ⇒ one `TermId`, identical index roots ⇒ one storage key. It carries a **control arm** (a genuinely different tag `"x"@fr` yields *different* roots) so the equality is evidence, not a tautology. It deliberately does **not** count statements. |
| **W3C-visible?** | No — graph isomorphism uses the same term-equality that defines the duplicate away. |

### The `Statement.equals` confound (read before writing an identity test)

The intuitive test — "insert both, then assert one statement via
`conn.getStatements(...)`" — is **invalid**: `Statement.equals` is component-wise
`Value.equals`, and `Value.equals` is case-insensitive for langStrings (and value-based
for numerics). So any `Set<Statement>`, and RDF4J's own statement handling, **collapse
the two regardless of how many `TermId`s the store actually minted.** The test reports
one statement whether the store is correct (one `TermId`) or buggy (two). It measures
the oracle, not the store.

This is the same blindness that hides the bug from the W3C suite, surfacing one layer up.
The escape is to measure where the distinction is still visible:

- **Byte identity at the encoder** — `encode(a) == encode(b)`. Confound-free by
  definition; this is what the catalog uses.
- **Root-hash equality across two stores** — build graph A with `a`, graph B with `b`,
  assert identical data-root hashes. Confound-free (the hash is over bytes, not over
  `Value.equals`). **Written:** `prolly-rdf4j/.../sail/LangTagContentAddressInvariantTest`
  — with a control arm proving a different term yields a different root.
- **Dictionary `TermId` equality** — `termId(a) == termId(b)`. Confound-free, but
  byte-identity already implies it.

**Never** validate a term-identity invariant with a count taken from an
RDF-equality-based collection.

## Counter-example: an identity-shaped bug the W3C suite *did* catch

Not every identity bug is invisible — the contrast sharpens when the suite is the right
instrument. The default-graph / named-graph context-isolation bug (fixed 2026-05-15,
`prolly-rdf4j/.../sail/ProllySailContextTest.java`) made a `null` context return *every*
graph's statements. The W3C **update** suite caught it immediately, because each update
test compares the post-update store **graph by graph** — so "default-graph reads leak
named-graph triples" changed the *answer* of ~60 of the 90 update tests. That bug lived
in a dimension the result oracle *does* measure (which graph a triple is in), so the
result-comparison instrument was exactly right for it.

The difference is the whole thesis of this catalog: **match the instrument to the
invariant.** Graph membership → the W3C suite sees it. Canonical-byte identity under an
equality the oracle erases → only a byte-level test sees it.

## Where this lives

- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/Dictionary.java` — bytes → `TermId`.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/SpocKey.java` — the `TermId` tuple the indexes key on.
- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/LangTagContentAddressInvariantTest.java` — `IDENT-1`'s confound-free validator (data-root identity + control arm).
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java` — `isDataTreeNoOp` / `rootHashOrNull`: the data-root comparison the test rests on.
- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/ProllySailContextTest.java` — the counter-example: an identity bug the W3C suite *did* catch.
- `prolly-rdf4j-compliance/src/test/java/com/earasoft/prolly/rdf4j/compliance/ProllySparql11UpdateComplianceTest.java` — the graph-by-graph update oracle.
