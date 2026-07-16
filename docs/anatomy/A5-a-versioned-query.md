---
tags:
  - versioning
  - rdf
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/anatomy/A5-a-versioned-query.md; links + module citations adapted to this repo's layout -->

# Anatomy of a versioned query

*From `?commit=…` or `Accept-Datetime` to a SPARQL result as of the past.*

> **What you'll learn** — how `ProllySail` answers a query *as it was* at an
> older commit: the three ways to name a point in history, how each resolves to
> a commit id, what a commit actually *is* (`RootMetaTree`), and why opening a
> historical snapshot costs almost nothing.
>
> _Reading time: ~11 minutes._
> _Prerequisites: [A4 · a SPARQL query](A4-a-sparql-query.md),
> [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)._

## 0 · The problem

[A4](A4-a-sparql-query.md) ran a query against *now*. This one asks the same
question of the past:

```http
GET /sparql?query=SELECT...&commit=6b1f3c7d8e4af0... HTTP/1.1
```

or, equivalently, without knowing any hashes:

```http
GET /sparql?query=SELECT... HTTP/1.1
Accept-Datetime: Tue, 12 May 2026 23:14:48 GMT
```

The result must reflect the graph **as it was** at that commit — not HEAD.
Follow it. (Time-travel is `ProllySail`-only; the flat Sail has no history.)

## 1 · Three ways to name a point in history

`SparqlController` (the HTTP layer — private-monorepo server product; the resolution
and snapshot machinery below is this repo's) accepts three:

- **`?commit=<hex>`** — a commit id directly.
- **`?branch=<name>`** — the *current head* of a named branch.
- **`Accept-Datetime: <RFC 1123>`** — a wall-clock instant (the
  [Memento](https://datatracker.ietf.org/doc/html/rfc7089) protocol's "give me
  the version current at this time").

`?commit` and `?branch` are mutually exclusive — supplying both is a `400`. All
three must end at the same thing: a single **commit id**.

## 2 · Resolving to a commit id

A branch name resolves through the **`RefsStore`** — a sidecar `refs/`
directory, one file per branch, each holding one line of hex:

```
<storeDir>/refs/main        ← hex RootMetaTree hash of main's head
<storeDir>/refs/feature-x   ← hex RootMetaTree hash of feature-x's head
```

```java
RefsStore refs = liveSail.refsStore().orElseThrow(...);
commitHash = refs.get(branchName).orElseThrow(() ->
    new ResponseStatusException(HttpStatus.NOT_FOUND,
        "branch '" + branchName + "' does not exist"));
```

> **The bug** — a branch name becomes a *file path* (`refs/<name>`). An early
> `RefsStore` took the name verbatim, so a branch named `../../etc/something`
> — or an absolute path — escaped the `refs/` directory, turning branch
> create/delete into **arbitrary file write/delete**. The fix: every branch
> name is validated against a strict `NAME_PATTERN` of filesystem-safe
> characters before it ever touches the disk. Any identifier that crosses the
> boundary into a path is an injection site — validate at the boundary.

An `Accept-Datetime` resolves through the **`CommitLog`** — an append-only
`commits.log`. Each line is
`<RFC 1123 datetime> <hex RootMetaTree hash> [<hex parent>…] [message]` — the
parent links make it the commit directed acyclic graph. Datetime resolution needs only the first
two tokens: `resolveAcceptDatetime` scans for the **latest commit at or before**
the requested instant:

```java
for (CommitLog.Entry e : log.get().entries()) {
    if (!e.timestamp().isAfter(target)) {
        if (best == null || e.timestamp().isAfter(best.timestamp())) best = e;
    }
}
return best == null ? null : HashUtils.toHex(best.metaTreeHash());
```

Either way, the controller now holds one commit id.

## 3 · What a commit actually is — the `RootMetaTree`

A commit id is the hash of a **`RootMetaTree`** — a single record that bundles
*every* table root the Sail owns: the dictionary root, the namespaces root, the
four permutation-index roots, stats, provenance. Its on-disk format is
deliberately **deterministic** (entries sorted by name, fixed framing), so an
identical set of roots always serializes to identical bytes — and therefore to
an identical hash.

> **Key idea** — a "commit" is not a diff or a log entry. It is one immutable
> record naming the complete set of tree roots that *together* are the database
> at that instant. Because every root is a content-addressed prolly-tree hash,
> the `RootMetaTree` hash transitively commits to every quad in the store. That
> hash *is* the version.

`RootMetaTreeStore` keeps a `meta-head` file pointing at the newest one — that
is how a `ProllySail` restores itself after a process restart.

## 4 · Opening the snapshot

With a commit id in hand, the controller opens a **read-only** Sail at it:

```java
ProllySail snapSail = ProllySail.openSnapshotAt(
    liveSail.store(), liveSail.pool(), new CompositeMeterRegistry(), commitHash);
SailRepository snapRepo = new SailRepository(snapSail);
snapRepo.init();
return new SnapshotScope(snapRepo, commitHash, when);
```

`openSnapshotAt` loads the `RootMetaTree` for `commitHash` and stands up a Sail
whose table roots are *those historical roots* — sharing the same underlying
content-addressed `store` and buffer `pool` as the live Sail. From there the
query runs **exactly** the [A4](A4-a-sparql-query.md) path: RDF4J evaluates the
algebra, calls `getStatements`, which scans the index trees — except those
trees are the historical ones. The response carries `Memento-Datetime` and
commit-id headers so the client knows which version it got, and `SnapshotScope`
is closed when the request ends.

## 5 · Why this is almost free

Opening a snapshot reads *one* `RootMetaTree` record and loads a handful of
root hashes. It copies nothing and rebuilds nothing.

That works because of [the prolly tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md): an
index tree is immutable and content-addressed, so a root hash from six months
ago still names a *complete, valid* tree — every node it needs is in the store,
shared with newer versions that didn't change it. A commit was never a
snapshot *copy*; it was just a new `RootMetaTree` pointing mostly at the same
subtrees as the one before it.

> **Key idea** — time-travel is not a feature bolted onto the query engine. It
> is the *same* scan machinery from [A1](A1-a-scan.md), pointed at an older set
> of roots. History is cheap because immutability made every old root
> permanently valid.

## Takeaways

- A versioned query names a point in history three ways — `?commit`,
  `?branch`, or `Accept-Datetime` — and all resolve to one commit id.
- `RefsStore` (branches) and `CommitLog` (timestamps) do that resolution; a
  branch name is a file path, so it must be validated — a real path-traversal
  vulnerability lived here.
- A commit *is* a `RootMetaTree`: one deterministic, content-addressed record
  bundling every table root. Its hash is the version.
- A snapshot is opened with `ProllySail.openSnapshotAt` over the *same* store;
  the query then runs the ordinary A4/A1 path against historical roots.
- It is cheap because immutable, content-addressed trees make every past root
  a still-valid, fully-shared tree — no copy, no rebuild.

## Where this lives

- `prolly-rdf4j-rest/.../server/SparqlController.java` *(private monorepo server
  product)* — `resolveAcceptDatetime`, `openSnapshotIfRequested`, `SnapshotScope`
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/RootMetaTree.java`,
  `RootMetaTreeStore.java` — the commit record
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/RefsStore.java` —
  named branches
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/CommitLog.java` —
  the commit log / directed acyclic graph (each entry carries its parent links)
- `prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java` —
  `openSnapshotAt`
- Builds on: [A4 · a SPARQL query](A4-a-sparql-query.md),
  [the-prolly-tree](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)
- Continues in: the
  [runnable demos catalog](../../prolly-rdf4j/README.md#examples--runnable-demos) —
  branch/merge, squash-merge, revert, blame/bisect, and commit diffs as executable,
  CI-locked narratives.
