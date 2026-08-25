# Changelog

Pre-1.0: **the on-disk format evolves freely and without backwards compatibility.** A
version bump may mean old store directories cannot be read; export/re-import is the
supported transition. Entries below are release-level; day-to-day history is the git log.

## Unreleased

### Write path

- **Dedupe hits ride the engine's per-run filters; lookup key blocks release on every path.**
  The adversarial re-review of the presence-index round confirmed its claim over-reached:
  the index accelerated ABSENT probes only, while a spilled dictionary's dedupe HITS (the
  majority of bulk-encode traffic) still walked `O(runs)` run files — twice, via
  `containsKey` + `get`. The engine now builds a Bloom filter per sealed run and collapses
  the double walk into one three-way lookup, so a hit reads ~one run block; this entry's
  predecessor's "amortized O(1) per term" is scoped accordingly (absent-side exact, hit-side
  ~one probe, both budget-bounded with graceful degradation — see the engine CHANGELOG).
  `Dictionary.findTermId`/`decode` also release their pool-borrowed key blocks in `finally`,
  so a throwing spilled-run probe (an `UncheckedIOException` mid-walk) can no longer skip the
  ADR-0062 D-4 release discipline.
- **The dictionary enables the engine's presence index.** Encode's per-term dedup was measured
  `O(runs)` per first-encountered term once the staging buffer spilled — the quadratic
  bulk-encode wall (consumer trace: quarkus-ontology-editor, benchmarks
  `ncit-runs/one-flush-probe.txt`, jstack-pinned to the run-file probes). The dictionary's
  Int64 keys are canonical (equal values build byte-identical tuples, pinned in
  `Int64KeyTest`), satisfying the index's soundness contract; a spilled dictionary now encodes
  in amortized O(1) per term and `prolly.tx.dict.spill.bytes` demotes from the only escape
  hatch to a tuning knob. The rebase construction routes through `newBuffer` so post-flush
  dictionaries keep the same properties.
- **Retained staged keys allocate exact-size.** `SpocKey`/`Int64Key` `toTupleSegment` switch
  to the engine's new `borrowRetained`: keys held in `MutableMap.edits` until flush stop
  paying the heap pool's 1 KiB bucket floor (24× per 42-byte quad key, ~85× per 12-byte dict
  key — the consumer's bulk-ingest `OutOfMemoryError`, trace `e2e-one-flush.txt` run 4).
  Backing-array-size tests pin both; the `ScopeTrackingPool` parity instrument forwards
  `borrowRetained` so the scope net exercises the production allocation shape.

### Scope

- **The twelve prolly-json architecture decision records moved out** to the
  [prolly-json ring](https://github.com/prollygraph/prolly-json/blob/main/docs/adr/README.md)
  (0023–0029, 0053–0055, 0057, 0058). They decide that ring's model and lived here only
  because both faces once shared a monorepo. The index keeps their rows, re-pointed, plus a
  note explaining the deliberate numbering gap — ADR numbers are never reused. 63 ADRs remain.

### Documentation

- **A whitepaper**, [Versioned RDF for Regulated Data](docs/whitepapers/VERSIONED_RDF_FOR_REGULATED_DATA.md):
  the argument for commits, branches, merges, and time-travel in a knowledge graph that has
  to satisfy auditors, mapped against the public text of 21 CFR Part 11, the HIPAA Security
  Rule, and GDPR. Four passages were rewritten to stand alone rather than defer to private
  documents — the merge-semantics section now states the four hard cases instead of pointing
  elsewhere.
- [`docs/prior-art.md`](docs/prior-art.md) gained the publishable substance of a private
  market analysis: a survey of what each triplestore *actually* offers (none, snapshots,
  audit log, point-in-time restore — none of it branchable), and three architectural
  differences from TerminusDB, including the shared-substrate point narrowed honestly (quads
  here, SQL rows there — no interoperability exists or would follow from byte-compatibility).
- [`docs/developer-skill-sets.md`](docs/developer-skill-sets.md) and
  [`docs/operator-notes.md`](docs/operator-notes.md) — what contributing asks of you, and
  what embedding this Sail commits you to (four index permutations, ingest as the heavy
  phase, choosing deliberately between the two Sails).
- AI disclosure statement in the README and contributor policy in `CONTRIBUTING.md`.

### Publication hygiene

- Removed internal publication-review headers that exposed a private policy path, and
  neutralised prose and comment references naming private strategy documents by title — a
  leak class the link rot-guard could not see, because they were bare paths rather than
  markdown links.
- ADR-0059 recorded the core extraction partly in open-core / moat terms; the engineering
  decision is unchanged and fully stated, but the business framing is now a licensing and
  release boundary. ADR-0044's "open-core engine" is now "the extracted engine".
- Apache-2.0 appendix names the copyright owner; `NOTICE` added — a copyright census
  confirmed all 533 sources here are Earasoft-original, so it says that and points at the
  engine ring for the upstream attribution belonging to that layer.

- Contributor-onboarding wave (2026-07-24/25, commits `d497413`, `571b263`, `5ba5108`,
  and this one): a Learning-the-code path + runnable-demos catalog in the READMEs, an
  embedded-Sail quickstart leading `getting-started.md`, `CONTRIBUTING.md`, 15 exported
  newcomer docs (5 anatomy walkthroughs + 10 foundations, adapted from the upstream
  monorepo's public-safe set), a repo-wide `DocsLinkTest` rot-guard (relative links +
  path citations), benchmark-vintage date-stamps in `the-two-sails`, and the community
  files (SECURITY / CODE_OF_CONDUCT / templates / this changelog).

## 0.2.0-BETA — 2026-07-16

- Extracted from the private monorepo as the **RDF ring** (`efd2174`): seven modules —
  `prolly-rdf-dependencies` (the Maven BOM), `prolly-codec`, `prolly-rdf`,
  `prolly-flatsail`, `prolly-rdf4j`, `prolly-urdna2015`, `prolly-rdf4j-compliance` —
  plus the `spec-compliance/` invariants catalog and background docs. Versioned in
  lockstep with [prolly-core](https://github.com/prollygraph/prolly-core); depends on
  its published `io.github.prollygraph` artifacts. Pre-extraction development history
  lives in the private monorepo.
