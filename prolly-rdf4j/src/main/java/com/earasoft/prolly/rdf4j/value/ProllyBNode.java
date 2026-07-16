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
package com.earasoft.prolly.rdf4j.value;

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.MemorySegment;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * RDF4J {@link BNode} backed by a {@link MemorySegment}.
 *
 * <p>Three forms: UUID-backed ({@code 0xA0}), labelled ({@code 0xA1}), canonical post-URDNA2015
 * ({@code 0xA2}).
 */
public final class ProllyBNode implements ProllyValue, BNode {

    private final MemorySegment encoded;
    // @Nullable lazy cache: decoded on first getID() call, then memoized.
    private @Nullable String cachedId;

    public ProllyBNode(MemorySegment encoded) {
        this.encoded = encoded;
    }

    @Override
    public String getID() {
        if (cachedId != null) return cachedId;
        byte tag = TermCodec.tagOf(encoded);
        MemorySegment payload = TermCodec.payloadOf(encoded);
        cachedId =
                switch (tag) {
                    case TermCodec.TAG_BNODE_UUID -> TermCodec.decodeBNodeUuid(payload).toString();
                    case TermCodec.TAG_BNODE_LABEL -> TermCodec.decodeBNodeLabel(payload);
                    case TermCodec.TAG_BNODE_CANON -> "c14n" + TermCodec.decodeBNodeCanon(payload);
                    default ->
                            throw new IllegalStateException(
                                    "ProllyBNode wrapping non-BNode tag 0x"
                                            + Integer.toHexString(tag & 0xFF));
                };
        return cachedId;
    }

    @Override
    public String stringValue() {
        return getID();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof BNode other)) return false;
        return getID().equals(other.getID());
    }

    @Override
    public int hashCode() {
        return getID().hashCode();
    }

    @Override
    public String toString() {
        return "_:" + getID();
    }

    /**
     * Serialization proxy: RDF4J's {@code Value} contract extends {@link java.io.Serializable}, but
     * this instance is backed by a non-serializable {@link MemorySegment}. Replace it on write with
     * the plain, fully-serializable {@link SimpleValueFactory} equivalent (the segment is never
     * written). See {@code bugs/rdf4j-repository-connection-contract-triage.md}.
     */
    private Object writeReplace() {
        return SimpleValueFactory.getInstance().createBNode(getID());
    }
}
