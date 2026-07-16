
# ADR-0047: gRPC tag storage

## Status

Accepted, 2026-06-06. Guides `plans/grpc-versioning-service.md`.

## Context

The gRPC versioning service is adding git-style **tags** — immutable named pointers
at a commit (`CreateTag` / `ListTags` / `GetTaggedCommit` / `DeleteTag`). Nothing in
the server stores tags today; this decides where that state lives.

Two constraints shape the choice:

1. **The wire model is deliberately lightweight.** `versioning.proto`'s tag is
   `TagInfo { string tag; CommitId commit; string message; }` — a name, a commit, and an
   optional annotation message. There is **no tagger, no timestamp, no signature** field.
   So the storage must hold a name → (commit-hash, message) mapping, nothing richer.

2. **Branch refs already have a store, and the branch read verbs already shipped.**
   `RefsStore` (`prolly-rdf4j/.../sail/RefsStore.java`) is a file-per-ref store with a
   compare-and-set primitive, a path-traversal-hardened `validateName`, and a flat
   `list()` that returns *every* ref. The already-implemented `ListBranches`,
   `GetBranchHeads`, and the `allBranchHeads()` helper in `ProllyVersioningService` call
   `refs().list()` with **no prefix filter** — they treat every ref as a branch.

The decision is *where tag state lives*, and it is non-trivial because the obvious reuse
(`RefsStore` under a `refs/tags/` namespace) has a hidden cost: it would make tags appear
as branches in three already-shipped verbs unless every branch-listing site learns to
filter a reserved prefix, and `RefsStore` cannot carry the tag `message` (its values are
hex hashes only).

## Options

| Option | Isolation from branch verbs | Stores the `message` natively | New code |
|---|---|---|---|
| **A** — `refs/tags/` namespace in `RefsStore` | **No** — must add `tags/` filtering to `ListBranches` / `GetBranchHeads` / `allBranchHeads` (+ the REST + SPA branch lists), and tags become reachability-root refs that branch ops must skip | **No** — needs a second sidecar store for the message | Low (reuse compare-and-set) but **high blast radius** (retrofits 3 live verbs) |
| **B** — dedicated `TagStore` beside `refs/` | **Yes** — tags never appear in any branch operation, by construction | **Yes** — one file per tag carries `<hex-commit>` + the message | One small store class (file-per-tag), reusing `RefsStore`'s `validateName` + atomic-write idiom |
| **C** — tags as commits on a hidden `_meta/tags/*` branch | Yes, but heavyweight | Yes (in the commit) | High — a whole branch-backed-state machine for an immutable pointer |

## Decision

**Option B — a dedicated `TagStore`.** The deciding tradeoff is **blast radius**: tags are a
*different concept* from branches (immutable named pointers vs mutable heads), and putting
them in `RefsStore` would force a retrofit of three verbs that already shipped and are
pinned by tests — re-introducing the exact "phantom branch" contamination the reserved-prefix
hacks (`staging/`, `_meta/`) exist to paper over elsewhere. A separate store isolates tags by
construction and stores the `message` natively, at the cost of one small new class.

- **D-1. `TagStore` is file-per-tag**, rooted at `<store-dir>/tags/` beside `<store-dir>/refs/`,
  mirroring `RefsStore.beside`. Each tag is a file whose first line is the hex commit hash and
  whose remaining bytes are the (optional) message. Tags are **immutable**: create-if-absent
  and delete only — no compare-and-set-update churn, so the store is simpler than `RefsStore`.
- **D-2. Reuse `RefsStore.validateName`'s rules** (the same whitelist + path-traversal + absolute-path
  guards — the RefsStore path-traversal fix is load-bearing) so a tag name cannot escape the
  `tags/` directory.
- **D-3. `ProllySail` owns the `TagStore`**, constructed beside its `RefsStore` in
  `PerRepoProllySailFactory`, and exposed through `VersionedSail.tagStore()` as
  `Optional<TagStore>` — the same shape as `refsStore()` / `commitLog()`. The gRPC service reaches
  it via `sail.versioning().tagStore()`; a `FLAT` repo has none.
- **D-4. Tags are garbage-collection reachability roots** (a tagged commit must not be swept).
  This is **recorded, not yet enforced** — the garbage collector is deferred to its own
  ADR-gated plan (`plans/grpc-versioning-gc-and-leases.md`);
  that plan's ProllySail-aware mark phase unions `TagStore` commits into its root set.

## Consequences

- **Positive:** the three shipped branch verbs are untouched — no retrofit, no phantom-branch
  filtering, no regression surface. The tag `message` has a native home. The store is tiny and
  unit-testable in isolation (like `RefsStore`).
- **Negative:** a second file-per-ref store duplicates a little of `RefsStore`'s atomic-write +
  name-validation idiom (mitigated by reusing `validateName`). A tag name and a branch name can
  now coincide (`v1` as both a branch and a tag) — acceptable and git-like (git keeps `refs/heads`
  and `refs/tags` separate for the same reason).
- **Neutral / punted:** annotated-tag metadata beyond `message` (tagger, timestamp, signature) is
  out of scope until the proto grows those fields; the file format has room (extra lines) without a
  format break. Tags-as-roots is inert until the garbage-collection plan lands (D-4).

## Follow-up / future work

- The garbage collector that must honor tags as roots: `plans/grpc-versioning-gc-and-leases.md` (deferred, ADR-gated).
- If the proto later adds tagger/timestamp/signature, extend the `TagStore` file format (append lines) — no migration needed pre-1.0.

## Open questions

- None at write time.
