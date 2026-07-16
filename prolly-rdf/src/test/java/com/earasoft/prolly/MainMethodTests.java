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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 *
 *
 * <h3>Main-method Test Suite Driver</h3>
 *
 * <p>Bridges the project's main-method-style tests into JUnit 5 so {@code mvn test} actually runs
 * them. The driver scans the test classpath for any class ending in {@code "Test"} that exposes a
 * {@code public static void main(String[])} method, then emits one {@link DynamicTest} per match.
 * Classes whose name ends in {@code "Demo"} are excluded — they are illustrative and should not
 * gate CI.
 *
 * <p>Why this exists: every test in this codebase was written as a stand-alone {@code main()} that
 * throws on failure. Without this driver Surefire would discover zero tests and CI would silently
 * pass.
 */
public class MainMethodTests {

    @TestFactory
    Stream<DynamicTest> runAllMainMethodTests() throws Exception {
        // Scan THIS module's own package root (com.earasoft.prolly). The
        // earlier version scanned com/dolthub/prolly — port-core's package —
        // which resolves to a "jar:" URL here (port-core is a dependency
        // JAR), so the driver returned Stream.empty() and silently ran zero
        // of this module's ~44 main-method tests.
        URL url = Thread.currentThread().getContextClassLoader().getResource("com/earasoft/prolly");
        if (url == null) return Stream.empty();
        // Defensive: if this module's classes are themselves resolved from a
        // JAR (Paths.get(URI) would throw FileSystemNotFoundException), skip
        // rather than fail. Under a normal `mvn test` the test-classes
        // directory is a "file:" URL.
        if (!"file".equals(url.getProtocol())) return Stream.empty();
        Path pkgRoot = Paths.get(url.toURI());

        // Optional focus: -Dprolly.mainmethod.only=<SimpleClassName> runs just
        // that one main-method test instead of all of them. The -Pcross-lang
        // profile uses it to run ONLY CrossLanguageFixtureTest as its own job
        // (the validator is a main()-style test, so surefire can't target it
        // directly — this driver is its only entry point). Unset = run all.
        String only = System.getProperty("prolly.mainmethod.only");

        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pkgRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Test.class"))
                    .filter(p -> !p.getFileName().toString().contains("Demo"))
                    .filter(p -> !p.getFileName().toString().equals("MainMethodTests.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(
                            p ->
                                    only == null
                                            || only.isBlank()
                                            || p.getFileName().toString().equals(only + ".class"))
                    .sorted()
                    .forEach(
                            p -> {
                                String fqcn =
                                        "com.earasoft.prolly."
                                                + pkgRoot.relativize(p)
                                                        .toString()
                                                        .replace(java.io.File.separatorChar, '.')
                                                        .replaceFirst("\\.class$", "");
                                tests.add(DynamicTest.dynamicTest(fqcn, () -> invokeMain(fqcn)));
                            });
        }
        return tests.stream();
    }

    private static void invokeMain(String fqcn) throws Throwable {
        Class<?> cls = Class.forName(fqcn);
        Method main;
        try {
            main = cls.getMethod("main", String[].class);
        } catch (NoSuchMethodException nsme) {
            return; // class matched the *Test pattern but isn't a main-style test
        }
        if (!Modifier.isStatic(main.getModifiers())) return;
        try {
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }
}
