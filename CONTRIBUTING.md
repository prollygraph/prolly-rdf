# Contributing to prolly-rdf

## Prerequisites

- **JDK 25** (the build targets Java 25; no preview flags).
- **The engine ring installed locally** — until its artifacts are on Maven Central:

  ```bash
  git clone git@github.com:prollygraph/prolly-core.git && cd prolly-core
  mvn -DskipTests install
  ```

## The build IS the quality gates

`mvn clean install` runs everything; a green `mvn test` is **not** enough — the gates
bind to lifecycle phases *after* `test`, so a formatting or license violation sails
through a fully green test run and fails `install`. What each gate guards, and the fix
when it fails:

| Gate | Guards | When it fails |
|---|---|---|
| spotless (google-java-format, AOSP) | consistent formatting | run `mvn spotless:apply`, commit the result |
| license-check (mycila) | every source file carries its license header | run `mvn com.mycila:license-maven-plugin:4.6:format` — **not** the bare `license:format` prefix, which resolves to a different plugin |
| enforcer `dependencyConvergence` | one version per transitive dependency | add a pin to [`prolly-rdf-dependencies`](prolly-rdf-dependencies/README.md) (see its README for the first-wins ordering trap) |
| CycloneDX | supply-chain inventory (`target/bom.json` per module) | it emits, never fails |

Ported-from-Dolt files keep their original DoltHub header — never relicense a ported
file by rewriting its header (`build/dolt-provenance-ledger.md` is the provenance
record; the header templates live in `build/`).

## Tests

- **State the count when you say "tests pass"** — `Tests run: N, Failures: 0` beats
  "tests pass"; a count surfaces a silently dropped suite.
- **Property tests are named `*Property` and need an explicit Surefire include.**
  Surefire's default includes match only `*Test`/`*Tests`/`*TestCase`, so a jqwik
  `FooProperty.java` in a module without the `**/*Property.java` include is silently
  never discovered — a green build with an absent test. `prolly-codec`, `prolly-rdf`,
  and `prolly-rdf4j` carry the include already; a new module with `*Property` files
  must add it (copy the block from `prolly-codec/pom.xml`).
- **The demos are contracts.** Each class in `prolly-rdf4j/.../examples/` has a
  locking `*DemoTest`; if your change breaks a demo narrative, update the demo and its
  test together — they are cited from the docs as CI-locked.
- **The W3C conformance baseline only shrinks.** A test not in
  `prolly-rdf4j-compliance/src/test/resources/known-failures/` that starts failing
  fails the build; fixing a known failure means deleting its line.

## When a change needs an architecture decision record

Write one (in [`prolly-rdf4j/docs/adr/`](prolly-rdf4j/docs/adr/), following the
existing 4-section template: Context → Options with a comparison table → Decision →
Consequences) when a change:

- touches the on-disk format or a wire protocol,
- picks between viable approaches with materially different tradeoffs, or
- has high revisitation cost (schema, API surface, security).

Routine bug fixes and refactors don't need one — a good PR description is enough.

## Pre-1.0: no backwards-compatibility code

Formats evolve freely before 1.0. Don't add defensive readers that accept old shapes,
`@Deprecated` shims, or auto-migration paths — change the format and the reader
together, cleanly. (The full rationale is in the root README's Status section.)

## Writing style for docs

- **Ground claims.** A count, a measurement, or a status claim in prose should be
  verified against the repo (and dated if it can drift) — a doc citing a runnable
  test or demo beats a doc asserting from memory.
- **Spell out abbreviations** in prose (write "worst-case-optimal join", not "WCOJ")
  except for universally-decoded ones (HTTP, JSON, SPARQL, RDF).

## AI-assisted contributions

AI assistance is welcome — this project itself is developed with it (see the
[AI Disclosure](README.md#ai-disclosure) in the README). Three requirements:

- **Disclose it** in the PR description: which tools, and roughly what they did (code,
  tests, docs).
- **Review it yourself before submitting.** You are the author of your PR; "the model
  wrote it" is not a review. The same bars apply as for any contribution — tests in the
  same PR, `mvn verify` green, docs updated.
- **Confirm you have the right to contribute it** under [Apache-2.0](LICENSE) — don't
  paste in AI output reproducing code whose license or provenance you can't vouch for.
