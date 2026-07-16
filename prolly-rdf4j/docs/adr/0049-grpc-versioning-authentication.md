
# ADR-0049: gRPC versioning face authentication

## Status

Accepted, 2026-06-07. Guides `plans/grpc-versioning-service.md`.

## Context

The gRPC versioning face (`ProllyVersioningService`) is becoming multi-tenant (ADR-0048): the
repo-lifecycle verbs scope their behaviour by the *caller* — `CreateRepo` stamps `createdBy`,
`GetRepo`/`ListRepos` show an admin everything but everyone else only public repos, `DeleteRepo`
records the audit actor. So the service needs the caller's identity, a `Principal` (username +
admin flag), per call.

Today it does not have one. The gRPC face authenticates only with a **shared `x-api-key`**
(`ApiKeyServerInterceptor`) — a single secret that proves "the client holds the key," not *which
user*. So `currentPrincipal()` is a stub returning `ANONYMOUS`, and the gRPC repo verbs run as the
system user regardless of who calls. This decides how the gRPC face establishes a real per-user
identity.

A grounding of the auth modules (the deciding constraint) — the module graph is
`prolly-platform → {prolly-rdf4j → prolly-rdf4j-grpc, prolly-platform-rest} → prolly-rdf4j-rest`,
so the gRPC module can only reach `prolly-platform` and below:

- **Reachable from gRPC** (`prolly-platform`): the user store (`UserStore.findUser → UserAccount`
  with `isAdmin()`), the token store (`TokensStore.findByHash → Token` with expiry/idle), and SSH
  verification (`KeysStore.findByFingerprint` + `SshSignatureVerifier.verify`). So **Bearer PAT and
  SSH-signature can be validated from the gRPC module.**
- **Not reachable**: HTTP Basic's password check. The BCrypt `PasswordEncoder` is Spring-coupled and
  lives in `prolly-platform-rest`/`prolly-rdf4j-rest` — *above* the gRPC module. The REST credential
  filters (`PatAuthenticationFilter`, `SshSignatureAuthenticationFilter`, the Basic filter) are all
  Spring `OncePerRequestFilter`s in `prolly-platform-rest`, equally out of reach.

There is also a *latent* hazard: if the gRPC face re-implements PAT validation rather than sharing
the REST face's logic, the two validators **drift** — and divergent credential validation is a
security defect.

## Options

| Option | Credential types | Validation source | Coupling / cost | Drift risk |
|---|---|---|---|---|
| **A** — PAT + SSH-signature, re-implemented in gRPC | PAT, SSH | new gRPC-local copy of the checks | low coupling | **High** — two validators to keep in step |
| **B** — PAT + SSH-signature via a **shared validator** in `prolly-platform` | PAT, SSH | one transport-agnostic class both faces use | a one-time auth-layer extraction | **None** — single path |
| **C** — also support Basic over gRPC | + HTTP Basic | extract BCrypt into `prolly-platform`, or inject a rest-provided authenticator bean | touches the password layer / adds runtime indirection | medium |
| **D** — provider pattern (rest supplies the authenticator) | all (incl. Basic) | a rest-layer Spring impl injected into gRPC at runtime | a runtime-bean seam; gRPC auth depends on rest being present | low |

## Decision

**Option B — the gRPC versioning face authenticates via Bearer PAT and SSH-signature, validated by a
transport-agnostic authenticator shared with the REST face; HTTP Basic is *not* accepted over gRPC.**

The deciding tradeoffs:

- **gRPC is the machine-to-machine API; PAT + SSH-signature are the machine-to-machine credentials** (ADR-0019), and
  HTTP Basic is the *human/interactive* credential that belongs to the REST face. A gRPC face that
  takes PAT/SSH and not Basic is principled, not a gap — and it is exactly what the module graph makes
  reachable. So Option C/D's extra machinery buys a use case (interactive Basic over a machine API)
  that should not exist.
- **A shared validator (B over A) removes the drift hazard.** Credential validation must have one
  definition; the gRPC interceptor and the REST `PatAuthenticationFilter` will both call it.

- **D-1. Extract `prolly-platform` authenticators.** A `PatAuthenticator`
  (`Optional<UserAccount> authenticate(String bearerValue, Instant now)` — strip `Bearer `, SHA-256,
  `TokensStore.findByHash`, expiry + idle + owner-`disabled` checks, `UserStore.findUser`) and the
  analogous SSH path. Pure `prolly-platform`, no Spring.
- **D-2. The REST `PatAuthenticationFilter` delegates to the extracted authenticator** — the existing
  auth battery is the gate (no behaviour change). One validation path, REST and gRPC.
- **D-3. A gRPC `AuthServerInterceptor`** reads `authorization` (`Bearer …` / `Signature …`), calls
  the shared authenticator, and binds a `Principal(username, isAdmin)` into
  `GrpcRepoContext.PRINCIPAL`. A missing/invalid credential leaves it **unbound** (anonymous), *not*
  an error — the gate is the existing `ApiKeyServerInterceptor` (transport auth) + the per-verb role
  checks; an anonymous caller simply sees only what an anonymous caller may.
- **D-4. Basic over gRPC returns `UNAUTHENTICATED`** with a message pointing the caller at a PAT — it
  is never silently downgraded to anonymous, so a human mis-using the machine-to-machine face gets a clear signal.

## Consequences

- **Positive:** `currentPrincipal()` becomes real for the credentials a machine client actually uses;
  one validation path means the gRPC and REST PAT checks cannot diverge; no new cross-module coupling
  (the authenticators sit in `prolly-platform`, which both faces already depend on).
- **Negative / cost:** an auth-layer refactor — the REST `PatAuthenticationFilter` is repointed at the
  extracted `PatAuthenticator` (re-verified by the auth tests). A small duplication of the SHA-256 +
  expiry logic is *removed*, not added.
- **Neutral / punted:** **Basic over gRPC is unsupported** (documented; `UNAUTHENTICATED`). The
  PAT **scope** narrowing (`repo:…`/`org:…`/`admin:…` from ADR-0019) is resolved at the existing
  role-authorization gate, not in this interceptor; the interceptor only establishes *who* the caller
  is. Idle-cutoff *touch-on-use* (updating a PAT's last-used timestamp) stays wherever it lives today;
  if the REST filter does it, the shared authenticator does it for both.

## Follow-up / future work

- If an interactive gRPC client ever genuinely needs Basic, revisit with Option C (extract a
  transport-agnostic `PasswordVerifier` into `prolly-platform`) — but only behind a real use case.

## Open questions

- None at write time.
