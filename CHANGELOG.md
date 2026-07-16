# Changelog

Pre-1.0: **the on-disk format evolves freely and without backwards compatibility.** A
version bump may mean old store directories cannot be read; export/re-import is the
supported transition. Entries below are release-level; day-to-day history is the git log.

## Unreleased

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
