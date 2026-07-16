
# ADR-0005: Event log at scale (>100 GB, many concurrent users)

## Status

Proposed. Companion to [ADR-0003 (per-triple event log)](0003-per-triple-event-log.md).
ADR-0003 establishes the storage shape and write semantics; this ADR
addresses the operational cost at production scale and tells operators
*when* to opt in.

## Context

ADR-0003 records one entry per mutation. For stable data that's
acceptable (one entry per triple ever added). For churny data the
storage cost grows linearly with mutation count, not triple count.
At the scale we expect production deployments to land at — **>100 GB
of base data, many concurrent users** — the question is not "does the
event log work" (it does) but "is the write amplification, storage
overhead, and concurrent-write coordination worth the queries it
unlocks?"

## Where ADR-0003 is still worth the cost at scale

1. **Regulated workloads where the event log *is* the product.**
   Medical records, financial audit trails, regulator reporting —
   workloads where "show me every mutation of this fact" is a
   load-bearing query, not a once-a-month forensic. There, the
   write cost is paying for the actual deliverable. If the
   operator's compliance posture mandates retaining the full
   mutation chain, this ADR is moot — they need it regardless of
   cost.

2. **Stores where churn is low.** If your 100 GB is mostly stable
   assertions with rare retractions — typical of ontology hosts,
   vocabulary servers, reference-data services — ADR-0003's cost
   equals ADR-0001's. The event log only explodes when triples
   *flap* (delete-then-readd, or repeated correction-update cycles).
   For a write-once-read-many workload, you pay the same per-entry
   cost either way and the event log gives you strictly more
   information.

3. **Workloads where the question is rare but precious.** If you
   only ever query the event log for incident response or
   regulator escalations, the *amortized* cost per query is low
   even if the per-write cost is high. The economic question is
   "can we afford the writes" not "are the reads worth it."

## Pragmatic answer at >100 GB + many concurrent users

Most production deployments **shouldn't enable ADR-0003 globally**.
The cost-effective shape is opt-in scoped, not opt-in store-wide.
Five concrete mitigations, ordered cheapest first:

1. **Don't enable it.** ADR-0001's first-seen-wins covers 99% of
   "when did this fact first appear?" questions. Operators who
   reach for the event log usually want a *specific* answer for a
   *specific* subject — the answer is to log app-level audit
   events for that subject, not to record every mutation in the
   substrate. This is the default recommendation; switch to the
   options below only when the workload genuinely needs full
   history.

2. **Per-namespace opt-in.** Enable the event log only for
   triples whose subject IRI falls in a configured prefix set
   (e.g., `urn:example:audited:*`). General ontology
   triples — `rdf:type`, `rdfs:label`, `owl:sameAs` — bypass the
   index entirely. Cuts write amplification proportionally to
   how concentrated the audit-required triples are; where
   audit-relevant subjects are a small fraction of total triples
   (often <5%), the write saving is large. Configuration:
   `prolly.rdf4j.provenance.events-subject-prefixes=urn:example:audited:`.

3. **Tiered storage — hot events in-tree, cold events archived.**
   Keep the last N commits' events in the EventLogIndex on the
   primary store; periodically (nightly cron) drain entries older
   than N commits into a separate cold archive (S3 or another
   prolly tree). The hot index stays small; cold reads pay one
   extra round-trip. EL.6 (compaction) is the natural place to
   hang this — make compaction *move*, not delete.

4. **Asynchronous write path.** Decouple the event-log write
   from the data write: data commits land synchronously, event
   entries queue to a buffer that flushes on a timer (e.g.,
   every 100 ms or every 10k entries). Trades durability for
   throughput. *Caveat*: a crash between data commit and event
   flush loses the event record. Acceptable when the loss is
   bounded (≤ flush interval) and the workload prefers throughput
   over forensic completeness.

5. **Read-replica with full event log.** Primary store keeps
   ADR-0001 only (cheap writes); a read-replica subscribes to the
   commit stream and builds the full EventLogIndex from each
   commit's diff. Audit queries hit the replica; the primary
   stays lean. Higher operational complexity (two stores, a
   subscriber), but the only option that gives full event
   coverage without paying for it on the write path.

## Decision

**Default: do not enable ADR-0003 store-wide.** The opt-in flag
`prolly.rdf4j.provenance.events-enabled` stays — but the
configuration *should* also accept a subject-prefix filter
(`prolly.rdf4j.provenance.events-subject-prefixes`). When the
prefix list is non-empty, only matching triples enter the event
log; the global flag without prefixes is a knob for tests, small
deployments, or workloads where the event log truly *is* the
product (option 1 above).

EL.6's compaction policy should be **drain-to-cold**, not
delete-in-place — preserves the regulator answerability at lower
hot-storage cost.

The async write path (option 4) is out of scope for v1; revisit
when a real deployment hits the throughput ceiling.

### Status of the implementation (as of #129–#130)

- **Hot/cold split landed.** EL.6 + cold-archive persistence ship
  via the `prolly-rdf4j-enterprise` module.
  `POST /sparql/provenance/log/compact?before=<hex>` drains stale
  events to a cold tree; the cold root persists via
  `<storeDir>/event-log-cold.head` and restores on startup.
- **Cheap-tier backend wired.** Set
  `prolly.rdf4j.staging.cold-store-dir=/cheap/disk` (or any other
  RocksDB-compatible path) and cold chunks land in that separate
  NodeStore. Reads merge hot + cold transparently. Operators can put
  the cold path on slower / cheaper / network-attached storage
  without changing application code.
- **S3 backend** — not in v1. The `NodeStore` interface is the
  extension point; an `S3NodeStore` could be added without further
  changes to the event-log code path. Build when a real customer
  asks.

## Cost ladder summary

| Mitigation | Setup cost | Per-write cost | Per-read cost | Forensic completeness |
|---|---|---|---|---|
| Don't enable (default) | None | None | N/A | None |
| Per-namespace prefix filter | Config | Proportional to %-matching | Same as full | Full for matching subjects |
| Tiered (hot + cold archive) | Cron job + cold store | Same as full | +1 round-trip for old events | Full |
| Async write | Buffer + flush thread | Lower (batched) | Same as full | Bounded loss window |
| Read-replica | Two stores + subscriber | Same as ADR-0001 only on primary | Same on replica | Full on replica |

## What this isn't trying to solve (deferred)

- **Tenant isolation for events.** ADR-0001 §9 axis 5 (genesis-hash
  scoping) applies the same way to the event log if it's enabled.
  Add `repoId` to the event entry value when wiring EL.3.
- **Cross-event analytics** ("how often does this fact flap?",
  "what's the average mutation frequency?"). Aggregations belong
  in a stats sidecar, not in the event log itself.
- **Real-time subscribers** ("notify me when this triple changes").
  That's a change-data-capture pattern over the commit log,
  orthogonal to the event-log query shape.

---

*Plan version 1. Read with [ADR-0003](0003-per-triple-event-log.md)
for the storage shape; this doc is purely about when to enable it.*
