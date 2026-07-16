
# Cross-Language Bit-Compatibility — Findings

> **Historical record (2026-05-29).** This documents an experiment run in the private
> monorepo; the cross-language fixture loop, `dev-scripts/`, and the Go toolchain steps
> it mentions live there, not in this repo. The findings travel because they define the
> parity POSTURE this ring builds on: Layers 0–2 parity with Dolt holds as
> *characterization*, byte-for-byte bit-compatibility is *optional and deliberately not
> pursued* — the port's own deterministic format is the contract.

**Date:** 2026-05-15
**Verdict:** the Java port is **not** byte-compatible with Dolt v2.0.3 — the
divergence is **multi-layer**. One layer fixed; one diagnosed and open; the
rest not yet reached.

This records the first real run of the cross-language fixture loop (see
`README.md`). It supersedes the "engine-port fidelity unverified"
line in a private strategy note §8 — the loop has now been *run*.

## What was run

| Piece | Value |
|---|---|
| Go toolchain | go1.26.3 (installed via `dev-scripts/install-go.sh`) |
| Dolt reference | **v2.0.3** (released 2026-05-14), cloned locally; `go.mod` `replace` points at it |
| Corpus | 1000 tuples — keys `golden-00000`…`golden-00999`, values `payload-0`…`payload-999` |
| Go-side root | `b96e85d18e25ff65247531af463eccb20bc936bc` (8 chunks) |
| Java pinned root | `1d9d81f40033ea3955bb85048704cb1fa53f710a` (`BoundaryGoldenVectorTest`) |

`gen_fixture.go`'s `buildTreeWithDolt` is now wired against Dolt v2.0.3 (no
longer a stub) — it compiles and runs; the generated fixture is internally
self-consistent (every `nodes/<hash>.bin` hashes to its filename).

## Oracle results

| # | Oracle | Result |
|---|---|---|
| 1 | Hash self-consistency (SHA-512/20) | ✅ pass |
| 2 | Node parse + tree walk | ✅ pass **after the Layer-1 fix below** (was ❌) |
| 3 | Tree content matches manifest | ❌ fail — `IndexOutOfBoundsException` in `Tuple.getField` |
| 4 | Pinned-root comparison | not reached |

## The layers

### Layer 0 — hashing ✅
Go and Java agree on SHA-512/20. Format-agnostic; oracle 1 confirms it.

### Layer 1 — chunk framing ✅ (fixed this run)
**Was broken.** Dolt v2.0.3 frames every prolly-node chunk as a `serial`
message (`go/store/prolly/serial/fileidentifiers.go`):

```
byte 0      NomsKind == SerialMessage          (observed: 0x1b)
bytes 1..3  big-endian uint24 — FlatBuffer payload size (= file size − 4)
bytes 4..   the FlatBuffer message  ([u32 root uoffset]["TUPM" id]…)
```

So the `"TUPM"` FlatBuffer file-identifier sits at **offset 8**. The port's
`Node.fromBytes` checked the bare-FlatBuffer offset **4**, missed it, and fell
back to `SimpleNodeSerializer` (the port's own non-Dolt format) → crash.

Byte evidence: root chunk `1b 00 01 68 | 28 00 00 00 | 54 55 50 4d` — prefix
size `0x000168` = 360 = 364-byte file − 4 ✓; leaf `1b 00 0f 90 …` — `0x000f90`
= 3984 = 3988 − 4 ✓.

**Fix applied:** `prolly-port-core/.../com/dolthub/prolly/Node.java`,
`fromBytes` — when the bare-FlatBuffer check fails, retry on a 4-byte-stripped
slice (`SERIAL_MESSAGE_PREFIX_SZ = 4`, matching Dolt's `serial.MessagePrefixSz`).
Additive — a new branch, only reached by buffers that previously crashed.
Status: **applied, uncommitted**, and correct (oracle 2 passes after it).

### Layer 2 — FlatBuffer node schema ✅
Oracle 2's tree walk succeeded after the Layer-1 fix — `parseFlatbuffer` /
the generated `serial.ProllyTreeNode` accessors read `treeLevel`, `treeCount`,
key-offset count, and child addresses correctly. The node-table schema is
compatible for the fields the walk exercises.

### Layer 3 — `val.Tuple` field layout ❌ (diagnosed, open)
Dolt v2.0.3 `go/store/val/tuple.go`: a tuple is
`[field values][field offsets][count:uint16]`, and *"the offset for the first
field is always zero and is therefore omitted"* — so Dolt stores **`count − 1`**
offsets.

The port's `Tuple.getFieldSegment` computes offset positions as
`size − 2 − (count − index)·2` — it expects **`count`** offsets, one per field
including the first. **Off by one slot.**

For a single-field key (`count = 1`), the port reads field 0's "end offset" 2
bytes inside the *string data* instead of the footer — it reads the ASCII byte
`'0'` (`0x30` = **48**) and tries to slice 48 bytes from a 15-byte tuple →
`IndexOutOfBoundsException … new length = 48`.

**Not fixed.** Unlike Layer 1, this is *not* a safe additive change:
`Tuple.getFieldSegment` is the core decode path for every tuple read, and the
port's own tuple *writer* must be checked first — switching the reader to
Dolt's omit-first-offset scheme could break the port reading its own data.

### Layer 4 — BuzHash root / chunk boundaries — not reached
Oracle 4 (compare the Go root against the Java pin) never ran; oracle 3 stops
first. The root hashes are known to differ (`b96e85d1…` vs `1d9d81f4…`), but
that is fully explained by Layers 1 + 3 — the two sides serialise nodes and
tuples in different formats — so it is not yet evidence of a BuzHash
divergence.

## Is bit-compatibility with Dolt required? No.

For **RDF to work correctly on prolly trees**, the engine needs three
properties — none of which is "match Dolt's bytes":

1. **Internal consistency** — the port's own writer and reader agree.
   `BoundaryGoldenVectorTest` confirms it (the port round-trips its own
   format and pins a stable root).
2. **Determinism** — identical logical input always yields identical trees
   and content hashes. This is what makes content-addressing, dedup, and
   structural diff/merge work; it is a property of the port *by itself*.
3. **Correct algorithms** — valid CDC (BuzHash) chunking, cursors,
   `applyMutations`, a sound Merkle structure.

A prolly tree with its *own* node format, tuple layout, and framing — if it
satisfies 1–3 — runs the entire prolly-rdf4j feature set correctly:
versioning, diff, merge, time-travel. The format being *Dolt's* is irrelevant
to RDF correctness.

The cross-language fixture's purpose was narrower: use Dolt's Go
implementation as an **independent differential oracle** to cross-check the
port's chunking/hashing. That is a testing convenience, not a correctness
requirement. So the finding above means **Dolt v2.0.3 cannot serve as a
drop-in differential oracle** — *not* that RDF-on-prolly is broken.

## Conclusion / recommendation

The port's on-disk format was built against an older Dolt; reconciling it to
v2.0.3 byte-for-byte is a multi-layer project — chunk framing ✅, `val.Tuple`
layout ⬜, oracle 3/4 ⬜, write-side framing ⬜. But since bit-compatibility is
**optional**, this is a cost/benefit call, and the benefit is thin:

- As a differential oracle, Dolt is **replaceable** — the port's own golden
  vectors (`BoundaryGoldenVectorTest`) plus property tests (round-trip,
  determinism, structural invariants) give the same assurance without it.
- As storage-ecosystem interop, it is a niche bet — RDF quads are not
  Dolt-shape data (see a private strategy note §6).

**Recommended: stop here.** Keep the `Node.fromBytes` framing fix (harmless,
and useful if anyone ever wants to read Dolt chunks); validate the engine on
the port's own terms; and treat "bit-level compatibility with Dolt" — a goal
stated in `PORT_PLAN.md` — as a project decision to revisit, not a blocking
loose end. Resume the layer-by-layer reconciliation only if storage-ecosystem
interop with Dolt becomes a real, named requirement.

## Reproduce

```bash
dev-scripts/install-go.sh                       # GO_INSTALL_ROOT to override /opt
# clone Dolt, git checkout v2.0.3, set cross-lang/go.mod's replace path
cd cross-lang && go mod tidy && go run gen_fixture.go
# build + run the Java validator:
mvn -pl prolly-rdf -am test-compile
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -cp prolly-rdf/target/classes:prolly-rdf/target/test-classes:prolly-port-core/target/classes:<deps> \
  com.earasoft.prolly.CrossLanguageFixtureTest
```
