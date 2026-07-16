
# ADR-0041 — Micrometer as the metrics framework (revamp SailMetrics)

**Status:** Accepted, 2026-06-02. Guides
`plans/sailmetrics-micrometer-revamp.md`. Follows
[ADR-0040](0040-adopt-caffeine-for-the-node-cache.md) (the node-cache hit/miss telemetry that needs a
*gauge* — which the current metrics API cannot express — is what surfaced this).

## Context

The Sail instruments itself through a bespoke `SailMetrics` interface (`prolly-rdf4j/obs`): a
push-counter API — `increment(name)`, `increment(name, delta)`, `recordDuration(name, nanos)` — with
three implementations (`NoopSailMetrics`, `InMemorySailMetrics` for tests, `MicrometerSailMetrics` the
production adapter), bridged to `/actuator/metrics` by `ProllyMetricsAutoConfiguration`. It extends
`EncoderMetrics`, a **1-method** interface in the low-level `prolly-codec` module (`Dictionary` records
collision-chain stats through it).

This abstraction *reinvents Micrometer* — the JVM-standard, vendor-neutral metrics facade — and does it
less well: it has **no gauges** (so the node-cache hit ratio, a derived value, can't be modelled) and
**no real timers** (only raw summed nanos). More fundamentally, a bespoke metrics API **isolates
prolly's observability from the world**: an operator can't point it at Prometheus/OTLP/CloudWatch
without writing an adapter, and a downstream consumer embedding the engine can't get its metrics into
their existing dashboards for free. For an **embeddable, multi-tenant** engine that is the expensive
property to get wrong. The constraint that shapes the choice: only `prolly-rdf4j-rest` depends on
Micrometer today, and `EncoderMetrics` reaches down into `prolly-codec`.

## Options

| Option | `prolly-codec` dep | custom code kept | gauges / timers | embeddability | idiom |
|---|---|---|---|---|---|
| **A** — inject `MeterRegistry` everywhere, drop `EncoderMetrics` too | **codec → micrometer-core (+HdrHistogram)** | none | native | full | purest |
| **B** — `MeterRegistry` at the Sail layer + up; keep `EncoderMetrics` seam in codec, bridged | **codec stays Micrometer-free** | one tiny adapter | native at Sail+ | full | clean boundary |
| **C** — keep the `SailMetrics` facade, just add gauges to it | codec stays clean | all 3 impls | wrapped/partial | none (still bespoke) | least change |

## Decision

**Option B.** Adopt Micrometer's `MeterRegistry` as *the* metrics framework at the Sail layer and above
(`prolly-rdf4j`, `-rest`, `-grpc`, `-cli`): instrument with native `Counter`/`Timer`/`Gauge`, and
**remove** `SailMetrics`, `NoopSailMetrics`, `InMemorySailMetrics`, and `MicrometerSailMetrics`
(pre-1.0 — no parallel abstraction). Sub-decisions:

- **Hold the dependency line at the codec.** `prolly-codec` keeps its dependency-free 1-method
  `EncoderMetrics` interface; the Sail constructs a thin `registry::counter`-backed `EncoderMetrics`
  adapter to hand down. Forcing `micrometer-core` (+ HdrHistogram) into a low-level codec for a *single*
  counter is Option A's only real cost, and it is avoidable — so we avoid it. (Revisit only if codec
  instrumentation grows enough to want native Micrometer.)
- **No-actuator fallback = empty `CompositeMeterRegistry`.** Without the actuator there is no registry
  bean; supply a child-less `CompositeMeterRegistry` (`@ConditionalOnMissingBean`) — meters are created
  but record and retain nothing, preserving today's zero-overhead `noop()` behaviour with no custom
  class. (`SimpleMeterRegistry` would accumulate in memory — wrong for a no-observability deployment.)
- **Tags over name-embedding.** Migrate `planner.choice.<x>` / `index.<x>` / `sail.merge.tree.<x>` to a
  base name + a dimensional tag, enabling per-repo/-tenant slicing — the multi-tenant payoff.
- **Tests assert against `SimpleMeterRegistry`** (Micrometer's test double), replacing the
  `InMemorySailMetrics` snapshot.

Option C is rejected: it keeps the bespoke abstraction (and its maintenance) when Micrometer already
*is* the abstraction. Option A is rejected for the codec-dependency cost alone.

## Consequences

- **+** Vendor-neutral: the operator (or an embedding consumer) chooses the backend — Prometheus, OTLP,
  CloudWatch, JMX — at deploy time, no code change. The engine's metrics join the *same* registry as
  JVM heap/GC, HTTP latency, thread pools, RocksDB JMX → correlated dashboards with zero glue.
- **+** Gauges (node-cache hit ratio) and timers (count + max + percentiles) become expressible;
  dimensional tags enable per-tenant observability. Less custom code (4 classes + an autoconfig deleted).
- **−** `micrometer-core` (+ HdrHistogram) becomes a compile dependency of `prolly-rdf4j` (and the
  modules that construct Sails: `-grpc`, `-cli`). Version is managed by the Spring Boot BOM already
  imported. **Not** added to `prolly-codec` (held at the `EncoderMetrics` seam).
- **−** A large, mostly **atomic** refactor: `ProllySail`'s metrics type changes, ~20 internal call
  sites move to the registry, explicit-metrics constructor callers + 7 test files migrate, and the
  bespoke classes are deleted — it does not split into independently-compiling micro-steps, so it lands
  as one reviewed change (plan Phase 1–2 + test migration).
- **−** Metric names may be re-tagged; the 19 current names are pinned in the plan's contracts table and
  renamed only with the rename recorded.
- **Deferred:** full OpenTelemetry (cross-signal: traces + logs + metrics) adoption — a broader question
  than a metrics revamp; Micrometer bridges to OTLP, so this does not foreclose it.
