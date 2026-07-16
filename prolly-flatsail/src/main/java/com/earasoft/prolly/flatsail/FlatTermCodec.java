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
package com.earasoft.prolly.flatsail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Self-contained codec mapping an RDF4J {@link Value} to and from a flat {@code byte[]} for the
 * flat Sail's dictionary.
 *
 * <p><b>Why not reuse the versioned {@code TermCodec}?</b> Its <em>encode</em> half lives in {@code
 * prolly-codec}, but its <em>decode</em> half (turning encoded bytes back into a {@code Value})
 * lives in {@code prolly-rdf4j}'s tree-coupled {@code ProllyValue}/{@code PrefixTable}. Reusing it
 * would drag the versioned engine into the flat Sail — exactly what the {@code prolly-codec} split
 * set out to avoid. The flat Sail is unversioned with no Dolt bit-compatibility requirement, so it
 * owns this simple, fully reversible length-prefixed encoding instead.
 *
 * <p>Wire format — a 1-byte kind tag, then:
 *
 * <ul>
 *   <li><b>IRI</b> ({@code 0x01}): UTF-8 of the IRI string.
 *   <li><b>BNode</b> ({@code 0x02}): UTF-8 of the node id.
 *   <li><b>Literal</b> ({@code 0x03}): a length-prefixed language block, a length-prefixed
 *       datatype-IRI block, then the UTF-8 label (the rest of the buffer). Exactly one of language
 *       / datatype is non-empty.
 * </ul>
 */
public final class FlatTermCodec {

    private static final byte KIND_IRI = 1;
    private static final byte KIND_BNODE = 2;
    private static final byte KIND_LITERAL = 3;

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final byte[] EMPTY = new byte[0];
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private FlatTermCodec() {}

    /**
     * Encode an RDF4J {@link Value} (IRI, blank node or literal) to bytes.
     *
     * @throws IllegalArgumentException for an RDF-star {@code Triple} or any other unsupported
     *     value kind
     */
    public static byte[] encode(Value value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            if (value instanceof IRI iri) {
                out.writeByte(KIND_IRI);
                out.write(utf8(iri.stringValue()));
            } else if (value instanceof BNode bnode) {
                out.writeByte(KIND_BNODE);
                out.write(utf8(bnode.getID()));
            } else if (value instanceof Literal literal) {
                out.writeByte(KIND_LITERAL);
                String language = literal.getLanguage().orElse(null);
                if (language != null) {
                    writeBlock(out, utf8(language));
                    writeBlock(out, EMPTY); // datatype implied: rdf:langString
                } else {
                    writeBlock(out, EMPTY); // no language
                    writeBlock(out, utf8(literal.getDatatype().stringValue()));
                }
                out.write(utf8(literal.getLabel())); // remainder = label
            } else {
                throw new IllegalArgumentException(
                        "the flat dictionary does not support this Value kind: "
                                + value.getClass().getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream cannot actually throw
        }
        return bytes.toByteArray();
    }

    /** Decode bytes produced by {@link #encode} back to an RDF4J {@link Value}. */
    public static Value decode(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte kind = in.readByte();
            return switch (kind) {
                case KIND_IRI -> VF.createIRI(new String(in.readAllBytes(), UTF_8));
                case KIND_BNODE -> VF.createBNode(new String(in.readAllBytes(), UTF_8));
                case KIND_LITERAL -> {
                    String language = new String(readBlock(in), UTF_8);
                    String datatype = new String(readBlock(in), UTF_8);
                    String label = new String(in.readAllBytes(), UTF_8);
                    yield language.isEmpty()
                            ? VF.createLiteral(label, VF.createIRI(datatype))
                            : VF.createLiteral(label, language);
                }
                default ->
                        throw new IllegalArgumentException("unknown flat term-kind tag: " + kind);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBlock(DataOutputStream out, byte[] block) throws IOException {
        out.writeInt(block.length);
        out.write(block);
    }

    /**
     * Read a length-prefixed block, bounding the declared length against the buffer's remaining
     * bytes BEFORE allocating (untrusted-input-boundary-hardening Step 3) — the same untrusted-byte
     * guard {@code Commit.deserialize} applies. Without it a corrupt or hostile length from {@code
     * readInt()} turns {@code new byte[len]} into an out-of-memory (huge positive) or a {@code
     * NegativeArraySizeException} (negative). {@link #decode} is a trust boundary: the dictionary
     * bytes are content-addressed, but a truncated or adversarial term must fail with a controlled
     * exception, never crash the process.
     *
     * <p>{@code available()} is exact here — {@link #decode} always wraps a {@link
     * ByteArrayInputStream}, whose {@code available()} returns precisely the unread byte count.
     */
    private static byte[] readBlock(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > in.available()) {
            throw new IllegalArgumentException(
                    "FlatTermCodec: block length "
                            + len
                            + " out of bounds (available="
                            + in.available()
                            + ") — truncated or malformed term");
        }
        byte[] block = new byte[len];
        in.readFully(block);
        return block;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(UTF_8);
    }
}
