"""Graph-RAG demo against prolly-rdf4j.

Runs end-to-end:
  1. Seeds a small FOAF-ish citation dataset
  2. Wires Anthropic's tool_use API to a run_sparql callback
  3. Asks 5 multi-hop questions with citation traces
  4. Demonstrates time-travel: re-asks a question against a prior commit

Pre-reqs:
  - prolly-rdf4j running on localhost:8080 with provenance enabled
  - ANTHROPIC_API_KEY environment variable set
  - pip install anthropic SPARQLWrapper requests
"""

# %% [markdown]
# # Graph-RAG on a versioned knowledge graph
#
# What's different from vector RAG:
# - Each answer is grounded in **structured retrieval** (SPARQL queries)
# - Each cited fact carries a **commit hash + datetime + message**
# - The same question can be **re-run at any historical state** via `?commit=`

# %% Setup
import os
import sys
import requests
from SPARQLWrapper import SPARQLWrapper, JSON, POST

ENDPOINT = os.environ.get("PROLLY_ENDPOINT", "http://localhost:8080/sparql")
ANTHROPIC_KEY = os.environ.get("ANTHROPIC_API_KEY")
if not ANTHROPIC_KEY:
    print("Set ANTHROPIC_API_KEY in your environment to run the LLM cells.", file=sys.stderr)

sparql = SPARQLWrapper(ENDPOINT)
sparql.setReturnFormat(JSON)
sparql.setMethod(POST)

def run_sparql(query: str, commit: str | None = None) -> dict:
    """Execute a SPARQL query; optionally pin to a historical commit."""
    target = ENDPOINT if not commit else f"{ENDPOINT}?commit={commit}"
    s = SPARQLWrapper(target)
    s.setReturnFormat(JSON)
    s.setMethod(POST)
    s.setQuery(query)
    return s.query().convert()

def run_update(sparql_update: str, message: str) -> dict:
    """Execute a SPARQL Update; returns the new commit's info."""
    r = requests.post(
        f"{ENDPOINT}/update",
        params={"message": message},
        headers={"Content-Type": "application/sparql-update"},
        data=sparql_update,
    )
    r.raise_for_status()
    return r.json()

# %% [markdown]
# ## 1. Seed the store
# A tiny FOAF-ish corpus: 4 papers, 9 authors, citation links between them.

# %% Seed
SEED = """
PREFIX ex: <http://example.org/>
PREFIX dcterms: <http://purl.org/dc/terms/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

INSERT DATA {
  # Papers
  ex:p-attention a ex:Paper ;
    dcterms:title "Attention Is All You Need" ;
    dcterms:created "2017" ;
    dcterms:creator ex:vaswani, ex:shazeer, ex:parmar, ex:uszkoreit,
                    ex:jones, ex:gomez, ex:kaiser, ex:polosukhin .

  ex:p-bert a ex:Paper ;
    dcterms:title "BERT: Pre-training of Deep Bidirectional Transformers" ;
    dcterms:created "2018" ;
    dcterms:creator ex:devlin, ex:chang, ex:lee, ex:toutanova ;
    dcterms:references ex:p-attention .

  ex:p-gpt3 a ex:Paper ;
    dcterms:title "Language Models are Few-Shot Learners" ;
    dcterms:created "2020" ;
    dcterms:creator ex:brown, ex:mann, ex:ryder ;
    dcterms:references ex:p-attention .

  ex:p-llama a ex:Paper ;
    dcterms:title "LLaMA: Open and Efficient Foundation Language Models" ;
    dcterms:created "2023" ;
    dcterms:creator ex:touvron, ex:lavril, ex:izacard ;
    dcterms:references ex:p-attention, ex:p-bert, ex:p-gpt3 .

  # Authors as FOAF Persons
  ex:vaswani a foaf:Person ; foaf:name "Ashish Vaswani" .
  ex:shazeer a foaf:Person ; foaf:name "Noam Shazeer" .
  ex:parmar a foaf:Person ; foaf:name "Niki Parmar" .
  ex:uszkoreit a foaf:Person ; foaf:name "Jakob Uszkoreit" .
  ex:jones a foaf:Person ; foaf:name "Llion Jones" .
  ex:gomez a foaf:Person ; foaf:name "Aidan Gomez" .
  ex:kaiser a foaf:Person ; foaf:name "Lukasz Kaiser" .
  ex:polosukhin a foaf:Person ; foaf:name "Illia Polosukhin" .
  ex:devlin a foaf:Person ; foaf:name "Jacob Devlin" .
  ex:chang a foaf:Person ; foaf:name "Ming-Wei Chang" .
  ex:lee a foaf:Person ; foaf:name "Kenton Lee" .
  ex:toutanova a foaf:Person ; foaf:name "Kristina Toutanova" .
  ex:brown a foaf:Person ; foaf:name "Tom Brown" .
  ex:mann a foaf:Person ; foaf:name "Benjamin Mann" .
  ex:ryder a foaf:Person ; foaf:name "Nick Ryder" .
  ex:touvron a foaf:Person ; foaf:name "Hugo Touvron" .
  ex:lavril a foaf:Person ; foaf:name "Thibaut Lavril" .
  ex:izacard a foaf:Person ; foaf:name "Gautier Izacard" .
}
"""
seed_resp = run_update(SEED, "baseline import — Transformers citation graph")
BASELINE_COMMIT = seed_resp.get("commitId")
print(f"[seed] committed; baseline = {BASELINE_COMMIT[:12]}…")

# %% [markdown]
# ## 2. Anthropic tool_use loop
# The model gets ONE tool: `run_sparql(query)`. It can call it multiple
# times to refine the answer.

# %% Tool-use loop
import anthropic
client = anthropic.Anthropic(api_key=ANTHROPIC_KEY) if ANTHROPIC_KEY else None

SYSTEM = """\
You answer questions over an RDF knowledge graph by emitting SPARQL.

The graph uses these namespaces:
  ex: <http://example.org/>
  dcterms: <http://purl.org/dc/terms/>
  foaf: <http://xmlns.com/foaf/0.1/>

Useful predicates: dcterms:creator, dcterms:title, dcterms:references,
foaf:name, rdf:type (ex:Paper, foaf:Person).

Use the run_sparql tool to retrieve. Construct the final answer from
its results. Emit valid SPARQL 1.1. After answering, summarize which
subjects/predicates you used so the user can verify the citation
trace.
"""

TOOLS = [{
    "name": "run_sparql",
    "description": "Execute a SPARQL 1.1 SELECT/ASK/CONSTRUCT query against the knowledge graph.",
    "input_schema": {
        "type": "object",
        "properties": {"query": {"type": "string"}},
        "required": ["query"],
    },
}]

def graph_rag(question: str, commit: str | None = None, max_rounds: int = 5):
    """Ask a question; return (answer_text, list_of_used_iris).

    Returns the SPARQL rounds used so we can map back to provenance.
    """
    if client is None:
        return "(no API key — skipping LLM call)", []
    messages = [{"role": "user", "content": question}]
    used_iris = set()
    rounds = 0
    while rounds < max_rounds:
        rounds += 1
        resp = client.messages.create(
            model="claude-opus-4-7",
            max_tokens=2048,
            system=SYSTEM,
            tools=TOOLS,
            messages=messages,
        )
        if resp.stop_reason == "end_turn":
            return _extract_text(resp), sorted(used_iris)
        for block in resp.content:
            if block.type == "tool_use":
                results = run_sparql(block.input["query"], commit=commit)
                _collect_iris(results, used_iris)
                messages.append({"role": "assistant", "content": resp.content})
                messages.append({"role": "user", "content": [{
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": str(results)[:8000],   # truncate for token budget
                }]})
                break
    return "(max rounds reached without a final answer)", sorted(used_iris)

def _extract_text(resp):
    for block in resp.content:
        if hasattr(block, "text"):
            return block.text
    return ""

def _collect_iris(results, sink):
    """Pull any URI-typed binding into the sink — IRIs we'd want provenance on."""
    for binding in results.get("results", {}).get("bindings", []):
        for v in binding.values():
            if v.get("type") == "uri":
                sink.add(v["value"])

# %% [markdown]
# ## 3. Citation trace
# For each IRI the model touched, fetch its first-seen commit via
# `/sparql/provenance`. This is the differentiator over vector RAG.

# %% Provenance lookup
def provenance_of(s: str, p: str, o: str) -> dict | None:
    r = requests.get(f"{ENDPOINT}/provenance", params={"s": s, "p": p, "o": o})
    if r.status_code != 200:
        return None
    body = r.json()
    return body if body.get("firstSeenAt") else None

def cite(iris: list[str]) -> list[str]:
    """Best-effort citation lookup — pulls predicates for each subject
    and prints provenance of the first matching triple."""
    out = []
    for s in iris:
        # Find any triple where s is the subject; cite that one's provenance.
        q = f"SELECT ?p ?o WHERE {{ <{s}> ?p ?o }} LIMIT 1"
        try:
            rows = run_sparql(q).get("results", {}).get("bindings", [])
        except Exception:
            continue
        if not rows:
            continue
        p_iri = rows[0]["p"]["value"]
        obj = rows[0]["o"]
        o_term = f"<{obj['value']}>" if obj["type"] == "uri" else f'"{obj["value"]}"'
        prov = provenance_of(s, p_iri, obj["value"] if obj["type"] == "uri" else o_term)
        if prov:
            commit_hex = (prov.get("firstSeenAt") or "")[:12]
            dt = prov.get("firstSeenAtDatetime", "")
            msg = prov.get("firstSeenAtMessage", "")
            out.append(f"  · {s} → first seen at {commit_hex}… ({dt}) [{msg}]")
    return out

# %% [markdown]
# ## 4. Ask the questions

# %% Questions
QUESTIONS = [
    "1. Who are the authors of the paper titled 'Attention Is All You Need'?",
    "2. Which papers cite 'Attention Is All You Need'?",
    "3. Which authors wrote a paper that references 'Attention Is All You Need'? Give their names, not URIs.",
    "4. List the papers and their author counts, sorted by most authors first.",
    "5. Which authors are listed as a creator of more than one paper?",
]

for q in QUESTIONS:
    print(f"\n{'=' * 70}\n{q}\n{'=' * 70}")
    answer, used_iris = graph_rag(q)
    print(f"\n{answer}\n")
    print(f"Citation trace ({len(used_iris)} subjects touched):")
    for line in cite(used_iris[:6])[:6]:   # cap output for readability
        print(line)

# %% [markdown]
# ## 5. Time-travel
#
# Make a change to the data (commit on main), then re-ask Q1 against the
# *baseline* commit (`?commit=<BASELINE_COMMIT>`). The model sees the
# pre-change state — exactly what someone reviewing an old answer would
# need.

# %% Time-travel
print(f"\n{'=' * 70}\n[time-travel] retract Vaswani as an author of Attention\n{'=' * 70}")
retract_resp = run_update(
    """PREFIX ex: <http://example.org/>
       PREFIX dcterms: <http://purl.org/dc/terms/>
       DELETE DATA { ex:p-attention dcterms:creator ex:vaswani }""",
    "retract Vaswani authorship (illustrative — not historically accurate!)",
)
RETRACTION_COMMIT = retract_resp.get("commitId")
print(f"  retraction commit = {RETRACTION_COMMIT[:12]}…")

print("\n--- Q1 at HEAD (post-retraction) ---")
ans_now, _ = graph_rag(QUESTIONS[0])
print(ans_now)

print(f"\n--- Q1 at baseline {BASELINE_COMMIT[:12]}… (pre-retraction) ---")
ans_then, _ = graph_rag(QUESTIONS[0], commit=BASELINE_COMMIT)
print(ans_then)

print(f"\n{'=' * 70}\nDone. The pre-retraction answer reflects the state BEFORE we deleted\nVaswani's authorship — this is the audit-defensibility story.\n{'=' * 70}")
