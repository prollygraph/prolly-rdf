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
package com.earasoft.prolly.bench;

import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the cost of the literal-encode <b>dispatch mechanism</b> — the current {@code
 * TermEncoder.encodeLiteral} {@code if}-chain of {@code dt.equals(XSD.*)} vs a registry ({@code
 * Map<IRI, BiFunction>}) — to settle whether replacing the if-chain with a registry on the encode
 * hot path has a real performance difference (asked on plans/spec-compliance-catalog-guard.md D-2).
 *
 * @implNote Isolating microbench with a control arm (CLAUDE.md benchmarking discipline). All arms
 *     do the SAME encode work — they are restricted to datatypes whose encoder is a public {@code
 *     TermCodec.encodeXxx} method, so the if-chain branch and the registry lambda call the
 *     identical method; only the DISPATCH differs. Arms:
 *     <ul>
 *       <li>{@code control_string_directCall} — no dispatch at all (the encode-work floor).
 *       <li>{@code ifChain_string} — the real if-chain, xsd:string (the FIRST branch + the common
 *           case): where the if-chain is already optimal (1 equals + a monomorphic, inlinable
 *           call).
 *       <li>{@code registry_string_monomorphic} — the registry on a single type (its best case: the
 *           {@code apply} site is monomorphic, so the JIT can still inline).
 *       <li>{@code ifChain_hexBinary} — the if-chain on the LAST branch (~24 equals scanned).
 *       <li>{@code ifChain_mixed} / {@code registry_mixed} — THE realistic A/B over a string-heavy
 *           mixed workload. The registry's single {@code apply} site sees 9 lambda types here →
 *           <b>megamorphic</b>, so the JIT cannot inline it — the effect that can make O(1) lookup
 *           lose to the O(N) if-chain's inlinable direct calls.
 *     </ul>
 *     {@code @OperationsPerInvocation(100)} so JMH reports ns per single encode (each arm does 100
 *     encodes into one confined {@link Arena}, amortizing the arena create/close so the delta is
 *     dispatch, not allocation).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(TermDispatchBenchmark.OPS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class TermDispatchBenchmark {

    static final int OPS = 100;
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final String STR = "hello world example value";

    private Literal strLit;
    private Literal hexLit;
    private Literal[] mixed;
    private Map<IRI, BiFunction<String, Arena, MemorySegment>> registry;

    @Setup
    public void setup() {
        strLit = VF.createLiteral(STR);
        hexLit = VF.createLiteral("0A1B2C3D4E5F", XSD.HEXBINARY);

        // Realistic, string-heavy RDF mix — datatypes whose if-chain branch calls a PUBLIC
        // TermCodec encoder (so the registry can call the identical method). 20 literals: 12
        // strings (~60%) + numerics/bool/anyURI/binary spread across early..last branch positions.
        List<Literal> m = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            m.add(VF.createLiteral("value-" + i + "-some object text"));
        }
        m.add(VF.createLiteral("9223372036854775807", XSD.LONG));
        m.add(VF.createLiteral("3.14159265358979", XSD.DOUBLE));
        m.add(VF.createLiteral("2.5", XSD.FLOAT));
        m.add(VF.createLiteral(true));
        m.add(VF.createLiteral("123.456", XSD.DECIMAL));
        m.add(VF.createLiteral("http://example.org/resource/x", XSD.ANYURI));
        m.add(VF.createLiteral("0A1B2C3D", XSD.HEXBINARY));
        m.add(VF.createLiteral("SGVsbG8gd29ybGQ=", XSD.BASE64BINARY));
        mixed = m.toArray(new Literal[0]);

        registry = new HashMap<>();
        registry.put(XSD.STRING, TermCodec::encodeXsdString);
        registry.put(XSD.ANYURI, TermCodec::encodeAnyURI);
        registry.put(XSD.BOOLEAN, TermCodec::encodeBoolean);
        registry.put(XSD.LONG, TermCodec::encodeLong);
        registry.put(XSD.FLOAT, TermCodec::encodeFloat32);
        registry.put(XSD.DOUBLE, TermCodec::encodeFloat64);
        registry.put(XSD.DECIMAL, TermCodec::encodeDecimal);
        registry.put(XSD.HEXBINARY, TermCodec::encodeHexBinary);
        registry.put(XSD.BASE64BINARY, TermCodec::encodeBase64Binary);
    }

    @Benchmark
    public void control_string_directCall(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < OPS; i++) {
                bh.consume(TermCodec.encodeXsdString(STR, a));
            }
        }
    }

    @Benchmark
    public void ifChain_string(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < OPS; i++) {
                bh.consume(TermEncoder.encode(strLit, a));
            }
        }
    }

    @Benchmark
    public void registry_string_monomorphic(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < OPS; i++) {
                bh.consume(registry.get(XSD.STRING).apply(STR, a));
            }
        }
    }

    @Benchmark
    public void ifChain_hexBinary(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < OPS; i++) {
                bh.consume(TermEncoder.encode(hexLit, a));
            }
        }
    }

    @Benchmark
    public void ifChain_mixed(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int r = 0; r < OPS / 20; r++) {
                for (Literal lit : mixed) {
                    bh.consume(TermEncoder.encode(lit, a));
                }
            }
        }
    }

    @Benchmark
    public void registry_mixed(Blackhole bh) {
        try (Arena a = Arena.ofConfined()) {
            for (int r = 0; r < OPS / 20; r++) {
                for (Literal lit : mixed) {
                    bh.consume(registry.get(lit.getDatatype()).apply(lit.getLabel(), a));
                }
            }
        }
    }
}
