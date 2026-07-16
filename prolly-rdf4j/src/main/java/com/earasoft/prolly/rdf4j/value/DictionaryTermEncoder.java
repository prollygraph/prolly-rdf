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

import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.jspecify.annotations.Nullable;

/**
 * Encodes an RDF4J {@link Value} to its tag-prefixed term bytes, <b>Dictionary-aware</b> for
 * custom-datatype literals. The write-side complement to {@link DictionaryTermResolver} (the read
 * side, bytes → {@link Value}).
 *
 * <p>{@link TermEncoder#encode} alone is datatype-agnostic and <em>throws</em> for a literal whose
 * datatype has no dedicated built-in tag — because the {@code encodeCustomLiteral} shape stores the
 * datatype IRI as a {@link TermId}, which only a {@link Dictionary} can allocate. This class
 * supplies the Dictionary: a custom literal becomes {@code (datatype-IRI-as-TermId, verbatim
 * lexical bytes)}. That is the general term-faithful path of ADR-0043 D-1 — {@code DTYPE-2}: every
 * datatype storable, each round-tripping to its own {@code (lexical, datatype IRI)}.
 *
 * @implNote <b>Collaborators:</b> {@link TermEncoder#isDedicatedDatatype} draws the custom/built-in
 *     line; {@link TermCodec#encodeCustomLiteral} lays out the bytes; {@link Dictionary#encode}
 *     interns (write) and {@link Dictionary#findTermId} looks up (read) the datatype-IRI {@code
 *     TermId}. <b>Dependents:</b> {@code ProllySailConnection.encodeTerm} (write) and {@code
 *     ProllySail}'s provenance look-ups (read). The read/write split is real: write
 *     <em>interns</em> the datatype IRI; read <em>looks it up</em> and reports the whole literal
 *     absent if the IRI was never interned (a custom literal cannot exist in the store unless its
 *     datatype IRI does).
 */
public final class DictionaryTermEncoder {

    private DictionaryTermEncoder() {}

    /**
     * Encode {@code v} to term bytes for a <b>write</b>. A custom-datatype literal interns its
     * datatype IRI in {@code dict} (allocating a {@code TermId} if absent) and is encoded as a
     * custom literal; everything else delegates to {@link TermEncoder#encode}. Never returns null.
     */
    public static MemorySegment encodeForWrite(Value v, Dictionary dict, Arena arena) {
        IRI customDt = customDatatype(v);
        if (customDt == null) {
            return TermEncoder.encode(v, arena);
        }
        TermId datatypeId = dict.encode(TermEncoder.encode(customDt, arena));
        return TermCodec.encodeCustomLiteral(datatypeId, ((Literal) v).getLabel(), arena);
    }

    /**
     * Look up {@code v}'s {@link TermId} for a <b>read</b>. For a custom-datatype literal the
     * datatype IRI is looked up (not interned): if it is absent the literal cannot be in the store,
     * so this returns {@link Optional#empty()}. Everything else delegates to {@link
     * Dictionary#findTermId}.
     */
    public static Optional<TermId> findTermId(Value v, Dictionary dict, Arena arena) {
        IRI customDt = customDatatype(v);
        if (customDt == null) {
            return dict.findTermId(TermEncoder.encode(v, arena));
        }
        Optional<TermId> datatypeId = dict.findTermId(TermEncoder.encode(customDt, arena));
        if (datatypeId.isEmpty()) {
            return Optional.empty();
        }
        return dict.findTermId(
                TermCodec.encodeCustomLiteral(datatypeId.get(), ((Literal) v).getLabel(), arena));
    }

    /**
     * The datatype IRI iff {@code v} is a custom-datatype literal (no dedicated tag, no language
     * tag), else {@code null}. A language-tagged literal is {@code rdf:langString}, handled by the
     * language path, so it is never custom.
     */
    private static @Nullable IRI customDatatype(Value v) {
        if (v instanceof Literal lit
                && lit.getLanguage().isEmpty()
                && !TermEncoder.isDedicatedDatatype(lit.getDatatype())) {
            return lit.getDatatype();
        }
        return null;
    }
}
