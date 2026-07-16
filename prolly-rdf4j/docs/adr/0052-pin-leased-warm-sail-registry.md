
# ADR-0052: Pin-leased warm-sail registry

## Status

Accepted, 2026-06-09. Guides `plans/warm-sail-registry-wiring.md`.
Refines [ADR-0048](0048-transport-agnostic-repo-hosting-core.md): the `RepoHost.resolve` contract changes
from a raw bundle to a *lease* the caller releases. The registry core it decides is already built
(`RepoSailRegistry`, commit `a749c8c1`); the wiring is the guiding plan.

## Context

A multi-tenant server keeps a *warm set* of open per-repo sail bundles (a `PerRepoSail`: a Sail plus the
RocksDB handle backing it). That set must be **bounded** — memory cannot grow without limit as the number
of repos grows — and the bound is enforced by least-recently-used eviction (close the coldest bundle to
make room).

Today there is no safe bound. The RDF face holds **two** structures: a bounded `LruRepoRegistry<ProllySail>`
whose factory closure reads from an **unbounded** `PerRepoSailRegistry` map (`openSails`) of the actual
bundles. The effect is unbounded warm-sail memory, and a latent hazard if the LRU ever did fire: it would
`shutDown()` a `ProllySail` that the `openSails` bundle still references (a use-after-close), because the
two structures own overlapping lifecycle.

The thing that makes safe eviction hard is a hard constraint: **a bundle holds an *exclusive* RocksDB
lock**, so two open bundles for one repo cannot coexist. So an evicted bundle cannot be eagerly re-opened
while an old reference to it is still in use (the re-open would hit a lock conflict), and it cannot be
closed while still in use (use-after-close). Either way, **safe eviction requires knowing when a bundle is
no longer in use** — and across concurrent callers there is no way to know that without the caller
*signalling* it. This decides how the warm set is bounded safely.

## Options

| Option | bounded? | safe under eviction? | caller cost | concurrency cost |
|---|---|---|---|---|
| **A** — unbounded (status quo) | **no** | n/a (it never evicts) | none | none |
| **B** — bounded LRU, close-on-evict, no pin | yes | **unsafe** — closes/​re-opens an in-use bundle (use-after-close, or RocksDB lock conflict) | none | none |
| **C** — bounded LRU + **refcount pin-lease** | yes | **safe** — eviction skips pinned bundles; delete defers close | every resolve must release a lease | a lock on resolve/release (cheap) |
| **D** — one global lock held across resolve *and* use | yes | safe | none | **serializes all repo access** |

A sub-decision within C — the **lease API shape**: a *handle* (`PinnedSail implements AutoCloseable`, released by `close()`) versus a *callback* (`withSail(id, fn)` that pins, runs, releases). The callback is leak-proof (release is automatic) but cannot express an **async** holder — the gRPC `BulkLoad` resolves at stream start and holds the bundle across many asynchronous `onNext` callbacks, which no single synchronous callback wraps.

## Decision

**Option C — a refcount pin-lease registry.** Bounded eviction is required (A is unbounded); B is a
use-after-close / lock-conflict waiting to happen; D trades correctness for throughput by serializing every
repo access. C is the only option that is both bounded and safe, and its cost — a release per resolve — is
the irreducible price of "knowing when a bundle is unused."

- **D-1. A dedicated `RepoSailRegistry`, not a generification of the platform `LruRepoRegistry<R>`.** The
  JSON/BOM faces keep the simple resolve-returns-`R` LRU; only the RDF face's bundle registry takes pins.
  This bounds the blast radius to the one face with the RocksDB-exclusive-lock + in-use-close hazard, rather
  than pushing a lease contract onto every face.
- **D-2. `resolve` returns a `PinnedSail` lease; eviction skips pinned bundles.** Each resolve increments a
  per-bundle pin count; the lease's `close()` decrements it. The least-recently-used **unpinned** bundle is
  evicted to honor the cap; a pinned bundle is never evicted, so the warm set is a **soft cap** — it may
  exceed capacity while many bundles are concurrently pinned, and shrinks back as leases release. A delete
  of a still-pinned repo removes it from resolution immediately but **defers the bundle's close** until the
  last lease releases (a `doomed` tombstone), so a delete never closes a bundle out from under an in-flight
  reader either.
- **D-3. The invariant: a bundle's `close()` happens only at pin-count 0, never between a resolve's pin and
  its release.** This is the linearizability property, and it is the thing proven (a concurrency test under
  eviction pressure + 12 deterministic cases, both verified to fail when the pin-before-evict ordering is
  broken — the test that caught a real use-after-close during development).
- **D-4. A handle API (`PinnedSail` `AutoCloseable`), with an `unmanaged` variant.** The handle (not a
  callback) is chosen because the async `BulkLoad` holder needs a lease it can release from a different
  callback than the one that acquired it. Leak-safety is recovered by *convention* — sync callers use
  try-with-resources; a no-leaked-pins test guards it. `PinnedSail.unmanaged(bundle)` is a no-op-release
  lease for the single-tenant `SingleRepoHost` and the in-process tests (no registry, nothing to evict), and
  it doubles as the **migration bridge**: the wiring flips the `resolve`→`PinnedSail` contract with
  unmanaged leases first (behavior-preserving), then swaps in real pins.
- **D-5. The `RepoHost.resolve` contract changes from `PerRepoSail` to `PinnedSail` (refines ADR-0048).**
  Every resolve site — the gRPC verbs, the REST per-request resolver — now holds a lease for the duration of
  its use and releases it. A leaked lease pins its bundle forever (it can never be evicted), so a lease is
  treated like a lock.

## Consequences

- **Positive.** The warm set is bounded by `warm-set-size` with *provably* no evict-in-use (D-3); the two
  overlapping structures collapse into one source of truth (`openSails` is deleted); and "is this bundle in
  use?" is explicit rather than guessed.
- **Negative / cost.** Every resolve site takes on release responsibility — a forgotten release is a slow
  leak (the bundle pins forever, the warm set can't reclaim it). The contract change is **wide** (the gRPC
  service, the REST resolver + its controllers, the host, the autoconfig, ~10 tests), so it is its own
  phased plan, not an incidental edit. The cap is **soft** — under heavy concurrent pinning the warm set can
  exceed `warm-set-size` until leases release; this is correct (you cannot close an in-use bundle) and
  self-correcting, but an operator watching the gauge should expect transient overshoot.
- **Neutral / punted.** A leak-proof `withSail(id, fn)` convenience wrapper over the handle is deferred —
  add it if no-leak discipline proves hard in practice. The re-open factory is **PROLLY-only** until the
  FLAT factory lands (0.2d); a FLAT repo is not yet hostable through the registry.

## Follow-up / future work

- The wiring plan (`plans/warm-sail-registry-wiring.md`) —
  three module-scoped phases (contract / real-pinning / cleanup+benchmark), each green at its gate.
- The `warm-set-size` thrash benchmark (the cold-scan open/close churn cost) lands with the wiring.
- A `withSail` convenience wrapper, only if leaks recur.

## Open questions

- None at write time.
