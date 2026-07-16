
# ADR-0013: User accounts and authenticated staging

## Status

Accepted, 2026-05-21. Drives `plans/user-accounts.md` (to-be-drafted).

## Context

### The current state

prolly-rdf4j currently has TWO weakly-coupled identity mechanisms:

1. **Shared-secret API key** (`prolly.rdf4j.api-key`) — when set,
   `ApiKeyAuthInterceptor` requires the header on POST/PUT/DELETE
   methods. Used by the sync surface. **One key for the whole
   server; not per-user.**
2. **Self-asserted username** — clients send `X-Prolly-User:
   <typed-name>` to identify themselves to the staging endpoints.
   `StagingUser.java` resolves to `staging/<typed-name>` branch.
   **No verification at all — anyone can claim any name.**

`StagingUser.java` line 20 calls this out explicitly:
> "should be tightened (JWT, Spring Security) before production"

### Why decide now

The system is becoming **usable** — schema editor and instance
editor both went GA in the same arc that produced this ADR. Both
write surfaces flow through staging branches per
`plans/staging-on-editors.md`. The next operator-visible step
is: "when Alice edits, her edits are AS Alice, not as whoever-typed-
'alice'-in-the-username-field-this-session."

### Strategic position

- **Pre-1.0** — clean breaks are allowed; no backwards-compat shims.
- **R&D + regulator target deployments** — audit-trail matters; reads
  + writes both need attribution.
- **Internal-team trust model** — not public-facing; SSO-eligible
  populations (enterprise IT, government IDP).
- **Storage commitment** — RocksDB is already the persistence layer;
  adding a separate auth DB would be operational overhead.

### Constraints

- Spring Boot 4.0.6 — Spring Security 6.x is available.
- The Java module is `prolly-rdf4j-rest` (the REST surface).
- The frontend is Angular 21 with `ApiService` mediating all HTTP.
- Per-user staging branches are the load-bearing motivation;
  staging branch resolution must derive from the authenticated
  identity, NOT a client-supplied header.

## Options

### Option A — HTTP Basic + Spring Security (selected)

Browser prompts for username + password on first auth-required
request; subsequent requests carry the `Authorization: Basic
<base64>` header. Server's Spring Security filter chain validates.
No login page; no session cookie; no CSRF concerns
(every request re-auths from the header). User store is
**RocksDB-backed** via a custom `UserDetailsService` reading from
a dedicated column family.

### Option B — Session cookie + custom login page

Operator submits username+password to `POST /auth/login`; server
sets a session cookie; subsequent requests carry the cookie.
Server holds session state (in-memory or RocksDB-backed). Needs
CSRF token plumbing because cookies fire on every request.

### Option C — JWT (stateless tokens)

`POST /auth/login` returns a signed JWT; client stores in
localStorage or sends in `Authorization: Bearer <jwt>` header.
Server stateless on auth. Hard to revoke (need a denylist or
short-lived tokens with refresh dance).

### Option D — OIDC delegation to external IdP

`/auth/oidc/login` redirects to Google / Microsoft / Keycloak.
Token comes back via OAuth2 callback. Spring Security has
out-of-the-box support. Eliminates password management.

### Comparison

| Option | Code added | Operator UX | Server state | Revocation | Audit | Path to enterprise |
|---|---|---|---|---|---|---|
| **A — HTTP Basic** | smallest | browser prompt (built-in) | none (each request re-auths) | trivial (delete user) | yes (every request authed) | Spring filter chain extends to OIDC later |
| B — Session cookie | medium | login form + logout button | session table | trivial (invalidate session) | yes | extends to OIDC similarly |
| C — JWT | medium | login form + token storage | none + maybe denylist | hard (short TTL + refresh) | yes | extends to OIDC |
| D — OIDC | largest upfront | redirect dance | OAuth client state | external IdP handles | depends on IdP | already enterprise-ready |

### Why Option A

- **Smallest code surface**: Spring Security's HTTP Basic filter is
  one bean configuration; no login page to design + test; no CSRF
  token plumbing; no session table.
- **Browser-native UX**: the credential prompt is a known shape;
  password managers fill it correctly.
- **Audit-friendly**: every request carries the credential, so the
  authenticated principal is available at every endpoint without
  session lookup.
- **Forward path**: Spring Security's filter chain composes. Adding
  OIDC later doesn't replace Basic — it adds another mechanism;
  operators can opt into either per deployment.

The runner-up tradeoff was Option B (session cookie): the login
page would be a more polished UX but doubles the code surface
(login flow + session state + CSRF) for v1. Basic ships sooner.

## Decision

**D-1. HTTP Basic authentication via Spring Security.**
Wire Spring Security's `HttpSecurity` to require Basic auth on
all endpoints. No login page; no session cookie; no JWT in v1.

**D-2. User accounts stored in the same RocksDB instance, separate column family.**
A dedicated `users` column family (or keyspace) keyed by username,
value is a serialized `UserAccount {username, passwordHash, createdAt,
disabled}` record. Reuses the existing RocksDB lifecycle
(open/close/backup); does NOT mix into the versioned RDF triple
store. The `UserDetailsService` implementation reads from this CF;
versioning of user accounts is **out of scope** (an account is
either active or disabled — its history doesn't need a Merkle DAG).

**D-3. All endpoints require authentication.**
Reads, writes, sync, staging — every HTTP request requires Basic
auth. Anonymous access is dropped. Aligns with regulator-deployment
audit requirements.

**D-4. All authenticated users have equal write privileges (v1).**
Once authenticated, an operator can stage, commit, branch, delete
freely. The commit author + staging branch derive from the
authenticated principal. Role-based authorization + per-branch
ACL are deferred to a future ADR.

**D-5. Username from authenticated session replaces `X-Prolly-User`.**
The `X-Prolly-User` header is deprecated + ignored. Staging branch
resolution derives the username from `SecurityContextHolder.getContext().getAuthentication().getName()`.
Per the pre-1.0 / no-backwards-compat rule, the client-supplied header
is dropped cleanly (not silently honored as a fallback).

**D-6.** *(Superseded by D-14)* Sync's `prolly.rdf4j.api-key` mechanism is retained as a separate machine-to-machine credential.
The shared secret continues to authorize sync requests (the gRPC /
HTTP machine-to-machine path). For human-operator workflows, Basic
auth is the path. The two coexist via separate filter beans —
sync's `ApiKeyAuthInterceptor` runs first; if no key was presented,
the Spring Security filter chain takes over.

**D-7. Password hashing via BCrypt (Spring Security default).**
BCrypt with cost factor 10 — Spring's `BCryptPasswordEncoder` ships
with sensible defaults. Argon2 is the marginal-best choice, but
BCrypt is the well-trodden Spring path and adequate for the
attacker model (no public exposure expected).

**D-8. First-run bootstrap via a server-side seeded `admin` account.**
On first server start with an empty users CF, seed an `admin`
account with a password from `prolly.rdf4j.bootstrap-admin-password`
config (required at first run; cleared from config after the
account is created). Operators then create additional accounts via
a CLI or admin endpoint. Without this, the server would be
inaccessible.

**D-9. Account-management surface (CRUD) — v1 minimum is CLI + admin endpoint.**
`POST /auth/users {username, password}` — admin-only.
`DELETE /auth/users/<username>` — admin-only.
`POST /auth/password {oldPassword, newPassword}` — self-service.
Frontend can add a thin "manage users" page later; for v1, CLI
suffices for ops.

**D-10. Staging branches keyed by authenticated username — same naming as today.**
`staging/<username>` continues; the only change is the source of
`<username>` (now authenticated, not header-typed). Existing
`staging/<typed-name>` branches on first-run servers stay where
they are; future commits to those branches require the typed name
to match an authenticated account.

## Consequences

### What this enables

- **Real attribution** on every commit — operator identity is
  authenticated, not self-asserted.
- **Audit trail** — every write request is authenticated; logs
  capture `(principal, action, target)` reliably.
- **Per-user staging** — `staging/alice` is unambiguously Alice's
  branch; no risk of Bob accidentally claiming "alice" by typing
  it.
- **Forward path to OIDC** — Spring Security's filter chain
  composes; an OIDC filter can be added without ripping out Basic.

### What this costs

- **Frontend churn** — the typed-username input on `/update`'s
  staging panel goes away. Operators are logged in (via browser
  Basic prompt) before they can do anything.
- **Server module** — new column family (users), new
  `UserDetailsService` implementation, new Spring Security
  configuration, new account-management endpoints, new bootstrap
  flow. Probably 500-800 lines of Java.
- **Test setup** — every existing test that hits an endpoint
  needs auth in its setup. ~50 test files in
  `prolly-rdf4j-rest/src/test` + every e2e spec in
  `prolly-rdf4j-e2e/tests` carrying the credential.
- **Migration** — existing operators using the typed-username flow
  must create an account on first start (D-8 bootstrap admin handles
  this); their typed-username history in `staging/<typed-name>`
  becomes orphaned unless they create an account with the same name.
- **Spring Security CSRF default** — needs to be **disabled for the
  REST endpoints** because Basic auth doesn't need it (and CSRF on
  REST APIs is a footgun). Configured explicitly.

### What we're punting (deferred to a future ADR / plan)

- **Roles + per-branch ACL** (admin/editor/reader; branch-level
  permissions). v1 is all-or-nothing.
- **OIDC / SAML integration** for enterprise SSO. Spring Security's
  filter chain composes; add as a follow-on.
- **MFA / TOTP** — would need a separate plan.
- **Password reset email flow** — out of scope. CLI password reset
  for v1.
- **Login throttling / rate limiting** — out of scope for v1; add
  if attacker volume justifies.
- **Account deletion preserves vs purges staging branches** — open
  question (Q1 below).

### Behavior changes operators will notice

- First time hitting any URL → browser Basic-auth prompt.
- The `/update` staging-panel's username input disappears (or
  shows the authenticated username as a read-only label).
- The staging-badge on the topbar can show "as alice" alongside
  the count (per the open question on `plans/staging-on-editors.md`).
- The commit-author metadata on `/commits` shows the authenticated
  name reliably.

## Follow-up / future work

- **ADR-0014 (when justified)**: roles + per-branch ACL.
- **ADR-0015 (when justified)**: OIDC integration.
- **Plan**: `plans/user-accounts.md` driven by this ADR — adds the
  Spring Security wiring, the RocksDB users CF, the bootstrap flow,
  the frontend Basic-auth handling, and the migration off
  X-Prolly-User. Probably ~25 steps across 6 phases.

## Decisions (extended)

**D-11. Account-deletion semantics — rename + configurable GC retention.**
When `DELETE /auth/users/alice` fires, `staging/alice` is RENAMED to
`staging/_deleted_alice_<unix-timestamp>` rather than deleted.
The renamed branch is preserved for a **configurable retention
period** (`prolly.rdf4j.deleted-user-branch-retention-days`,
default 90), after which the existing
`StagingGcService` purges it.

**Why this shape:**
- **Audit trail preserved** — the deleted operator's in-progress
  work (potentially 5-50 micro-commits on `staging/alice`) stays
  inspectable during retention. Forensic / compliance lookup
  works: `git log staging/_deleted_alice_<ts>` shows what Alice
  was doing at termination.
- **Identity collision resolved** — the slot `staging/alice` is
  immediately free for a new user (admin recreating the account,
  or a different person named alice joining). The new alice can't
  see the old alice's drafts.
- **Configurable retention matches compliance variability** —
  retention duration is a per-deployment compliance question
  (see "Audit retention frameworks" below), not a one-size-fits-all
  hardcode. Default 90 days is a reasonable starting point;
  operators set per-deployment.
- **GC reuses existing infrastructure** — `StagingGcService` already
  handles staging-branch lifecycle (7-day inactivity + 24h activity
  window per the audit). The deleted-user branches plug in as a
  second purge category with a longer retention.

### Audit retention frameworks (rationale for D-11's configurability)

Different regulatory regimes prescribe different retention windows
for account-lifecycle and audit-trail data after termination:

| Framework | Retention rule (approximate) |
|---|---|
| HIPAA (US healthcare) | 6 years from creation or last use; termination doesn't reset |
| SOX (US public-company financials) | 7 years for records + audit trails |
| GDPR (EU privacy) | Opposite pull — "right to be forgotten"; typical resolution is pseudonymization (audit kept, identity scrubbed) |
| NIST 800-53 | 1-3 years general logs; 5-7 years for account-lifecycle events |
| PCI DSS (payment cards) | 1 year minimum, 3 months immediately accessible |
| FDA 21 CFR Part 11 (pharma) | Record lifetime + extension — often decades |
| ISO 27001 | "Appropriate retention" — orgs typically default 1-3 years |

**The retention window varies by 30× across these frameworks**
(90 days → decades). Hardcoding a single value would force
deployment-specific forks; the config flag makes prolly-rdf4j
deployable into any compliance regime without code change.

The **GDPR pseudonymization case** is addressed separately in D-16
below — supporting "delete identity, keep audit" requires a
separate audit-log scrub mechanism beyond just renaming the
staging branch.

**D-12. Bootstrap admin password — WARN logging, no force-change-on-first-login.**
The server logs a WARN line at every startup if the bootstrap
password from `prolly.rdf4j.bootstrap-admin-password` is still
in use (i.e., the admin account's stored hash matches that
config value). Operators see the warning in their logs;
rotating is their choice + their compliance responsibility.
Avoids the complexity of a force-change flow (state-tracking,
"please change password" UI, blocking otherwise-valid sessions
on first-login).

*Why no force-change:* the bootstrap is a one-time operator-driven
event; the warning ensures awareness without adding a code path
(force-change-flow + state-tracking + login page logic) that
wouldn't be exercised again after initial setup.

**D-13. Configurable anonymous-read mode for public deployments.**
A new config flag `prolly.rdf4j.public-reads=true` (default
`false` per D-3). When `true`, GET endpoints — `/sparql`,
`/sparql/commits`, `/sparql/branches`, `/sparql/version`,
`/health` — accept unauthenticated requests. POST/PUT/DELETE
still require auth regardless. **Read-only public deployment
shape**: think DBpedia, Wikidata SPARQL endpoint, regulator
publishing reference data. Auth-required deployments (the
default + most internal deployments) are unchanged.

*Why a single flag, not per-endpoint:* per-endpoint
configurability is the "Q3 option C" — flexible but
mentally taxing. A single deployment-shape flag captures the
real-world distinction (public-read-only deployment vs
internal-authenticated deployment) without endpoint enumeration.

**D-14. Phase out the shared-secret sync API key in favor of service accounts.**
The current `prolly.rdf4j.api-key` mechanism (one shared
secret for the whole server) is replaced by **service
accounts** — regular user accounts with non-human names
(`service_replica1`, `service_backup`) that authenticate via
HTTP Basic. Sync's machine-to-machine path uses
`Authorization: Basic <service-account-creds>` instead of
`X-API-Key: <secret>`. Single auth path; no parallel filter
chain.

*Migration:* per the project's pre-1.0 / no-backwards-compat
rule, the API key is **dropped cleanly** — not silently
honored. Existing deployments using the API key must create a
service account and update their sync configuration.
`ApiKeyAuthInterceptor.java` is deleted; the
`prolly.rdf4j.api-key` config setting is removed.

*Why service accounts not API keys:* one identity model, one
audit trail. Today's shared-secret approach can't tell two
replicas apart; service accounts can ("replica1 fetched at
14:23 / replica2 fetched at 14:24"). The auth attack surface
shrinks (no parallel secret to compromise alongside user
passwords).

**D-15. Session-on-top-of-Basic for the web UI; pure Basic for CLI / sync.**
The Basic-auth-only footgun (browsers cache credentials
aggressively; no clean logout) is resolved via a **hybrid
session layer** for browser clients:

- **First request**: browser prompts for username + password
  (standard Basic auth).
- **Server validates against the RocksDB user store**, then
  issues an opaque session cookie (`PROLLY_SESSION`, HttpOnly,
  Secure, SameSite=Lax). The cookie maps to a server-side
  session record (RocksDB column family `user_sessions`) keyed
  by random 256-bit token, valued with `{username,
  issuedAt, lastUsedAt, ipHint}`.
- **Subsequent requests**: server checks the cookie first; if
  valid + not expired (configurable TTL, default 24h sliding),
  the request is authenticated. Otherwise falls back to Basic
  auth from the `Authorization` header.
- **Logout endpoint** (`POST /auth/logout`): invalidates the
  session cookie server-side; responds 401 with a
  `WWW-Authenticate: Basic realm="prolly-rdf4j (logged out)"`
  header so some browsers re-prompt on the next protected
  request. Frontend also issues a deliberately-bogus
  `Authorization: Basic xxx:yyy` request afterward to flush
  the browser's cached credential — the well-known workaround.
- **Account-disabled state**: when a logged-in operator's
  account is disabled mid-session, the next request returns
  403 with a JSON body `{error: "account_disabled"}`. Frontend
  surfaces a clear "Your account is disabled, contact admin"
  page rather than looping on auth.

**CLI + sync clients** (non-browser) use **pure Basic** — no
cookies, no logout endpoint. Spring Security's filter chain
distinguishes them by `User-Agent` or by a path prefix; web
UI requests get the cookie path, programmatic clients get
pure Basic.

*Why this hybrid not pure Basic:* pure Basic's logout problem
is a real UX regression — operators can't sign out, period.
Adding a session layer is ~150 lines of Spring Security
configuration but solves the problem the modern web expects
solved.

*Why not pure cookies (drop Basic):* CLI / sync paths need a
non-cookie credential mechanism; Basic is the simplest such
mechanism for HTTP. The hybrid keeps both paths working.

**D-16. GDPR pseudonymization — out-of-band identity-pseudonym map.**
For deployments subject to GDPR's right-to-be-forgotten, a
separate workflow scrubs the operator's identity from
displayed audit data while preserving the underlying commit
content + hashes (re-hashing would break downstream refs).

- **New column family** `user_pseudonyms`: maps `username →
  pseudonym_id` (e.g., `alice → user_42891`). Admin-only
  access; this map is the link-back between identity and
  audit history and is itself privacy-sensitive.
- **"Forget alice" workflow** (admin endpoint):
  1. Generate a random opaque pseudonym ID (e.g., `user_42891`)
  2. Write the mapping into `user_pseudonyms`
  3. Delete the user account itself (alice can't log in)
  4. Rename staging branch to
     `staging/_pseudonym_user_42891_<timestamp>` (variant of
     D-11's rename)
- **UI substitution layer**: every render of an author name
  reads through the pseudonym map. /commits shows
  `user_42891` instead of `alice`; the staging-branch list
  shows `user_42891`'s history; the editor's "Edited by"
  affordances substitute. Commit content + diffs unchanged;
  hashes unchanged.
- **Out-of-scope for v1**: a CLI command + admin endpoint
  for the forget operation; the UI substitution implementation
  across all rendering surfaces; whether commit-message bodies
  that mention names (e.g., "Updated Alice's record")
  need separate scrubbing (probably yes — captured as Q7 in
  the follow-up ADR).

*Why out-of-band not re-hash:* re-hashing commits would
invalidate every downstream reference (parent pointers,
staging-branch tips, commit IDs in /commits URLs). The
pseudonym map preserves the Merkle DAG intact while satisfying
the displayed-identity requirement.

*Why deferred-implementation beyond v1:* the substitution
layer touches every author-rendering surface
(/commits, /branches, /sync, editor pages, staging UIs).
That's a non-trivial follow-on plan, not v1 of accounts.
v1 ships the COLUMN FAMILY + the workflow design; the UI
substitution lands in a follow-on plan referenced from this
decision.

## Open questions

**Q1.** *(Resolved as D-11 above)* Was: account-deletion semantics
for orphaned staging branches. Resolution: rename to
`staging/_deleted_<username>_<timestamp>` with configurable
GC retention.

**Q2.** *(Resolved as D-12)* Was: should the bootstrap admin
password require force-change-on-first-login? Resolution: no — WARN
logging at every startup is sufficient. Force-change adds
state-tracking complexity for a one-time operator-driven event.

**Q2-original. Bootstrap admin password — config rotation.**
After first-run uses the bootstrap password, do we require the admin
to change it? Force-change-on-first-login is the standard pattern;
adds complexity. Suggestion: log a `WARN` at every startup if the
bootstrap password is still in use; let operators decide.

**Q3.** *(Resolved as D-13)* Was: should there be a deployment-shape
flag for anonymous reads (public-deployment use case)? Resolution:
yes — `prolly.rdf4j.public-reads=true` flag added, defaults `false`.

**Q3-original. Anonymous-readable schema endpoint?**
Public RDF stores (e.g., DBpedia) expose `/sparql` to the world.
D-3 says all endpoints require auth, but if there's ever a "public
read-only" deployment shape, we'd want this configurable. Defer to
a deployment-profile config flag (`prolly.rdf4j.public-reads=true`)
when the use case materializes.

**Q4.** *(Resolved as D-14)* Was: phase out the shared-secret API
key in favor of service accounts? Resolution: yes — API key dropped
cleanly per the pre-1.0 / no-backwards-compat rule. Service accounts
(non-human-named users with Basic credentials) replace it for
machine-to-machine paths.

**Q4-original. Sync API key — phase out in favor of service accounts?**
D-6 keeps `prolly.rdf4j.api-key` as a machine-to-machine credential.
But once we have real user accounts, a "service account" (a user
with a non-human name + a long-lived password) does the same job
without the shared-secret risk. Defer to ADR-0014 when sync's auth
gets revisited.

**Q5.** *(Resolved as D-15)* Was: how does the frontend handle 401
from a stale Basic session? Resolution: session-on-top-of-Basic
hybrid — opaque session cookie issued after first Basic auth;
logout endpoint invalidates it server-side + realm-rotation triggers
browser re-prompt. CLI/sync paths use pure Basic (no cookies).

**Q5-original. How does the frontend handle 401 from a stale Basic session?**
Browser Basic auth caches the credential; if the server invalidates
(admin disabled the account), the browser keeps sending the bad
credential. v1 likely surfaces an error toast + a "logged out" UI
state; full re-auth requires closing + reopening the browser tab
in current browsers. Document this limitation explicitly.

**Q6.** *(Resolved as D-16)* Was: should GDPR pseudonymization be
supported? Resolution: yes — out-of-band identity-pseudonym map in
a dedicated RocksDB column family. UI substitution layer reads
through the map at render time. v1 ships the column family + the
workflow design; the UI substitution itself lands in a follow-on
plan because it touches every author-rendering surface.

**Q6-original. GDPR pseudonymization — separate from D-11's preserve-and-GC.**
D-11 preserves the deleted operator's staging branch for audit; the
commits ON that branch (and any commits the operator made to main
during their tenure) still carry their authored-by-name. GDPR's
"right to be forgotten" + the broader principle that EU operators
can request identity scrub require a different mechanism: scrub the
operator's name from commit metadata while keeping the commit hash
+ content + diff intact. Conceptually: replace `alice` with
`user_42891` in the author field across history. This is **not
trivial** because the commit hash includes the author; scrubbing
would require either (a) re-hashing — breaks downstream refs; (b)
maintaining an out-of-band identity-pseudonym map that the UI
substitutes at render time — preserves hashes but adds a layer of
indirection. Deferred to a follow-on ADR when a GDPR-regulated
deployment surfaces. For now: D-11's rename-and-GC handles audit;
pseudonymization is a separate compliance path.
