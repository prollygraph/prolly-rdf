
# ADR-0019 — Personal Access Tokens + SSH-key authentication

## Status

Accepted, 2026-05-25. Guides
plans/auth-tokens-and-ssh-keys.md.
Builds on [ADR-0013 (user accounts)](0013-user-accounts-and-authenticated-staging.md)
+ [ADR-0015 (auth backend choice)](0015-auth-backend-choice.md).

## Context

ADR-0013 retired the shared-secret API key in favor of per-user
accounts authenticated via HTTP Basic or session cookie. That
solved the "everyone-shares-one-key" anti-pattern but left the
**machine-to-machine** workflow in an awkward place: CI runners,
backup scripts, and integration jobs had to authenticate as
"service accounts" — regular users with passwords. The pain:

- **No per-credential revocation.** Compromising a CI runner
  meant rotating the service account password, which broke every
  other runner using the same account.
- **No scoped permissions.** A token that should only read one
  repo had the same blast radius as the user — wherever they had
  access, the leaked credential could write.
- **Passwords on disk on every CI box.** SSH-key auth is the
  standard answer; we had no story.

## Options

| Option | Revocation | Scoping | M2M ergonomics |
|---|---|---|---|
| **A — Stay on Basic + service accounts** (status quo) | Rotate password (affects all callers) | None | Painful |
| **B — JWT bearer tokens** | Impossible (stateless) — wait for expiry | Embed claims in JWT | Standard |
| **C — Opaque PATs + SSH-key auth** (this ADR) | Immediate via DB delete | Scope strings on each token | Standard |
| **D — OAuth / SSO** | Token revocation via authorization server | OAuth scopes | Federated; large project |

## Decision

Option **C — opaque PATs (random tokens; DB-backed lookup) +
HTTP-Signatures SSH-key auth.** Nine sub-decisions:

**D-1 — Token format: `prdf4j_pat_<32 base32 random><4 checksum>`.**
160-bit entropy in the random body. The `prdf4j_pat_` prefix
makes leaked tokens grep-able in logs (and recognizable on
GitHub if accidentally committed). The 4-char checksum is the
first 4 base32 chars of SHA-256(random) — lets clients
validate format before hitting the server.

*Withdrawn from plan*: bcrypt of the token value. The plan
called for bcrypt; the implementation uses SHA-256 only. 160
bits of entropy makes brute-force infeasible, and bcrypt costs
~100ms per auth — too expensive on the hot path.

**D-2 — Opaque tokens, not JWT.** JWT's stateless nature means
revocation requires a separate denylist or short expirations,
neither of which we want. With opaque tokens we get O(1) lookup
via secondary index (`auth_tokens_by_hash` CF) and immediate
revocation by deleting the row.

**D-3 — Scope strings narrow user permissions, never elevate.**
Format: `repo:{target}:{verb}`, `org:{target}:{verb}`,
`admin:{capability}`. Empty scope set = inherit full user
permissions. Non-empty = AND with user role at the gate.
Wildcard targets allowed (`repo:*:read`); write implies read.

**D-4 — Optional `expiresAt` + 90-day idle cutoff for
no-expiry tokens.** Operators who want hard expiry set it
explicitly; otherwise the `TokenIdleCutoffSweeper` revokes
abandoned tokens after 90 days idle (counted from
`lastUsedAt` if present, else `createdAt`).

**D-5 — SSH-signature auth via HTTP Signatures, NOT SSH
protocol.** Avoids running an SSH server (port 22, key
exchange, channels). The signature scheme: caller signs
`{method}\n{path}\n{Date-header}` with their ed25519 key,
sends in `Authorization: Signature keyId="<fp>",signature="<b64>"`.

*Plan deviation*: WWW-Authenticate challenge/response.
Initial ship is the simpler single-request shape with a
±5-minute Date freshness window. The challenge/response
variant is a future enhancement when replay-window matters.

**D-6 — Key types: ed25519, RSA 2048+, ECDSA P-256.**
Registration accepts all three; signature verification ships
ed25519 only on first ship. RSA + ECDSA verification is a
follow-on (the storage layer accepts the keys; the verifier
returns `UnsupportedAlgorithmException` until implemented).

**D-7 — Fingerprint is globally unique across users.**
Registering a public key whose fingerprint is already on file
(for any user) returns 409. Prevents cross-user impersonation
via shared key.

**D-8 — Admin can list + revoke any user's tokens/keys but
cannot mint on behalf of another user.** Audit clarity:
mint = "Alice created this token", not "admin created this
token for Alice". An admin who needs to issue a token for a
service account uses `sudo -u alice` outside the API
(impersonation is operationally rare).

**D-9 — Disabling a user invalidates all their tokens + keys
atomically.** `users.disabled` is the master switch. The
auth filters check `account.disabled()` and pass-through if
true. The `TokensStore.deleteAllByUser` + `KeysStore.deleteAllByUser`
primitives cascade when an admin disables/deletes a user.

## Consequences

**Operational wins:**
- CI runners can hold a `repo:foo:write`-scoped PAT, leaked
  compromise is bounded to that repo
- SSH-key auth removes passwords from disk on CI boxes (the
  standard Git-server pattern)
- Per-credential revocation: one CI runner compromise → one
  token revoke, no others affected

**Operational costs:**
- Operators have two more things to inspect (`/account/tokens`,
  `/account/keys`) plus admin variants
- Idle-cutoff sweeper runs every 6h — a tiny load
- Two new CF families in the shared auth RocksDB

**Known follow-ons:**
- **CLI client (`prolly sync --key=`).** Out of scope for
  this plan; the signature scheme is documented above so a
  future PR adds the flag without server changes.

**Resolved follow-ons:**
- ~~RSA + ECDSA signature verification.~~ Landed 2026-05-25.
  `ssh-rsa` verifies SHA256withRSA per RFC 8332
  (`rsa-sha2-256`); `ecdsa-sha2-nistp256` verifies
  SHA256withECDSA with ASN.1 DER signatures.
- **Nonce-challenge for replay protection.** Today's ±5-minute
  Date window catches most replay attempts. A nonce-challenge
  variant would close the residual window.
- **SPARQL backing.** Per the cohesive sparql-mode follow-on
  (CLAUDE.md), tokens + keys would move to named graphs when
  that plan ships.

## Cross-links

- [ADR-0013 (user accounts)](0013-user-accounts-and-authenticated-staging.md)
- [ADR-0014 (auth graph gates)](0014-auth-graph-write-read-gates.md)
- [ADR-0015 (auth backend choice)](0015-auth-backend-choice.md)
- [ADR-0017 (org namespace + permission cascade)](0017-org-namespace-and-permission-cascade.md)
- plans/auth-tokens-and-ssh-keys.md
