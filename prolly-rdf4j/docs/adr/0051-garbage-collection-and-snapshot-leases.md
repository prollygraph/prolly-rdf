
# ADR-0051: Garbage collection and snapshot leases

## Status

Accepted, 2026-06-08. Guides `plans/grpc-versioning-gc-and-leases.md`.
**Implementation deferred** behind the simulation gate (D-6): the decisions here are ratified, but no
chunk-deleting code ships until the deterministic concurrent-collection test is green. Resolves the
plan's open questions Q1 (lease storage) and Q3 (the sweep abstraction).

## Context

The gRPC versioning face ([ADR-0048](0048-transport-agnostic-repo-hosting-core.md)) exposes prolly-rdf4j's
versioning to an **out-of-process** consumer. Two of its proto verbs are unimplemented on purpose —
`RunGarbageCollection` and the snapshot-lease trio (`AcquireSnapshotLease` / `RenewSnapshotLease` /
`ReleaseSnapshotLease`) — because they are *coupled*, and the coupling is the whole problem this ADR
decides.

**Why garbage collection is needed.** A prolly tree is content-addressed and copy-on-write: every commit
or write creates *new* chunks and never mutates old ones. Superseded versions, abandoned branches, and
orphaned trees therefore accumulate with no upper bound — nothing reclaims them. Garbage collection is
the mark-and-sweep that walks from the live roots, marks every reachable chunk, and deletes the rest.

Three obstacles stand between "we want garbage collection" and "we can safely run garbage collection on this server", and each forces a
decision:

**1. The aux-root reachability gap.** The repo already has a core garbage collector
(`prolly-storage/.../GarbageCollector.java`), but it operates on the `prolly-storage` `Database` model and its
mark phase walks **only** branch-head commit graphs + each commit's data tree. It explicitly does **not**
mark the RDF4J `ProllySail`'s out-of-band roots — the `RootMetaTree` and the
provenance / prefixes / stats / namespaces / index roots it bundles, which no `Commit` carries. Running
it on a `ProllySail` store would sweep those aux roots → **silent data loss**. This is documented at
`GarbageCollector.java:61-71` and pinned by `GcRootReachabilityTest`. So a `ProllySail` needs its *own*
collector whose mark roots are complete.

**2. The out-of-process read-vs-collect race.** Garbage collection deletes anything unreachable from a root. But a remote
consumer may be streaming a snapshot at commit `X`, or about to read it, where `X` is *not* a branch head
(a historical commit, or a commit on no branch). If garbage collection runs and `X` is not a root, it sweeps `X`'s chunks
mid-read → the consumer's stream breaks or reads missing chunks. **In-process** this race is solved by a
shared lock (the reader holds it, the collector waits). **Across the network there is no shared lock** —
the reader is in another process — so the in-process-solved race re-appears at the boundary. A
multi-client server therefore cannot expose garbage collection at all without a way for a remote reader to *pin* the
commits it is using.

**3. The sweep has no home in the store abstraction.** The mark phase (reachability) is clean on any
store. The *sweep* (iterate every key, delete the unreachable ones) is not: the content-addressed
`NodeStore` interface (`prolly-port-core/.../NodeStore.java`) exposes only `read` / `write` /
`begin|endWriteBatch` — **no key iteration and no delete**. The core collector cheats by reaching into
the *concrete* `RocksNodeStore.db()` (a RocksDB iterator + `delete`). A `ProllySail`-level collector
written against the `NodeStore` *interface* — which must also cover `InMemoryNodeStore` (used by the
safety test), the `MetricsNodeStore` / `IntegrityVerifyingNodeStore` / `ErrorInjectingNodeStore`
decorators, and the network-backed `RemoteNodeStoreClient` — has nowhere to sweep. So "how does a store
sweep?" is an explicit abstraction decision, not a quiet add (the plan's Q3).

The shape of the answer: a new, aux-root-complete collector; a sweep capability on the store layer;
time-to-live-bounded leases that turn a remote reader's in-use commits into mark roots; and — because this is
chunk-*deleting* code — a deterministic concurrency gate before any of it ships.

## Options

### A. The collector — wrap the existing one, or build a `ProllySail`-aware one

| Option | aux-root safety | target layer | cost |
|---|---|---|---|
| **A1** — wrap the `Database` `GarbageCollector` | **unsafe** — sweeps `RootMetaTree` aux roots (data loss) | `Database` | low |
| **A2** — a new `ProllySailGarbageCollector` | safe — marks every `RootMetaTree` root | `ProllySail` | medium |

### B. The sweep abstraction (Q3) — where does "delete a chunk" live?

| Option | covers the in-memory test store | touches the core `NodeStore` (≈11 impls) | "is this store collectable?" |
|---|---|---|---|
| **B1** — add `iterate`+`delete` to `NodeStore` | yes | **yes** — every impl incl. the remote client must satisfy it | implicit |
| **B2** — require a concrete `RocksNodeStore` | **no** (loses the test path) | no | backend-locked |
| **B3** — a narrow `SweepableStore` capability interface | yes (it implements it) | no (opt-in) | **explicit, type-checked** |

### C. Lease storage (Q1) — where do leases live?

| Option | survives a restart | complexity | reserved namespace the collector must read |
|---|---|---|---|
| **C1** — in-memory `Map<leaseId, (commit, expiresAt)>` | no — and that is *fine* (a restart drops the streams the leases protected) | low | none |
| **C2** — durable `tags/`-style refs | yes | higher | yes |

## Decision

**D-1. A new `ProllySailGarbageCollector`, not a wrap of the `Database` collector (A2 over A1).** The
existing collector targets the wrong layer and is unsafe on a store with out-of-band roots. The deciding
tradeoff is data safety, not effort: A1 would sweep the `RootMetaTree` aux roots. The new collector
gathers roots from the `ProllySail`'s own `RefsStore` (branch heads) + `TagStore` (tags;
[ADR-0047](0047-grpc-tag-storage.md)) + the lease registry + **every root bundled in the `RootMetaTree`**
(`RootMetaTree.entries().values()` — enumeration is trivial), then marks via the existing
`ReachabilityWalker` over `sail.store()`.

**D-2. Mark roots are explicit, total, and fail-closed.** The mark phase takes the *union* of all root
sources and aborts the sweep on any unknown or unreadable root rather than risk an under-marked set. An
under-mark is silent data loss; an abort is a loud, safe failure. This directly answers the
`GarbageCollector.java:61-71` warning.

**D-3. The sweep is a narrow `SweepableStore` capability interface (B3).** Rather than burden the
read/write `NodeStore` that every layer implements (B1) or lock the collector to one backend (B2), a
small opt-in interface — e.g. `Set<String> liveKeys()` + `delete(byte[])`, or a single
`sweep(Set<String> reachable)` — is implemented only by the stores that can actually be collected:
`RocksNodeStore` (a RocksDB iterator), the in-memory store (a map scan), and the decorators (delegate).
The `RemoteNodeStoreClient` deliberately does **not** implement it — you do not garbage-collect a remote
over the wire — so "is this store collectable?" becomes an explicit, compile-time-checked property
instead of a runtime surprise. The collector *requires* a `SweepableStore`.

**D-4. Leases are time-to-live-keyed reachability roots; storage is in-memory (C1).** `AcquireSnapshotLease(commit,
ttl)` records a `(commit, expiresAt)` that the mark phase unions into its roots; `RenewSnapshotLease`
extends it; `ReleaseSnapshotLease` drops it; expiry is reaped on each mark. An open `Snapshot` / `Diff`
stream **auto-holds** a lease for its duration and releases on completion. The **time-to-live is the load-bearing
part**: across a network there is no reliable "I am done" signal — a client can crash or disconnect
mid-read — so a plain pin would let a dead client leak storage forever. An expiring lease is a
self-healing reference hold (the same idea as an MVCC read-snapshot kept alive under a long query).
In-memory storage is chosen because a restart drops the very streams the leases protected, so durability
(C2) buys nothing but a reserved namespace and complexity.

**D-5. `RunGarbageCollection` is admin-gated.** It is a destructive, repo-wide operation; the verb
requires the global-admin (or repo-admin) role at the gRPC edge, mirroring the REST admin surface and the
auth model of [ADR-0049](0049-grpc-versioning-authentication.md).

**D-6. Every sweep gates on a deterministic concurrent-collection simulation — non-negotiable.** No
chunk-deleting path ships until one test proves, deterministically: a leased commit survives a concurrent
collect; an *expired* lease does not; an open stream is never swept; and the aux roots survive. Sweep
correctness is a reachability invariant under concurrency, and a clean-looking collector that has never
been *raced* is the project's canonical "precision without validity" trap. The collector quiesces writers
under the `ProllySail` write-exclusion primitive (a `Semaphore`, the same guard the flush-window fix
uses) during mark+sweep.

## Consequences

- **Positive.** Bounded storage on a long-lived, history-churning, multi-client store; the aux roots are
  protected by construction (D-1/D-2); a remote reader's in-use commits are safe under concurrent garbage collection
  (D-4); and "can this store be collected?" is an explicit type property rather than a downcast that
  fails at runtime (D-3).
- **Negative / cost.** Real new machinery: a collector, a lease registry, the auto-lease stream
  wrapping, and the `SweepableStore` plumbing across the local stores. The `RemoteNodeStoreClient` cannot
  be collected (correct, but it means garbage collection is a *local-store* operation, named so no one expects otherwise).
  Leases are lost on restart (acceptable, per D-4). Most of all, chunk-deleting code is the
  highest-stakes code in the system, so D-6's simulation gate is mandatory and **slows delivery on
  purpose** — that is the cost of not corrupting a store.
- **Neutral / punted.** Durable leases (C2) — revisit only if a real use case needs a lease to outlive a
  restart. Incremental / generational collection — the collector is stop-the-world, matching the existing
  one; an online collector is a separate, much larger effort. Cross-repo collection — each repo is
  collected independently (per-repo isolation). `gc.*` observability metrics ride with this work, not the
  call-level metrics already shipped.

## Follow-up / future work

- The plan (`plans/grpc-versioning-gc-and-leases.md`) is the step-by-step; it is re-anchored (2026-06-08)
  with the grounded findings this ADR formalises. Phase 1 (collector) → Phase 2 (leases) → Phase 3 (verbs
  + the D-6 gate).
- If the warm-set / lease counts ever grow large enough that a linear reap dominates, revisit the
  in-memory lease structure (a min-heap by `expiresAt`) — but only behind a measurement.

## Open questions

- None at write time. (Q1 resolved → D-4 in-memory; Q3 resolved → D-3 `SweepableStore`.)
