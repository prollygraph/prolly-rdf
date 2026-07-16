
# ADR-0004: Extract `prolly-platform` from `prolly-rdf4j`; let faces plug in

## Status

Proposed. Pre-1.0 refactor — the cost of doing this now (no
external consumers yet) is small; the cost of doing it after
`prolly-bom` and friends ship as derivatives of `prolly-rdf4j` is
permanent.

## Context

The repo today lives in two layers:

- `prolly-port-core` — pure Dolt port (`com.dolthub.prolly.*`),
  content-addressed Merkle trees + prolly-tree algorithms.
- `prolly-rdf4j` — RDF4J Sail SPI implementation, Dictionary, four
  quad-order indexes, SPARQL controller, AND a pile of substrate
  that isn't RDF-specific: `CommitLog`, `RefsStore`, `MergeEngine`,
  `RootMetaTree`, `RootMetaTreeStore`, `ProvenanceIndex`, the HTTP
  versioning endpoints (`/sparql/commits`, `/sparql/branches`,
  `/sparql/diff`, `/sparql/provenance`).

A future repo layout the team has discussed includes additional
"faces" that aren't RDF:

```
prolly-json/             ← face 3: JSON documents
prolly-csv/              ← face 4: tabular rows
prolly-parquet/          ← face 5: columnar
prolly-xbrl/             ← face 6: financial facts
prolly-fhir/             ← face 7: medical records
prolly-feature-flags/    ← face 8: flag definitions
prolly-secrets/          ← face 9: KV with audit
```

Each face module would declare:

- **Schema** — what kind of `TupleDescriptor`(s) it stores in the
  tree (BOM might have *components*, *dependencies*, *licenses* as
  separate named tables in the RootMetaTree).
- **Adapter** — how its native API (CycloneDX JSON in,
  `Statement` in/out for RDF, `Row` in/out for tabular) maps onto
  the shared tree operations.
- **HTTP surface** — `/bom/query`, `/sparql`, `/csv/select`. Each
  face owns its query language; the platform supplies versioning +
  diff + branch endpoints uniformly.

## Why stacking on `prolly-rdf4j` is the wrong direction

- **Semantic mismatch.** A BOM component has 30+ structured fields.
  Representing it as ~30 RDF triples per component is technically
  possible (JSON-LD does it daily) but the query model becomes
  "every BOM query is a SPARQL join across 30 patterns." Bad
  ergonomics, bad performance, bad debuggability.
- **Query-language coupling.** Every face would have to either
  accept SPARQL or build a translation layer to it. JSON users
  don't want SPARQL; XBRL users have XBRL-specific query
  semantics.
- **Cardinality of evolution.** The RDF face wants to evolve (RDF
  1.2, RDF-star, etc.) on a different cadence from the BOM face. A
  shared upstream means RDF changes ripple into BOM testing.
- **Storage shape.** Tabular and columnar faces benefit from
  row-grouped or column-grouped trees, not the SPOC/POSC/OSPC/CSPO
  quad orders RDF needs. Forcing them through RDF means storing
  everything as quads even when a single-table layout is far more
  efficient.

## What stays shared cleanly

- **Versioning UX**: branches, commits, merges, diff, time-travel,
  blame. Same primitives, same Web UI patterns. The
  `prolly-rdf4j-ui` we're building is mostly a *version-control*
  UI; the SPARQL editor is one tab among potentially many. A
  future `prolly-bom-ui` could share the topbar, branch switcher,
  commits page, Compare page, even the drawer pattern.
- **Storage backend**: one RocksDB per deployment, multiple named
  tables in the `RootMetaTree`, faces mount their tables
  side-by-side. Branches and merges work across all of them
  atomically.
- **Authn/authz**: one auth layer, faces use it.
- **Observability**: one metrics surface, faces register their
  counters.

## Concrete recommendation

1. **Keep `prolly-port-core` as it is** (the pure Dolt port —
   `com.dolthub.prolly.*`).
2. **Split `prolly-rdf4j` into two modules:**
   - **`prolly-platform`** — the substrate currently mixed into
     the Sail's package: `CommitLog`, `RefsStore`, `MergeEngine`,
     `RootMetaTree`, `RootMetaTreeStore`, `ProvenanceIndex`, the
     HTTP versioning endpoints.
   - **`prolly-rdf4j`** — now just the Sail SPI implementation +
     Dictionary + four quad-order indexes + SPARQL controller.
3. **New faces** (`prolly-bom`, `prolly-json`, etc.) depend on
   `prolly-platform`, not on `prolly-rdf4j`.
4. **The UI module stays general**: `/branches`, `/commits`,
   `/compare`, `/schema`, `/blame` work for any face. Each face
   contributes a face-specific tab (*Explore* for SPARQL,
   *Components* for BOM, etc.).

## When stacking on RDF might still make sense

If a face is genuinely RDF-shaped — e.g., `prolly-fhir` could
plausibly live on RDF since HL7 already has a FHIR/RDF spec —
then storing it as RDF in `prolly-rdf4j` is fine. The deciding
question per face:

> "Would this domain reach for SPARQL on its own merits?"

- For BOM, JSON, CSV, Parquet: **no**.
- For FHIR and possibly XBRL: **maybe**.

## Bottom line

The valuable thing being built is a **content-addressed,
versioned, mergeable, diffable, blame-able multi-table store with
an HTTP control plane**. RDF/SPARQL is *one face* on it. Make that
explicit in the module layout — extract the platform, let faces
plug in — and the BOM/JSON/etc. modules become tractable instead
of awkward derivatives.

The cost of doing this refactor now (pre-1.0, before external
consumers exist) is small. The cost of doing it after
`prolly-bom` ships and depends on `prolly-rdf4j` is permanent.

## Suggested mechanics

A migration that doesn't break the world:

1. **Create `prolly-platform` module** with the existing parent
   pom plumbing. Move (don't rewrite) the substrate classes from
   `prolly-rdf4j/src/.../sail/` into a new package
   `com.earasoft.prolly.platform.*` inside the new module.
2. **Make `prolly-rdf4j` depend on `prolly-platform`** — same
   classes, different import paths. The Sail keeps its current
   public API; internal references just point at platform.
3. **Move the controller** (`SparqlController`) — keep RDF-specific
   endpoints (`/sparql/query`, `/sparql/update`, `/sparql/load`,
   `/sparql/provenance`) in `prolly-rdf4j-rest`; extract the
   versioning ones (`/api/branches`, `/api/commits`, `/api/diff`,
   `/api/merge`) into `prolly-platform-server`. Faces register
   under `/<face-name>/...` for their face-specific routes.
4. **Repath the UI**: existing `/sparql/commits` → `/api/commits`,
   etc. The Angular API service abstracts the path so the
   UI-side change is a single file. SPA shell stays the same; the
   Explore tab continues to talk to `/sparql/*`.
5. **Test**: existing prolly-rdf4j tests stay green via the
   re-exports. Add a smoke test that proves a `prolly-platform`
   consumer can mount its own table in the RootMetaTree without
   touching the Sail.

## What this isn't trying to solve (deferred)

- **Cross-face joins** ("show me the BOM components whose authors
  also appear in this RDF graph"). Useful but not load-bearing.
  Faces talk to their own tables in v1; cross-table queries are a
  later iter.
- **Face-aware merge** (a JSON face might want different conflict
  semantics than RDF's set-union). The platform's `MergeEngine`
  exposes the LCA + diff primitives; faces can layer their own
  resolvers on top.
- **Per-face UI plugins**. The first faces (BOM, JSON) can
  bundle Angular components in their own module; routing
  composition is a v1.x concern, not v1.0.

## Open questions

1. **Package naming** — `com.earasoft.prolly.platform` vs
   `com.dolthub.prolly.platform` (or stay under
   `prolly-port-core` as a sub-package)? Probably `earasoft` for
   anything we author here, `dolthub` reserved for the literal
   Dolt port.
2. **`ProvenanceIndex`** lives in `prolly-rdf4j/index/` today
   under the implicit assumption it's RDF-keyed (SpocKey =
   four TermIds). Generalizing it to "blame any tuple in any face"
   means parameterizing by the table's `TupleDescriptor`. Cheap
   for new tables; semantic question for existing ones.
3. **Branch-per-table or branch-across-all-tables?** A merge or
   branch should ideally span every mounted table atomically.
   The platform handles this naturally because branches are
   `RootMetaTree`-level (all named tables in one tree); confirm
   nothing in the current design assumes single-table.

---

*Plan version 1. Ready for stakeholder review before the move
starts.*
