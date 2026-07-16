
# Benchmark dataset cards — the receipts behind the numbers

Every performance figure in this repo's docs traces to a run on a named public
dataset. These cards are the provenance: what the dataset is, what each benchmark
tests, the measured tables, and the caveats — so a skeptic can follow the receipt
without access to the private work tracker.

| Card | Dataset | What it grounds |
|---|---|---|
| [wiki-vote](wiki-vote.md) | SNAP wiki-Vote (103,689 directed edges, power-law, cyclic) | The triangle-join table: triejoin 3.2 s vs 10.4/36.8/46.4 s, 131,925 triangles agreed |
| [ncit](ncit.md) | NCI Thesaurus (10.77M triples, deep acyclic taxonomy) | The ingest inversion, the read sweet-spots (both vintages), the versioning economics |

**Read ratios, not absolutes** — the reference box is a small single machine; the
rankings and ratios are the findings. Vintages matter: `the-two-sails`
[dates every number](../foundations/the-two-sails.md).
