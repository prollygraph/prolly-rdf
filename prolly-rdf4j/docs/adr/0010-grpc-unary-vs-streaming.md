
# ADR-0010: gRPC transport — unary RPCs that mirror REST 1:1, not streaming

## Status

Accepted, 2026-05-19. Guides `plans/grpc-transport.md`
(12-step plan, ~Phase 0–3). The HTTP transport in
`docs/distributed_sync_protocol.md`
remains canonical; gRPC ships as a second `RemoteRepository` impl,
semantically identical.

## Context

The distributed-sync layer landed an HTTP transport across plan Steps 12–14:
four `/sync/*` endpoints (`advertiseRefs`, `fetchPack`, `receivePack`,
`compareAndSetRef`) each unary, each carrying a `SyncPack` body whose wire
format is owned by `SyncPackCodec` (a typed binary frame — `[u32 count]`
followed by `[hash][u32 len][data]` chunks plus a trailing commit log
section). `RepoSync` is the client engine; it sits behind the
`RemoteRepository` interface so the wire is swappable.

Adding gRPC as a second transport is parked roadmap from
`plans/distributed-sync.md`. The
design question is whether the gRPC service should:

1. **Mirror the REST surface 1:1** — four unary RPCs, each a near-direct
   translation of the matching HTTP endpoint. The pack body rides inside
   the RPC message as raw `SyncPackCodec` bytes.
2. **Take advantage of streaming** — `FetchPack` becomes a server-streamed
   RPC (server emits chunks one at a time); `ReceivePack` becomes a
   client-streamed RPC. The client applies chunks incrementally without
   ever buffering the whole pack.

Streaming is the more "gRPC-native" shape and lets very large fetches
avoid an O(pack-size) memory blip on both ends. The mirror-REST shape is
simpler, lets the two transports share `SyncPackCodec` exactly, and keeps
the surface auditable next to the HTTP one.

`PackBuilder` produces the entire pack in memory on the server side today
(post-Step 22 it walks every commit's tree closure into one `Set<String>`),
so the server can't usefully stream until that pipeline is reworked. Pack
sizes in current usage are small — fuzz workloads are dozens of KB and
single-figure-MB; the `SyncLimits` default cap is 1 GiB.

## Options

| Option | RPC shape | Wire format | Memory profile | Codec sharing |
|---|---|---|---|---|
| **A. Mirror REST 1:1** | 4 unary | `bytes pack = 1;` carries `SyncPackCodec.serialize` output verbatim | O(pack) on both peers (same as HTTP today) | Yes — both transports decode through `SyncPackCodec.parse` |
| **B. Streaming pack** | 2 unary + 2 streaming (`FetchPack` server-streamed, `ReceivePack` client-streamed) | A `Chunk { hash, data }` message per stream item; commit-log entries either a trailing message or a separate RPC | O(1) per chunk on both peers — but only when the server-side `PackBuilder` is rewritten to yield chunks lazily | No — gRPC handlers bypass `SyncPackCodec` entirely; HTTP keeps using it. Two distinct serialization paths |
| **C. Hybrid** | Unary today, streaming behind a flag later | Same as A initially; B as a v2 .proto rev | Same as A | Yes initially, splits if streaming actually lands |

### Comparison

**Pack-size headroom (today).** The fuzz workload tops out in the
single-digit-MB range; production caps at 1 GiB via `SyncLimits`. Option A
fits comfortably in any plausible JVM. Option B's O(1)-per-chunk window is
a future optimization, not a present need.

**Semantic drift between transports.** Option A keeps both transports
encoding the same SyncPack the same way, so a bug in `SyncPackCodec` shows
up in both surfaces identically and a single integrity test (the chunk-
hash check, `CommitLogSync.mergeInto`'s `metaTreeHash` walk) covers both.
Option B forks the wire — a streaming bug on gRPC won't surface on HTTP
and vice versa, doubling the integrity-test surface.

**Implementation cost.** Option A is ~12 steps: scaffolding the module,
generating stubs, four client method translations, four service-impl
translations, an auth interceptor, an end-to-end test. Option B is ~18:
add a streaming pack iterator on the server (which means refactoring
`PackBuilder` to expose a `Stream<Chunk>` instead of a `List<byte[]>`),
client-side reassembly, flow control plumbing, and a streaming-specific
integrity story (when does the receiver decide the pack is complete and
trip the `SyncLimits` cap?).

**Reversibility.** Option A → Option B is a clean .proto v2 add: introduce
new streaming methods alongside the unary ones, keep the unary ones for
compatibility, deprecate later. Option B → Option A is harder — once
clients stream, rolling back means them buffering anyway.

## Decision

**Option A** — four unary RPCs that mirror the REST endpoints 1:1, pack body
carried as `bytes` and round-tripped through `SyncPackCodec`.

The deciding tradeoffs:

- **No present need for streaming.** Pack sizes today are well below any
  plausible memory pressure. Option B optimizes a problem we don't have
  and would have to invent test workloads for.
- **Single codec, single integrity surface.** Both transports decoding
  through the same `SyncPackCodec` keeps the integrity story testable in
  one place — the Step 22 work cuts across both transports as long as
  they share the same encoding.
- **Reversibility favors A.** A future Option B is a clean .proto add;
  the inverse migration is painful. If a real consumer arrives with a
  pack-too-big-to-buffer requirement, we add streaming methods then —
  on the same service.
- **Smaller plan (12 vs 18 steps).** Lets us close gRPC in the same
  cadence as the HTTP work without expanding the feature set.

## Consequences

**Performance.** Pack creation and pack application are still O(pack
bytes) in working memory on both ends — same as HTTP today. The
`SyncLimits` default cap (1 GiB) is the upper bound. For deployments
above that, streaming becomes mandatory and the future-work item below
unblocks.

**Codec coupling.** Both transports depend on `SyncPackCodec`. A
breaking change to the codec breaks both; that's intentional — they're
two faces of one wire spec.

**Stub generation.** `protobuf-maven-plugin` + `os-maven-plugin` enter
the build for the gRPC module only. They don't touch the rest of the
reactor.

**Authentication.** A gRPC `ServerInterceptor` mirrors
`ApiKeyAuthInterceptor`'s `gateAllMethods=true` semantics. The API-key
header (`x-api-key`) becomes a `Metadata` key. Auth code paths are
parallel, not shared, but the property (`prolly.rdf4j.api-key`) is.

**Test surface.** End-to-end coverage is one `SyncOverGrpcTest` that
mirrors `SyncOverHttpTest`. The unary-RPC choice lets it use blocking
stubs against an in-process channel; no streaming flow control to test.

## Follow-up / future work

- **ADR-0011 (when justified):** Add streaming `FetchPack` /
  `ReceivePack`. Prerequisites: a real consumer hitting the 1 GiB ceiling,
  plus a refactor of `PackBuilder` to a chunk iterator that defers the
  full `Set<String>` materialization. The .proto v2 keeps the unary
  methods for compatibility.
- **TLS / mTLS** is out of scope here — covered by a separate deployment
  doc; the gRPC service plaintext-tests against in-process channels in
  this plan.

