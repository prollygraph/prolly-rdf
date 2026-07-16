
# ADR-0063: Per-value arena in the value factory

## Status

Accepted, 2026-06-15. Fixes a production heap leak root-caused the same day via a
resource/memory soak (`test-support/soak-bench.sh` + `SoakLeakDriver`); the surviving-allocation
profile is reproduced by `test-support/soak-alloc-profile.sh` and the histogram series by
`test-support/soak-leak-localize.sh`.

## Context

A `ProllyValue` (the `ProllyIRI` / `ProllyBNode` / `ProllyLiteral` / `ProllyTriple` wrappers) carries
its pre-encoded term bytes in a `MemorySegment` it holds **directly** (no defensive copy), decoded
lazily. `ProllyValueFactory` produces them, and `ProllySail` holds **one** factory for its whole
lifetime (`getValueFactory()` returns the same instance). Every value created by the server — the
SPARQL parser, the HTTP write path, sync, result materialization — flows through that one factory.

The original factory held a **single `Arena.ofAuto()`** and encoded *every* value into it
(`createIRI(iri)` → `new ProllyIRI(TermCodec.encodeFullIri(iri, arena), ...)`). That is the bug.

**The leak.** A 1-hour resource/memory soak (production config: `ProllySail` over `RocksNodeStore`
+ `HeapBufferPool`, rollback-churn so persistent state is bounded) **out-of-memoried in ~9 minutes**
on the Java heap. Root cause, established by measurement rather than reasoning:

- `jmap -histo:live` (8 snapshots) — one class grew monotonically, everything else flat:
  `jdk.internal.foreign.SegmentFactories$1`, **21.8M live instances** at OOM (~1 GiB). `[B` (byte
  arrays) stayed flat ⇒ the leak is *native*-arena bookkeeping, not heap blocks.
- async-profiler `alloc --live` (surviving allocations) — the dominant stack, by 2×, is
  `ProllyValueFactory.createIRI → TermCodec.encodeFullIri → ArenaImpl.allocate`. **Not** the
  transaction/rollback path.
- `ProllyIRI` and the segment wrappers were flat (~4k live) — the *values* are collected normally.

`SegmentFactories$1` is the per-allocation **cleanup action** that `Arena.allocate` registers in the
arena's session resource list. An **automatic arena retains that list for its entire lifetime**; you
cannot close it early. With one process-lifetime arena for all value encoding, **every value created
permanently leaks ~48 bytes of bookkeeping** — independent of whether the value is distinct, written,
or rolled back. (Bounding the workload's vocabulary to 200k terms did *not* slow the climb: the leak
is per-`createIRI`-**call**, not per-distinct-value.) `HeapBufferPool` is the production default and
the factory is process-lifetime, so any long-running server leaks until OOM — a production blocker.

**Constraint that shapes the fix.** The 3-argument constructor `(prefixes, hashFn, arena)` is a
deliberate **use-after-free test seam**: `ProllyValueUseAfterFreeTest` injects an `Arena.ofConfined()`
so that closing it invalidates *every* value built from it (the H1 hazard). The fix must keep that
shared-injected-arena behavior while changing only the default.

## Options

| Option | Leak fixed? | Per-value runtime cost | `prolly-codec` API churn | Alignment risk | Keeps UAF seam |
|---|---|---|---|---|---|
| **A — per-value `Arena.ofAuto()`** (default path) | **yes** | +1 session + Cleaner per value (freed with the value) | **none** | **none** (native, as before) | **yes** |
| **B — heap-backed value bytes** (`MemorySegment.ofArray`) | yes | cheapest — a `byte[]` GC'd with the value, no native malloc/Cleaner | **wide** — `encode*` take `Arena`; would widen ~20 overloads to `SegmentAllocator` | numeric encoders may write `JAVA_LONG` at unaligned offsets (heap `byte[]` = alignment 1) | yes |
| **C — bounded interning cache** of encoded values | **partial** — only dedups; every cache miss still leaks per-call into the shared arena | low | none | none | complicates |
| **D — lazy encode** (defer until write) | yes for query-only values | low | large | n/a | changes the `ProllyValue` contract (no longer eagerly holds bytes) |

## Decision

**D-1 — The default path allocates each value its own `Arena.ofAuto()` (Option A).** A private
`arenaForValue()` returns a fresh `Arena.ofAuto()` per call. Each value's segment references its own
session, so the value's single cleanup action is freed when the *value* is garbage-collected. This
bounds live cleanup-action bookkeeping to the **live-value working set** instead of total-ever-created.

The deciding tradeoff: Option A is the only choice that fixes a production-blocking leak with **zero
codec API churn and zero alignment risk** while preserving native-segment semantics and the
use-after-free test seam — a contained, low-risk fix. Option B is *cheaper at runtime* and arguably
the "more correct" end state (small value bytes need not be native at all), but it widens ~20 encode
overloads across the shared `prolly-codec` module and carries a real alignment question on the numeric
encoders; it is the measured follow-up, not the emergency fix.

**D-2 — Injected-arena mode (3-arg constructor) keeps the shared-arena behavior on purpose.**
`arenaForValue()` returns the injected arena unchanged when non-null; the use-after-free suite needs
every value in one closeable arena. The field is renamed `injectedArena` (nullable) to make the two
modes explicit.

**Verified.** The bounded-vocabulary rollback-churn reproducer that OOM'd at ~175 s (`-Xmx1g`) now
runs the full 4 minutes (21,820 cycles) with `SegmentFactories$1` **bounded and GC-reclaimed**
(45k–134k, oscillating up *and* down) and RSS ~0.5 GiB — versus the leak's monotonic climb to 21.8M /
4 GiB. Pinned by `ProllyValueUseAfterFreeTest.defaultFactoryGivesEachValueItsOwnSession`.

## Consequences

- **Positive.** The leak is gone; a value's native memory is freed promptly when the value becomes
  unreachable; no `prolly-codec` change; the use-after-free seam is intact. The fix is pinned two
  ways: the fast mechanism unit test (distinct memory session per value) and the soak bench (the
  implementation-agnostic guard that caught the leak in the first place).
- **Cost (accepted).** A per-value `Arena.ofAuto()` adds a `MemorySessionImpl` + Cleaner registration
  per value, where the old shared arena appended to one session. For a *very* value-heavy path this is
  more per-value overhead than a bump allocator — but the old bump allocator *was* the leak. If
  profiling later shows this matters, Option B (heap-backed) is the measured next step.
- **Known-separate (not fixed here).** `ProllyValueFactory.tripleComponents` is an unbounded
  `HashMap` — every `createTriple` component is cached for the factory's lifetime. That is a *second*,
  RDF-star-only unbounded-growth concern, out of scope for this leak; filed as follow-up.
- **Test coupling (documented).** `defaultFactoryGivesEachValueItsOwnSession` asserts distinct
  per-value sessions, so it is coupled to Option A; a future move to Option B would retire it in
  favour of the soak bench.

## Follow-up / future work

- **Measure the per-value-arena overhead** on a value-heavy path (SPARQL parse, large result
  materialization). If material, pursue **Option B (heap-backed value bytes)**: widen `encode*` to
  `SegmentAllocator` and resolve the numeric-encoder alignment question first.
- **Bound or remove `ProllyValueFactory.tripleComponents`** (the RDF-star component-cache growth).
- **Wire the rollback-churn soak as the resource/soak CI job** (`plans/resource-walls-as-benches.md`)
  so this class of regression is caught automatically, not only by a manual soak.

## Open questions

- **Q1 — numeric-encoder alignment under heap-backed segments (gates Option B).** Do
  `TermCodec.encodeLong` / `encodeFloat64` / … write fixed-width values at offsets that require
  `JAVA_LONG`/`JAVA_INT` alignment a `byte[]`-backed segment (alignment 1) cannot satisfy? If so,
  Option B needs unaligned layouts or a re-laid-out encoding. Unanswered until B is pursued.
