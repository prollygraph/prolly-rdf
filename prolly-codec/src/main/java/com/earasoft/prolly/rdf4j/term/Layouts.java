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
package com.earasoft.prolly.rdf4j.term;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Canonical {@link ValueLayout} constants for the prolly-rdf4j encoded-term format.
 *
 * <p>All multi-byte reads from term payloads MUST use these constants. The default {@code
 * ValueLayout.JAVA_LONG} etc. are native-byte-order (little-endian on x86/ARM) AND
 * alignment-required, both wrong for our packed big-endian encoding.
 *
 * <p>The pattern matches {@link com.dolthub.prolly.TypeCodec}, which already uses {@code
 * JAVA_LONG_UNALIGNED.withOrder(BIG_ENDIAN)} for lex-encoded values.
 *
 * <p>The {@code BE*_U} constants are for lex-encoded payloads (sortable by raw bytes). The {@code
 * LE*_U} constants are for runtime native-order values.
 */
public final class Layouts {
    private Layouts() {}

    /** Big-endian, unaligned 64-bit integer — lex-encoded longs. */
    public static final ValueLayout.OfLong BE64_U =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Big-endian, unaligned 32-bit integer. */
    public static final ValueLayout.OfInt BE32_U =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Big-endian, unaligned 16-bit integer. */
    public static final ValueLayout.OfShort BE16_U =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Big-endian, unaligned 64-bit IEEE-754 double. */
    public static final ValueLayout.OfDouble BE_F64_U =
            ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Big-endian, unaligned 32-bit IEEE-754 float. */
    public static final ValueLayout.OfFloat BE_F32_U =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Little-endian, unaligned 64-bit integer — for runtime values. */
    public static final ValueLayout.OfLong LE64_U =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Little-endian, unaligned 32-bit integer. */
    public static final ValueLayout.OfInt LE32_U =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Little-endian, unaligned 16-bit integer — Tuple offset/count footer. */
    public static final ValueLayout.OfShort LE16_U =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Single byte (order-irrelevant). */
    public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
}
