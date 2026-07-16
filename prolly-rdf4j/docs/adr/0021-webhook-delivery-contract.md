
# ADR-0021: Webhook delivery contract

## Status

Accepted, 2026-05-25. Guides `plans/webhooks.md`.

## Context

prolly-rdf4j ships
[ADR-0020](0020-merge-request-review-workflow.md)'s merge-request
review workflow, but every meaningful integration — CI runs,
chatops bots, audit pipelines, dashboards — depends on the
server proactively notifying external systems of MR state
transitions. Without webhooks the operator's only option is
polling `GET /repos/{repo}/mrs?state=open` which costs every
subscriber a full list-scan per tick.

The webhook surface introduces five concerns that don't have
obvious right answers:

1. **Event surface.** Which transitions emit events, what
   does the wire payload look like, who decides the schema?
2. **Subscription model.** Per-repo vs. per-org vs. global?
   Who can register? Are filters regex or glob or strict
   event names?
3. **Signing.** Which algorithm? What's in the signed scope?
   How do subscribers verify?
4. **Delivery semantics.** Retry on failure? At-least-once?
   Exactly-once? Order? What about the dead-letter case?
5. **SSRF + replay defense.** What stops an attacker from
   registering `http://localhost/admin` as a subscriber and
   pivoting through the server? What stops a captured
   delivery from being replayed?

Each of these has GitHub's webhook contract as a useful
reference but not a perfect fit — prolly is multi-tenant by
ADR-0016 and ADR-0017, has its own auth model
(ADR-0013/0019), and the audit-log surface is more granular
than GitHub's.

## Options

| Option | Event surface | Subscription scope | Delivery |
|---|---|---|---|
| **A — GitHub-style HMAC + at-least-once with retry** (chosen) | Strongly-typed `MrEvent` sealed hierarchy with 6 kinds | Per-repo, REPO_ADMIN gate | At-least-once via durable RocksDB queue; 30s/2m/8m/32m/2h/8h/24h backoff |
| **B — Pub/sub (Kafka, NATS, Redis Streams)** | Event-bus topic per kind | Server-wide stream; subscribers manage their own filters | Exactly-once-ish via consumer offsets; broker handles retry |
| **C — Long-polling on `GET /events?since=…`** | Same `MrEvent` shape but server-pulled | Per-repo, repo-scoped streams | Subscriber-driven cadence; server caches recent N events |

## Decision

**Option A — GitHub-style HMAC + at-least-once with retry.**

The deciding tradeoffs:

- **A vs B:** Option B needs an external broker (Kafka /
  NATS / Redis) running alongside prolly. That's a deploy-
  surface explosion for the v1 webhook story — operators who
  just want "tell my CI when an MR merges" suddenly need to
  stand up Kafka. Option A's durable-RocksDB queue gives the
  same at-least-once + ordered-per-subscriber properties
  without the extra hop. When/if prolly scales past the
  single-process-can-handle-it threshold, the broker version
  becomes a follow-on plan, not a replacement.

- **A vs C:** Polling-based long-poll puts the cadence
  decision on the subscriber and makes the server inherently
  reactive rather than proactive. For "MR merged → run CI"
  that's a hard latency win for push: subscribers don't poll,
  they're called. Plus GitHub-style HMAC interop is "every
  subscriber library already knows this shape"; long-poll
  requires custom client code.

Eight sub-decisions follow, all from `plans/webhooks.md`'s
D-1 through D-10:

**D-1. Per-repo subscriptions, not global.** A webhook is
registered against a specific repo (or composite `{org}/{repo}`
key for org-owned repos per ADR-0017). Cross-repo "fire on
any merge" subscriptions are out of scope — operators who
need them register the same URL against multiple repos. The
SPA settings page makes multi-register trivial. Aligns with
the existing permission model: `REPO_ADMIN` controls webhook
CRUD; a global-webhooks surface would need a new permission
tier.

**D-2. Event filter is glob, not regex.** Patterns like
`mr.*`, `mr.merged`, `*` match the event name. Regex is
overpowered for a small fixed namespace (8-10 event names),
and glob's simpler mental model wins for an admin-facing
config knob. Matcher is ~30 LOC.

**D-3. At-least-once delivery with permanent-failure cap.**
5xx and timeouts retry; 4xx is "subscriber says don't send
this" and stops. Backoff schedule: 30s, 2min, 8min, 32min,
2h, 8h, 24h (7 retries, 8 attempts total, ~35h wall-clock).
Matches GitHub's multi-hour outage tolerance — a deploy that
takes an hour to recover doesn't lose the entire event
burst. After the 7th retry the delivery is marked `dead`.
Bulk `redeliver-dead` endpoint lets operators replay every
dead delivery in one call after a long outage.

**D-4. HMAC-SHA256 with per-webhook secret. Header
`X-Prolly-Signature: sha256=<hex>`.** Matches GitHub's
contract verbatim — subscribers who already wrote
GitHub-style verification code reuse it as-is. Secret is
stored hashed (SHA-256) so administrators can't recover it
via `GET /repos/{repo}/webhooks/{id}`; the GET response
includes `secret_hint` (first 4 chars of the hash) for "yes
you set the right one" recognition.

**D-5. Synchronous publish to durable queue + async
dispatch.** The controller's `bus.publish(MrEvent)` writes
one row to `webhook_deliveries` CF with `status=PENDING` and
returns. The HTTP dispatch (Step 8 — not yet shipped at
v0.X.Y) runs on a background `@Scheduled` tick. Decoupling
via the durable queue means the controller never blocks on
subscriber latency, and a server crash mid-publish doesn't
lose events that already made it to the store.

**D-6. Delivery timeout 10 seconds, NOT configurable per
webhook.** A fast subscriber responds in <1s; the 10s cap
protects against pathological subscribers holding the
dispatch thread. Operators with slow subscribers wrap them
in a 202-returning proxy. Per-webhook timeout knobs add a
debugging surface that isn't worth the flexibility.

**D-7. `webhook.ping` test event bypasses subscriber's
event filter.** A separate event name sent only when an
operator clicks "Test webhook" — never fired by real
activity. Body is `{event: "webhook.ping", repo, timestamp}`.
Test deliveries SKIP the filter — the Test button is a
connectivity check, not a routing check. A subscriber
registered with `mr.opened` still gets the ping. The
delivery response header `X-Prolly-Test: true` lets
subscribers distinguish test from real traffic.

**D-8. 4xx responses log but do NOT auto-deactivate.** A
subscriber returning 404 means "this URL doesn't exist
anymore", but we don't auto-deactivate because 401/403/410
happen transiently during deploys. Admin UI surfaces the
failure rate prominently so operators clean up manually.
Auto-deactivate historically generates more inbound support
tickets than it saves.

**D-9. SSRF defense at registration AND delivery time.**
Block loopback / RFC 1918 / link-local / IPv6 ULA / non-HTTP
URLs by default. Resolution happens at registration AND at
every delivery (DNS rebinding mitigation). Opt-in via
`prolly.rdf4j.webhooks.allow-private-targets=true` for
deployments that legitimately webhook to internal services
(internal-CI tunneling through prolly).

**D-10. Replay defense via timestamp in the signed
payload.** HMAC alone signs the body, not the time. Add the
timestamp to the signed material: signed payload becomes
`<ISO-8601-timestamp> + ".\n" + <body>` (dot+newline
separator prevents length-extension ambiguity). Subscribers
verify signature matches AND timestamp is within ±5 minutes.
The `X-Prolly-Delivery: <uuid>` header is regenerated on
redeliver so subscribers can dedupe replays.

## Consequences

### Operational surface

A `auth.backend=rocksdb` deployment running this contract
ships:

- 6 REST endpoints for subscription CRUD
  (`POST/GET/PATCH/DELETE /repos/{repo}/webhooks[/{id}]`)
- 4 REST endpoints for delivery audit + replay
  (`GET .../deliveries`, single + bulk redeliver,
  `webhook.ping` test)
- 2 new RocksDB CFs (`webhooks`, `webhook_deliveries`)
- 1 `@Scheduled` GC sweep (daily, 30-day retention)
- 1 `@Scheduled` dispatcher (1-second tick) — Step 8, not
  yet shipped at v0.X.Y; PENDING rows accumulate until it
  lands.

Subscribers see traffic shaped like:

```
POST https://my-ci.example.com/webhook HTTP/1.1
Content-Type: application/json
X-Prolly-Event: mr.merged
X-Prolly-Delivery: 4b8c2a91-3d4e-…
X-Prolly-Timestamp: 2026-05-25T12:00:00Z
X-Prolly-Signature: sha256=a3f9d2…

{"event":"mr.merged","repo":"default","mrId":42,
 "actor":"alice","timestamp":"2026-05-25T12:00:00Z",
 "data":{"strategy":"merge","commit":"abc123…"}}
```

### Known gaps + follow-ons

- **Step 8 dispatcher** — the HTTP-POST half of D-5. Until it
  ships, PENDING rows accumulate in the delivery store and
  the GC sweep eventually drops them. The bus + queue + audit
  surface all work without it; an operator can register
  webhooks, verify them via the SPA, and inspect what would
  have been delivered.
- **AES-GCM secret encryption** — the Webhook record stores
  SHA-256 hashes only today. Step 8 must add an
  encrypted-secret field so the dispatcher can read the raw
  secret to sign. Documented in the Webhook record's class
  javadoc + plan.
- **SPA settings page** (Phase 4 Steps 14-17) — the operator-
  facing UI for managing subscriptions. The REST surface is
  callable via curl today; the SPA wraps it.

### Forward compatibility

- The `X-Prolly-Delivery` uuid is per-delivery, regenerated
  on redeliver. Subscribers can rely on uniqueness for dedup.
- The JSON payload shape is `{event, repo, mrId, actor,
  timestamp, data}` where `data` is kind-specific. New event
  kinds add new `data` shapes without breaking existing
  subscribers (they see the top-level fields and ignore
  unknown `data` keys).
- Adding more event kinds (commit events, branch events, sync
  events) is additive — the `EventGlobMatcher` already
  supports patterns like `*` to opt every new kind in.

### Audit + observability

- Every delivery row stays in `webhook_deliveries` for 30
  days. The audit UI shows attempts, status, last response.
- `redeliver` + `redeliver-dead` log info-level entries; the
  delivery audit log preserves the original DEAD row when a
  redeliver mints a fresh one.
- The bus publish logs at INFO when subscribers fan out
  (count + event + repo + mrId).
