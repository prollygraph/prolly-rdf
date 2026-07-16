
# ADR-0015: Auth backend choice — RocksDB or SPARQL

## Status

Accepted, 2026-05-22. Guides
`plans/auth-backend-choice.md`.
Supersedes the implicit "SPARQL is the only auth backend" position
of [ADR-0013](0013-user-accounts-and-authenticated-staging.md) +
[ADR-0014](0014-auth-graph-write-read-gates.md).

## Context

[ADR-0013](0013-user-accounts-and-authenticated-staging.md) defined
user accounts. `plans/user-accounts.md`
shipped them backed by dedicated RocksDB column families.
`plans/auth-on-sail.md` moved that
state into SPARQL named graphs, with
[ADR-0014](0014-auth-graph-write-read-gates.md) adding gates to close
the privilege-escalation surface that SPARQL exposed.

After all of that work, an honest comparison
(documented in this session
under "Real findings"):

| Dimension | RocksDB design | SPARQL design (+ gates) |
|---|---|---|
| Net Java LOC | ~600 | ~1400 |
| Production attack surface | none (no SPARQL path) | gated (3 controller-level checks) |
| Session-cookie lookup latency | ~10µs (RocksDB point-get) | ~1-2ms (SPARQL ASK) |
| Bootstrap complexity | 1 step (insert admin row) | 3 steps (TBox + admin via `RepositoryConnection.add` + gate-aware) |
| Operator-queryable user state | no (separate DB tech) | yes (admin-only via gate) |
| Native audit trail in `/commits` | yes | yes |
| Self-describing schema | no | yes (TBox loaded into Sail) |

The benefits of SPARQL mode (queryable state + self-describing schema)
are real but **strategic / aesthetic**, not operational. The costs
(2× LOC, ~100× session lookup latency, new attack surface needing
gates) are **measurable + operational**.

Different deployments value these differently. A regulator-facing
deployment that uses SPARQL queries for audit-trail review benefits
from SPARQL mode. A high-throughput service-account deployment
benefits from RocksDB's session-path performance + safer-by-
construction design.

Forcing one mode on all deployments leaves the other camp ill-served.
Both code paths already exist + are tested. The natural design is to
let operators pick at config time.

## Options

| Option | Default | Operator choice point | Test matrix burden | Strategic alignment |
|---|---|---|---|---|
| **A — RocksDB only** | n/a | none — auth-on-sail work is reverted | 1× | Drops the RDF-native identity story |
| **B — SPARQL only** | n/a | none — current state | 1× | Forces every deployment to pay for gates + perf cost |
| **C — Config flag, RocksDB default** | `rocksdb` | `prolly.rdf4j.auth.backend=rocksdb\|sparql` | 2× | Safe-by-default; deployments opt into SPARQL when they want it |
| **D — Config flag, SPARQL default** | `sparql` | same | 2× | RDF-native by default; deployments opt out for perf/simplicity |
| **E — Compile-time selection** | per build | Maven profile | 1× per build | Two artifacts to ship; awkward |

## Decision

**D-1. Option C — Config flag, RocksDB default.** Operators set
`prolly.rdf4j.auth.backend=sparql` to opt into the SPARQL-backed mode;
`rocksdb` is the default (also the value when the property is unset).

The deciding tradeoff: **safe-by-default + reversibility**. The
RocksDB design is the proven, safer-by-construction path. Defaulting
to it means new deployments inherit zero new attack surface +
zero new perf cost; they opt into SPARQL when they have a concrete
reason. Reversibility is a feature: operators can flip back to
RocksDB if SPARQL mode surfaces a bug in their deployment.

*Rejected Option A (RocksDB only):* drops the RDF-native identity
story + the ~1400 LOC of SPARQL+gate work that's tested + working.
The codebase + ontology investment stays as a viable choice.

*Rejected Option B (SPARQL only):* forces every deployment to pay
for the gate complexity + perf cost even when they don't benefit.
A high-throughput service-account deployment shouldn't have to
audit SPARQL gate logic to ship.

*Rejected Option D (SPARQL default):* the RDF-native default would
be aesthetically nicer but operationally worse — a new operator
following the README would inherit a higher-risk surface they may
not have audited.

*Rejected Option E (compile-time):* two jars to ship; operator
mistakes harder to recover from (a config mistake is a service
restart; a wrong-jar mistake is a rebuild).

**D-2. Both stores share a common Java interface.** Extract
`UserStore` + `PseudonymStore` (singular) as interfaces with the CRUD
methods both `RocksDbUserStore` (renamed from `UsersStore`) +
`SparqlUsersStore` already share. Downstream consumers
(`AuthController`, `SessionAuthFilter`, `UserDetailsServiceImpl`,
`BootstrapAdminInitializer`) take the interface.

*Rationale:* without the interface, every consumer would need a
`@ConditionalOnProperty` `@Bean` factory + duplication; with the
interface, only the two STORE beans are conditional + consumers
are agnostic.

**D-3. Gates only fire when SPARQL backend is active.** The
`/sparql/update` write-gate + `/sparql/query` read-gate are
controller-layer protections specifically for the SPARQL surface.
When RocksDB mode is active, auth state isn't in the Sail at all —
the gates have nothing to protect; they no-op (the prefix check
finds no auth-namespace IRIs because auth isn't in the Sail).

*Rationale:* zero overhead in RocksDB mode (the gate code still
runs but always finds no auth-namespace refs + lets the query
through). No `@ConditionalOnProperty` needed on the gate code itself
— the gate is correct in both modes by design.

**D-4. Backend choice is a one-way migration (with one-way escape
hatch).** Operators who flip `auth.backend` from `rocksdb` to
`sparql` trigger a one-shot data migration on next boot (via
`RocksToSparqlMigrator`). Flipping back from `sparql` to `rocksdb`
is supported via a separate `SparqlToRocksMigrator` (a future plan;
not blocking this ADR). The escape hatch exists so a regrettable
opt-in is not catastrophic.

*Rationale:* operators need confidence that opting into SPARQL isn't
a one-way door. Bidirectional migrators add code but the symmetry
matches the symmetric Option C design.

**D-5. Documentation surfaces the choice clearly.** Both the
`auth-as-data.md`
newcomer doc + CLAUDE.md call out the two backends + their
tradeoffs + which is the default. Operators reading the docs
shouldn't be surprised by encountering either mode.

## Consequences

**Cost of fix**:
- Extract `UserStore` + `PseudonymStore` interfaces (mechanical).
- Rename `UsersStore` → `RocksDbUserStore`; `SparqlUsersStore` already
  has the right type — just add `implements UserStore`.
- Refactor `AuthSecurityConfiguration` from one set of `@Bean`
  factories to a pair guarded by `@ConditionalOnProperty`.
- Refactor `AuthController`, `SessionAuthFilter`,
  `UserDetailsServiceImpl`, `BootstrapAdminInitializer` to depend on
  interface (not concrete class).
- Parameterize the test classes that exercise these consumers over
  backend (`@ParameterizedTest` with a `Backend` enum, or pair-class
  approach with shared abstract).
- `SparqlToRocksMigrator` (reverse migration) — future plan, not
  blocking.
- ~10-15 doc updates (CLAUDE.md, newcomer doc, ADR cross-refs).

**Operator-visible changes**:
- New config knob: `prolly.rdf4j.auth.backend=rocksdb|sparql`.
- Existing deployments **default to `rocksdb`** — same as the
  pre-auth-on-sail state. Zero migration friction.
- Operators who want SPARQL mode set the property + restart;
  `RocksToSparqlMigrator` runs once.
- `/auth/users` endpoints work identically in both modes.
- `/sparql/query` against the auth users graph: in `rocksdb` mode,
  returns empty (auth isn't in the Sail); in `sparql` mode, the
  ADR-0014 gate applies.

**Cost we accept**:
- 2× test matrix for the auth-touching tests. Worth it — both
  modes ship, both get equal coverage.
- Two storage shapes documented (more for newcomers to learn).
  Mitigation: clear default in the newcomer doc + a 1-line decision
  guide ("which should I use?").
- Maintenance burden of keeping both implementations in sync as
  features get added. Mitigation: the interface enforces parity;
  any new method must land in both impls.

**Cost we DON'T accept**:
- We do NOT ship a "hybrid mode" where some accounts live in RocksDB
  + some in SPARQL. That's complexity with no use case.

**Migration story**:
- Existing deployments running on pre-this-ADR `sparql` mode: stay
  on `sparql` mode if config sets it explicitly. The default flip
  to `rocksdb` only affects deployments that don't set the property.
  Per ADR D-1, the default IS `rocksdb` so unset = `rocksdb`. The
  one-line operator action for "stay on SPARQL": set
  `prolly.rdf4j.auth.backend=sparql` in their config.

**Open hardening findings still untouched**:
- E-3 (session-survives-delete-recreate): identical behavior in
  both backends; documented; cascade is in the DELETE controller.
- G-3 (SPARQL findUser latency): only affects `sparql` mode; not
  a concern in `rocksdb` mode.

## Follow-up / future work

- **Implementation plan**:
  `plans/auth-backend-choice.md` — 10 steps:
  (1) extract `UserStore` + `PseudonymStore` interfaces;
  (2) rename `UsersStore` → `RocksDbUserStore` + add `implements`;
  (3) `SparqlUsersStore` + `SparqlPseudonymStore` add `implements`;
  (4) `AuthSecurityConfiguration` conditional `@Bean` factories;
  (5) downstream consumers (`AuthController`, `SessionAuthFilter`,
  `UserDetailsServiceImpl`, `BootstrapAdminInitializer`) take the
  interface;
  (6) parameterized test refactor;
  (7) acceptance against booted jar in BOTH modes;
  (8) `SparqlToRocksMigrator` skeleton + flip case;
  (9) update CLAUDE.md + newcomer doc with the backend-choice section;
  (10) production readiness sign-off.
- **ADR-N+1 (when justified)**: per-tenant or per-realm backend
  selection. Currently global; multi-tenant would need refinement.

## Open questions

- **Q1**: Do we ship a deprecation timeline for one of the backends
  eventually? Probably no — both are first-class for the
  foreseeable future. If one falls behind (no new features land
  in it), it'll naturally be deprecated.
- **Q2**: What's the bidirectional-migrator code-review bar?
  Probably same as the forward migrator: idempotent, fixtured
  tests, one-shot. Decide during Step 8.
- **Q3**: Should `prolly user` CLI (deferred) be aware of the
  backend? Probably no — it talks to `/auth/users` endpoints which
  abstract the backend.
