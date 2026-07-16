
# Graph-RAG demo notebook

End-to-end Graph-RAG against a versioned RDF knowledge graph stored
in prolly-rdf4j. Companion to
[`docs/graph-rag-integration-guide.md`](../../docs/graph-rag-integration-guide.md).

The point: show what's different about Graph-RAG on a *versioned*
substrate — every cited fact carries a commit hash + datetime, and
the same question can be re-run against any historical state via
`?commit=<hex>`. Vector RAG can't do either.

## Prerequisites

- **Server running:** prolly-rdf4j on `http://localhost:8080` with
  provenance enabled. From the repo root:
  ```
  make build-ui run-mem  # or run with --prolly.rdf4j.provenance-enabled=true
  ```
- **Anthropic API key:** export `ANTHROPIC_API_KEY=…`
- **Python deps:** `pip install anthropic SPARQLWrapper requests`

## Run

```bash
python demo.py
```

Or open `demo.py` in VSCode / Jupyter — the `# %%` markers make each
section run as a notebook cell.

## What it does

1. Seeds ~80 triples about authors, papers, and citations into a fresh
   prolly-rdf4j store (records the commit hash of this baseline).
2. Defines a `graph_rag(question)` function that wires Anthropic's
   tool-use API to a `run_sparql` callback.
3. Asks 5 questions covering simple-lookup → multi-hop → aggregation →
   negation patterns.
4. For each answer, fetches the citation trace via
   `/sparql/provenance` and prints "first seen at commit X on
   YYYY-MM-DD".
5. Stages and squash-commits new data (a retraction), then re-asks
   the first question against `?commit=<pre-retraction>` to show
   time-travel.

Total runtime: ~30 seconds (mostly Anthropic round-trips).

## Expected output (abbreviated)

```
[seed] committed 78 triples; baseline = a3f1c…
Q1: Who wrote "Attention Is All You Need"?
  → 8 authors, 1 SPARQL round
  → citation: first seen at a3f1c… on 2026-05-14 18:32:11 GMT (msg: "baseline import")

Q5: Which authors have written ≥3 papers but are NOT cited by anyone else?
  → 2 authors, 3 SPARQL rounds
  → citations: a3f1c… (baseline import)

[time-travel] after retracting Vaswani→knows→Hinton, re-ask Q1:
  → at HEAD:        still 8 authors (Vaswani still listed as author)
  → at pre-commit:  identical 8 authors (sanity check)
```
