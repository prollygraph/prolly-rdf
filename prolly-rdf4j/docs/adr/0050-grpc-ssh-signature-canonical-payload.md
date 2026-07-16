
# ADR-0050: gRPC SSH-signature canonical payload

## Status

Accepted, 2026-06-07. Guides `plans/grpc-versioning-service.md`.
Refines [ADR-0049](0049-grpc-versioning-authentication.md) (which deferred SSH-signature over gRPC
to "the analogous authenticator extraction").

## Context

ADR-0049 wired Personal-Access-Token authentication on the gRPC versioning face and explicitly
punted the second machine-to-machine credential — SSH-signature auth — to a follow-on. This ADR
decides the one genuinely new question that follow-on raises: **what does a gRPC caller sign?**

SSH-signature auth proves identity by signing a per-request *canonical string* with a registered
private key; the server recomputes the same string and verifies. The signature verifier
(`SshSignatureVerifier.verify(openSshLine, payload, signatureB64)`) is already fully
transport-agnostic — it verifies a signature over an *arbitrary* payload string. So is the store
lookup (`KeysStore.findByFingerprint`) and the owner resolution (`UserStore.findUser`). The
**only** transport-specific piece is the canonicalization: how a request becomes the signed
string.

The REST face (`SshSignatureAuthenticationFilter`) signs `<method>\n<path>\n<Date>` — the HTTP
verb, the request URI, and the literal `Date` header (checked within ±5 min to mitigate replay).
gRPC has **neither an HTTP verb** (every gRPC call is an HTTP/2 POST — the field would be a
meaningless constant) **nor a `Date` header** (gRPC carries metadata, not HTTP request headers).
So the REST canonicalization cannot be copied; a gRPC-native one must be defined. And once a
client signs with it, the string is a **wire contract** — changing it silently breaks every
signing client — which is why this is decided in an ADR before any client depends on it.

Two properties the canonical string must have, both already true of the REST one:

- **Bind the signature to the specific operation**, so a signature captured for a read cannot be
  replayed against a write. REST gets this from the path; gRPC's analog is the *full method name*
  (`<package>.<Service>/<Method>`), which is literally the HTTP/2 `:path` of a gRPC call.
- **Bind it to a freshness window**, so a captured signature expires. REST gets this from the
  `Date` header; gRPC needs a chosen metadata key carrying the same ISO-8601 instant.

## Options

The axis that decides it is the **first field** of the three-field, newline-joined string (the
verifier and the ±5 min freshness window are shared regardless):

| Option | Canonical string | Mirrors REST shape | Domain separation REST↔gRPC | First field meaningful for gRPC |
|---|---|---|---|---|
| **A** — mirror REST verbatim | `POST\n<fullMethod>\n<ts>` | identical 3-field | weak (only the path field differs) | no — `POST` is a constant filler |
| **B** — two-field gRPC-specific | `<fullMethod>\n<ts>` | diverges (2 vs 3 fields) | incidental (field count differs) | n/a — field dropped |
| **C** — transport-tagged | `grpc\n<fullMethod>\n<ts>` | same 3-field structure | **explicit** (first field is the transport) | **yes** — names the transport |

## Decision

**Option C.** The gRPC canonical payload is `grpc\n<fullMethodName>\n<timestamp>`:

- **D-1. First field `grpc` — a literal transport tag.** It keeps the three-field, newline-joined
  structure REST already uses (so a client library shares the *shape*), while replacing REST's
  meaningless-for-gRPC verb field with something true. Its real job is **domain separation**: a
  signature minted for a REST `GET\n/path\n<date>` can never verify a gRPC call and vice-versa,
  because the first field differs by construction. Cheap, defensive, and removes any cross-transport
  replay ambiguity rather than relying on the path/method-name happening to differ.
- **D-2. Second field `<fullMethodName>`** — `MethodDescriptor.getFullMethodName()`, e.g.
  `com.earasoft.prolly.rdf4j.grpc.versioning.ProllyVersioning/Commit`. The gRPC analog of REST's
  path; binds the signature to the exact RPC, so a signature for one verb can't be replayed against
  another.
- **D-3. Third field `<timestamp>`** — an ISO-8601 instant carried in a dedicated `prolly-timestamp`
  metadata key (namespaced like the existing `prolly-repo` routing key, not the bare `date` key
  which risks collision with HTTP/2 internals). Verified within **±5 minutes** of server time — the
  *same* window and policy as REST, shared in one place so the two faces cannot drift.
- **D-4. The credential rides `authorization: Signature keyId="<sha256-fp>",signature="<b64>"`** —
  the same scheme string and the same `keyId`/`signature` parameter parsing as the REST filter, so
  the `authorization` metadata is scheme-discriminated (`Bearer` → PAT, `Signature` → SSH, `Basic` →
  `UNAUTHENTICATED`) exactly as a single HTTP `Authorization` header would be.
- **D-5. The policy is a shared `SshAuthenticator` in `prolly-platform`** (mirroring `PatAuthenticator`
  from ADR-0049 D-1): it owns the freshness window, the store lookups, the verify call, the
  owner-`disabled` check, and the last-used touch. Only the *payload string* is built by each
  transport and passed in. The REST filter is repointed to delegate to it (no behaviour change,
  re-verified by the existing auth battery), so REST and gRPC SSH validation have one definition —
  the same drift-elimination argument ADR-0049 made for PAT.
- **D-6. A missing/invalid signature → anonymous, not an error** (consistent with ADR-0049 D-3): the
  `ApiKeyServerInterceptor` is the transport gate and the per-verb role checks are the authority; an
  unauthenticated caller simply sees only what an anonymous caller may. (`Basic` remains the one
  scheme that is actively rejected, per ADR-0049 D-4.)

## Consequences

- **Positive:** SSH-signature is a real second machine-to-machine credential on the gRPC face;
  `currentPrincipal()` resolves for it. The shared `SshAuthenticator` means REST and gRPC SSH
  validation cannot diverge, and a small duplication (freshness + verify + owner check) is *removed*
  from the REST filter, not added. The transport tag closes cross-transport replay by construction.
- **Negative / cost:** the canonical string is now a frozen wire contract — a future change owes
  signing clients a migration. The signed scope does **not** include the target repo
  (`prolly-repo` metadata): within the ±5 min window a captured signature could be replayed against a
  *different* repo, but the per-verb role gate re-checks the principal's permission on the *resolved*
  repo, so this grants no privilege the caller didn't already hold. This matches REST's threat model
  exactly (REST's path includes the repo, but its ±5 min same-request replay is equally open) — it is
  parity, not a new gap, and is named here so it isn't mistaken for stronger-than-it-is.
- **Neutral / punted:** a **production client-side SSH signer** (load an OpenSSH private key, build
  the canonical string, sign, attach the metadata) is out of scope here — the server-side acceptance
  is verified with an in-test ed25519 signer. PAT scope narrowing stays at the role gate, unchanged.

## Follow-up / future work

- **Client-side SSH signer — DONE (2026-06-08).** `SshSignatureClientInterceptor` signs each call
  over this canonical string (via the shared `GrpcSignatureScheme`), proven by a client-signs →
  server-verifies round-trip test. It holds a `java.security.PrivateKey`. What remains is *not* the
  signer but its consumers: (a) an OpenSSH/PKCS#8 private-key *file* loader, and (b) a gRPC client
  command surface in `prolly-cli` to carry a `--key=` flag — `prolly-cli` has no gRPC client today,
  so both are a separate plan, not a thin layer. Deferred deliberately to avoid building a key-file
  loader with no caller.
- If repo-bound replay within the freshness window is ever judged unacceptable, add the
  `prolly-repo` value as a fourth signed field — but that couples signing to routing and should
  follow a real threat, not precede one.

## Open questions

- None at write time.
