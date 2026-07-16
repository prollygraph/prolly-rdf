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

import com.dolthub.prolly.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 *
 *
 * <h3>JMH Benchmark Entry Point</h3>
 *
 * <p>Run all benchmarks:
 *
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -cp target/classes:target/test-classes:&lt;rocks.jar&gt;:&lt;flatbuffers.jar&gt;:&lt;jmh-core.jar&gt;:&lt;jopt.jar&gt;:&lt;commons-math3.jar&gt; \
 *      com.dolthub.prolly.bench.JmhRunner
 * </pre>
 *
 * <p>Run a subset by name pattern:
 *
 * <pre>
 *   java ... com.dolthub.prolly.bench.JmhRunner BuzHash    # all BuzHash*
 *   java ... com.dolthub.prolly.bench.JmhRunner compareBinaryParity
 * </pre>
 *
 * <p>The pattern is a regex matched against fully-qualified benchmark names ({@code
 * com.dolthub.prolly.bench.X.method}). Default warmup/measurement settings are tuned for ~30 sec
 * per benchmark; full suite is ~5–10 min. Override via JMH's standard system properties (e.g.
 * {@code -Djmh.iterations=10}) or by editing this runner.
 */
public final class JmhRunner {
    public static void main(String[] args) throws Exception {
        OptionsBuilder builder = new OptionsBuilder();
        builder.include(args.length > 0 ? args[0] : ".*Benchmark.*");
        builder.shouldFailOnError(true);
        builder.shouldDoGC(true);
        Options opt = builder.build();
        new Runner(opt).run();
    }
}
