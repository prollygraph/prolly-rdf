---
tags:
  - storage
---

# The chunk store: how the tree lives in RocksDB, and the chunk-size dial

*RocksDB here is not the index — it's a content-addressed bag of bytes. A **chunk** is any content-addressed blob in that bag: the prolly tree's nodes are the primary kind, and commit objects are the second, stored directly with no tree machinery in between (the closing section). RocksDB just maps each chunk's hash to its bytes. This doc shows what a tree-node chunk holds, why the data isn't written into RocksDB directly, and the one tunable that trades read speed against cheap versioning.*

> **What you'll learn** — that ProllySail uses RocksDB as a `node-hash → node-bytes`
> store, not as the sorted index; that a “chunk” means any content-addressed blob
> (a tree node or a commit object — the term is NOT the chunker's output, though
> tree-node chunks are what the chunker cuts); what a tree-node chunk actually
> holds; why writing the data *directly* as RocksDB keys (what the flat sail does)
> would forfeit versioning; that content-defined chunking *is* the versioning
> mechanism, not an incidental size cap; and the chunk-size dial — bigger chunks buy
> read locality, smaller chunks buy finer cross-version sharing.
>
> _Reading time: ~11 minutes._

> **Prerequisites** — the-prolly-tree (content addressing +
> the tree), the-two-sails (versioned vs unversioned),
> [the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md) (a node's wire layout). This doc is
> the layer *between* the tree and RocksDB; it leans on all three and on
> structural-sharing-and-churn.

## RocksDB is a content-addressed bag of bytes

A common first assumption is that ProllySail stores triples in RocksDB the way a
SQL engine stores rows — keyed by the data, sorted by RocksDB. It does not. The
node store's whole interface is two methods:

```
byte[]            write(MemorySegment nodeBytes)   // returns hash(nodeBytes)
Optional<byte[]>  read(byte[] hash)                 // hash → the bytes back
```

(`InMemoryNodeStore`, `RocksNodeStore`.) RocksDB holds a single
`node-content-hash → serialized-node-bytes` map. The **sort and the structure live
in the prolly tree**, not in RocksDB: a node's bytes contain the keys that route a
search and the *hashes* of its children. RocksDB is just the bag those immutable
node-blobs live in, keyed by their own hash.

So a lookup is a **descent**, not one `get`: fetch the root chunk by its hash →
search its keys → get a child's hash → fetch that chunk → … → leaf. Each fetch is a
[`NodeCache`] hit or a RocksDB point-`get` keyed by a node hash.

## What's inside a chunk

A chunk is the serialized bytes of one tree node (its wire layout is
[the-on-disk-format](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-on-disk-format.md)'s job; here is what it *means*):

- **Leaf node (level 0)** — a sorted run of `(key, value)` pairs, the actual data.
  In the dictionary tree: `(TermId → encoded-term bytes)`. In a SPOC index tree:
  `(SpocKey tuple → marker)` — the key *is* the data. In the data tree: the rows.
- **Internal node (level > 0)** — a sorted run of `(separator-key, child-node-hash)`
  pairs, plus a per-child **subtree count** (what makes cardinality O(log)). No
  values — just the keys that route a search and the hashes of the child chunks.

That is why a descent works: at an internal node the search lands on a child *hash*
(fetch it next); at a leaf it lands on the *value*.

## Why not write the data into RocksDB directly?

The flat sail *does* exactly that: the SPOC tuple **is** the RocksDB key, stored
sorted in RocksDB's log-structured merge-tree. One mutable sorted map — fast,
simple, and **unversioned**. RocksDB's tree has no notion of immutable subtrees
shared across versions.

ProllySail pays an extra layer — the content-addressed chunks — to buy three things
a direct-in-RocksDB layout cannot give (this is the heart of
the-two-sails):

1. **Structural sharing → cheap versions.** Identical bytes hash identically and are
   stored once. A new version rewrites only the touched chunk(s) plus the path to
   the root — O(log N) new chunks; every untouched subtree is shared by hash. N
   versions cost the deltas, not N copies. Direct-in-RocksDB gives *no* sharing —
   versioning would mean copying the whole keyspace.
2. **History-independence → verifiable identity + cheap diff/merge.** Same data →
   same tree → same root hash; diff and merge skip identical subtrees by comparing
   hashes.
3. **Lock-free fork.** Immutable chunks are shared across branches and read
   concurrently without locks.

The cost of the layer: a lookup is a descent of several node-hash `get`s instead of
one, and the four permutation indexes plus node framing run ~3–4× a plain store's
footprint (io-and-zfs). That is the deliberate
trade — versioning for indirection. Note RocksDB's log-structured merge-tree still
runs *underneath*: it stores and compacts the `hash → bytes` map. The only question
is what the RocksDB *key* is — a logical tuple (flat, mutable) or a node hash (tree,
versioned).

## Content-defined chunking *is* the versioning mechanism

What decides where one node ends and the next begins? A rolling hash
(`RollingHashSplitter` over `BuzHash`): a node boundary falls wherever the rolling
hash hits a pattern, so boundaries are a function of *content*, not of position or
insertion order. This is not a cosmetic size cap — it is what makes versioning
cheap:

> **Boundary stability.** Because boundaries are content-defined, inserting or
> changing one item re-chunks only a *local span* — the edited tree reuses every
> unchanged chunk of its predecessor. With fixed or arbitrary boundaries, an insert
> near the start would shift every later boundary, so *every* chunk would change,
> every version would be a near-full copy, and sharing would collapse. So you cannot
> "turn off chunking" and keep cheap versioning — chunking is the engine of it.

## The chunk-size dial

`RollingHashSplitter` targets chunks of roughly **512 bytes to 16 KiB**
(`MIN_CHUNK_SIZE = 2^9`, `MAX_CHUNK_SIZE = 2^14`, plus a pattern mask for the
average). That range is a **dial**, and it is the honest answer to "can I have
bigger nodes but keep versioning?" — *yes: raise the target.* It stays
content-defined (so sharing, dedup, history-independence all survive), the nodes
just get bigger. The trade is granularity:

| Bigger chunks | Smaller chunks |
|---|---|
| Fewer node-`get`s per descent → better read locality | More chunks/levels → more indirection per read |
| Larger per-node sorted array | Tiny per-node array |
| **Coarser** sharing — an edit rewrites a bigger chunk → more bytes per version (more churn — see structural-sharing-and-churn) | **Finer** sharing — small deltas per version |

There is a floor: one giant node = a plain sorted array with *no* sharing →
versioning becomes a full copy per version, defeating the point. And a ceiling on
the benefit: nodes stay small enough that an edit shares most of the tree. The
sweet spot is empirical — the right move is to *measure* read latency and
per-commit chunk churn across settings, not to guess.

The dial also touches read-algorithm choices. Bigger nodes mean larger in-node
sorted arrays, which is exactly the regime where an interpolation in-node search
(over the uniform hash keys this engine uses) starts to beat binary search — a
measured result, with the surprises that come from benchmarking the *real*
comparator rather than a model of it, written up in
`_research_performance/rocksdb-interpolation-search.md`.

## The store is a choice: the filesystem backend

Because the whole contract is just `write(bytes) → hash` and `read(hash) → bytes`,
*anything* that maps a content hash to its bytes can be the store. Three
implementations ship, and the tree is identical over all of them (same operations →
same root hash — content addressing makes the engine backend-independent):

- **`RocksNodeStore`** — the production packer. The `hash → bytes` map lives in
  RocksDB's log-structured merge-tree; millions of chunks pack into a handful of
  files.
- **`InMemoryNodeStore`** — a `HashMap` twin for tests and the reference.
- **`FileNodeStore`** — the **filesystem itself is the store**: each chunk is one
  immutable file at `<root>/<hex[0:2]>/<hex[2:40]>`, named by its content hash —
  exactly git's loose-object layout (the 2-character fan-out keeps any one directory
  from holding every object). Because the filename *is* the hash, dedup is automatic
  (identical bytes → same path → one file) and writes are atomic and lock-free with
  no write-ahead log: write a temp file, then `rename` it onto the final path.
  POSIX `rename` is atomic, and two writers racing on the same chunk produce
  byte-identical files, so the race is a non-event. A crash mid-write leaves a temp
  (garbage, never read) and no final file — the chunk is simply *absent*, never torn,
  because content addressing means a half-written chunk could never hash to its name
  anyway. Durability is a dial (`NONE` / `BATCH` / `EACH`) over when the file's bytes
  are fsync'd.

**When to pick `FileNodeStore`.** A *small* store you want to inspect by hand (`ls`
the fan-out, `cat` a chunk), back up with `tar`/`rsync`, or diff with git tooling —
dev fixtures, a portable single-repo export, a zero-dependency chunk store. **When
not to:** anything write-heavy or large. The loose-object design is *not* space- or
inode-efficient — a million chunks is a million tiny files, a million inodes — and it
is measurably slower, because every read pays an `open()`/`read()`/`close()` syscall
per chunk and every write pays a temp-create + `rename` per chunk, where RocksDB folds
a whole commit's chunks into one batch. The
filesystem-node-store build-log
measures the trade on ZFS: warm reads ~3.9× slower than RocksDB (pure syscall
overhead, no disk touched), writes ~9–56× slower depending on the fsync mode. That
regime — millions of chunks, write throughput — is what packfiles exist for, a
separate plan; `FileNodeStore` deliberately stops at the small-and-inspectable end.
It is **not** the production default.

## Commits are chunks too — the log is just an index

The `write(bytes) → hash` / `read(hash)` contract holds one more thing than the tree
nodes: **the commits themselves.** A commit's *identity* is a content hash over
`{RootMetaTree hash, ordered parent-ids, author, message}` — the wall-clock time is
deliberately left out so the *same* logical commit computed on two peers gets the
*same* id (that determinism is what lets distributed sync converge; see
ADR-0071).
ADR-0073 takes
the next step: the commit *object* is serialized and stored as a content-addressed
chunk in the same `NodeStore`, so **a commit id literally *is* the chunk address** —
`store.read(commitId)` returns the commit, exactly as `store.read(treeNodeHash)`
returns a tree node. Everything reachable is a chunk (git's object model: commits,
trees, and — here — the RDF data all live in one content-addressed bag).

So what is `commits.log`? A **thin sidecar index**, not the source of truth. Each row
is just `<datetime> <commit-id>` — the id points at the chunk that holds the content,
and the datetime carries the one thing the id can't (the excluded wall-clock time, for
Memento / `/sparql/timemap`). Reading history reconstructs each entry from its chunk
plus the row's timestamp. The two things that *cannot* be content-addressed stay
outside the chunk store: mutable **branch refs** (a pointer that moves — the
`RefsStore`, git's `refs/`) and the **timestamp** (the sidecar). Everything else is a
chunk.

## Rules & gotchas

- **RocksDB is not the index.** Do not reach for RocksDB range scans over "the
  triples" in ProllySail — the order lives in the tree; RocksDB only resolves a
  node hash to its bytes. (The flat sail is where RocksDB *is* the index.)
- **You can't remove chunking and keep versioning.** Content-defined boundaries are
  the sharing mechanism; raise the size target, don't disable it.
- **The chunk-size dial is a real trade, measured not guessed.** Bigger = faster
  reads, more per-version churn; smaller = the reverse. Pin the choice with the
  churn benchmark + a read benchmark, per the "measure the real thing" lesson.
- **The store backend is a choice, not the default.** `RocksNodeStore` is production;
  `FileNodeStore` (loose objects on the filesystem) is for small, inspectable,
  portable stores and pays a syscall per chunk — do not reach for it at millions of
  chunks (a million inodes), and never promote it without its own green resource +
  performance gate.

## Where this lives

- `prolly-storage/src/main/java/com/earasoft/prolly/storage/RocksNodeStore.java` — the RocksDB-backed `hash → bytes` node store (with the optional `NodeCache`).
- [`InMemoryNodeStore.java`](https://github.com/prollygraph/prolly-core/blob/main/dolthub-java-port/src/main/java/com/dolthub/prolly/InMemoryNodeStore.java) — the in-memory twin; shows the bare `write(bytes) → hash` / `read(hash)` contract.
- `prolly-storage/src/main/java/com/earasoft/prolly/storage/FileNodeStore.java` — the filesystem backend: one immutable file per chunk (git loose-objects layout), atomic-rename writes, a durability dial.
- [`RollingHashSplitter.java`](https://github.com/prollygraph/prolly-core/blob/main/dolthub-java-port/src/main/java/com/dolthub/prolly/RollingHashSplitter.java) — content-defined chunking; `MIN_CHUNK_SIZE` / `MAX_CHUNK_SIZE` are the dial.
- [`BuzHash.java`](https://github.com/prollygraph/prolly-core/blob/main/dolthub-java-port/src/main/java/com/dolthub/prolly/BuzHash.java) — the rolling hash the splitter runs.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/SpocKey.java` — a leaf key in an index chunk (the four-`TermId` tuple).
- `_research_performance/rocksdb-interpolation-search.md` — the chunk-size/read-algorithm tie-in and the binary-vs-interpolation measurements.

## Where to go next

- the-two-sails — the full versioned-vs-flat comparison this doc's "why not direct" argument summarizes.
- structural-sharing-and-churn — the versioning-cost side of the dial, in depth.
- io-and-zfs — what these chunks look like once RocksDB compaction + ZFS get hold of them on disk.
- filesystem-node-store build-log — the measured read/write cost of the loose-object backend, and the measure-and-retract on a noisy first result.
