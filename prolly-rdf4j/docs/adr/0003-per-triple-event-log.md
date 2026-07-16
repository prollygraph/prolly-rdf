
# ADR-0003: Per-triple event log (full mutation history)

## Status

Accepted, backfilled 2026-06-23 (predated the `## Status` convention) — the per-triple event sink is wired into the commit path.

## Context

[ADR-0001](0001-provenance-index.md) introduced a `ProvenanceIndex`
sidecar prolly tree keyed by `SpocKey → parent-commit-at-first-appearance`.
The design is deliberately **first-seen-wins**: idempotent inserts of
the same triple never overwrite the recorded provenance, and *deletes
record nothing*.

That's the right shape for the common question — "which commit
introduced this fact?" — and keeps storage cost low. But it
discards information by design:

- A triple inserted at commit X, deleted at Y, and re-inserted at Z
  still records its provenance as X. The delete and re-insert events
  are not retrievable.
- "Has this assertion flapped?" can't be answered.
- Audit questions like "show me every modification to this medical
  record" need the full event history, not just first-seen.

This ADR explores a richer index — **per-triple event log** — that
keeps every INSERT/DELETE event for a triple, unlocking a `git log
-- <triple>` style query. Captures alternatives evaluated and the
recommended shape.

## What the proposal extends

The current ProvenanceIndex:

```
SpocKey → parent-commit-hash       (one entry per triple, never overwritten)
```

The proposal:

```
(SpocKey, commit) → event-type     (one entry per mutation)
```

Each `(triple, commit)` pair records `INSERT` or `DELETE`. Walking
"all entries for this SpocKey" sorted by commit reconstructs the
full history.

## What this unlocks

- **Full event history per triple** — not just "first seen at" but
  "deleted at X, re-inserted at Y, deleted again at Z".
- **`git log -- <triple>` semantics** — walk the entries to render
  a per-triple commit history in the UI.
- **Audit / compliance** — "show me every time this medical record
  was modified" is a first-class query, important for regulated
  workloads.
- **Drift analysis** — how often does a given assertion flap?
- **Provenance on DELETEs** — currently a delete records nothing;
  this gives you the commit that removed a fact.

## Variants considered

The user's framing reached for a **doubly-linked list** per triple
(`prev` / `next` event-node pointers). That's the right intuition
for "I want to walk forward and backward", but it pays for that
expressiveness:

### Variant A — DLL per triple (rejected)

```
node = { commit, prevEvent, nextEvent }
```

- **Storage explosion**: ~80 bytes per event before framing (two
  32-byte commit hashes + the SpocKey reference).
- **Pointer mutation in a content-addressed Merkle tree is wrong**.
  Each new event would need to *update* the previous event's
  `next` pointer. In an immutable per-commit tree you'd write a
  new version of the prev node, cascading rewrites up the Merkle
  path. Workable but expensive and conceptually awkward — you're
  fighting the content-addressed grain.

### Variant B — sorted event log keyed by `(SpocKey, commit)` (recommended)

```
key:   SpocKey || commit-hash
value: event-type (1 byte: 0x01 INSERT, 0x00 DELETE)
```

- Walking history = range scan over all keys with the same SpocKey
  prefix.
- **No pointers**: order is implicit in the key sort, and event
  arrival order matches commit topological order (we're single-
  writer for v2.0).
- **No mutation**: every event is a write-once entry. Plays well
  with the Merkle tree's content-addressed semantics.
- Per-event cost: ~25 bytes (32-byte SpocKey reference + 32-byte
  commit-hash + 1-byte type, plus prolly framing).

### Variant C — sparse deltas on top of first-seen-wins (compromise)

Keep ADR-0001's `ProvenanceIndex` for the 99% query ("when was this
first added?"); add a second sparse sidecar that only records
**delete-then-readd** events (the rare case). Storage stays small;
the audit story is partial.

### Variant D — per-subject coarser event log (orthogonal)

Coarser granularity, keyed by `(SubjectTermId, commit)`. Answers
"what happened to `:Alice` over time?" without per-triple
amplification. Useful for "show me the life of this entity"
queries that don't need triple-level precision. Could coexist
with Variant B.

## Recommendation

**Variant B — sorted `(SpocKey, commit)` event log** — is the
right primitive for an event-history index in a content-addressed
store. It avoids pointer mutation, has the smallest per-event
footprint of the full-history variants, and turns the "walk
history" question into a range scan (which the prolly tree
already does well).

**Variant D** can be added later as a second sidecar without
disturbing Variant B; the two answer different questions at
different granularities. Treat as a follow-up gated on real
demand, not bundled.

**Variant A (DLL)** is rejected — pointer mutation in a content-
addressed tree is a fight you don't want to pick when sort-key
order already gives you the same traversal.

## Cost vs ADR-0001's first-seen index

| Dimension | First-seen (ADR-0001) | Event log (this ADR, Variant B) |
|---|---|---|
| Writes per triple lifetime | 1 (first INSERT only) | N (every INSERT/DELETE) |
| Bytes per entry | ~40 (SpocKey + parent hash) | ~25 (SpocKey + commit + 1 byte) |
| "Who first added X?" | O(1) lookup | O(1) range-scan start |
| "Has X ever been deleted?" | not answerable | O(scan) |
| "Show me X's history" | not answerable | O(K) range scan where K = # events |
| Storage growth for stable data | bounded | bounded (same as first-seen) |
| Storage growth for churny data | bounded | unbounded (one entry per change) |

For a stable workload (most triples inserted once, rarely deleted),
the event log isn't materially larger than first-seen — same number
of events. For a churny workload (frequent additions and removals),
it grows linearly with churn. That tradeoff is acceptable as long
as it's **opt-in** (like ADR-0001) so deployments that don't need
it don't pay.

## Plan (sub-iters)

1. **EL.1**: Spec the on-disk format — `(SpocKey, commit-hash)` →
   1-byte event type. New `RootMetaTree` entry
   `NAME_PROVENANCE_EVENTS`, opt-in via
   `prolly.rdf4j.provenance.events-enabled=true` (separate from the
   ADR-0001 flag so deployments can pick first-seen-only or full-log).
2. **EL.2**: Implement `EventLogIndex` with `recordEvent(SpocKey,
   commit, type)` and `Iterator<Event> scan(SpocKey)`.
3. **EL.3**: Wire into `ProllySailConnection`'s insert + delete
   paths.
4. **EL.4**: Endpoint `GET /sparql/provenance/log?s=&p=&o=` returns
   the full event chain.
5. **EL.5**: UI — "Show full history" link in the blame popover
   that opens a side panel rendering each event with its commit
   message + datetime.
6. **EL.6**: Compaction policy. Long-lived stores accumulate
   events; decide whether to coarsen old history (drop intermediate
   delete/re-insert pairs that net to no change) or accept linear
   growth.

## What this isn't trying to solve (deferred)

- **Author at event time** — same auth-model gap as ADR-0001.
- **GC of orphaned events** — same commit-pruning question.
- **Schema-aware event filtering** — "show me only events that
  changed the object" requires reasoning over schema, out of scope.
- **Cross-triple events** — "what triples changed together in this
  commit?" is the existing `/sparql/diff` view, not an event log
  question.

## Relationship to ADR-0001

ADR-0001 §9 listed three follow-up axes (naming, scope of record,
granularity). This ADR adds a **fourth axis**:

> **History depth.** ADR-0001 records first-seen-only. ADR-0003
> records the full event chain. Both indexes can coexist — the
> first-seen index for the cheap common-case lookup, the event
> log for forensic detail when an operator asks for it.

In production: ship ADR-0001 as the default; ADR-0003 is the
opt-in upgrade for audit-heavy workloads (medical records,
financial trades, regulated data).

---

*Plan version 1. Ready for stakeholder review before EL.1.*
