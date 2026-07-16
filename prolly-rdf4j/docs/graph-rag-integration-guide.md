
# Graph-RAG integration guide

> How to wire prolly-rdf4j into an LLM stack as the **versioned graph
> backend** for retrieval-augmented generation. The substrate exposes
> SPARQL + per-triple provenance + ?commit= time-travel over plain HTTP;
> the recipes below show what to do with that from existing LLM
> ecosystems. We ship a guide instead of a Maven module by design.

## What you get from the substrate

| HTTP surface | Why it matters for Graph RAG |
|---|---|
| `POST /sparql` (SPARQL 1.1 query) | The LLM emits SPARQL → results come back as JSON. |
| `POST /sparql/update` (SPARQL 1.1 update) | Write new facts the model extracted; gets a commit hash + Memento-Datetime header for audit. |
| `GET /sparql/provenance?s=…&p=…&o=…` | **Citation source per triple.** Returns the commit that introduced this fact + datetime + message. Closes the "where did the model get this?" loop. |
| `POST /sparql?commit=<hex>` | Time-travel: query the data state as it was at any prior commit. Essential when reviewing an answer the model gave months ago. |
| `POST /sparql?branch=<name>` | Query a branch other than `main` — useful for sandboxed extraction (write into a branch, validate, merge). |
| `X-Prolly-Commit-Id` response header | Each query response carries the commit-id it ran against. Cite this in the model's output. |

Everything else (staging, event log, branches) is orthogonal to RAG — pick
it up only if you need the corresponding feature.

## What the model needs to know

A SPARQL-emitting LLM benefits from three prompt-time inputs:

1. **The schema (or a sample of it).** `SELECT DISTINCT ?p { ?s ?p ?o }
   LIMIT 100` is a 3-line predicate dump that gives the model enough
   structure to generate sensible queries.
2. **A handful of example query→answer pairs** for the common patterns
   in your domain. Few-shot beats schema-only by a wide margin.
3. **A "retry on parse error" loop.** SPARQL parsers reject ambiguous
   prefixes; the model corrects with one feedback round about 80% of
   the time.

The recipes below all assume those three.

---

## Recipe 1 — LangChain (Python)

LangChain ships a generic SPARQL adapter that works against any
endpoint advertising the W3C SPARQL Protocol — prolly-rdf4j included.

```python
# pip install langchain langchain-anthropic SPARQLWrapper
from langchain_anthropic import ChatAnthropic
from langchain_community.graphs import RdfGraph
from langchain.chains import GraphSparqlQAChain

graph = RdfGraph(
    source_file=None,
    serialization=None,
    query_endpoint="http://localhost:8080/sparql",
    update_endpoint="http://localhost:8080/sparql/update",
    standard="rdf",                  # SPARQL 1.1 / RDF (not RDFS/OWL)
)
graph.load_schema()                  # SELECT DISTINCT ?type ?p — auto-feeds the prompt

llm = ChatAnthropic(model="claude-opus-4-7", temperature=0)
chain = GraphSparqlQAChain.from_llm(llm=llm, graph=graph, verbose=True)

answer = chain.invoke({"query":
    "Which papers cite Smith 2024 and were published after January?"})

# Cite back to the source triples
# (LangChain's SparqlQAChain doesn't capture them automatically — do it manually)
import requests
for triple in answer.get("source_triples", []):
    prov = requests.get("http://localhost:8080/sparql/provenance", params={
        "s": triple["s"], "p": triple["p"], "o": triple["o"],
    }).json()
    print(f"  cited triple first seen at commit {prov['firstSeenAt']} ({prov['firstSeenAtDatetime']})")
```

What this wires up:

- `RdfGraph` emits SPARQL against `/sparql` using the LangChain prompt
  templates. The model sees the schema dump and the user's question;
  emits SPARQL; LangChain executes; results round-trip to the model
  for narrative answer construction.
- The provenance loop is hand-rolled — LangChain doesn't surface
  triple-level sourcing yet. Worth it for the citation story.

**Pitfalls:**
- LangChain's `RdfGraph` uses SPARQLWrapper under the hood, which
  sometimes prefers GET over POST and breaks on long queries. If
  you hit `URI too long`, monkeypatch the wrapper or use the raw
  recipe below.
- The default LangChain prompt assumes a `rdfs:Class` schema. If
  your store is FOAF-style (predicates, not classes), pass
  `standard="rdf"` (as shown) and seed the prompt with example
  queries.

---

## Recipe 2 — LlamaIndex (Python)

LlamaIndex's `KnowledgeGraphIndex` accepts a SPARQL backend via the
`SimpleGraphStore` extension or a custom `GraphStore` class. The
quickest path uses the raw query interface:

```python
# pip install llama-index llama-index-llms-anthropic SPARQLWrapper
from llama_index.core import KnowledgeGraphIndex, Document
from llama_index.core.graph_stores import SimpleGraphStore
from llama_index.llms.anthropic import Anthropic
from SPARQLWrapper import SPARQLWrapper, JSON

ENDPOINT = "http://localhost:8080/sparql"

class ProllyGraphStore(SimpleGraphStore):
    """Adapter — forwards LlamaIndex's graph operations to SPARQL."""
    def __init__(self, endpoint):
        super().__init__()
        self.sparql = SPARQLWrapper(endpoint)
        self.sparql.setReturnFormat(JSON)

    def get(self, subj):
        self.sparql.setQuery(f"SELECT ?p ?o WHERE {{ <{subj}> ?p ?o }} LIMIT 50")
        rows = self.sparql.query().convert()["results"]["bindings"]
        return [(r["p"]["value"], r["o"]["value"]) for r in rows]

    def upsert_triplet(self, subj, pred, obj):
        # Route through /sparql/update so it becomes a real commit.
        from urllib.parse import urlencode
        import requests
        requests.post(
            f"{ENDPOINT}/update?message=llama-extracted",
            headers={"Content-Type": "application/sparql-update"},
            data=f"INSERT DATA {{ <{subj}> <{pred}> <{obj}> }}")

llm = Anthropic(model="claude-opus-4-7")
graph_store = ProllyGraphStore(ENDPOINT)
index = KnowledgeGraphIndex.from_documents(
    [Document(text=open("paper.txt").read())],
    graph_store=graph_store,
    llm=llm,
    max_triplets_per_chunk=10,
    include_embeddings=False,        # pure graph, no vector sidecar
)

query_engine = index.as_query_engine(
    include_text=False,
    response_mode="tree_summarize",
    llm=llm,
)
print(query_engine.query("Summarize the paper's main claims and cite supporting triples"))
```

This pattern lets LlamaIndex's extraction pipeline write *real
commits* into prolly-rdf4j — each `upsert_triplet` becomes one
durable, hash-addressable mutation. Reviewing what the LLM extracted
last week is `?commit=<hash-from-last-week>` away.

---

## Recipe 3 — DSPy

DSPy's compositional style fits prolly-rdf4j well: a SPARQL-emitting
signature, a retry-on-parse-error module, and a citation step.

```python
# pip install dspy-ai SPARQLWrapper anthropic
import dspy
from SPARQLWrapper import SPARQLWrapper, JSON

class GenerateSparql(dspy.Signature):
    """Translate a natural-language question to a SPARQL SELECT query."""
    schema_sample: str = dspy.InputField(desc="A few predicates from the store")
    question:      str = dspy.InputField()
    sparql:        str = dspy.OutputField(desc="A valid SPARQL 1.1 SELECT")

class GraphRAG(dspy.Module):
    def __init__(self, endpoint):
        super().__init__()
        self.endpoint = endpoint
        self.generate = dspy.Predict(GenerateSparql)
        self.sparql = SPARQLWrapper(endpoint)
        self.sparql.setReturnFormat(JSON)

    def schema_sample(self):
        self.sparql.setQuery("SELECT DISTINCT ?p WHERE { ?s ?p ?o } LIMIT 50")
        rows = self.sparql.query().convert()["results"]["bindings"]
        return "\n".join(f"- {r['p']['value']}" for r in rows)

    def forward(self, question):
        for attempt in range(3):                       # retry-on-parse-error
            out = self.generate(schema_sample=self.schema_sample(), question=question)
            try:
                self.sparql.setQuery(out.sparql)
                results = self.sparql.query().convert()
                break
            except Exception as e:
                question = f"{question}\n\nThe last attempt failed with: {e}. Try again."
        else:
            return dspy.Prediction(answer="Could not produce a valid SPARQL query.")

        # Cite each bound row's first-seen commit.
        import requests
        citations = []
        for binding in results.get("results", {}).get("bindings", []):
            # Use any subject in the binding as the cited triple.
            s = next((b["value"] for b in binding.values() if b["type"] == "uri"), None)
            if s:
                prov = requests.get(f"{self.endpoint}/provenance",
                    params={"s": s, "p": "<...>", "o": "<...>"}).json()
                citations.append(prov.get("firstSeenAt", "unknown"))

        return dspy.Prediction(
            sparql=out.sparql,
            results=results,
            citations=list(set(citations)),
        )

dspy.configure(lm=dspy.LM("anthropic/claude-opus-4-7"))
rag = GraphRAG("http://localhost:8080/sparql")
print(rag(question="Which authors cited Hinton's 2023 paper?"))
```

The retry loop is the productivity unlock — DSPy compositional style
makes it trivial to add and trace.

---

## Recipe 4 — Raw Anthropic SDK (Python or TypeScript)

When you don't want a framework, Anthropic's `tool_use` is the
shortest path. The model gets one tool: `run_sparql(query)`. It can
call it multiple times.

```python
# pip install anthropic SPARQLWrapper
import anthropic
from SPARQLWrapper import SPARQLWrapper, JSON

ENDPOINT = "http://localhost:8080/sparql"
sparql = SPARQLWrapper(ENDPOINT)
sparql.setReturnFormat(JSON)

def run_sparql(query: str) -> str:
    sparql.setQuery(query)
    return str(sparql.query().convert())

client = anthropic.Anthropic()

def graph_rag(question: str, time_travel_commit: str | None = None):
    base_url = ENDPOINT if not time_travel_commit else f"{ENDPOINT}?commit={time_travel_commit}"
    sparql.endpoint = base_url

    messages = [{"role": "user", "content": question}]
    for _ in range(5):                       # max 5 tool-use rounds
        resp = client.messages.create(
            model="claude-opus-4-7",
            max_tokens=2048,
            system=(
                "You answer questions over an RDF knowledge graph by emitting SPARQL. "
                "Schema: " + run_sparql("SELECT DISTINCT ?p { ?s ?p ?o } LIMIT 30") +
                "\nUse the run_sparql tool. End your answer with citations: "
                "for each triple you used, call run_sparql for its provenance."
            ),
            tools=[{
                "name": "run_sparql",
                "description": "Execute a SPARQL 1.1 query against the store.",
                "input_schema": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            }],
            messages=messages,
        )
        if resp.stop_reason == "end_turn":
            return resp.content[0].text
        # Otherwise handle tool calls...
        for block in resp.content:
            if block.type == "tool_use":
                result = run_sparql(block.input["query"])
                messages.append({"role": "assistant", "content": resp.content})
                messages.append({"role": "user", "content": [{
                    "type": "tool_result", "tool_use_id": block.id, "content": result,
                }]})
                break
    return "Reached tool-use cap; no final answer."

# Time-travel example: ask the same question against today's data vs last quarter's
today = graph_rag("Who are the top 5 most-cited authors?")
old   = graph_rag("Who are the top 5 most-cited authors?",
                  time_travel_commit="abc123…")  # last quarter's main commit
```

Prompt-caching tip: the schema dump and tool definition are stable across
questions. Mark them as cache breakpoints (the Anthropic SDK supports
`cache_control: {"type": "ephemeral"}` on prompt blocks) to save tokens
between turns.

The same shape works in TypeScript via `@anthropic-ai/sdk` — see the
optional `prolly-rdf4j-ts` SDK once it ships (task #141).

---

## End-to-end: citation traces in action

Once one of the above is running, a complete Graph-RAG response looks
like:

```text
Q: Who authored "Attention Is All You Need"?

  → emitted SPARQL:
    SELECT ?author WHERE {
      <https://doi.org/10.48550/arXiv.1706.03762> dcterms:creator ?author .
    }
  → results: Vaswani, Shazeer, Parmar, ... (8 rows)
  → citations:
    - Vaswani  : first seen at commit a3f1c… on 2024-03-12 (msg: "initial NeurIPS-2023 dataset import")
    - Shazeer  : first seen at commit a3f1c… on 2024-03-12 (same commit)
    - ...
A: The paper has 8 authors: Vaswani, Shazeer, Parmar, Uszkoreit, Jones,
   Gomez, Kaiser, Polosukhin (all introduced into the store in the
   2024-03-12 import).
```

The citation block is what differentiates this from vector RAG. Every
fact has a hash-addressed origin, and the same query against
`?commit=<old>` would tell you what the model would have said three
months ago.

---

## What's deliberately not in this guide

- **Construction (text → graph extraction).** Use Microsoft GraphRAG,
  LlamaIndex's extraction (Recipe 2), or hand-rolled prompt templates.
  We're the storage backend; construction is somebody else's problem.
- **Embeddings / vector retrieval.** Hybrid RAG is real, but the
  vector half lives in Pinecone/Weaviate/pgvector. Glue both into a
  fan-out retriever yourself.
- **Multi-tenant isolation.** Each tenant should land on its own
  prolly-rdf4j instance OR a per-tenant branch with strict
  `?branch=` enforcement at the LLM proxy. The substrate doesn't
  do auth.

## End-to-end runnable demo

Want to see all of this stitched together against a real (small)
dataset? See [`examples/graph-rag-demo/`](../examples/graph-rag-demo/).
The demo seeds ~30 triples about Transformers-era papers, asks 5
multi-hop questions through Anthropic's tool-use API, prints the
citation trace for each answer, and demonstrates time-travel by
retracting a fact then re-asking against the pre-retraction commit.

```bash
# from the repo root
make build-ui run-mem
cd prolly-rdf4j/examples/graph-rag-demo
export ANTHROPIC_API_KEY=…
python demo.py
```

Runtime ~30 seconds. The notebook also opens cleanly in VSCode /
Jupyter (cells are delimited by `# %%`).

## What's coming
- **TypeScript SDK** (task #141) — for browser-side LLM apps that
  prefer typed bindings over raw HTTP.
- **Native LangChain / LlamaIndex connectors.** Tracked under the
  Graph-RAG market analysis as "what would change the build-vs-doc
  recommendation." Not built yet.
