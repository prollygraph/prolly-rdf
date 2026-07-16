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

/**
 * Equivalent-mutant catalog for {@code TermCodec} — the documented justification for the mutation
 * survivors that are <b>not</b> killable (no input distinguishes them), kept so the next mutation
 * re-run (ADR-0043 campaign, Phase 2/3 "Step 13") does not have to re-derive them.
 *
 * <p><b>This class holds no live {@code @Test} any more — and that is the calibrated-honest record
 * of a retraction.</b> Its one killable-boundary test, {@code
 * gYearMonth_rejects_year_outside_int16}, defended the old fixed-width Int16 year cap (±32767, with
 * month/day range checks). Term-faithful storage (ADR-0043 Step 6, calendar types) removed that
 * encoding entirely: {@code encodeGYearMonth}/{@code encodeGYear}/{@code encodeDate} now store the
 * verbatim lexical bytes with no range check, so there is no boundary left to kill — any
 * well-formed year stores. The test was deleted rather than kept green against a vanished code
 * path. (The verbatim round-trip that replaced it is pinned by {@code TermCodecDateTest}.)
 *
 * <p>The surviving {@code TermCodec} mutants are <b>equivalent mutants</b>, justified here rather
 * than chased (no input distinguishes them):
 *
 * <ul>
 *   <li><b>2 × {@code & 0xFF} inside an exception-message</b> (decodeQuotedTriple,
 *       decodeQuotedQuad): AND→OR changes only the hex shown in a thrown error string, never the
 *       decoded value or control flow. (Was 3 ×; the {@code decodeBoolean} survivor retired when
 *       xsd:boolean went term-faithful — ADR-0043 Phase 1 — so the method no longer exists.)
 *   <li><b>6 × {@code if (len > 0) MemorySegment.copy(...)} guards</b> (encodeTaggedUtf8,
 *       encodeLangString ×2, encodeShortPrefixIri, encodeLongPrefixIri, encodeCustomLiteral):
 *       {@code >0}→{@code >=0} performs a zero-length copy, which is a no-op identical to skipping
 *       it. (Was 7 ×; {@code encodeRawBytes} retired when base64/hex went verbatim lexical — Step
 *       6e.)
 * </ul>
 *
 * <p>Retired survivor categories (the methods no longer exist): the {@code encodeIntegerBig}
 * zero-trim survivor (removed as xsd:integer went term-faithful, ADR-0043 Step 4a); the float NaN
 * lex-flip survivors (with the IEEE value encoding, Step 5); and the <b>2 × {@code
 * tzMinutesFromOffset} Short-range check</b> survivors — they were the last value-encoded-temporal
 * mutants, and retired when xsd:time went term-faithful (Step 6, time), deleting {@code
 * tzMinutesFromOffset} along with the UInt48 helpers. So every remaining survivor is one of the two
 * categories above. Step 13's mutation re-run re-establishes the authoritative count over the
 * term-faithful codec.
 */
class TermCodecBoundaryTest {}
