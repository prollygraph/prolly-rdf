
# prolly-rdf4j

A **versioned** RDF store exposed as an RDF4J `Sail`, backed by a content-addressed
[prolly tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md). It speaks standard SPARQL through the
RDF4J stack while adding git-like history — commits, branches, merges, time-travel reads — over the
triple store.

## Examples — runnable demos

`src/main/java/.../examples/` ships 13 self-contained demos, each a plain `main()` you can
run and read top to bottom. **Every demo has a locking `*DemoTest`** in `src/test` that
runs the same narrative in CI (`VersioningDemoTest` runs it over every store backend), so
the demos cannot silently drift from the code — they are the recommended first stop for
learning the Sail. Run any of them with:

```bash
mvn -pl prolly-rdf4j exec:java \
    -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.GettingStartedDemo \
    -Dexec.args="/tmp/prolly-demo"   # omit the arg for a self-cleaning temp dir
```

In rough curriculum order:

| Demo | What it shows |
|---|---|
| `GettingStartedDemo` | The full lifecycle: open a Sail at a path, ingest, query, shut down, re-open — data survives via the RootMetaTree auto-restore pointer |
| `SparqlDemo` | Driving the Sail purely through SPARQL — update + query, no Java data API |
| `RdfFileLoadDemo` | Loading RDF files — the most common way real data arrives |
| `NamedGraphDemo` | Named graphs (quads) |
| `VersioningDemo` | Versioning & time-travel; `--store=` selects the backend (RocksDB vs file store — same story either way) |
| `SparqlSnapshotDemo` | SPARQL time-travel: ordinary queries against a past commit's snapshot |
| `BranchMergeDemo` | Branch & merge — git-like version control for the graph |
| `SquashMergeDemo` | Squash-merge: collapse a branch's work into one commit on the target |
| `RevertDemo` | Revert / rollback |
| `BlameBisectDemo` | Git-style `blame` and `bisect` over the commit history |
| `SparqlDiffDemo` | Diffing two commits — "what changed?" — via SPARQL |
| `ReadPathCostDemo` | What a read actually costs — a measured probe of the read path |
| `JvmWarmupDemo` | Preloading the SPARQL engine so the first real query is fast |

The embedded quickstart in [`docs/getting-started.md`](docs/getting-started.md) walks
`GettingStartedDemo`'s narrative in prose.

## Observability — metrics via Micrometer

prolly-rdf4j instruments its hot paths (reads, writes, commits, merges, query planning, the node cache)
as native **[Micrometer](https://micrometer.io)** meters, so they flow to whatever backend your
`MeterRegistry` is wired to — Prometheus, OTLP, CloudWatch, JMX — with no prolly-specific glue. When the
Spring Boot actuator is on the classpath the meters appear at `/actuator/metrics`; without it, an empty
registry makes instrumentation a near-zero-overhead no-op.

Why a vendor-neutral facade rather than a bespoke metrics API:

1. **Embeddability.** prolly-rdf4j is meant to be embedded. A consumer who already runs a Micrometer
   registry gets prolly's metrics in their dashboards for free — versus a bespoke `SailMetrics` they'd
   have to hand-bridge. It's the difference between "our metrics" and "metrics."
2. **Multi-tenant tags.** Micrometer's dimensional tags let a meter be sliced per repo / org / tenant —
   the difference, in an N-tenant process, between one global number and per-tenant observability.
3. **One registry, correlated.** The Sail's meters land on the *same* registry as JVM heap/GC, HTTP
   latency, thread pools, and RocksDB JMX — correlated on one dashboard ("node-cache hit ratio vs heap
   vs query p99") with zero glue.
4. **The right meter types.** Gauges (the node-cache hit ratio) and timers (count + max + percentiles)
   are expressible natively — a name-only push-counter API can't model them.

See [ADR-0041](docs/adr/0041-micrometer-as-the-metrics-framework.md) for the decision and the
dependency-boundary rationale (the codec keeps a Micrometer-free `EncoderMetrics` seam).
