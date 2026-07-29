---
tags:
  - architecture
---

# Prior art: where prolly-rdf4j sits among versioned & graph data systems

*How this project relates to Dolt, Fluree, TerminusDB, lakeFS/Nessie, and plain triple stores — what it borrows, what's different, and where it's honestly behind.*

> **What you'll learn** — the neighbors in the "versioned / immutable / graph
> data" landscape, the one axis each shares with prolly-rdf4j and the one it
> doesn't, and the specific four-way intersection this project occupies.
>
> _Reading time: ~9 minutes._

> **Caveat.** This describes the *design space*, not a benchmark shoot-out.
> prolly-rdf4j is **v0.2.0-BETA**; every system below is more mature, and several
> are shipping commercial products. Treat competitor details as "best general
> understanding" (knowledge has a cutoff and these projects move) — verify against
> their docs before quoting.

## The landscape in one table

| System | Granularity | Versioning model | Query | Maturity |
|---|---|---|---|---|
| **prolly-rdf4j** (this) | RDF triples/quads | **git directed acyclic graph** (branch/merge/diff/bisect) | SPARQL + **worst-case-optimal join triejoin** | beta |
| **Dolt** | SQL rows | git directed acyclic graph | SQL | production |
| **Fluree** | RDF triples | immutable **ledger** + git-style branch/rebase (linear, fast-forward merge) | FlureeQL/SPARQL/GraphQL + SHACL policy | production |
| **TerminusDB** | documents/graph | git-style branch/merge | WOQL/GraphQL | production |
| **lakeFS / Nessie / Iceberg** | files / tables | git-style branch/merge | engine-dependent (Spark/SQL) | production |
| **RDF4J NativeStore / GraphDB / Stardog / Neptune** | RDF triples | **none** | SPARQL | production |

## The neighbors

### Dolt — the direct ancestor
Dolt is "git for **SQL**," and its prolly tree is the data structure this project
**ports to Java** (`com.dolthub.prolly`). The shared substrate is the whole
point: content-addressed Merkle B-tree, content-defined chunking,
history-independence, structural sharing. The divergence is the surface —
prolly-rdf4j puts **RDF + SPARQL** on the tree instead of SQL, and it is **not
byte-compatible** with Dolt (a deliberate, documented divergence —
[`cross-lang/BITCOMPAT_FINDINGS.md`](./bitcompat-findings.md)). If
you want git-for-data and your data is *tabular*, use Dolt; this project exists
because the data is a *graph*.

### Fluree — the closest RDF cousin, a different integration model
Fluree is also an immutable, time-travel RDF database on a content-addressed
tree, and it now ships **git-style branch/rebase/merge** of data ledgers (`fluree
branch create | rebase | merge`) — so the substrates rhyme more than this doc once
claimed (an earlier version asserted Fluree had "no branch/merge"; that is no
longer true). Two real differences remain. **(1) linear integration vs. 3-way
merge.** Fluree integrates a branch by *rebase + fast-forward* — replay the
branch's changes onto a linear, cryptographically-verifiable ledger ("prove this
fact at `t`"), then fast-forward. prolly-rdf4j does a **true 3-way structural
merge** of divergent branches (`MergeEngine.mergeStructural` — two parents,
triple-level conflict detection against the common ancestor, no replay). Fluree
linearizes history and proves it; prolly-rdf4j reconciles divergence into a
merge commit. **(2) governance vs. join.** Fluree ships query-time SHACL
**policy enforcement** and decentralized cryptographic trust; prolly-rdf4j ships
a **worst-case-optimal triejoin** (a measured ~14× on cyclic queries) and a
*planned* regulated-forensics audit layer (`prolly-audit`, currently a skeleton).
Fluree optimizes governance + decentralized trust + verifiable temporal proof;
this project optimizes 3-way merge + cyclic-join performance + (eventually)
compliance-grade audit.

### TerminusDB — the other git-for-graphs
The nearest peer in *shape*: a revision-controlled graph database with
branch/merge. It uses documents + WOQL over succinct data structures and its own
store; prolly-rdf4j is an **RDF4J Sail** (standard SPARQL, drops into the Java
RDF ecosystem) on a Dolt-derived prolly tree. Same git-for-graphs thesis,
different query language, store, and ecosystem fit.

Three architectural differences worth naming precisely:

- **Integration model.** TerminusDB is out-of-process for a JVM consumer;
  prolly-rdf4j is a library — `mvn dependency:tree` ends at `prolly-rdf4j` and
  the Sail runs in your process. That cuts both ways: in-process means no
  network hop, and also dependency + Java-version coupling
  ([ADR-0044](../prolly-rdf4j/docs/adr/0044-server-client-default-integration-model.md)
  makes the client model the default for exactly that reason).
- **Shared substrate with Dolt — narrow, and not a headline.** prolly-rdf4j and
  Dolt share the prolly *tree* engine, so in principle they could share chunk
  storage and remote tooling. But the data shapes differ — quads here, SQL rows
  there — so there is **no** "open your RDF graph in Dolt" interoperability, and
  byte-compatibility would not create any. The real value of the shared lineage
  is narrower: Go as a golden-master oracle for the Java port's correctness
  ([cross-language fixtures](bitcompat-findings.md)). Treat it as a niche
  property, not a differentiator.
- **Licensing shape.** Both are open source; TerminusDB pairs its with a
  commercial cloud offering, while this substrate is Apache-2.0 throughout.

### lakeFS / Nessie / Iceberg — git-for-data, wrong layer
These bring branch/merge/time-travel to **data lakes** — files, object stores,
table formats — not to a queryable graph of triples. The granularity is a
file/table commit, not a triple-level merge with semantic diff. Complementary,
not competing: they version the lake; this versions the graph inside it.

### Plain triple stores — the baseline

"No versioning" flattens real differences; what these engines actually offer
ranges from nothing to point-in-time recovery, none of it branchable:

| Engine | Implementation | Versioning posture |
|---|---|---|
| Apache Jena (TDB, Fuseki) | Java | none — the default JVM choice, mature and widely deployed |
| RDF4J NativeStore | Java | none — the embedded library this project implements a Sail for |
| Ontotext GraphDB | Java | snapshots |
| Stardog | Java | snapshots; enterprise reasoning + virtualization |
| Virtuoso | C/C++ | audit log, no branching; multi-model SQL + SPARQL |
| Amazon Neptune | managed | point-in-time restore, not branching |
| Oxigraph | Rust | none — deliberately light and embeddable |

The pattern is the point: **versioning gets layered on by pipelines, not by the
store.** Teams stitch git plus a transformation tool plus the triplestore, and
the graph itself still has no commits to diff or merge. That is the gap this
project targets. prolly-rdf4j's pitch against them is precisely
the two things they lack — **history (branch/merge/time-travel)** and the **worst-case-optimal join** — delivered as an RDF4J Sail so it slots into the same ecosystem. Their
pitch back is overwhelming: years of production hardening, scale, and features.

## The intersection this project occupies

Plenty of systems hit *one* of these; few hit all four at once:

1. **git-style branch + true 3-way merge** of divergent data (not linear rebase +
   fast-forward), on
2. **RDF/SPARQL** (not SQL or files), via a
3. **drop-in RDF4J Sail** (ecosystem fit), with a
4. **worst-case-optimal join** for cyclic graph queries — plus a *designed*
   compliance-grade forensics audit (`prolly-audit`).

Dolt has 1 but not 2. Fluree now has 2 and the *branch* half of 1 — but it
integrates by linear rebase + fast-forward, not the true 3-way merge of divergent
branches that 1 names, and it has no worst-case-optimal join. TerminusDB has 1+2
but its own stack, not RDF4J + SPARQL + worst-case-optimal join. That specific
4-way intersection is the project's reason to exist.

## Where it's honestly behind

- **Maturity:** every system above is production; this is beta. No contest today.
- **Single-writer:** no multi-writer consensus (federation is git-style sync, not
  a cluster) — where Fluree/Neptune scale out.
- **Bulk ingest:** the per-commit-rebuild crater (the
  bulk-load plan is the fix, not yet built).
- **`prolly-audit` is a skeleton** — the forensics/verifiability story is designed
  (atomic co-commit, URDNA2015 triple hashing, NIST 800-53 / FIPS mapping) but the
  default implementation throws. Fluree's verifiability *ships*.

The differentiators are real and the combination is uncommon; the production
story isn't written yet.

## Where to go next

- the-two-sails — the versioned/unversioned split.
- the-leapfrog-triejoin — the worst-case-optimal join differentiator.
- the-go-port — the Dolt relationship + parity verification.

## Where this lives

- [`TreeMutator.java`](https://github.com/prollygraph/prolly-core/blob/main/dolthub-java-port/src/main/java/com/dolthub/prolly/TreeMutator.java) — the Dolt prolly-tree port (the shared substrate with Dolt/Fluree).
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java` — the versioned RDF4J Sail (the branch/merge + RDF4J-native angle).
- `prolly-rdf/src/main/java/com/earasoft/prolly/semantic/LeapfrogTriejoin.java` — the worst-case-optimal join (the differentiator vs other triple stores).
- `prolly-audit/src/main/java/com/earasoft/prolly/audit/AuditLog.java` — the (skeleton) forensics audit layer: the planned answer to Fluree-style verifiability, aimed at regulated/compliance use.
- `cross-lang/BITCOMPAT_FINDINGS.md` — the documented (non-)relationship to Dolt's on-disk bytes.
