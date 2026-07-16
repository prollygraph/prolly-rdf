
# prolly-rdf4j — Design Docs

Living documentation about implemented architecture and forward-looking
design. Distinct from [`ARCHITECTURE.md`](../ARCHITECTURE.md), which is the
top-level reference. (The phase-by-phase work plans this set once sat beside
live in the private monorepo's work tracker, not in this repo.)

| Doc | Status | What it covers |
|---|---|---|
| [`connection-isolation.md`](connection-isolation.md) | implemented (Phase 3 iter 23) | Per-connection mutation buffering: Sail-level vs per-tx split, lifecycle, rollback mechanism, the 4 verifying tests |
| [`cas-rebase.md`](cas-rebase.md) | design (Phase 4 target) | Multi-writer CAS-rebase protocol building on the per-connection foundation: retry loop, rebase strategies, conflict semantics, effort estimate |
| [`cas-rebase-runbook.md`](cas-rebase-runbook.md) | runbook (executable companion to `cas-rebase.md`) | 12 numbered implementation steps with file paths, test gates per step, pre-flight checks, risk hotspots, and abort signals |
| [`graph-rag-integration-guide.md`](graph-rag-integration-guide.md) | integrator-facing | How to wire prolly-rdf4j as the versioned graph backend for LLM Graph-RAG flows: LangChain, LlamaIndex, DSPy, raw Anthropic SDK recipes. |

When a doc transitions from design → implemented, update its Status row.
