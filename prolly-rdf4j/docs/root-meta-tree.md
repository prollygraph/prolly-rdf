
# Concept — RootMetaTree

> The single object that turns a pile of independent prolly trees into
> something you can commit, branch, merge, and time-travel through.

## TL;DR

A **RootMetaTree** is the root-of-roots for one commit: a deterministic,
content-addressed chunk that names every table the Sail persists, paired
with that table's prolly-tree root hash at this commit. Hashing the chunk
gives the commit's id. Reading the chunk rewinds the entire Sail to that
commit's state in `O(<number of tables>)` chunk reads.

If git has *trees* (filesystem snapshots) and *commits* (tree + parents +
metadata), the RootMetaTree is our tree. The commit-id concept and the
parent-pointer concept live in [`CommitLog`](#) and the `commits.log`
sidecar, separately from the tree itself.

## Why we need it

The Sail isn't one prolly tree — it's several, each playing a distinct
role:

| Tree name | Role |
|---|---|
| `dict` | Encoded RDF term ↔ TermId dictionary |
| `spoc` / `posc` / `ospc` / `cspo` | Four quad indexes, one per access pattern |
| `namespaces` | SPARQL prefix declarations |
| `stats` | Per-term frequency counters for the query planner |
| `prefixes` | URI prefix table |
| `provenance` | Sidecar mapping `(s,p,o,c)` → "first-seen parent commit" |

A "commit" needs to be an atomic snapshot across *all* of them. Without
a RootMetaTree we'd have two bad options:

1. **Commit each tree separately** — no atomicity. Crash midway and the
   on-disk state is torn (new dict root, old SPOC root → query results
   reference TermIds that aren't in the dictionary).
2. **One giant prolly tree** — kills the per-table abstraction. Schema
   changes ripple everywhere; the query planner can't reason about per-
   table stats without scanning the whole structure.

RootMetaTree is the third path: each table keeps its own prolly tree
with its own optimized shape; one tiny chunk binds their roots together;
that chunk's hash is the atomic commit id.

## On-disk shape

```
[u32 BE entry-count]
per entry (sorted by name lex order):
  [u8 name-length]
  [N  UTF-8 name bytes]
  [u8 hash-length]
  [M  hash bytes]
```

Sorted by name → fully deterministic. Identical workloads produce
identical chunks → identical hashes. This is what lets two writers
working on the same data converge to the same commit id, and what
lets `git diff`-style dedup catch unchanged subtrees on merges.

The presence of an entry name in the chunk is itself a feature flag:

- A Sail without provenance enabled produces RootMetaTrees that lack
  the `provenance` entry. The bytes are byte-identical to what Dolt's
  Go port would write for the same data. (See
  [ADR-0001](adr/0001-provenance-index.md).)
- A provenance-enabled Sail always emits the entry; absence means "no
  provenance recorded for this commit".

There's no version byte. Adding a new table doesn't bump anything — it
just adds another entry name. Readers that don't recognize a name
simply don't restore that table; they keep working.

## How a commit happens

```
ProllySailConnection.commitInternal:
  ┌─────────────────────────────────────────────────────────────┐
  │ 1. dictTx.commit() → new dict root hash                     │
  │ 2. for each index: indexTx.commit() → new index root hash   │
  │ 3. prefixes.commit() → new prefixes root                    │
  │ 4. namespacesTx.commit() → new namespaces root              │
  │ 5. statsTx.commit() → new stats root                        │
  │ 6. (if provenance enabled) provIdxTx.commit() → prov root   │
  │ 7. sail.persistMetaTreeIfConfigured():                      │
  │      - build RootMetaTree from those roots                  │
  │      - mt.writeTo(store) → emits the chunk, returns its hash│
  │      - rootMetaTreeStore.put(hash)  →  root-head sidecar    │
  │      - commitLog.append(now, hash, [parent])  →  commits.log│
  │      - refsStore.put(currentBranch, hash) →  refs/main      │
  └─────────────────────────────────────────────────────────────┘
```

The chunk hash returned at step 7 is the commit id. Three sidecars all
hold variations of it:

- `root-head` — the latest hash (single value, atomically replaced)
- `commits.log` — append-only list of `<datetime> <hash> [parent...]`
- `refs/<branch>` — one file per branch, holding that branch's tip hash

The HTTP layer never sees the RootMetaTree directly. `/sparql/commits`
returns the hashes; `/sparql?commit=<hex>` opens a snapshot at one.

## How a read happens at HEAD

```
ProllySail.initializeInternal:
  1. rootMetaTreeStore.get() → latest hash from root-head sidecar
  2. RootMetaTree.readFrom(store, hash) → deserialize the chunk
  3. for each (name, rootHash) entry:
       loadStaticMap(rootHash, schemaForName) → in-memory root
  4. Sail is now at the latest commit, ready to serve queries
```

Steps 1-3 are seven small chunk reads. No replay, no scan, no warm-up.

## How a snapshot read happens

```
ProllySail.openSnapshotAt(store, pool, metrics, commitHash):
  1. RootMetaTree.readFrom(store, commitHash) → that commit's chunk
  2. Build a fresh, sidecar-less ProllySail
  3. restoreFromMetaTree → roots point at the historical state
  4. Caller wraps in SailRepository for read-only queries
```

Same seven-chunk read, just against an arbitrary historical hash
instead of HEAD. `/sparql?commit=<hex>` and `/sparql?branch=<name>` use
this. The snapshot Sail has no `RootMetaTreeStore` / `CommitLog` /
`RefsStore` so writes can't escape back into live state.

## Hash-of-hashes: why merges are cheap

Each entry in a RootMetaTree points to a Merkle DAG (its prolly tree).
When two branches diverge and merge:

- `dict` root often *identical* on both sides (most terms are the same)
  → no new chunks emitted
- `stats` root differs in a few leaf chunks → most internal chunks are
  reused
- `spoc` differs only in the subtrees containing changed keys → again,
  mostly reused
- A merge commit's RootMetaTree might reference 70% of the same prolly
  chunks as the parent

This is the whole point of content-addressing all the way up.
`MergeEngine.mergeStructural` (Phase 8) drives a per-tree three-way
merge through `com.dolthub.prolly.MergeEngine` rather than enumerating
triples: the underlying diff is a leaf-cursor lockstep walk that
short-circuits whole-tree and per-leaf content-identical regions. Cost
is O(leaf-nodes), not O(triples) — a small divergence on a large store
costs thousands of leaf byte-compares, not millions of triple decodes.
The legacy scan-and-reinsert `merge()` is retained only as the fallback
for provenance-enabled Sails, which fold provenance through the RDF4J
connection commit path that the structural merge bypasses.

## Naming

The class lives at:
```
prolly-rdf4j/src/main/java/com/earasoft/prolly/rdf4j/sail/RootMetaTree.java
```

It used to be called `RootMetaTree`. The rename was for clarity: "Root"
makes it explicit this IS the root of all the trees, not "a tree about
trees" (which "meta" could imply). The companion sidecar reader/writer
lives in `RootMetaTreeStore`.

## Where to look next

- [`RootMetaTree.java`](../src/main/java/com/earasoft/prolly/rdf4j/sail/RootMetaTree.java) — the class
- [`RootMetaTreeStore.java`](../src/main/java/com/earasoft/prolly/rdf4j/sail/RootMetaTreeStore.java) — the sidecar pointer
- [`CommitLog.java`](../src/main/java/com/earasoft/prolly/rdf4j/sail/CommitLog.java) — the (timestamp, hash, parents) log
- [`ProllySail.persistMetaTreeIfConfigured`](../src/main/java/com/earasoft/prolly/rdf4j/sail/ProllySail.java) — where it gets built
- [ADR-0001](adr/0001-provenance-index.md) — why we added `provenance` as an entry
