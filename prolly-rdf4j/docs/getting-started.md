
# Getting started — versioned SPARQL with prolly-rdf4j

Two ways in: the **embedded quickstart** right below — the RDF4J `Sail` is what this
repo actually ships — or the **server walkthrough** further down (the HTTP server
product lives in the private monorepo; its steps are kept as a deployment reference).

## Quickstart — embedded (this repo)

Open a durable, versioned Sail at a filesystem path and use it like any RDF4J
repository — every transaction commit is a prolly commit:

```java
Path dir = Path.of("/tmp/prolly-demo");
try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
    ProllySail sail =
        new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(dir));
    Repository repo = new SailRepository(sail);
    repo.init();

    try (RepositoryConnection conn = repo.getConnection()) {
        ValueFactory vf = repo.getValueFactory();
        conn.begin();
        conn.add(vf.createIRI("urn:demo:alice"),
                 vf.createIRI("urn:demo:knows"),
                 vf.createIRI("urn:demo:bob"));
        conn.commit(); // a prolly commit: new RootMetaTree, appended to commits.log

        try (TupleQueryResult r = conn.prepareTupleQuery(
                "SELECT ?s ?o WHERE { ?s <urn:demo:knows> ?o }").evaluate()) {
            r.forEach(System.out::println);
        }
    }
    repo.shutDown();
}
// Re-opening against the same dir later auto-restores HEAD via the RootMetaTree
// sidecar — no manual root injection.
```

Don't copy-paste from prose — run the compiling, CI-locked source of this sketch,
[`GettingStartedDemo`](../src/main/java/com/earasoft/prolly/rdf4j/examples/GettingStartedDemo.java)
(its `GettingStartedDemoTest` pins the narrative, including the restart round-trip):

```bash
mvn -pl prolly-rdf4j exec:java \
    -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.GettingStartedDemo \
    -Dexec.args="/tmp/prolly-demo"
```

From there, the [runnable demos table in the module README](../README.md#examples--runnable-demos)
continues the curriculum: SPARQL-only driving, file loads, named graphs, versioning +
time-travel, branch/merge, revert, blame/bisect, commit diffs. The artifacts are
`io.github.prollygraph:prolly-rdf4j:0.2.0-BETA` after the local `mvn install` described
in the [root README](../../README.md#build).

## The server walkthrough

The rest of this page drives the same stack over HTTP: start the server, load some
Turtle, run queries, see the commit DAG, branch + merge, and query past commits.

Total time: ~5 minutes once the build is done.

## What you get

| URL                                     | What it does                                          |
|-----------------------------------------|-------------------------------------------------------|
| `GET  /sparql?query=…`                  | SPARQL 1.1 query (also `?commit=` / `?branch=`)       |
| `POST /sparql/update`                   | SPARQL 1.1 Update (`application/sparql-update`)       |
| `POST /sparql/load`                     | Bulk-load RDF (Turtle / N-Triples / RDF-XML)          |
| `GET  /sparql/commits`                  | Commit DAG as JSON (newest first, with parents)       |
| `GET  /sparql/diff?a=…&b=…`             | Triple-set diff between two commits                   |
| `GET  /sparql/branches`                 | List branches with HEAD pointers                      |
| `POST /sparql/branches`                 | Create a branch                                       |
| `DELETE /sparql/branches/{name}`        | Delete a branch                                       |
| `POST /sparql/branches/{target}/merge`  | Three-way merge `source` into `target`                |
| `GET  /sparql/health`                   | Readiness/liveness probe                              |

Every successful SPARQL response carries:

- `X-Prolly-Commit-Id` — the commit served (content-addressed hash)
- `Memento-Datetime` — wall-clock of that commit (RFC 1123)
- `Link: …; rel="timemap"` — points at `/sparql/commits`

You can also send `Accept-Datetime` (RFC 1123) to read the latest
commit ≤ that time — full RFC 7089 Memento semantics for time travel.

## Step 1: Build

> **Note (2026-07-16):** the server + SPA modules named below (`prolly-server`,
> `prolly-rdf4j-ui`, `prolly-rdf4j-rest`) live in the **private monorepo**, not this
> repo — this repo ships the embeddable Sail (see the embedded quickstart above / the
> module README). The server steps are kept as a reference for deployments that have
> the product distribution.

```bash
mvn -pl prolly-server,prolly-rdf4j-ui -am install -DskipTests
```

This runs `npm install` + `ng build` for the Angular SPA, copies the
bundle into the REST module's `static/` resources, then builds the
`prolly-server` fat jar that bundles `prolly-rdf4j-rest` + the SPA.

## Step 2: Start the server

The bundled config exposes the SPARQL endpoint at port 8080. Enable
the ProllySail backend with `prolly.rdf4j.enabled=true`; point at a
RocksDB directory to make data survive restarts.

```bash
java -jar prolly-server/target/prolly-server-*.jar \
  --prolly.rdf4j.enabled=true \
  --prolly.rdf4j.store-dir=/tmp/prolly-demo
```

In-memory (data lives for the JVM lifetime only):

```bash
java -jar prolly-server/target/prolly-server-*.jar \
  --prolly.rdf4j.enabled=true
```

## Step 3: Load some data

```bash
curl -X POST http://localhost:8080/sparql/load \
  -H 'Content-Type: text/turtle' \
  --data '@prefix ex: <urn:test:> .
          ex:alice ex:knows ex:bob .
          ex:bob ex:knows ex:carol .
          ex:alice ex:age 30 .
          ex:bob ex:age 25 .'
```

Response:
```json
{"added": 4, "totalAfter": 4, "durationMs": 12}
```

## Step 4: Query

```bash
curl 'http://localhost:8080/sparql?query=SELECT%20%2a%20WHERE%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D'
```

Take note of the response headers — `X-Prolly-Commit-Id` tells you
exactly which commit was served. That's your "memento URI" in
Memento parlance.

## Step 5: See the commit DAG

```bash
curl http://localhost:8080/sparql/commits | jq
```

```json
{
  "current": "6b1f3c7d…",
  "commits": [
    { "id": "6b1f3c7d…",
      "datetime": "Wed, 13 May 2026 12:34:56 GMT",
      "parents": [] }
  ]
}
```

## Step 6: Branch + commit + diff

```bash
# Create a feature branch pointing at the current HEAD:
curl -X POST http://localhost:8080/sparql/branches \
  -H 'Content-Type: application/json' \
  -d '{"name":"feature-experiment"}'

# Commit something new on main:
curl -X POST http://localhost:8080/sparql/update \
  -H 'Content-Type: application/sparql-update' \
  -d 'INSERT DATA { <urn:test:dave> <urn:test:age> 42 }'

# See what changed between the branch tip and HEAD:
curl 'http://localhost:8080/sparql/diff?a=<feature-experiment-hash>&b=<main-hash>'
```

## Step 7: Query a past commit

Two equivalent ways:

```bash
# By commit hash:
curl 'http://localhost:8080/sparql?query=SELECT%20%2a%20WHERE%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D&commit=6b1f3c7d…'

# By branch name:
curl 'http://localhost:8080/sparql?query=SELECT%20%2a%20WHERE%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D&branch=feature-experiment'

# By Memento Accept-Datetime (RFC 7089 time-travel):
curl 'http://localhost:8080/sparql?query=…' \
  -H 'Accept-Datetime: Wed, 13 May 2026 12:00:00 GMT'
```

The response's `X-Prolly-Commit-Id` and `Memento-Datetime` will show
you which historical commit was actually served.

## Step 8: Merge

```bash
curl -X POST http://localhost:8080/sparql/branches/main/merge \
  -H 'Content-Type: application/json' \
  -d '{"source":"feature-experiment"}'
```

```json
{
  "result": "OK",
  "target": "main",
  "newCommit": "a04e2b6c…",
  "incoming": 12,
  "sourceDeletes": 0
}
```

The new commit's entry in `/sparql/commits` lists two parents — both
branch tips — so the DAG view shows the merge clearly.

## Step 9: Use the Angular UI

Browse to `http://localhost:8080/`. The bundled SPA gives you:

- **Query** page: SPARQL editor with results table, ASK boolean, and
  CONSTRUCT raw view; collapsible UPDATE and Turtle-file-load panels.
- **Commits** page: visualized DAG with click-through to query each
  commit.
- **Branches** page: list / create / delete / merge with conflict
  reporting.

In dev mode, run the SPA separately with `ng serve` from
`prolly-rdf4j-ui/` — CORS is pre-configured for `localhost:4200`.

## Logging

The server uses SLF4J. Useful production loggers (set to INFO):

- `com.earasoft.prolly.rdf4j.sail.ProllySail` — Sail lifecycle, restore
- `com.earasoft.prolly.rdf4j.sail.ProllySailConnection` — commits with
  added/removed counts
- `com.earasoft.prolly.rdf4j.server.SparqlController` — HTTP requests
  with duration

See `prolly-rdf4j/docs/logging.md` for the full taxonomy and grep
recipes.

## Architecture in one paragraph

ProllySail is an RDF4J Sail backed by content-addressed Prolly Trees.
Every commit produces a new RootMetaTree (one chunk hash bundling four
quad indexes + dictionary + namespaces + stats); the RootMetaTree hash is
the commit id and lands in an append-only `commits.log`. Branches are
sidecar files in `refs/` that point at RootMetaTree hashes. Queries open a
read-only snapshot Sail at any historical hash without touching the
live state. Merges find an LCA in the commit DAG and set-union triples
into a new commit with two parents.
