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
/**
 * {@code RocksDbFlatSail} — an <strong>unversioned</strong> RDF4J {@link
 * org.eclipse.rdf4j.sail.Sail} storing quads as plain sorted RocksDB keys in column families. The
 * fast, simple sibling of the versioned {@code ProllySail}: no Merkle tree, no history, branching,
 * diff or time-travel — for high-churn data that needs none of that.
 *
 * <p>Quads are dictionary-encoded — RDF terms map to 8-byte {@code TermId}s (shared codecs from
 * {@code prolly-codec}), so each index key is a fixed 32-byte 4×TermId permutation with an empty
 * value. Four permutation indexes ({@code spoc}/{@code posc}/{@code ospc}/{@code cspo}) plus
 * dictionary and namespace column families live in one RocksDB instance; transactions buffer into a
 * {@code WriteBatch} and commit atomically with the WAL on.
 *
 * <p>Implementation is incremental — see {@code plans/RocksDbFlatSail-impl.md}: {@code
 * FlatKeyCodec}, {@code RocksFlatStore}, {@code FlatDictionary}, then {@code RocksDbFlatSail} and
 * its connection.
 */
package com.earasoft.prolly.flatsail;
