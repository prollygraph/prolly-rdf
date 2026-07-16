<!-- provenance: exported 2026-07-26 from the private monorepo's test_ontologies_zips/ncit.md; links + run instructions adapted to this repo -->

# `ncit.zip` — NCI Thesaurus (deep taxonomy)

| | |
|---|---|
| **Source** | [NCI Thesaurus OBO Edition](https://evs.nci.nih.gov/) (`ncit.owl`, RDF/XML) |
| **Size** | ~811 MB unzipped · **10,769,587** triples · zip entry `ncit.owl` |
| **Shape** | deep taxonomy · literal-heavy (definitions/synonyms) · **acyclic** (`subClassOf` DAG) · static · TBox · default-graph |
| **Why it's here** | the realistic *bulk + taxonomic* corpus — the dataset that overturned the synthetic ingest ranking |

## What it tests

| Aspect | Benchmark | Engines |
|---|---|---|
| Bounded-sample ingest | `NcitIngestBenchmark` (JMH) | prolly / flatsail / rdf4j-native |
| Whole-file streaming ingest | `StreamingNcitIngest` (tool) | prolly / flatsail / rdf4j-native |
| Loaded reads (point / scan / join) | `NcitReadBenchmark` (JMH, 500k) | prolly / flatsail / rdf4j-native |
| Versioning (commit / diff / structural sharing) | `NcitVersioningBenchmark` (tool, ProllySail-only) | prolly |

## Performance findings

**Ingest — the synthetic ranking *inverts* on real data.** The synthetic dense-core toy ranked ProllySail
the ingest winner (58 ms vs 131 ms); on real NCIt it is the slowest. Bounded 100k: prolly ~1.85× slower,
~2.2× memory, ~3.4× disk. Whole-file (10.77M): **ProllySail does not finish a single-pass load** (>10 min,
batched commits); flatsail ~175 s / 172 MB heap / 684 MB disk; native ~167 s / 1.3 GB heap / 1020 MB disk.
On-disk and heap rankings *reverse* at scale — the cost of versioning + content-addressing + 4 permutation
indexes that the toy hid.

**Reads — the sweet-spots *hold* on real data** (500k sample, indicative; prolly noisy from garbage-collection pauses, ordering
robust):

| read | prolly | flatsail | rdf4j-native | winner |
|---|---:|---:|---:|---|
| point lookup (µs) | 78 | 8.7 | **4.7** | native |
| `rdfs:label` scan (µs) | **1,470** | 3,715 | 42,596 | prolly (29× over native) |
| full scan (ms) | **293** | 1,415 | 567 | prolly |
| subclass→label join (ms) | 1,063 | 125 | **70** | native |

ProllySail wins **scans**; native wins **point + acyclic join**; flatsail has fast points, worst full scan.
So real data *corrected* the ingest story but *confirmed* the read story — which axis it overturns depends
on the operation.

**Versioning — the benefit only ProllySail can offer, finally measured.**
- **Cross-commit sharing works:** per-commit cost is independent of accumulated history — the N-th "monthly
  release" commit is as cheap (~700 ms, ~6,500 new chunks at 150k) as the 1st. flatsail/native retain *zero*
  history (they overwrite).
- **Intra-commit churn rewrites ≈ the whole tree**, and you can barely localize it: batching edits by subject
  saved only ~8% (it localizes only SPOC; POSC/OSPC/CSPO scatter the same triples). Per-index decomposition
  confirms the cost is `dict(~8%) + 4 × permutation(~23% each)` — the four permutation indexes write within
  1% of each other. TermIds are hash-derived, so you can't localize by term choice.
- **Diff is exact** (reports exactly the churn) with time that tracks corpus size (scatter defeats the Merkle
  short-circuit).

## How to run

The bench classes live in this repo, `prolly-rdf4j/src/test/java/.../bench/` —
`StreamingNcitIngest` (whole-file streaming ingest), `NcitReadBenchmark` (JMH, loaded
reads), `NcitVersioningBenchmark` (commit/diff/churn). Build the module's test
classpath (`mvn -pl prolly-rdf4j test-compile`), download `ncit.owl` from the source
above, and point the runs at it with `-Dncit.zip=...` plus
`--enable-native-access=ALL-UNNAMED -Xmx6g`. Absolute milliseconds are
machine-specific — compare **ratios**, and read
[the-two-sails](../foundations/the-two-sails.md) for both measurement vintages.
