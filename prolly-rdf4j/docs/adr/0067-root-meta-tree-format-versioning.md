
# ADR-0067: Root meta tree format versioning

## Status

Accepted, 2026-06-23. Discharges the deferred Step 2 follow-on of
`plans/prepublic/untrusted-input-boundary-hardening.md`.

## Context

`RootMetaTree` is the Sail's "root of roots": one content-addressed chunk bundling every table's
root hash (`dict`, the four quad orders `spoc`/`posc`/`ospc`/`cspo`, `namespaces`, `stats`,
`prefixes`, and `provenance` when enabled). Its content hash **is the commit id** — the commit log's
`Entry.metaTreeHash` is exactly this chunk's hash.

Before this decision its on-disk format was a bare `[u32 BE entry-count]` followed by per-entry
`[u8 name-length][name][u8 hash-length][hash]` — **no magic, no version**. Step 2 of
`untrusted-input-boundary-hardening` added a fail-closed bound on the entry count (reject negative /
absurd counts before allocating) but **explicitly deferred** adding a magic/version header, because
that changes the serialized form and is a format-evolution decision for the owner.

Meanwhile the engine's **core** serialized types already self-describe: the store marker and the
`Commit` record each carry a `[4-byte magic][1-byte version]` header that is
verified *before any field is read*, failing closed with `UnsupportedFormatException` rather than
mis-reading arbitrary bytes (see `Commit.COMMIT_MAGIC` + `FormatVersion.CORE_FORMAT_VERSION`,
introduced by `core-format-versioning.md`). `RootMetaTree` is the one versioned-on-disk type that
diverged from that pattern.

> **Correction (2026-06-27):** this Context paragraph originally also listed **the `Node` format** as
> already carrying the `[magic][version]` header. That was overstated — `Node.fromBytes` carries only the
> `TUPM` *flatbuffer file-identifier* (a magic, no version) and silently falls back to `SimpleNodeSerializer`
> rather than failing closed. Node-format versioning was the deferred `core-format-versioning` Step 3, split
> to [`core-node-format-versioning`](https://github.com/prollygraph/prolly-core/blob/main/dolthub-java-port/plans/core-node-format-versioning.md) and only
> made active 2026-06-27. The store marker + `Commit` claim stands; the `Node` claim did not, and is
> retracted here in place. (This ADR's *decision* about `RootMetaTree` is unaffected.)

**Why now:** the format stabilizes ahead of a public release, and the project's discipline is that
every versioned on-disk type should self-describe so a wrong / foreign / future blob fails closed
instead of mis-parsing. **Constraints:** (1) pre-1.0 **no-backwards-compat** — no defensive
multi-version readers; a format change is handled by back-up + restore. (2) `RootMetaTree` is a
**Sail-layer** (`prolly-rdf4j`) type, *not* a core type — its format must be free to evolve
independently of the core tree/commit/node format.

## Options

Deciding axes: does the blob self-describe; how far a version bump's invalidation reaches (blast
radius); and consistency with the existing `Commit` header pattern.

| Option | Self-describing | Version blast radius | Consistency with `Commit` |
|---|---|---|---|
| **A** — status quo (Step 2 count-bound only, no header) | No — a foreign blob is read as an entry count | n/a | Diverges from the core pattern |
| **B** — magic + **local** version *(chosen)* | Yes — typed rejection of wrong magic/version | `RootMetaTree`-only; independent of the core | Matches |
| **C** — magic + reuse `CORE_FORMAT_VERSION` | Yes | **Coupled** — a core bump invalidates every metatree, and a metatree change forces a core bump that invalidates every node/commit | Matches magic, wrong coupling |

## Decision

Add a `[4-byte magic][1-byte version]` header to `RootMetaTree`, verified before any field read,
failing closed with `UnsupportedFormatException` — mirroring `Commit`.

- **D-1. Magic = `{'P','R','M','T'}`** (Prolly Root Meta Tree), distinct from `Commit`'s `'PCMT'`.
  Lets `deserialize` reject a non-`RootMetaTree` / pre-versioning / foreign blob cleanly instead of
  reading its leading bytes as an entry count.
- **D-2. A local `FORMAT_VERSION` (starts at 1), independent of `FormatVersion.CORE_FORMAT_VERSION`.**
  *Deciding tradeoff:* `RootMetaTree` is a Sail-layer type. Reusing the core's single version line
  (Option C) would couple the two layers — a core format bump would spuriously invalidate every
  metatree, and a metatree format change would force a core bump that invalidates every node and
  commit. An independent version keeps each layer's blast radius minimal. (If more Sail-layer types
  gain on-disk versioning, consolidate into an `RdfFormatVersion` line then — not warranted for one
  type today.)
- **D-3. Exception taxonomy:** header problems (too short for the header, bad magic, unsupported
  version) throw `UnsupportedFormatException` (a `ProllyException`, like `Commit`); *post-header*
  structural problems (bad count, truncated entry) keep the Step 2 `IllegalArgumentException` /
  `IndexOutOfBoundsException`. This distinguishes "wrong format — run a matching engine or restore"
  from "right format, malformed bytes — corruption".

## Consequences

- **(Negative, accepted) A format break.** The header changes `RootMetaTree`'s bytes → its hash →
  the commit id. An existing store's commit history becomes unreadable by the new engine
  (`deserialize` rejects header-less old blobs with `UnsupportedFormatException`). Per the pre-1.0
  no-backwards-compat rule the migration is **back-up + restore** (re-ingest); the project is
  v0.2-BETA, where every user can `tar` the store. Only *new* commits get new-format metatrees; the
  index-root chunks (`Node`s) are unchanged, since they are separate content-addressed chunks.
- **(Positive) `RootMetaTree` now matches the core self-describing pattern** — a wrong / foreign /
  future blob fails closed with a typed exception that names the offending and expected version,
  instead of mis-parsing leading bytes as a count.
- **(Positive) Future `RootMetaTree` format changes are detectable** (a version bump yields a clean
  `UnsupportedFormatException` on an old engine), and independently of core bumps (D-2).
- **(Neutral — honest framing) The marginal *security* gain on the wired path is modest.**
  Content-addressing already guarantees the wired reader fetches the right chunk; the header's real
  value is robustness against type-confusion / non-content-addressed / future reads, plus
  consistency. This is a consistency + future-proofing change, **not** a live-exploit fix — the live
  hardening was the Step 2 count-bound.
- **(Neutral) New commit ids differ** from what they would have been. No consumer assumes a specific
  commit-hash *value* (there is no golden-hash test), and the hash length/format is unchanged, so
  nothing downstream breaks on the value change.

## Follow-up / future work

- If additional Sail-layer types gain on-disk versioning, consolidate the per-type local version
  constants into an `RdfFormatVersion` sibling of the core `FormatVersion` (one auditable rdf4j-layer
  line). Not warranted for a single type today.
