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
package com.earasoft.prolly.rdf4j.compliance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a file-backed known-failures baseline (plan 10, §10.10).
 *
 * <p>The resource holds one W3C manifest test name (its {@code mf:name}) per line; {@code '#'}
 * starts a comment; blank lines are ignored. Each listed name is fed to {@code
 * SPARQLComplianceTest.addIgnoredTest}, which skips it.
 *
 * <p>This is the conformance <em>ratchet</em>: a test that is <em>not</em> on the list and starts
 * failing is not skipped, so it fails the build. Fixing a known failure means deleting its line.
 */
final class KnownFailures {

    private KnownFailures() {}

    /** Parse the classpath {@code resource} into the list of ignored test names. */
    static List<String> load(String resource) {
        InputStream in = KnownFailures.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("known-failures resource not found: " + resource);
        }
        List<String> names = new ArrayList<>();
        try (BufferedReader r =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.strip();
                if (!line.isEmpty()) {
                    names.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
        return names;
    }
}
