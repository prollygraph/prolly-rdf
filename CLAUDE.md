# prolly-rdf — the RDF ring

Guidance for anyone — human or agent — working in this repository.
Contribution mechanics are in [CONTRIBUTING.md](CONTRIBUTING.md); this file is the
standard the work is held to.

## What this repository is

RDF and SPARQL on the prolly substrate: term codecs, the versioned RDF4J Sail, a flat Sail
for comparison, canonicalization, and the conformance suites. It depends on the engine ring.

## Ring-specific things to know

- **The Sail contract is someone else's specification.** Transaction lifecycle, isolation,
  and connection semantics are RDF4J's, and the conformance suites are unforgiving. When a
  suite disagrees with your intuition, the suite is usually right.
- **Four index permutations** (SPOC, POSC, OSPC, CSPO) so any triple pattern has a covering
  order. Any comparison against a two-index engine must normalise by index count.
- **Two Sails, one store.** The versioned Sail buys history and pays in write amplification;
  the flat one does not. Neither is "the" implementation.
- **Cyclic patterns route to the worst-case-optimal triejoin; acyclic ones deliberately do
  not** — the bind-join wins there. The advantage is asymptotic, not constant.
- **Demos are CI-locked.** The runnable examples under `examples/` are executed by tests, so
  a published narrative cannot rot. Add new ones the same way.
- **`RingDocsLinkTest` guards relative links** — but it only sees markdown links. A bare
  path in prose is invisible to it, which is how stale references survive.

## The build IS the quality gates

**`mvn test` passing is not the bar — `mvn verify` is.** Gates bind to lifecycle phases
that run *after* `test`, so a formatting, licensing, or dependency-convergence violation
sails straight through a green `mvn test`:

| Gate | Phase | Fix a failure |
|---|---|---|
| dependency convergence (enforcer) | `validate` | add a convergence pin to the root `dependencyManagement` |
| spotless (google-java-format AOSP) | `verify` | `mvn spotless:apply` |
| license headers | `verify` | `mvn com.mycila:license-maven-plugin:4.6:format` |

Trust the `BUILD` line and the artifacts, not a bare exit code.

## How this project writes and reasons

These conventions are the reason the code and the prose can be trusted without
re-deriving them. They are written as instructions to you, the contributor — human or
agent.

### Ground every claim, or mark it ungrounded — in the sentence

A factual claim names its evidence: a `file:line`, a measured number, a cited document.
A claim you cannot ground, you label as reasoning *where you make it*. "I think, but
haven't verified" is always acceptable; asserting it is not. Never let an inference wear
the clothes of a fact.

Four moves follow from that:

- **Never fabricate to fill a gap.** If the input is missing — the file isn't there, the
  number was never measured — say so and stop. A confident guess presented as fact is the
  worst failure available, because one invented detail costs the reader's trust in
  everything else.
- **No invented quantities.** A number is either verified by re-running or re-reading, or
  explicitly flagged as order-of-magnitude intuition with the reasoning shown. Prefer
  ratios to absolutes, and name the machine.
- **Scrutinise your own superlatives.** "Always", "every", "the fastest" — each absolute
  invites a counterexample. Defend it or narrow it before it ships.
- **Retract in place, visibly.** When a claim turns out wrong, correct it *and record that
  you did*. The retraction is the credible artifact, not an embarrassment to bury.

### Measure the real thing

Benchmarking does not substitute for understanding the system; you need the understanding
to design the benchmark. Before measuring, name three things — if you cannot, you do not
yet understand the system well enough to measure it:

1. **The variable** you are changing.
2. **The regime where it can act.** A cache policy only matters when the cache is smaller
   than the working set; a lock only under real concurrency. Measuring outside the regime
   measures "no effect" and is a false negative.
3. **The confounds** to isolate.

Two further rules, both learned expensively: **real data is not a real workload** (the
access *sequence* usually decides the result), and **the instrument must be cheaper and
cleaner than the system under test, or you measure the instrument.** Distrust a clean
result — ask what workload would flip it, then go test that.

### Deterministic work gets a script, not the model

If an operation must be repeatable, format-exact, or is run more than a couple of times,
encode it in a tested script and run that. A model asked to perform the same mechanical
edit twice produces subtly different output. The test: *if I did this twice, would I want
byte-identical results?* If yes, script it.

### Reuse hardened infrastructure — build only the novel value

For a solved, commodity problem — above all one that parses untrusted bytes — use the
mature library rather than hand-rolling. The deciding question is not "library or
hand-rolled" (this project's whole worth is hand-built) but: *is the hard part here
**hardening**, or **novelty**?* If hardening, reuse; if it is the thing nothing
off-the-shelf ships, build it.

### Search the record before you investigate

Before chasing a bug or writing a design, grep the repository's own documents — the
architecture decision records, the changelog, the docs tree. The thing you are about to
study may already be decided, documented, or fixed. Re-deriving a solved problem is the
most avoidable way to spend an afternoon.

### Pre-1.0: no backwards-compatibility code

New fields are required; readers do not accept old shapes. No defensive readers, no
deprecation shims, no auto-migration in a boot path. If a format change needs a migration,
it belongs in an operator-run one-shot tool. When in doubt, remove the old code cleanly.

### Spell out abbreviations

Write "property-based testing", not "PBT"; "garbage collection", not "GC". Exempt are
terms everyone decodes (HTTP, JSON, API, RDF, SPARQL) and literal identifiers, which keep
their real spelling. The rule exists because you cannot fluently discuss what you cannot
pronounce.

### Test the production primitive

A test that exercises a non-production implementation proves nothing about production.
Where a primitive is swappable, parameterise over both; before promoting a non-production
one to default, its own test must be green first.

## Where this came from

These conventions were distilled in the private monorepo this ring was extracted from,
where they were learned from the times the discipline actually mattered. They are
reproduced here because a ring is where the *public* artifacts live — which is exactly
where a confidently-wrong claim costs the most.
