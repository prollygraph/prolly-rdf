# Security policy

## Reporting

Please report suspected vulnerabilities **privately** via GitHub's security advisories
("Security" tab → "Report a vulnerability") rather than a public issue. If that path is
unavailable, open an issue that says only "security — requesting a private channel"
without details, and a maintainer will arrange one. (Same channel as
[prolly-core](https://github.com/prollygraph/prolly-core/blob/main/SECURITY.md) — one
family, one process.)

## The trust model, honestly stated

This ring builds RDF storage on content-addressed bytes. Content addressing verifies
that bytes match their *name* — it does **not** make incoming bytes trustworthy before
that check happens (see
[the untrusted-byte boundary](docs/foundations/the-untrusted-byte-boundary.md) for the
full model; the engine-side parsers it describes are hardened and in scope in the
prolly-core repo). Ring-specific surfaces worth a researcher's attention:

- The term/tuple decode paths (`prolly-codec` — `TermCodec`, `SpocKey`) — these parse
  stored bytes back into RDF values; the store is normally self-written, but a copied
  or synced store directory is a plausible attack vector.
- RDF and SPARQL text parsing is **Eclipse RDF4J's (Rio + the query parser)** by
  deliberate reuse-the-hardened-parser policy — issues there belong upstream, but
  reports about how this ring *drives* those parsers are welcome here.
- The HTTP server product is **not in this repo** (it lives in the private monorepo);
  reports about a deployed server are still welcome via the same channel and will be
  routed.

## Scope

The seven Maven modules of this repo are in scope. Pre-1.0 caveat: the on-disk format
is not stable; "old store directories can't be read after a format change" is expected
behavior, not a vulnerability.
