---
tags:
  - security
  - format
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/the-untrusted-byte-boundary.md; links + citations adapted to this repo's layout -->

# The untrusted-byte boundary

*Where bytes you didn't write enter the system — and why every parser at that edge must reject the malformed without crashing, hanging, running out of memory, or escaping.*

> **What you'll learn** — which byte streams in prolly-port are *untrusted*
> (network sync, on-disk logs, REST input) versus *trusted*; the one invariant
> every deserializer at that edge must hold; a real length-field denial-of-
> service that shipped in `Commit.deserialize`; why content-addressing — the
> thing that *does* make stored data tamper-evident — did **not** catch it; and
> how coverage-guided fuzzing is wired as the standing enforcement.
>
> _Reading time: ~10 minutes._

> **Prerequisites** —
> [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md) (content addressing — every chunk is
> named by its own hash), [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) (`Node`,
> `Tuple`, the serialized chunk shape).

## Why it matters

A storage engine reads bytes it produced earlier and trusts them. A *networked,
persistent* storage engine also reads bytes it did **not** produce: a sync peer
streams you a pack, a crash leaves a half-written line in a log, an operator
hand-edits a ref file, a REST client posts a branch name. Those bytes arrive at
a **parser** — `Node.fromBytes`, `Commit.deserialize`, `CommitLog.Entry.parse`,
`RefsStore.validateName` — and a parser is the most dangerous code in the system,
because it turns attacker-chosen bytes into in-process state *before* any
higher-level check runs.

The boundary is the set of these parsers. The rule for every one of them is the
same, and it is absolute:

> **The boundary invariant.** Malformed or hostile input is rejected with a
> *controlled* exception. Never crash the Java virtual machine, never hang, never allocate an
> attacker-chosen amount of memory, never silently mis-parse into
> inconsistent state, never let a name escape its directory.

Miss it in one parser and a remote peer can take the server down — or worse.

## The bug: a length field is a loaded gun

`Commit.deserialize` read a commit chunk like this (the shipped code):

```java
// Commit.java — BEFORE
int pCount = bb.getInt();                       // attacker-controlled
List<byte[]> parents = new ArrayList<>();
for (int i = 0; i < pCount; i++) { ... }
long ts = bb.getLong();
byte[] authorBytes = new byte[bb.getInt()];     // attacker-controlled length
byte[] msgBytes    = new byte[bb.getInt()];     // attacker-controlled length
```

`new byte[bb.getInt()]` allocates an array whose size is read **straight from
the input**. Feed it `authorLen = 0x7FFFFFFF` and the Java virtual machine tries to allocate ~2 GB
— `OutOfMemoryError`, the whole process wobbles or dies. Feed it a negative int
and it's `NegativeArraySizeException`. A 36-byte hostile commit blob is a
denial-of-service.

The fix is the boundary invariant made literal: bound every attacker-controlled
length against the bytes that *actually remain* before allocating.

```java
// Commit.java — AFTER
int len = bb.getInt();
if (len < 0 || len > bb.remaining()) {
    throw new IllegalArgumentException(
        "malformed commit: " + field + " length " + len + " exceeds remaining bytes");
}
byte[] out = new byte[len]; bb.get(out);
```

A length can never exceed the buffer that contains it, so this rejects the
hostile blob with a clean `IllegalArgumentException` and leaves valid commits
untouched.

## Why content-addressing did *not* catch this

Here is the part worth internalizing, because the intuition is so tempting and
so wrong. prolly-port is **content-addressed**: every chunk's key *is* the hash
of its bytes, and `IntegrityVerifyingNodeStore` re-hashes on read and throws on
mismatch (see [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)). So surely a corrupt commit
is caught before `deserialize` ever sees it?

No — and the reason is a boundary, not a bug:

- **Content-addressing protects data you have already stored.** You ask the
  store for chunk `H`; it returns bytes; it checks `hash(bytes) == H`. A
  bit-flip on your disk is caught here. ✅
- **It cannot protect data arriving from a peer, because you must parse *before*
  you can verify.** On the sync receive path a remote hands you a *pack* — a
  framed stream of chunks. To compute any hash you must first parse the framing
  and the chunk bodies. A forged length field bites *during that parse*, before
  a single hash has been computed. The verification you were counting on is
  downstream of the crash. 🧨
- **And the on-disk commit log / ref files are not chunks at all** — they're
  plaintext sidecar files (`CommitLog.Entry.parse`, `RefsStore`), never
  content-addressed, editable by hand and tearable by a crash.

> **The meta-lesson — a defense has a domain, and the boundary is outside it.**
> "Everything is content-addressed, so corruption is detected" is true for
> *stored* data and false for *incoming* data. The trust boundary is exactly the
> place where your strongest guarantee does not yet apply, because the operation
> that establishes it (hashing) is downstream of the operation that is unsafe
> (parsing). Defend the parser on its own terms; don't lean on a guarantee that
> only exists after the parser has already run.

## The boundary, surface by surface

Four parsers consume untrusted/persisted bytes. Each must hold the invariant; a
[fuzz harness](#fuzzing-is-how-the-invariant-stays-true) pins each.

| Surface | Untrusted because… | Defense |
|---|---|---|
| `Node.fromBytes` (core) | sync pack bodies; stored chunks | bounds-checked parse; rejects malformed with `IllegalArgument`/`IndexOutOfBounds`/`BufferUnderflow` |
| `Commit.deserialize` (core) | sync packs (pre-verify); commit log | length fields bounded against remaining bytes (the fix above) |
| `CommitLog.Entry.parse` (rdf4j) | plaintext log; torn/edited lines | every internal failure funnels to one `IllegalStateException("malformed commit-log line")` |
| `RefsStore.validateName` (rdf4j) | REST + sync ref names → file paths | rejects `..` segments + absolute paths; **an accepted name must resolve inside `refs/`** |

`RefsStore` is the sharpest of the four: a branch name becomes a *file path*
(`dir.resolve(name)`), so a name like `../../etc/passwd` or a leading `/` would
turn a branch write into an **arbitrary-file write/delete**. Its invariant is
phrased as a path-containment property: *for any string, either `validateName`
throws, or `dir.resolve(name)` stays strictly inside the refs directory.*

## Fuzzing is how the invariant stays true

You cannot eyeball "rejects *all* malformed input" — the input space is every
byte string. So the invariant is enforced by **coverage-guided fuzzing**
([Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)): a `@FuzzTest`
feeds the parser machine-generated inputs and *fails* if any of them trips an
uncontrolled failure.

```java
// CommitDeserializerFuzzTest — the shape every boundary parser gets
@FuzzTest(maxDuration = "60s")
void deserializeRejectsMalformedWithoutCrashing(byte[] data) {
    try {
        Commit c = Commit.deserialize(data);   // any bytes Jazzer invents
        if (c != null) { c.getParents(); c.getAuthor(); /* touch the surface */ }
    } catch (IllegalArgumentException | IndexOutOfBoundsException
             | BufferUnderflowException expected) {
        // a CONTROLLED rejection is correct — caught, not a finding
    }
    // anything else (OutOfMemoryError, NPE, hang) propagates → Jazzer finding
}
```

Two modes:

- **Regression (default `mvn test`):** replays a small checked-in seed corpus —
  e.g. the `huge-author-len` (`0x7FFFFFFF`) seed that pins the denial-of-service fix forever.
  Fast, offline, runs on every build.
- **Active (`-Pfuzz`, `JAZZER_FUZZ=1`):** generates millions of inputs under a
  time budget. The hardened `Commit.deserialize` survived **7.2 M inputs in
  61 s**; `RefsStore` survived **6.0 M strings** with zero path escapes. Any
  crasher Jazzer finds is written to the corpus and becomes a permanent
  regression seed.

> **Gotcha — Jazzer's two modes read different corpus directories.** JUnit
> regression replay reads seeds from `src/test/resources/<dotted.FQCN>/<method>/`;
> active `-Pfuzz` reads (and writes) `.cifuzz-corpus/<dotted.FQCN>/<method>/`
> (gitignored). Put seeds for the default-build gate in the dotted-FQCN resource
> dir, or they won't replay.

## Takeaways

- **The trust boundary is the set of parsers that consume bytes you didn't
  write** — sync packs, on-disk logs/refs, REST input. It is the highest-value
  attack surface in the system.
- **Every length read from untrusted bytes must be bounded against
  bytes-remaining *before* `new byte[len]`.** An unbounded length-prefix is a
  one-line out-of-memory / denial-of-service.
- **Content-addressing is a defense for *stored* data, not *incoming* data** —
  you must parse before you can hash, so the verify is downstream of the unsafe
  step. Don't let a parser lean on it.
- **Names that become paths need a containment property**, not just a regex:
  *accepted ⇒ resolves inside the directory*.
- **"Rejects all malformed input" is a fuzz claim, not a code-review claim.**
  Pin each boundary parser with a `@FuzzTest`; the found crasher becomes a seed.

## Where to go next

- [the-termid-ordering-trap](the-termid-ordering-trap.md) — the companion
  "green at the top is not green all the way down" lesson: a defect that an
  end-to-end test structurally cannot see, caught only at the seam.
- [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) — the `Node` / `Tuple` / commit
  byte shapes these parsers consume.
- the-test-landscape *(private monorepo contributing doc)* — where the fuzz
  tier sits among the property, oracle, concurrency, and simulation tiers.

## Where this lives

- `prolly-core:dolthub-java-port/src/main/java/com/dolthub/prolly/Commit.java` — `deserialize`: the bounded-length-prefix reader (`readLengthPrefixed`).
- `prolly-core:dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java` — `fromBytes`: the chunk-body parser.
- `prolly-core:dolthub-java-port/src/test/java/com/dolthub/prolly/CommitDeserializerFuzzTest.java` — the commit fuzz harness + seed corpus.
- `prolly-core:dolthub-java-port/src/test/java/com/dolthub/prolly/NodeDeserializerFuzzTest.java` — the node fuzz harness.
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/RefsStore.java` — `validateName`: rejects `..` segments + absolute paths.
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/CommitLog.java` — `Entry.parse`: the single-`IllegalStateException` line parser.
- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/RefsStorePathTraversalFuzzTest.java` — the path-containment fuzz.
- `prolly-rdf4j/src/test/java/com/earasoft/prolly/rdf4j/sail/CommitLogLineFuzzTest.java` — the log-line fuzz.
- `prolly-rdf/plans/prolly-rdf-test-strategy.md` *(private monorepo work tracker)* — Step 33, the parser-fuzzing gate this doc narrates.
