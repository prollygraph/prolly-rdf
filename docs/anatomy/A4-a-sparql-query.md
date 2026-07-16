---
tags:
  - rdf
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/anatomy/A4-a-sparql-query.md; links + module citations adapted to this repo's layout -->

# Anatomy of a SPARQL query

*From an HTTP `POST /sparql` to a streamed result set.*

> **What you'll learn** — how a SPARQL request travels through the REST server:
> the `SparqlController` endpoint, query parsing and the runaway-query guard,
> how RDF4J evaluates the query by calling back into the Sail's `getStatements`,
> and how the result is serialized.
>
> _Reading time: ~10 minutes._
> _Prerequisites: [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md),
> [A1 · a scan](A1-a-scan.md)._

*A note on module boundaries: the HTTP layer this doc walks (`SparqlController`, the
REST server) ships in the private monorepo's server product, not this repo — this repo
ships the Sail it calls into. The doc is exported anyway because §4, how RDF4J's
engine decomposes a query into Sail `getStatements` calls, is this repo's contract;
the [getting-started walkthrough](../../prolly-rdf4j/docs/getting-started.md) keeps
the server steps as a deployment reference.*

## 0 · The problem

A client asks the running server a question over HTTP:

```http
POST /sparql HTTP/1.1
Content-Type: application/sparql-query
Accept: application/sparql-results+json

SELECT ?friend WHERE { <http://ex.org/alice> <http://schema.org/knows> ?friend }
```

Somewhere between that request and the JSON response, a query is parsed,
evaluated against stored quads, and serialized. Follow it.

## 1 · The endpoint

`SparqlController` implements the **W3C SPARQL 1.1 Protocol**. The spec allows
three request shapes, and the controller has a mapping for each:

```java
@GetMapping                                              // GET /sparql?query=...
@PostMapping(consumes = APPLICATION_FORM_URLENCODED_VALUE)// POST form: query=...
@PostMapping(consumes = SPARQL_QUERY_MIME)               // POST body: raw query
```

All three funnel into one private `executeQuery(...)`. The very first check is
infrastructural:

```java
if (repository.isEmpty()) {
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "No RDF4J Repository bean is configured. ...");
}
```

The controller depends on an `Optional<Repository>` — if no backend Sail was
wired, it answers `503` rather than failing obscurely. When versioning
parameters (`?commit=`, `?branch=`, `Accept-Datetime`) are present it opens a
read-only snapshot repository instead of the live one — that mechanism is
[A5's](A5-a-versioned-query.md) subject; here, assume the live repository.

## 2 · Parse, then guard

The query is evaluated inside an RDF4J `RepositoryConnection`:

```java
try (RepositoryConnection conn = repo.getConnection()) {
    Query query;
    try {
        query = conn.prepareQuery(QueryLanguage.SPARQL, queryString);
    } catch (MalformedQueryException mqe) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Malformed SPARQL query: " + mqe.getMessage(), mqe);
    }
    if (queryTimeoutSeconds > 0) {
        query.setMaxExecutionTime(queryTimeoutSeconds);
    }
```

`prepareQuery` parses the SPARQL text into RDF4J's internal **query algebra** —
a tree of operators (joins, filters, projections) over triple patterns. A parse
failure is caught and turned into a clean `400`, not a `500`.

> **Key idea** — every failure mode maps to a deliberate HTTP status. Malformed
> query → `400`; no backend → `503`; a `SailException` during evaluation →
> `503` (retryable); a `RepositoryException` → `500`; a query that overruns its
> time budget → `504`. The status *is* the API contract.

That last one is why `setMaxExecutionTime` exists:

> **Trade-off / Gotcha** — `/sparql` is an open endpoint, and a pathological
> query (a huge cartesian join) would otherwise pin a worker thread forever — a
> denial-of-service vector. A per-query timeout (`prolly.rdf4j.query-timeout-seconds`,
> default 30s) caps it. The cost: a legitimately long analytical query can be
> cut off and must raise the limit.

## 3 · Dispatch by query form

A parsed SPARQL query is one of four forms; the controller dispatches on type:

```java
if (query instanceof TupleQuery tq)   { serializeTupleResult(tq, accept, response); }
else if (query instanceof BooleanQuery bq) { serializeBooleanResult(bq, accept, response); }
else if (query instanceof GraphQuery gq)   { serializeGraphResult(gq, accept, response); }
```

`SELECT` → `TupleQuery` (rows of bindings), `ASK` → `BooleanQuery` (true/false),
`CONSTRUCT`/`DESCRIBE` → `GraphQuery` (a graph). Our `SELECT ?friend` is a
`TupleQuery`. Memento / commit-id response headers are stamped *before* the
body starts streaming — once bytes flush, headers are frozen.

## 4 · Evaluation — RDF4J calls back into the Sail

`serializeTupleResult` calls `tq.evaluate()`, and this is the important part:
**the query engine lives in RDF4J, not in the Sail.** The Sails implement no
query pushdown. RDF4J's `DefaultEvaluationStrategy` walks the query algebra,
and every triple pattern at the bottom becomes a call back into the Sail
connection:

```
tq.evaluate()
  └─ RDF4J DefaultEvaluationStrategy walks the algebra
       └─ for each triple pattern:  SailConnection.getStatements(s, p, o, …)
            └─ getStatementsInternal  ──►  the scan from A1
```

So a SPARQL query decomposes into one or more **scans** — exactly the path
[A1](A1-a-scan.md) walked: index selection, prefix seek, decode, term
resolution. A multi-pattern query is RDF4J **joining** the statement streams
those scans produce — a nested-loop join driven from RDF4J's side.

> **Gotcha** — because there is no pushdown, a SPARQL join is RDF4J iterating
> one pattern's `getStatements` results and probing the next pattern per row.
> For selective patterns this is fine; for large intermediate results it is
> not, and join-heavy queries are a known performance soft spot — especially on
> the versioned `ProllySail`. Treat a slow query as a *join shape* problem
> first.

Each result row is then written by the serializer in the format the client's
`Accept` header asked for (`application/sparql-results+json`, `+xml`, CSV…),
streamed straight to the response. The connection closes; the gate was never
taken — a query is a reader.

## Takeaways

- `SparqlController` implements the SPARQL 1.1 Protocol's three request shapes;
  all converge on one `executeQuery`.
- Parsing happens up front; each distinct failure maps to a specific HTTP
  status, and a per-query timeout guards the open endpoint against denial of service.
- The query engine is **RDF4J's** — the Sails do no pushdown. RDF4J evaluates
  the algebra and calls `getStatements` for every triple pattern.
- A SPARQL query is therefore one or more [A1 scans](A1-a-scan.md) joined by
  RDF4J; join-heavy queries are the performance risk.
- Results stream out in the client's requested format; a query is a
  lock-free reader.

## Where this lives

- `prolly-rdf4j-rest/.../server/SparqlController.java` *(private monorepo server
  product)* — the endpoint, parsing, dispatch, error mapping
- `prolly-rdf4j-rest/.../server/SparqlRequests.java` *(private monorepo server
  product)* — request shapes
- The Sail side of evaluation:
  `prolly-flatsail/.../RocksDbFlatSailConnection.java` (`getStatementsInternal`)
- Foundations assumed:
  [rdf-in-five-minutes](../foundations/rdf-in-five-minutes.md)
- Builds on: [A1 · a scan](A1-a-scan.md)
- Continues in: [A5 · a versioned query](A5-a-versioned-query.md) — the
  `?commit=` / `Accept-Datetime` snapshot path.
