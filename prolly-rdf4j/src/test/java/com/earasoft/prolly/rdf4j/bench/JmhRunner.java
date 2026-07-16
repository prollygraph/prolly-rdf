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
package com.earasoft.prolly.rdf4j.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 *
 *
 * <h3>JMH entry point for the prolly-rdf4j Sail benchmarks.</h3>
 *
 * <p>Compile the module's test sources first ({@code mvn -pl prolly-rdf4j -am test-compile}), then
 * run with the test classpath:
 *
 * <pre>
 *   java --enable-preview --enable-native-access=ALL-UNNAMED \
 *        -cp "$CP" com.earasoft.prolly.rdf4j.bench.JmhRunner [JMH options] [include-regex]
 * </pre>
 *
 * <p><b>Full JMH option passthrough.</b> Every argument is parsed by JMH's own {@link
 * CommandLineOptions}, so the standard flags all work — {@code -f 3} (forks), {@code -prof gc}
 * (allocation profiler), {@code -i}/{@code -wi} (measurement / warmup iterations), {@code -p
 * key=val} (param override), {@code -rf json -rff out.json} (machine-readable results), and a
 * trailing regex to select a subset (e.g. {@code SailComparison} or {@code sparqlJoin}). For a
 * quick smell-test, override the rigorous defaults: {@code -f 1 -wi 1 -i 1}.
 *
 * <p><b>Two defaults this launcher layers on top</b> (only when the caller did not specify them),
 * per {@code plans/benchmarking-and-bottleneck-methodology.md} Phase 0 (D-3, D-6):
 *
 * <ul>
 *   <li><b>{@code -prof gc} is on by default</b> — bytes/op is reported beside wall-time, because
 *       on the JVM a wall-time gap is usually a GC-pressure gap (D-3). Pass any {@code -prof ...}
 *       to replace it.
 *   <li><b>The forked JVM's {@code java.io.tmpdir} defaults to {@code <repo>/target/benchtmp}</b>
 *       (real disk), <i>not</i> the inherited {@code /tmp} — which on this host is a small,
 *       quota-limited tmpfs that broke a disk-backed RocksDB benchmark mid-run. Override with
 *       {@code -Dbenchtmp=/some/roomy/disk}.
 * </ul>
 *
 * <p>The benchmark classes carry their own (multi-fork) warmup/measurement settings; the
 * passthrough above overrides them per-run when needed.
 */
public final class JmhRunner {
    public static void main(String[] args) throws Exception {
        // Parse with JMH's own CLI parser so -f/-prof/-i/-wi/-p/-rf all work.
        CommandLineOptions cmd = new CommandLineOptions(args);

        // Scratch dir off the quota-limited /tmp tmpfs (Phase 0, Step 3 / D-3).
        String benchTmp =
                System.getProperty("benchtmp", System.getProperty("user.dir") + "/target/benchtmp");
        Files.createDirectories(Path.of(benchTmp));

        ChainedOptionsBuilder b =
                new OptionsBuilder()
                        .parent(cmd)
                        .shouldFailOnError(true)
                        .shouldDoGC(true)
                        .jvmArgsAppend("-Djava.io.tmpdir=" + benchTmp);

        // Layer defaults only when the caller did not override them.
        if (cmd.getIncludes().isEmpty()) {
            b.include("com\\.earasoft\\.prolly\\.rdf4j\\.bench\\..*");
        }
        if (cmd.getProfilers().isEmpty()) {
            b.addProfiler(
                    GCProfiler.class); // default -prof gc (D-3: bytes/op as a first-class metric)
        }

        new Runner(b.build()).run();
    }
}
