
# ADR-0065: Triejoin routing default on

## Status

Accepted, 2026-06-21. Guides `prolly-rdf4j/plans/triejoin-default-on.md`.
Reverses D-6/Step 12 of `triejoin-evaluation-wiring.md`
(which deferred default-on); builds on [ADR-0035](0035-worst-case-optimal-join-for-bgps.md) (the
worst-case-optimal join) and [ADR-0036](0036-unify-rdf-encoding-on-term-codec.md).

## Context

ADR-0035 chose a worst-case-optimal leapfrog triejoin for cyclic basic graph patterns;
`triejoin-evaluation-wiring.md` shipped it behind the `prolly.rdf4j.triejoin-enabled` flag with
**conservative, cyclic-only routing** (a `Join` subtree routes only when its variable hypergraph is
cyclic — acyclic / star / single-pattern stay on RDF4J's bind-join, which wins on those) and a
**correctness lock**: the flag-on result set is identical to flag-off, pinned by the W3C SPARQL 1.1
query suite run flag-on (171/0, identical to the flag-off baseline) plus a randomized Sail-level
agreement property.

**Why this was deferred.** D-6/Step 12 of the wiring plan kept the default **off** for a *measured* reason,
not a correctness one: the end-to-end SPARQL win on the cyclic triangle was only ~1.12× (small synthetic
graph, single fork). A CPU profile attributed the dilution to the SPARQL layer's **per-result-cell
decode + `BindingSet` construction**, not the join algorithm. D-6/Step 12 deferred default-on pending two
things: **(a)** a *measured-robust* end-to-end win, and **(b)** trimming that per-cell cost.

**What changed (2026-06-21).** Both conditions are now satisfied:

- **(b)** A streaming-cursor rewrite of the join's row emission (an index-aligned `BindingCursor` plus a
  per-query term-resolver cache) cut the per-cell decode/`BindingSet` overhead D-6/Step 12's profile named.
- **(a)** Re-measured multi-fork (`-f3`), scored with fork-aware significance (Welch's t): the cyclic
  triangle is **2.56× faster at 380 edges and 2.81× at 2000** (both *significant* — `t = −91.6` / `−59.3`,
  95% CI excludes 0), while the acyclic 2-hop control is **within noise** (no regression). The bind-join
  baseline is unchanged from D-6/Step 12 (~39 ms); the streaming work halved the routed path (~33.7 → ~15.4 ms),
  turning D-6/Step 12's 1.12× into a significant 2.56× *at the same small size*. On real power-law data the win
  is categorical (wiki-Vote: 3.2 s routed vs 36.8 s bind-join = 11.5×).
- The multi-tenant per-repo path now honors the flag (it previously did not), so the decision can
  actually take effect on the production surface.

The decision is whether to keep paying the opt-in friction (operators must discover and set a flag to
get a safe, measured win) or make the routing the default.

## Options

| Option | operator default | cyclic-query perf | acyclic-query perf | reversible? |
|---|---|---|---|---|
| **A** — keep default-off (opt-in) | bind-join unless opted in | win left unrealized for non-opters | unaffected | n/a |
| **B** — default-on, cyclic-only routing, keep kill-switch *(chosen)* | triejoin for cyclic, bind-join for acyclic | **2.56–2.81× faster (measured, significant)** | unaffected (never routed) | yes — `=false` |
| **C** — default-on, route *all* patterns (drop the cyclic gate) | triejoin everywhere | faster on cyclic | **regresses 1.6–1.85×** (triejoin loses on acyclic) | yes — `=false` |
| **D** — make it unconditional (remove the flag) | triejoin for cyclic, no escape | faster | unaffected | **no escape hatch** |

## Decision

**Option B — default-on with the existing cyclic-only routing and the `=false` kill-switch retained.**
The deciding tradeoff: the win is *measured, significant, and safe* — 2.56–2.81× on cyclic patterns with
**no acyclic regression**, because the cyclic-only gate means acyclic queries never take the triejoin
route. Correctness is locked (flag-on ≡ flag-off results), so keeping the default off (Option A) buys no
safety it doesn't already have; it only leaves the win unrealized for everyone who never finds the flag.
Option C is rejected outright (it reintroduces the acyclic regression the cyclic gate was built to
avoid). Option D is rejected to preserve an escape hatch (a default change must not be one-way).

Sub-decisions:

- **D-1 — Flip the *property* default, not the embedder field.** `ProllySailProperties.triejoinEnabled`
  defaults `true` (the server default); `ProllySail`'s own field default stays `false`. A bare
  `new ProllySail(...)` (embedded / programmatic use, which has no Spring configuration) therefore stays
  conservative — the decision's scope is the *server* default, and flipping the field too would silently
  change behavior at every constructor call site.
- **D-2 — Routing stays cyclic-only.** "On" means *available + selectively routed*, not "every pattern
  through the triejoin." The cyclic gate is the safety property; it is unchanged.
- **D-3 — Keep the kill-switch, honored everywhere.** `prolly.rdf4j.triejoin-enabled=false` restores the
  byte-identical bind-join path on both the single-tenant Sail and per-repo multi-tenant Sails.

## Consequences

- **Positive.** Cyclic SPARQL is 2.56–2.81× faster by default (categorical on real power-law graphs);
  operators get the win without having to discover a flag.
- **Neutral.** Acyclic queries are unaffected (cyclic-only gate); the only added cost is a per-query
  routing/eligibility check (negligible). The bind-join is still the engine for everything the triejoin
  does not win.
- **Cost / risk.** The triejoin path is now the default for cyclic patterns — more exercised in
  production than as an opt-in. This is bounded by the correctness lock (W3C flag-on + the agreement
  property pin ON ≡ OFF results) and the kill-switch. An operator with a pathological cyclic workload
  sets `=false`.
- **Test-impact (validated).** Flipping the property default ran the `prolly-rdf4j-rest` suite (the
  property-consuming module) with **zero flip-caused failures** (1099 tests; the one error is a
  pre-existing, unrelated store-format-migration test — see
  `bugs/versioned-tree-migration-test-unversioned-fixture.md`).
- **Reversal.** This overturns D-6/Step 12 of `triejoin-evaluation-wiring.md`, which is retracted in place there.

## Follow-up / future work

- **Cost-based variable ordering** (`cost-based-variable-ordering.md`) —
  the routed path still chooses a *naive* first-appearance variable order; a cost-based order would
  improve the routed-query constant further (and is its own evidence-gated plan).
- **Full-suite + both-flags W3C confirmation** under the new default is CI's final gate (the local
  test-impact pass covered the property-consuming module).
