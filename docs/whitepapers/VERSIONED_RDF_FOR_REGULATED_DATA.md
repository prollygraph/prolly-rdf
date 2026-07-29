# Versioned RDF for Regulated Data

**A whitepaper-shaped argument for first-class commits, branches, and
merges in knowledge graphs that have to satisfy auditors.**

**Audience:** compliance leads, data-governance architects, and KG
platform engineers in regulated industries (life sciences, financial
services, healthcare, public-sector data).

**Status:** positioning document. Cites public regulatory texts and
public vendor documentation; does not claim conformance certification.
Engineering gaps remain open before any of this is production-grade;
this paper argues the shape of the solution, not that it is finished.

---

## Abstract

Knowledge graphs in regulated environments must answer two kinds of
question that ordinary triplestores answer poorly:

1. **What did the graph assert at time T, exactly?**
2. **Who changed it between T₁ and T₂, why, and on whose authority?**

The standard mitigation is periodic export of the graph to a file
versioned in `git` or object storage. That preserves bytes but loses
query semantics — auditors cannot ask "diff person:Alice between Q1 and
Q2" without re-loading both snapshots into a query engine.

This paper argues that the right shape of solution is to push
versioning *down into the storage layer of the triplestore itself*: a
content-addressed Merkle DAG of RDF state with first-class commits,
branches, three-way merge, and blame. We sketch the architectural
pattern, walk through three regulated-industry worked examples, map
the resulting primitives onto specific compliance regimes (21 CFR
Part 11, HIPAA, BCBS 239, GDPR), and call out the open problems —
blank-node identity, RDF-star semantics, the right-to-erasure tension.

---

## 1. The audit-trail problem in regulated knowledge graphs

### 1.1 What regulated data looks like in practice

Regulated workflows that increasingly use RDF:

- **Clinical trials and pharmacovigilance.** CDISC, IDMP, OMOP — RDF
  vocabularies anchor data dictionaries used across sponsors, sites,
  and regulators. Schema evolves continuously; old submissions must
  remain re-queryable against the schema-as-of-submission.
- **Financial reference data and regulatory reporting.** FIBO,
  XBRL-as-RDF, BCBS 239 lineage requirements. The same security
  identifier can have different attribute values across reporting
  periods; "as-of-2024-12-31" snapshots are the unit of audit.
- **Healthcare clinical knowledge.** SNOMED CT, FHIR-as-RDF,
  drug-interaction graphs. Clinicians are entitled to see the
  reasoning that supported a decision *as it stood at the time of
  the decision*, not as the graph reads today.
- **Public-sector master data.** Land registries, statistical office
  classifications (SKOS), regulatory rulebooks. Historical queries
  ("what did this regulation say in 2019?") are routine.
- **LLM training corpora.** "What facts did model v3.2 train on?"
  is a reproducibility question that boils down to a
  content-addressable snapshot of the KG.

### 1.2 What auditors actually ask for

Boiling down typical regulator questions across these verticals:

| Question                                                          | Today's answer                          | What's missing                             |
|-------------------------------------------------------------------|-----------------------------------------|---------------------------------------------|
| "Show me the graph as of 2024-Q3."                                | Reload Q3 dump in a separate triplestore | Query against historical state in place     |
| "Who changed this triple, and when, and why?"                     | Grep audit logs by hand                  | First-class blame on triple level           |
| "Show me all changes between submissions A and B."                | Diff two N-Triples files                 | Semantic diff that survives blank-node renames |
| "Prove the snapshot you're querying is the one we approved."      | Hash the dump, hope nobody touched it    | Content-addressed root hash, signed         |
| "Two reviewers edited the same ontology branch. Reconcile them."  | Manual N-Triples merge tooling           | Three-way merge over RDF semantics          |

Existing triplestores (Apache Jena/Fuseki, RDF4J/GraphDB, Stardog,
Virtuoso, Amazon Neptune) typically provide insert timestamps and
named-graph snapshotting but not the "git-shaped" primitives those
questions imply. **TerminusDB** is the visible exception, and its
existence is itself evidence that there is a market.

### 1.3 Why "snapshot to a file in git" is not enough

The default workaround — periodic dumps to N-Triples or HDT, versioned
in git or S3 — has three concrete problems for regulated use:

1. **Lossy diff.** Blank nodes get renamed on each export; line-level
   diff between two N-Triples dumps reports thousands of false changes.
2. **Auditor ergonomics.** "Re-load the snapshot on a separate server,
   run SPARQL there" is friction enough that historical queries get
   skipped in practice. They become real audit-time emergencies.
3. **No mid-graph branching.** Two reviewers cannot work concurrently
   on the same ontology branch and reconcile their work.

A versioning system that is *part of the query engine* eliminates all
three.

---

## 2. What "versioned RDF" means

We use the term in a specific, narrow sense:

| Primitive       | RDF-flavored meaning                                                                  |
|-----------------|---------------------------------------------------------------------------------------|
| Commit          | An immutable snapshot of the entire RDF dataset, identified by a content hash, with parent commit(s), author, timestamp, and message. |
| Branch          | A named, mutable pointer to a commit. Updates fast-forward or merge.                  |
| Three-way merge | Given a common-ancestor commit and two child commits, produce a child commit whose dataset is the set-union of the two children's changes, modulo blank-node renaming and (where the engine knows it) RDF semantics. |
| Diff            | Given two commits, return added and deleted quads, in the most semantically meaningful form the engine can produce (e.g. blank-node-aware). |
| Blame           | Given a triple in the current dataset, return the commit that introduced it (and by whom). |
| Time-travel query | SPARQL evaluated against the dataset *as it stood at* a named commit, branch tip, or timestamp. |

These are *exactly* the primitives a Git user expects, applied to
quads instead of files. The whole architectural argument of this
paper is: build them at the storage layer, not as scripts on top of a
non-versioning triplestore.

---

## 3. The architectural pattern: content-addressed RDF

The shape that makes the primitives in §2 efficient is a
**content-addressed Merkle DAG of the dataset state**, with the
triplestore's index structures (SPOC, POSC, etc.) layered on top of
content-addressed nodes rather than mutable B-tree pages.

```
                        +-------------------+
                        |  Manifest         |   (refs/heads/main, refs/tags/v1)
                        +--------+----------+
                                 |
                       +---------v----------+
                       |  Commit (hash)     |   parent[], author, msg, timestamp,
                       |                    |   data_root_hash
                       +---------+----------+
                                 |
                +----------------v----------------+
                |  Prolly Tree / Merkle tree of   |   per-quad chunks,
                |  the SPOC index                 |   content-defined chunking
                +----------------+----------------+
                                 |
                +----------------v----------------+
                |  Chunk store (RocksDB / S3 /    |   immutable, content-addressed
                |  custom)                        |
                +---------------------------------+
```

The properties that fall out:

- **Identity is a hash.** "The graph as of submission #4781" is a
  20-byte hash. You can sign it, embed it in audit reports, attach it
  to a regulatory filing.
- **Diff is structural.** Two trees that share most of their content
  share most of their chunks. Diff is "compare two trees by hash"; it
  costs O(changes), not O(dataset).
- **Time-travel is free.** A historical query is just a SPARQL
  evaluation rooted at an older commit's `data_root_hash`. No reload.
- **Concurrency is branch-and-merge.** Two reviewers each get their
  own branch, edit independently, three-way merge at the end.

This is the architecture Dolt brought to relational tables and
TerminusDB brought to RDF; this whitepaper is associated with an
open-source JVM implementation (`prolly-port` + `prolly-rdf` +
`prolly-jena` / `prolly-rdf4j`) currently under development. Vendor
choice is orthogonal to the shape of the argument — the point is
that *some* such system has to exist underneath any triplestore that
takes regulated workloads seriously.

### 3.1 Where blank nodes break naive merges

A subtlety that matters more in regulated RDF than in most other
contexts: **blank nodes** (anonymous resources) get re-minted on
every parse, so two parsers reading the same N-Triples file produce
different blank-node identifiers. A three-way merge implemented as
"set-union over quad bytes" therefore double-inserts every reified
statement, every list, every owl:Restriction.

The right fix is **RDF dataset canonicalization** — the W3C
RDFC-1.0 (formerly URDNA2015) algorithm, which assigns deterministic
labels to blank nodes based on graph structure. Run it on each side
of a merge before set-union and equivalent statements collapse.

For regulated data this is not a nice-to-have. Reified statements
(rdf:Statement clusters: subject + predicate + object + provenance)
are the typical encoding for "X said Y at time T" — exactly the
metadata the auditor will read first. A merge that double-asserts
those statements silently corrupts the audit trail. The hardest cases
are the ones where set-semantics and provenance disagree: concurrent
assertion of the same triple with different provenance, deletion racing
re-assertion, blank-node identity across branches, and reification or
named-graph metadata that must not be duplicated by a merge. Canonical
labelling of blank nodes (URDNA2015) is the precondition for deciding
any of them.

---

## 4. Worked examples

### 4.1 Clinical trials — submission lineage

A sponsor maintains a CDISC-derived ontology for an ongoing oncology
trial. Over the trial's life, the ontology gains terms (new adverse
events emerge), retires others, and refines hierarchies after FDA
feedback rounds.

**With versioned RDF**, each amendment is a commit, signed by the
amending biostatistician and tagged with the IRB ticket. The CSR
(Clinical Study Report) submitted to the FDA references the exact
commit hash of the ontology as of database lock. Two years later,
when the FDA opens a post-marketing surveillance question, the
sponsor's data team can:

```sparql
# query the dataset as of submission day
SELECT ?ae ?grade WHERE {
  ?subject ex:hadEvent ?ae . ?ae ex:grade ?grade
}
# evaluated against commit 3f9c2a... (pinned in the CSR)
```

…and answer in the schema the agency originally reviewed, not the
schema that has drifted since.

### 4.2 Financial reference data — quarter-end reconciliation

A G-SIB maintains a FIBO-derived security master with several
hundred million quads. Each quarter, three independent reviewers
load corrections into branches `correction-equities`,
`correction-fixedincome`, `correction-derivatives`. At quarter-end,
the data-governance team merges the three branches into `main`.

**With versioned RDF**, the merge is a single API call that produces:
- a new commit with three parents,
- a list of conflicts (quads where two reviewers disagreed),
- a content hash that becomes the "official" Q4 reference dataset,
  signed and submitted to BCBS 239 lineage tooling.

Without versioned RDF this is a multi-day ETL exercise and the audit
trail is whatever the reviewers thought to put in their commit
messages on a separate Confluence page.

### 4.3 LLM training corpora — reproducibility under audit

A foundation-model team curates a domain KG (medical knowledge,
financial regulations, scientific literature) and uses it to
ground-truth model training. Six months after model v3.2 ships, an
incident response asks: "what facts did v3.2 see during alignment?"

**With versioned RDF**, the model card embeds the data-root hash of
the KG snapshot used. The team can re-evaluate any SPARQL query
against that exact snapshot, without restoring a backup, without
risking schema drift in the answer.

---

## 5. Mapping to compliance regimes

We claim **enabling-shape** alignment, not certification. Each row
below states what a versioned-RDF substrate makes *easier* to
demonstrate to an auditor; it does not certify any specific product
against any specific regime.

### 5.1 21 CFR Part 11 (FDA — electronic records / electronic signatures)

| Part 11 requirement                                            | Versioned-RDF property that helps                                         |
|----------------------------------------------------------------|---------------------------------------------------------------------------|
| §11.10(b) accurate, reproducible record retrieval              | Time-travel query at any commit hash; content-addressed identity proves the record is the one approved. |
| §11.10(e) computer-generated audit trails                      | Every commit carries author + timestamp + parent hash; chain is verifiable end-to-end without separate audit log. |
| §11.50 signature manifestations linked to records              | Sign the commit hash (e.g. detached signature, transparency-log entry); the signature binds to the entire record state, not a row. |
| §11.70 signature/record linking that cannot be excised         | Commits are immutable in a content-addressed store; any tampering changes downstream hashes. |

### 5.2 HIPAA Security Rule (45 CFR §164.312)

| HIPAA requirement                                              | Versioned-RDF property that helps                                         |
|----------------------------------------------------------------|---------------------------------------------------------------------------|
| §164.312(b) audit controls                                     | First-class blame: every PHI triple traces to the commit that introduced it. |
| §164.312(c) integrity controls                                 | Content addressing: any unauthorized alteration produces a different root hash. |
| §164.312(e) transmission security                              | Sync transports content-addressed chunks; receivers verify hashes; no implicit trust in the bus. |

**Tension to call out:** the *Right to amend* (§164.526) and HIPAA's
expectation that records can be corrected sit awkwardly with
content-addressed immutability. The pattern that works is "amend by
new commit, retain old commit, query API surfaces the latest by
default." This is the same shape Git uses for `git revert`.

### 5.3 BCBS 239 (Basel — risk data aggregation and reporting)

| Principle                                                      | Versioned-RDF property that helps                                         |
|----------------------------------------------------------------|---------------------------------------------------------------------------|
| P3 Accuracy and Integrity                                      | Content-addressed storage detects drift; merge is auditable.             |
| P6 Adaptability                                                | Branches let the firm trial reporting changes without disturbing prod.   |
| P11 Distribution                                               | Snapshots are byte-stable; identical hash proves identical data across consumers. |

### 5.4 GDPR — the right-to-erasure tension

GDPR Article 17 (right to be forgotten) is genuinely awkward for any
content-addressed system. You cannot *remove* a triple from history
without invalidating downstream commit hashes. Real-world patterns:

1. **Tombstone-then-rewrite.** Mark the triple as erased in the
   current commit; on a defined retention horizon, rewrite the
   relevant chunks with a documented purge process and a new root.
   Audit log records the purge as itself a regulated event.
2. **Encrypt-with-rotated-key.** Store sensitive payloads encrypted;
   destroy the key on erasure. The graph structure is preserved;
   the cleartext is not recoverable. Compatible with content
   addressing because the ciphertext is what's hashed.
3. **Out-of-band PII.** Keep PII in a separate, mutable store keyed
   by IRI; the versioned RDF graph holds only IRIs, not PII payloads.
   Erasure is a deletion in the side store; the RDF graph survives.

None of these are clean. Each has trade-offs. A regulated deployment
needs to pick one *before* the first sensitive triple lands.

### 5.5 EU AI Act — high-risk system data governance

Article 10 requires "data governance and management practices"
including "examination in view of possible biases" and traceability
of training data. The hash-of-dataset-as-input-to-training is
exactly the artifact Article 10 is reaching for; versioned RDF makes
that artifact a primitive, not an export.

---

## 6. Implementation considerations

### 6.1 Canonicalization is non-negotiable

If the engine's merge does not run W3C RDFC-1.0 (or stronger) on
each side before set-union, **it will silently corrupt audit trails
that use reification or RDF-star**. This is the single most
important engineering fact for a regulated deployment.

Practical knobs:
- Apply canonicalization at *commit time*, not merge time, so
  blank-node rename never crosses commit boundaries.
- Time-budget the canonicalizer (URDNA2015 is worst-case
  super-polynomial on adversarially-cyclic blank-node graphs);
  fail closed with an explicit error rather than producing a
  best-effort canonicalization on timeout.

### 6.2 Time-travel queries

A useful surface area:

```
SELECT ?ae WHERE { ?s ex:hadEvent ?ae }
  -- AS OF COMMIT 3f9c2a...
  -- AS OF BRANCH 'submission-2024-q4'
  -- AS OF TIMESTAMP '2024-12-31T23:59:59Z'
```

Each form maps to selecting a different `data_root_hash` for query
evaluation. The query engine itself is unchanged. The "as of
timestamp" form requires a timestamp-to-commit index, which is just
a sorted list maintained at commit time.

### 6.3 Conflict UX

When two branches assert contradictory metadata about the same
statement (different `ex:certainty`, different `prov:wasDerivedFrom`),
the engine cannot pick a winner. The minimum surface is:

- Conflict object: triple, ours-value, theirs-value, ancestor-value.
- Resolution API: record a human decision as a *new commit* on the
  merge branch.
- Audit trail: who resolved, when, with what justification.

This part is open: the conflict *model* is settled (triple-level, against
the common ancestor), the resolution *policy* for provenance-bearing
statements is not.

### 6.4 Sync and distribution

Content-addressed chunks turn replication into a
"Merkle-tree-difference" protocol — the same shape Git, IPFS, and
Dolt use. A regulated deployment with multiple subsidiaries can
replicate without a write-leader, and every receiver can verify the
chunks it got match the hashes it asked for.

### 6.5 What this is *not* a substitute for

- **A SIEM.** Application-level access logging, anomaly detection,
  user-session forensics — versioned RDF doesn't replace any of
  that. It is a substrate; SIEM stays adjacent.
- **A backup system.** Content addressing makes recovery from
  accidental rewrites tractable, but operational backup with
  off-site retention is still required.
- **Process compliance.** A pristine commit history doesn't make a
  process Part-11-compliant; SOPs, training records, and validation
  protocols still have to exist.
- **A query optimiser miracle.** Time-travel queries against a
  10-year-old commit pay the same cost as queries against `main`;
  there is no automatic "old data is colder" tiering unless you
  build one.

---

## 7. Open problems

These are the items a regulated buyer should ask any versioned-RDF
vendor (commercial or open source) about before committing:

1. **Reasoner output handling.** RDFS/OWL inference can produce
   triples not asserted by either branch. Naive merge double-asserts
   them. Solution requires per-quad asserted-vs-derived tagging.
   See `TODO` for scoping.
2. **RDF-star quoted triples in merge.** RDF 1.2 lets quoted triples
   carry metadata. The W3C spec is still settling. Pass-through is
   the v1 stance; structurally-aware merge is future work.
3. **Performance at scale beyond ~10⁷ quads.** URDNA2015's average
   case is polynomial but the constant is non-trivial. Deployments
   with billions of triples need sharded canonicalization, which is
   itself a research project.
4. **Erasure under content addressing.** §5.4 above. No clean answer;
   pick a pattern, document it, get sign-off before going live.

---

## 8. Glossary

| Term                       | Definition                                                                            |
|----------------------------|---------------------------------------------------------------------------------------|
| RDF                        | Resource Description Framework (W3C). The data model is "a set of subject-predicate-object triples (or quads, with a graph context)." |
| Triplestore                | A database whose query language is SPARQL and whose data model is RDF.                |
| Merkle DAG                 | A directed acyclic graph in which each node's identifier is the cryptographic hash of its content (including child references). |
| Prolly tree                | "Probabilistic B-tree." A Merkle tree whose structure is determined by content-defined chunking, so equivalent datasets converge to identical structure. |
| URDNA2015 / RDFC-1.0       | W3C RDF Dataset Canonicalization 1.0. Produces a deterministic labelling of blank nodes based on graph structure. |
| Reification                | RDF idiom for talking *about* a triple: a 4-triple cluster typed as `rdf:Statement` with `rdf:subject`, `rdf:predicate`, `rdf:object`. |
| RDF-star                   | RDF 1.2 syntax `<<:s :p :o>>` for quoted triples used as subjects/objects.            |
| 21 CFR Part 11             | US FDA regulation governing electronic records and electronic signatures.             |
| BCBS 239                   | Basel Committee principles for effective risk data aggregation and risk reporting.    |

---

## 9. Further reading

Further reading in this repository:

- [`prolly-rdf4j/docs/adr/`](../../prolly-rdf4j/docs/adr/) — the architecture decision records behind the
  versioned RDF substrate (commit model, merge, canonicalization).
- [`prolly-urdna2015/`](../../prolly-urdna2015/) — the URDNA2015 canonical
  labelling implementation this argument depends on.
- [`README.md`](../../README.md) — what the ring actually ships today.

External references (for the regulatory citations above):

- 21 CFR Part 11 — `https://www.ecfr.gov/current/title-21/chapter-I/subchapter-A/part-11`
- HIPAA Security Rule — 45 CFR §164.312
- BCBS 239 — Basel Committee on Banking Supervision, "Principles for effective risk data aggregation and risk reporting"
- W3C RDFC-1.0 — `https://www.w3.org/TR/rdf-canon/`
- EU AI Act — Regulation (EU) 2024/1689, Article 10
- GDPR Article 17 — Regulation (EU) 2016/679
