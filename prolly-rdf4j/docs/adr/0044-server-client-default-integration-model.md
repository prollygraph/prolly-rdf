
# ADR-0044: Server/client as the default integration model (embedded as opt-in)

## Status

Accepted, 2026-06-05. Guides `plans/grpc-versioning-service.md`.

## Context

prolly-rdf4j can be consumed two ways:

- **Embedded** — a JVM library on the consumer's classpath. The consumer's *own* process
  loads `ProllySail` **and prolly's entire transitive graph**: RDF4J, RocksDB (with its
  native binding), Spring, Jackson, the Foreign Function & Memory paths, and prolly's
  **Java version**.
- **Server/client** — prolly runs as a separate process exposing a wire protocol (gRPC, per
  the driving plan; the HTTP/SPARQL surface already exists), and a consumer links only a
  **thin client + the wire contract** — none of prolly's transitive graph.

The embedded model **couples the consumer to prolly's whole dependency closure and Java
version**, and that coupling has cost real, repeated engineering time in this project:

- the **rdf4j-bom dependency drag** — an old logback/Jackson dragged in under a BOM import,
  producing ~310 hidden test errors that only unmasked in layers;
- the **Spring-Boot-4 alignment** churn (slf4j/logback/Jackson/JUnit/Mockito pins);
- most vividly, the **Java 21→26 bump (2026-06-05)**: the code compiled clean on JDK 26 with
  *zero* changes, but the coverage agent (JaCoCo) **cannot instrument class-file major
  version 70** — `Unsupported class file major version 70`. An embedder on Java 21 cannot
  host an engine that wants Java 26; it is dragged onto prolly's *tooling* lag, not just its
  libraries.

**Why now:** the gRPC versioning service is being planned, the engine is being prepared for
extraction/publication, and platform integrators are choosing how to consume prolly. The
default we set here steers all of them — and it is the same wire-vs-classpath boundary that
decides the productized direction (a hosted deployment is *inherently* the server model). The
decision has high revisitation cost: it shapes the primary API surface (a wire contract vs a
Java API), what every consumer must adopt, and where compatibility is versioned.

## Options

| Option | Dependency + Java-version isolation | Per-operation latency | Ops + our maintenance surface | Independent update / polyglot |
|---|---|---|---|---|
| **A** — Embedded-first (library is the product) | **none** — consumer inherits prolly's whole graph + Java version + tooling | **best** — in-process, nanoseconds, no serialization | lowest — a jar; no service to run | **no** — consumer rebuilds/retests on every prolly bump; JVM-only |
| **B** — Server/client-first (wire protocol is the product; embedded opt-in) | **strong** — only the wire contract is shared | network hop + serialization (µs–ms), mitigable | a server to deploy/secure/scale + a client SDK + the protocol | **yes** — server updates behind a stable contract; any-language clients |
| **C** — Co-equal peers (both first-class, no default) | mixed | both | **highest** — maintain + document *both* fully | partial |

## Decision

**Option B.** Server/client is the **default** integration model; embedded is a **supported
opt-in exception**.

**D-1. The wire contract is the primary, supported way to consume prolly.** gRPC (the driving
plan) plus the existing HTTP/SPARQL surface. **Deciding tradeoff:** the dominant, *repeated*
cost in this project has been mismatched versions (the rdf4j-bom drag, the Java-26 JaCoCo
lag) — a cost the embedded model imposes on *every* consumer and the server model eliminates
by making the wire contract the only shared surface. We optimize the default for the pain we
actually have, not for the per-operation latency most consumers do not need.

**D-2. Embedded stays supported — but the version-coupling tax is now opt-in, not the
default.** The library API remains (the extracted engine *is* the embedded
artifact). A consumer who needs in-process latency or single-process operational simplicity
opts *into* embedding, and thereby into the dependency + Java-version coupling, with
shaded/relocated dependencies as the mitigation. **Deciding tradeoff:** in-process latency is
a genuine advantage for latency-critical, high-operation-rate, single-tenant cases; *removing*
embedded to force everyone onto the wire would sacrifice that for consumers who legitimately
need it. Keep it; make the tax deliberate rather than universal.

**D-3. Compatibility is versioned at the wire contract, not the classpath.** For server/client
consumers, classpath compatibility is explicitly a non-goal: versioning moves from the entire
transitive graph to one **designed, evolvable boundary** (gRPC field-number forward/backward
compatibility), owned by the gRPC versioning plan. **Deciding tradeoff:** this is the move
that makes D-1's isolation *real* — without a disciplined wire-contract versioning story we
would merely relocate the version pain; with it, we relocate it to the one place built to
absorb it.

## Consequences

**Positive:**
- Consumers are isolated from prolly's dependencies + Java version; prolly can bump Java 26 /
  churn its graph freely — the JaCoCo-v70 lag is a **non-event** for clients.
- One designed, evolvable compatibility surface (the wire contract) replaces the whole
  classpath as the thing that can break.
- The hosted deployment and the integration model are the **same architecture** — one bet.
- Polyglot clients become possible (any language with a gRPC/HTTP client), broadening adoption.
- Multi-tenant isolation is server-enforced rather than each consumer's problem.

**Negative / costs:**
- We build + maintain **more** surface: the server, the wire protocol, *and* client SDK(s) —
  versus a single library.
- **Per-operation latency**: a network hop + serialization. Must be mitigated by batching,
  server-side streaming, client-side caching, and conditional reads (the gRPC plan's
  performance decisions + the client plan's caching decision). Latency-bound consumers may
  still need embedded (D-2).
- An **operational surface**: a service to deploy, secure (auth / SSRF / replay), scale, and
  back up.
- The **gRPC-Java client still carries netty/protobuf/guava** — a small residual conflict
  surface; the near-zero-dependency escape hatch is the HTTP/JSON (SPARQL) client.
- Embedded staying supported means we still own + document the library API, the dependency
  tax, and shading guidance.

**Neutral / follow-ons:**
- The extracted engine remains the embedded artifact; the server is built *on* it.
  Both coexist — a library, and a server over the library.
- Integrators are steered to the client model by default; embedding becomes a documented,
  deliberate exception rather than the unspoken assumption.

## Follow-up / future work

- **The gRPC versioning service** — the wire contract + its versioning discipline:
  `plans/grpc-versioning-service.md`.
- **A client-SDK strategy ADR** — when a second-language client lands (the polyglot promise of
  D-1), decide the SDK surface + generation story.
- **Embedded shading/relocation guidance** — a doc for opt-in embedders (D-2) on how to avoid
  the dependency tax when they must embed.

## Open questions

- **Q1** — Does the embedded artifact ship dependencies shaded/relocated *by default* (jar
  bloat, but conflict-free), or document the host's responsibility (lean jar, host owns the
  conflict)? The native RocksDB binding complicates a clean relocation.
- **Q2** — Is the existing HTTP/SPARQL surface the recommended near-zero-dependency client, or
  do we also ship a thin REST SDK for ergonomics?
