
# Logging — operator reference

Customer-deployment-oriented overview of the loggers emitted by
`prolly-rdf4j` and `prolly-rdf4j-rest` (the latter lives in the private
monorepo; its logger rows are kept here because operators of the full
product read this page). Use this to tune log levels in production,
debug specific issues, and grep for individual operations.

All output goes through SLF4J. Pick any backend (Logback, Log4j2,
java.util.logging via `jul-to-slf4j`). The Spring Boot starter pulled in
by `prolly-rdf4j-rest` defaults to Logback.

## Logger taxonomy

| Logger                                                              | Purpose                                       | Default level |
|---------------------------------------------------------------------|-----------------------------------------------|---------------|
| `com.earasoft.prolly.rdf4j.sail.ProllySail`                         | Lifecycle: init, RootMetaTree restore, shutdown   | INFO          |
| `com.earasoft.prolly.rdf4j.sail.ProllySailConnection`               | Per-tx commit / rollback / mutation counts    | INFO          |
| `com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore`                      | Sidecar `meta-head` pointer writes            | INFO          |
| `com.earasoft.prolly.rdf4j.server.SparqlController`                 | HTTP SPARQL endpoint: queries in / out        | INFO          |

Everything else inherits from the root logger.

## What you'll see at each level

### `ProllySail` (INFO)

- `ProllySail init: store=<NodeStoreImpl>, rootMetaTreeStore=<configured|none>`
  — emitted once per `Sail.initialize()`.
- `ProllySail restoring from RootMetaTree with N entries` — present iff the
  Sail was opened against an existing on-disk state.
- `ProllySail starting fresh — no RootMetaTree pointer found` — first boot, or
  in-memory deployment.
- `ProllySail shutdown` — emitted on `Sail.shutDown()`.
- DEBUG: per-table restore lines (`restored table 'spoc' from chunk
  6b1f3c7d…`). Enable when you suspect a partial restore.
- ERROR: `failed to load RootMetaTree` / `failed to persist RootMetaTree pointer` —
  these are fatal for the connection that triggered them.

### `ProllySailConnection` (INFO)

Each connection gets a `[cnN]` tag. The N is process-monotonic so you can
follow one connection across log lines.

- `[cnN] commit: added=A removed=R duration=Tms` — emitted on every commit
  that actually mutated state. INFO if `T < 250`, WARN otherwise.
- `[cnN] commit (no-op): duration=Tms` — DEBUG only (read-only tx).
- `[cnN] rollback: discarding added=A removed=R` — emitted on every
  rollback. Common during SPARQL UPDATE failures.
- DEBUG: `connection opened` / `connection closed`.
- TRACE: every `addStatement` — heavy; use only for short reproductions.
- ERROR: `commit failed after X added / Y removed` — the underlying
  exception is chained.

### `RootMetaTreeStore` (DEBUG by default — quiet)

- DEBUG: `RootMetaTreeStore.put <path> -> chunk <hash-prefix>` — one per
  commit when persistence is enabled.

Crank this up if a customer reports "we committed but the data isn't
showing up after a restart" — the DEBUG line confirms whether the pointer
was actually advanced.

### `SparqlController` (INFO)

Each HTTP request gets a `[qN]` tag.

- `[qN] SPARQL recv len=L accept=<mime> query=<first-240-chars>` —
  per-request, query is whitespace-collapsed and truncated.
- `[qN] SPARQL ok kind=<TUPLE|ASK|GRAPH> duration=Tms` — success. WARN
  instead of INFO if `T >= 1000`.
- `[qN] SPARQL 400 malformed: <parser message>` — WARN.
- `[qN] SPARQL 400 unsupported query type: <class>` — WARN, rare.
- `[qN] SPARQL 503: no Repository bean configured` — WARN, deployment misconfig.
- `[qN] SPARQL 503 Sail error` — ERROR with stack.
- `[qN] SPARQL 500 Repository error` — ERROR with stack.

## Recommended levels per environment

### Production (default)

```yaml
# application.yml (Spring Boot)
logging.level:
  root: WARN
  com.earasoft.prolly.rdf4j: INFO
  org.eclipse.rdf4j: WARN
```

You get: Sail boot lines, every commit/rollback with counts, every SPARQL
query with duration, and WARN/ERROR for anything off the happy path.
Volume is bounded — `O(commits + queries)` per minute, no per-statement
spam.

### Debugging a specific issue

| Symptom                                                  | Bump to DEBUG                                                       |
|----------------------------------------------------------|---------------------------------------------------------------------|
| Data missing after restart                               | `com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore`                      |
| Wrong rows returned / slow scans                         | `com.earasoft.prolly.rdf4j.index.IndexPlanner` (when added)         |
| Connection leak (open count grows)                       | `com.earasoft.prolly.rdf4j.sail.ProllySailConnection`               |
| SPARQL endpoint occasionally hangs                       | `com.earasoft.prolly.rdf4j.server.SparqlController`                 |

TRACE on `ProllySailConnection` will log every `addStatement` — useful
for reproducing a specific data-corruption claim, but turn it off
immediately after capture.

### Quiet (CI / smoke tests)

```yaml
logging.level:
  com.earasoft.prolly.rdf4j: WARN
```

Only commit-slow / rollback / errors will surface.

## Grep cheatsheet

- All log lines for one HTTP query: `grep '\[q42\]'`
- All log lines for one Sail connection: `grep '\[cn7\]'`
- All slow commits in the last hour: `grep 'commit slow:'`
- All non-200 SPARQL responses: `grep -E 'SPARQL (4|5)[0-9][0-9]'`
- Persistence pointer advances: `grep 'RootMetaTreeStore.put'`

## Adding new loggers

When you introduce a new class along a customer-facing code path:

1. Add `private static final Logger LOG = LoggerFactory.getLogger(...)` at
   the top of the class.
2. Use parameterized logging (`LOG.info("x={} y={}", x, y)`) — never
   string concatenation. SLF4J skips the format step entirely when the
   level is disabled.
3. Tag noisy paths with a stable per-instance id (`[cnN]`, `[qN]`) so
   one operation's lines can be grep'd as a single trace.
4. Log at INFO only for events the operator should know happened. Use
   DEBUG for "would help if something went wrong" detail. Use TRACE for
   "useful for one debug session, do not leave on."
5. Always log exceptions with the `(msg, throwable)` overload, never
   `e.getMessage()` — losing the stack costs more than the line length
   saves.
