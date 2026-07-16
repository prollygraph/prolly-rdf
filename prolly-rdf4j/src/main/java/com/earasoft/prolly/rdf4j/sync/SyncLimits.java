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
package com.earasoft.prolly.rdf4j.sync;

import com.earasoft.prolly.sync.SyncPack;

/**
 * Resource bounds applied at the {@link SyncPack} receive boundary (plan Step 23) — both the
 * inbound REST {@code POST /sync/push} and the client- side {@link RepoSync#fetch} validate the
 * pack against these limits before doing any storage work, so a malicious or runaway peer cannot
 * drain memory or wedge the receiver with a multi-gigabyte pack.
 *
 * <p>Limits are advisory caps, <em>not</em> the system's full capacity — the defaults are generous
 * enough that legitimate pushes fit, but small enough that a single bad pack stays bounded by what
 * the receiver can shed.
 *
 * @param maxChunks maximum number of chunks allowed in a single pack
 * @param maxBytes maximum total chunk-payload bytes (chunk data only; excludes the codec framing
 *     and {@code commits} list)
 */
public record SyncLimits(int maxChunks, long maxBytes) {

    /** Defaults (1,000,000 chunks; 1 GiB). */
    public static SyncLimits defaults() {
        return new SyncLimits(1_000_000, 1L << 30);
    }

    public SyncLimits {
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks must be > 0: " + maxChunks);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0: " + maxBytes);
        }
    }

    /**
     * Reject {@code pack} if it would breach either limit. Counts the total chunk-payload bytes
     * once; for very large packs this is O(n) but the point is to surface the breach
     * <em>before</em> any chunk lands in the receiver's store, not to be sub-linear.
     *
     * @throws IllegalArgumentException with a clear, log-friendly message
     */
    public void validate(SyncPack pack) {
        if (pack.chunks().size() > maxChunks) {
            throw new IllegalArgumentException(
                    "SyncPack rejected: chunk count "
                            + pack.chunks().size()
                            + " exceeds maxChunks="
                            + maxChunks);
        }
        long bytes = 0;
        for (byte[] chunk : pack.chunks()) {
            bytes += chunk.length;
            if (bytes > maxBytes) {
                throw new IllegalArgumentException(
                        "SyncPack rejected: chunk-payload total > maxBytes="
                                + maxBytes
                                + " (seen at least "
                                + bytes
                                + " bytes)");
            }
        }
    }
}
