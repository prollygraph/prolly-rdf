
# ADR-0059: Extract prolly-port-core to its own repository

## Status

Accepted, 2026-06-11. **The decision to extract is made** (Option B); the **release/consumption model**
(D-2) is recommended with one open question (Q1: published artifact vs git submodule). Execution is a
follow-on plan (`plans/extract-prolly-port-core.md`, to be drafted). Not prolly-json — the build hold does
not apply.

## Context

`prolly-port-core` is the **faithful Java port of Dolt's Go prolly tree** (`com.dolthub.prolly`) — the
content-addressed chunk store, the probabilistic-boundary tree, the cursor / diff / merge engine. It is the
*ported* foundation; everything else in the repo (`prolly-rdf`, `prolly-json`, the RDF4J Sail, the REST/UI
faces) is built on top of it. The ported code is separated from the rest for organizational clarity and a
clean **licensing / provenance boundary**: the Apache-2.0 Dolt-derived port is the naturally-shareable
foundation, and giving it its own repository lets it be released, versioned, and attributed on its own
terms rather than inheriting the surrounding codebase's release cadence.

### The boundary is already clean (measured, not assumed)

A dependency-graph dig confirmed `prolly-port-core` is a textbook extractable leaf:

- **No internal module dependencies** — its only `prolly-*` reference is the parent pom; it depends only on
  external libraries (RocksDB, Flatbuffers) via the BOM.
- **Its main code imports zero `com.earasoft`** — the port never reaches *outward* into the novel layer.
- **`com.dolthub.prolly` is 100% contained** in the module (all ~35 files; no leakage into other modules'
  main code).
- **Reverse-deps are few + normal**: `prolly-codec`, `prolly-concurrency`, `prolly-platform`, `prolly-rdf`
  depend on it directly; the rest transitively. The dependency direction is uniformly *toward* the foundation.
- **An external-consumer integration test already exists** (`prolly-dependencies/src/it/external-consumer`) —
  external consumption of the published artifacts is already validated.
- **License**: Apache-2.0 (the file headers) — publishable.

### Two preconditions the owner raised — both already met

- **Test suite (the contract that travels with a published artifact):** `plans/core-engine-test-strategy.md`
  is **`complete` (32/32)** — the mutation-testing gate, jcstress concurrency proofs, and the differential
  oracle are in place. The precondition is satisfied today; the extraction *declares* the dependency and
  verifies the gates are green, but does not wait. (The one `deferred` test strategy is prolly-json, which
  *consumes* core and is irrelevant to core's contract.)
- **Panama / the Foreign Function & Memory API:** core was written for Java 21 (where the API was preview) but
  its usage is **already on the finalized (Java 22+) form** — `MemorySegment` / `ValueLayout` / `Arena`, with
  **no** leftover preview `MemorySession` / `SegmentScope`. The Java 25 bump already did the preview→final
  modernization, so there is no memory-API debt blocking extraction; Java 25 also gives free performance there.
  Any further modernization is a small *optional* audit, best done after extraction against the clean repo with
  the complete test suite as the net (D-4).

## Options

| Option | Boundary | Build | Cost | Reversibility |
|---|---|---|---|---|
| **A — directory move (same repo)** | organizational only | one reactor (unchanged) | ~zero | trivial |
| **B — own repo, published artifact (chosen)** | hard version + license boundary | core releases independently; the rest pins a version | release/versioning workflow; loses monorepo co-evolution | moderate |
| C — status quo | none | one reactor | none | n/a |

**Option A — directory move.** `git mv prolly-port-core <dir>/` + a `<module>` path update. Pure structure;
no version or release boundary; the substrate cannot be consumed or versioned independently. (A cheap stepping
stone, not the goal.)

**Option B — own repository, published artifact.** `prolly-port-core` moves to its own git repo, releases on
its own version line, and is published as `com.earasoft:prolly-port-core:X.Y.Z`; the monorepo consumes a
**pinned version** instead of a reactor module. The version becomes the boundary — aligning with the
"network/version is the boundary" conviction.

## Decision

**Adopt Option B: extract `prolly-port-core` into its own repository, published + versioned independently, and
consumed by the rest of the codebase as a pinned Maven artifact.** The boundary is already clean enough that
the risk is in the *release workflow*, not the code.

- **D-1 — Gate extraction on the (already-complete) core test suite.** The published artifact's safety net is
  its own tests, so extraction *declares a dependency on* `core-engine-test-strategy` (complete) and verifies
  the mutation + concurrency + coverage gates are green at the cut. Satisfied today — a checkpoint, not a wait.
- **D-2 — Publish as a versioned artifact; the monorepo pins a version (recommended) — see Q1.** Core gets its
  own semantic version, tags, and release CI; a core change is *publish-then-bump-the-pin*. The deciding
  tradeoff: this is the whole point (independent versioning + a hard license/provenance boundary), paid for
  by losing the monorepo's "change core and its consumers in one commit, one build" convenience. During active
  co-development, `-SNAPSHOT` publishing keeps the loop tight; stable consumers pin releases.
- **D-3 — License + provenance boundary is explicit.** The extracted repo is Apache-2.0 (Dolt-derived),
  carries the NOTICE/attribution, and is the designated published artifact that the other repositories
  consume. This makes the release boundary structural — a repository boundary rather than a per-file
  judgment call.
- **D-4 — Foreign-Function-&-Memory-API modernization: essentially nothing to do for core (verified
  2026-06-11).** Core is already on the finalized API **and** already uses the optimal *zero-allocation*
  idiom — the hot read paths hoist `ValueLayout` to `static final` constants so `MemorySegment.get` is
  intrinsified by the just-in-time compiler (the per-call `VarHandle` + boxing allocation, once *"the #1
  descent allocator in the triejoin profile"*, is already fixed in `Tuple` / `TypeCodec` / `TupleBuilder`, per
  `prolly-rdf/plans/triejoin-performance.md` Phase 3). Core is also deliberately heap-backed (`HeapBufferPool`
  avoids `Arena`); the only off-heap `Arena.ofShared()` lives in `prolly-rdf`'s `DirectBufferPool` (the novel
  layer, not core). So there is **no core modernization pass worth doing** — Java 25 gives free
  intrinsification perf, and a `byteArrayViewVarHandle` micro-rewrite would lose the codec's zero-copy slicing
  for a likely-wash gain (their profiling fixed the VarHandle alloc, not the segment wrapper — so the wrapper
  was not the bottleneck). The one small adjacent item — the `Arena.ofShared()` lifetime in `prolly-rdf` — is
  separate from this extraction. Not a gate, and not a recommended follow-on either.
- **D-5 — Reuse the existing external-consumer harness as the acceptance proof.** The
  `prolly-dependencies/src/it/external-consumer` integration test already exercises core as an outside
  consumer would; the extraction is "done" when the monorepo green-builds against the *published* artifact and
  that consumer test passes against it.

## Consequences

**Positive.**
- A real release boundary: the shareable Apache-2.0 substrate is its own repository with its own history and
  version line. Publication becomes structural, not a per-file judgment call.
- Independent versioning + release of the substrate (it can be consumed by other projects, or a future server,
  on its own cadence).
- The clean dependency boundary + the existing external-consumer test make the mechanical cut low-risk.

**Negative / costs.**
- **Loss of monorepo co-evolution:** a change spanning core + a consumer is now two repos, two commits, a
  publish, and a pin-bump — slower than the single-reactor edit. Mitigated by `-SNAPSHOT` during active work.
- **Release machinery to stand up:** core's own CI, versioning, signing/publishing (Maven Central or a private
  registry), and a NOTICE/attribution file.
- **Pin drift:** the monorepo can lag core; needs a deliberate bump discipline (and the BOM updated).

**Neutral.**
- Option A (directory move) remains available as a zero-cost intermediate if the release workflow is not yet
  wanted — it buys the organizational separation without the boundary.

## Follow-up / future work

- The execution plan `plans/extract-prolly-port-core.md`: new repo scaffolding, CI + publish, NOTICE/license,
  repoint the monorepo to the pinned artifact (+ the BOM), and the external-consumer test as the acceptance
  gate.
- An optional `ffm-java25-modernization` pass against the extracted repo (D-4).

## Open questions

- **Q1 — published artifact (D-2) vs git submodule?** A *published artifact* is the true version/boundary
  (recommended for the release-boundary goal). A *git submodule* (separate repo, but source-built in the monorepo's
  reactor) is lighter — separate history without the publish/pin workflow — but keeps build coupling and gives
  no real version boundary. Recommendation: published artifact; submodule only if you want the repo split now
  but are not ready to run releases.
- **Q2 — where is it published?** Maven Central, a private registry, or GitHub Packages in the interim.
  Tied to release timing, not to this decision.
