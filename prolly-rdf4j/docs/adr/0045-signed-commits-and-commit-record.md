
# ADR-0045: Signed commits and commit record

## Status

Accepted, 2026-06-05. Guides `plans/grpc-versioning-service.md`
(the `CommitSpec` is that plan's Commit-verb wire message, ratified in its Step 1) and the
embedded `ProllySail` commit path.

## Context

A commit today is a hand-rolled `Commit` **class** —
`prolly-port-core/.../Commit.java:54` — carrying `rootValueHash`, `parents`,
`author`, `message`, `timestamp`, with a bespoke `serialize()`/`deserialize()`. There is
**no signature**. The commit *intent* is staged through **three volatile setters** on the
Sail (`ProllySail.java:140-148`) — `nextCommitMessage`, `nextCommitAuthor`,
`nextCommitMergeParent` — consumed and cleared inside `conn.commit()`
(`ProllySail.java:584-593`), where an unset author or message silently defaults to `""`.

Three problems converge:

1. **The staging surface is mutable, partial, and non-atomic.** Three independent setter
   calls plus a transaction commit; forget one and the commit records a blank field (the
   `""`-author smell is this failure already shipping). It is not a value you can validate,
   pass, or reason about as a unit.
2. **There is no authorship attestation.** The `author` is a free string; anyone who can
   write can claim any identity. Nothing cryptographically binds a commit to who made it.
3. **The gRPC versioning service needs an atomic, stateless commit verb over the wire.**
   A setter *sequence* has no wire analog; the Commit RPC must carry one self-contained
   message. That message is the single most expensive thing to change once the Mobi client
   vendors the contract, so its shape must be decided **now**, not retrofitted.

Constraints that shape the answer:

- **Commits are content-addressed and chained by parent hash.** Re-hashing one commit
  re-hashes every descendant — so any field that participates in the hash cannot be added
  after the fact without rewriting history.
- **Pre-1.0, no backwards-compatibility** (CLAUDE.md). The on-disk format may change freely;
  the reader requires the new shape — no defensive old-format path.
- **An SSH-signature stack already exists.** `SshSignatureVerifier`
  (`prolly-platform/.../auth/`) verifies `ssh-ed25519` / `ssh-rsa` /
  `ecdsa-sha2-nistp256`, and `/auth/keys` + `KeysStore` (ADR-0019) already hold users'
  registered public keys for HTTP-Signature authentication.

## Options

Two orthogonal sub-decisions. The first is small; the second is the load-bearing one.

**Sub-decision 1 — how the commit *intent* is represented:**

| Option | Atomicity | gRPC fit | Failure mode |
|---|---|---|---|
| **1A** — mutable setters (status quo) | none (3 calls + commit) | poor — no wire analog | partial fill; `""`-author |
| **1B** — immutable `CommitSpec` record | atomic, one value | native (a proto message) | rejected at construction |

**Sub-decision 2 (headline) — does the signature participate in the commit hash?**

| Option | Identity stability | Retro / detached signing | Tamper model | Precedent |
|---|---|---|---|---|
| **2A** — signature **inside** the hash | signing **forks** the hash | impossible — re-hashes the whole directed acyclic graph | sig welded to identity | git `gpgsig` / `commit -S` |
| **2B** — signature **excluded**; signs the same payload | stable whether signed or not | cheap, safe, after-the-fact | sig over the exact bytes the hash commits to; policy at the ref layer | — |

## Decision

- **D-1 — Commit intent is an immutable `CommitSpec` record.** Fields: `author` (required),
  `message` (required), `Optional<Instant> timestamp` (empty → engine stamps now),
  `List<byte[]> extraParents` (merge parents beyond HEAD), `Optional<CommitSigner> signWith`.
  The compact constructor rejects blank author/message — **the `""` default is designed out**.
  This single value replaces the three setters and is the gRPC Commit verb's wire message
  (Option 1B).

- **D-2 — `Commit` becomes a record** gaining `Optional<CommitSignature> signature`, where
  `CommitSignature(String algorithm, String keyFingerprint, byte[] signature)`. The other
  fields (`rootValueHash`, `parents`, `author`, `message`, `timestamp`) are unchanged.

- **D-3 (headline) — the signature is EXCLUDED from the commit hash (Option 2B).**
  `hash = H(signingPayload)` over `{rootValueHash, parents, author, message, timestamp}`; the
  signature signs that *identical* payload and is stored outside the hash input. The deciding
  tradeoff: **content-addressing must address content, not attestations.** Two byte-identical
  histories share a hash regardless of who vouched for them — which (a) lets a signature be
  added or replaced **without re-hashing the directed acyclic graph** (decisive, because in a chained content-
  addressed log re-hashing one commit cascades to all descendants — Option 2A forbids
  detached/after-the-fact signing entirely, which is exactly git's `commit -S`-changes-the-SHA
  wart), and (b) still binds tightly: a verifier recomputes `H(signingPayload)`, confirms it
  equals the commit hash, then verifies the signature over that payload. Stripping the
  signature cannot forge authorship — it only downgrades to "unsigned"; substituting one
  requires a *valid* signature over the same payload by the claimed author's registered key.

- **D-4 — signing reuses the existing auth stack.** Verification calls `SshSignatureVerifier`
  over `commit.signingPayload()`; `keyFingerprint` is the *same* sha256 fingerprint the
  `/auth/keys` registry uses for HTTP-Signature auth. One key both authenticates a user and
  vouches for their commits — no second key system.

- **D-5 — signing is a callback, not a stored key.** `CommitSigner` exposes
  `byte[] sign(byte[] payload)` plus its `algorithm` + `keyFingerprint`. The engine cannot
  pre-sign (it lacks `rootValueHash`/parents until the tree mutation completes), so it signs
  *after* assembling the payload. No private key ever enters `CommitSpec` as data.

- **D-6 — "require signed commits" is branch-protection policy, not a hash property.** A
  `RepoConfig.requireSignedCommits` flag (sibling to `requiredApprovals`) is enforced at
  `recordBranchCommit` / the merge gate. This keeps the three concerns separated: **identity**
  (the hash), **authentication** (the signature), **policy** (the ref layer).

- **D-7 — a separate `committer` field is deferred.** Git's author-vs-committer split earns
  its place only once an operation *reattributes* a change (rebase, patch-apply,
  cherry-pick-under-a-different-actor). Until then `author == committer` and a second field is
  dead weight. Trigger to add it: the first such reattributing operation.

- **D-8 — no-BC format change.** `Commit`'s serialization gains a required
  signature-present flag (`0` = absent is a legitimate value); when present, a
  length-prefixed `{algorithm, keyFingerprint, signature}` block follows the existing fields.
  The reader requires the new shape — no defensive old-format branch (CLAUDE.md pre-1.0 rule).

## Consequences

**Positive:**

- The partial-fill / `""`-author failure class is eliminated by construction — a commit's
  metadata is one validated, immutable value.
- Commits gain cryptographic authorship, reusing keys users already register; the `/commits`
  UI and gRPC `GetCommit` can surface `signed` / `verified` / `unsigned` state.
- The gRPC Commit verb is a clean, stateless message instead of a setter dance.

**Negative / cost:**

- **On-disk format break.** Existing stores' commit logs must be rebuilt — pre-1.0, this is an
  operator backup/restore (no auto-migrator, per the no-BC rule). 
- **New untrusted-byte surface.** `Commit.deserialize` gains a trailing length-prefixed block;
  it must be fuzzed at the parser boundary (the length-field denial-of-service lesson already learned there —
  see the untrusted-byte-boundary work).
- **A verification path to build + keep correct** (`verifyCommitSignature(commitHash)`), plus
  the embedded `ProllySail` migration from three setters to `stageCommit(CommitSpec)` (the
  setters are *deleted*, not deprecation-shimmed).

**Neutral:**

- Signing stays optional; an unsigned commit is fully valid unless a branch's policy (D-6)
  requires otherwise.

## Follow-up / future work

- **Key-rotation / revocation semantics for historical commits** — a key revoked *after* it
  signed a commit: verification must distinguish "valid at signing time" from "currently
  trusted." Out of scope here; a future ADR when audit requirements demand it.
- **Multiple / cross-signatures** (more than one signer per commit) — the record carries a
  single `Optional<CommitSignature>` for now; widen to a list only if a co-sign/notary
  workflow appears.
- **`committer` field** — add per D-7 when a reattributing operation lands.

## Open questions

- **Q1** — Should `timestamp` migrate from epoch-millis `long` to a richer encoding (e.g.
  RFC 3339 with offset) at this format break, since we are breaking the format anyway? Leaning
  no (the `long` is wire-stable and timezone is presentation), but the break is the cheapest
  moment to reconsider.
