/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.earasoft.prolly.rdf4j.sail;

import java.util.List;

/**
 * Computes a commit's content-addressed identity from its logically-meaningful content.
 *
 * <p>Per <a
 * href="../../../../../../../../docs/adr/0071-commit-identity-includes-parents.md">ADR-0071</a> a
 * commit id is {@code hash(tag ‖ metaTreeHash ‖ parent-ids ‖ author ‖ message)} — the RootMetaTree
 * hash, the commit's parent ids in their <b>recorded order</b>, and its author + message. The
 * wall-clock timestamp is deliberately <b>excluded</b>. This replaced the previous identity model
 * where the id <i>was</i> the RootMetaTree hash alone (which collapsed two distinct commits sharing
 * a tree but differing in parents to one id — the measured root cause of a sync-convergence
 * failure).
 *
 * <p>The id and the on-chunk commit-object serialization share <b>one</b> byte definition: {@code
 * of(...)} is exactly {@link CommitObject#of}{@code (...).}{@link CommitObject#id() id()}. The
 * injective byte layout, the domain tag, and the bounds-checked parse all live in {@link
 * CommitObject}; per <a
 * href="../../../../../../../../docs/adr/0073-commit-objects-in-the-nodestore.md">ADR-0073</a>
 * (D-1) that makes a commit id the {@code NodeStore} address of its commit object. This class stays
 * the stable façade callers use to obtain an id.
 *
 * @apiNote The id is <b>deterministic</b>: the same logical commit produced independently on two
 *     peers gets the same id, regardless of when or where it was made. That is what lets
 *     distributed sync converge by construction (a fixed-point merge loop terminates because a
 *     merge of two equal-tree commits yields the same id on both peers). The returned id is exactly
 *     {@code HashUtils.hash} wide (SHA-512/20 = 20 bytes), so it drops into every existing 20-byte
 *     hash slot (refs, parent lists, {@code commits.log}). Parent <b>order is significant</b>
 *     ({@code [A,B]} ≠ {@code [B,A]}): "merge B into A" and "merge A into B" are distinct commits,
 *     exactly as in git (ADR-0071 D-3). Two commits identical in tree+parents+author+message
 *     collapse to one id — under content-addressing that is correct (byte-identical commit records
 *     <i>are</i> one commit); the timestamp difference is not part of identity (ADR-0071 D-2).
 * @implNote Delegates to {@link CommitObject} — the wire format, the domain-separation tag, and the
 *     bounds-checked {@code deserialize} trust boundary all live there (one serialization, no
 *     duplication; extracting it did not change any id — the byte definition is preserved). <b>
 *     Dependents:</b> {@code CommitLog.Entry} (stores the id alongside the tree hash); the sync +
 *     graph layer ({@code CommitGraph}, {@code CommitClosure}, {@code MergeEngine}, {@code
 *     RepoSync}) addresses commits by it.
 */
public final class CommitId {

    private CommitId() {}

    /**
     * Compute the commit id from its content — {@code CommitObject.of(...).id()}. See {@link
     * CommitObject#of} for the field coercion (null author/message → empty; null parent list →
     * empty) and validation.
     *
     * @param metaTreeHash the RootMetaTree chunk hash (the commit's tree); must not be null
     * @param parentIds the parent commit ids in <b>recorded order</b> (order is significant — D-3);
     *     a null or empty list denotes a genesis commit
     * @param author the commit author; null is treated as the empty string
     * @param message the commit message; null is treated as the empty string
     * @return the 20-byte content-addressed commit id
     * @throws IllegalArgumentException if {@code metaTreeHash} is null, or any parent id is null
     */
    public static byte[] of(
            byte[] metaTreeHash, List<byte[]> parentIds, String author, String message) {
        return CommitObject.of(metaTreeHash, parentIds, author, message).id();
    }
}
