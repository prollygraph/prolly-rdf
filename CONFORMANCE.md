# Conformance report

**Scope:** `ProllySail` (the versioned RDF4J Sail) at `0.2.0-BETA`, RDF4J
`5.1.4`, JDK 25. Every number below is produced by the gated build —
`mvn clean install` re-derives all of them — and every accepted failure is
named, classified, and gated so it can shrink but never silently grow.

## Results

| Suite | Result | Baseline file |
|---|---|---|
| W3C SPARQL 1.1 **query** | **174 / 176** | [`known-failures/sparql11-query.txt`](prolly-rdf4j-compliance/src/test/resources/known-failures/sparql11-query.txt) (2 entries) |
| W3C SPARQL 1.1 **update** | **90 / 90** | [`known-failures/sparql11-update.txt`](prolly-rdf4j-compliance/src/test/resources/known-failures/sparql11-update.txt) (**empty** — any new failure breaks the build) |
| RDF4J store / repository contract suites | pass, with the documented gap classes below | categorized in the [conformance frontier](prolly-rdf4j-compliance/docs/conformance-frontier.md) |
| Full ring | **3,085 tests, 0 failures** (measured 2026-07-24, full gated build; 2 tests deliberately parked with a filed bug — an upstream concurrency choreography needing two concurrently-open write transactions — `@Disabled` with the reason inline) | — |

The query suite is also re-run with worst-case-optimal triejoin routing forced
on: **the routed and unrouted engines must produce identical results** — the
suite doubles as the join engine's correctness lock.

## The two known query failures

Self-reported, with the classification from the
[conformance frontier](prolly-rdf4j-compliance/docs/conformance-frontier.md):

| Test (`mf:name`) | Category | Why it fails |
|---|---|---|
| `constructwhere04 - CONSTRUCT WHERE` | unimplemented | `CONSTRUCT WHERE` with a `FROM` dataset clause — `FROM`-document resolution is not wired through the Sail. Candidate fix identified: wire it through. |
| `(pp35) Named Graph 2` | unimplemented | Property-path evaluation across named graphs is not implemented. Feature backlog. |

History: the baseline was captured 2026-05-15 at 171/176 and **shrank to
174/176 on 2026-06-12** when term-faithful storage (ADR-0043) fixed
`TZ()`/`TIMEZONE()` and `tsv03`. The shrink is recorded, with root causes, in
the frontier document.

## Contract-suite gap classes (documented, categorized)

| Gap | Category | Ruling |
|---|---|---|
| `Statement` Java serialization (`NotSerializableException`) | architectural | out of scope — values are backed by off-heap `MemorySegment`; a serialization adapter would be a separate feature |
| Literals > 64 KiB | architectural | out of scope without a blob/overflow layer — the tuple format's `uint16` offsets cap a value's length (see the engine's [format spec](https://github.com/prollygraph/prolly-core/blob/main/docs/spec/on-disk-format.md)) |

Three further gap classes in this table were **fixed in June 2026** (ill-typed
literal round-trip, timezone-absent temporals, pre-set bindings on
`FILTER`-only variables) and remain listed in the frontier document with their
root causes — fixed entries stay visible rather than disappearing.

## Methodology — how to distrust this report efficiently

1. **Fixtures are Eclipse's, not ours.** The W3C suites arrive at test time
   via the published `rdf4j-*-testsuite` Maven artifacts (version `5.1.4`,
   pinned in `prolly-rdf-dependencies`). No fixture files are embedded or
   edited in this repository.
2. **The baseline is the claim.** A conformance test not listed in
   `known-failures/` that fails **breaks the build**
   (`KnownFailuresBaselineTest`). The update baseline is empty; the query
   baseline has exactly the two entries above.
3. **The ratchet is enforced, not aspirational.** `MustShrinkBaselineTest`
   fails the build if the baseline grows. Shrinks are recorded with root
   causes in the frontier document.
4. **Reproduce it:**

   ```bash
   git clone https://github.com/prollygraph/prolly-core && (cd prolly-core && mvn -DskipTests install)
   git clone https://github.com/prollygraph/prolly-rdf && cd prolly-rdf
   mvn clean install                        # full gated build — the live count
   mvn -pl prolly-rdf4j-compliance -am test # just the conformance module
   ```

Requires JDK 25. The build's other gates (formatting, license headers,
dependency convergence) run in the same invocation; a green `mvn clean
install` *is* the claim above.

## What this report does not claim

- No SPARQL 1.1 **protocol** or **Graph Store HTTP** conformance is claimed —
  the HTTP server product is not in this repository.
- No performance claims are made here; benchmark write-ups live with their
  harnesses in the repos' `docs/`.
- Pre-1.0: the on-disk format is not stable, and conformance numbers are
  re-derived by every build rather than frozen with a badge.
