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
package com.earasoft.prolly.semantic;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Quad Pattern</h3>
 *
 * <p>Represents a single pattern in a graph query (Subject, Predicate, Object, Context). Fields are
 * modeled as {@link Iri} objects, which can represent constants or variables.
 */
public record QuadPattern(Iri s, Iri p, Iri o, @Nullable String c) {

    /**
     * Explicit canonical constructor — {@code c} (the context/graph token) is {@code @Nullable}
     * (null = the default graph), declared on the parameter so NullAway honors it at every {@code
     * new QuadPattern(...)} / {@link #of} site (a record's implicit canonical-constructor parameter
     * does not reliably inherit the component annotation).
     */
    public QuadPattern(Iri s, Iri p, Iri o, @Nullable String c) {
        this.s = s;
        this.p = p;
        this.o = o;
        this.c = c;
    }

    public static QuadPattern of(String s, String p, String o, @Nullable String c) {
        return new QuadPattern(Iri.of(s), Iri.of(p), Iri.of(o), c);
    }

    public boolean isVar(Iri field) {
        return field != null && field.isVar();
    }

    public int findVarIdx(String varName) {
        if (s.value().equals(varName)) return 0;
        if (p.value().equals(varName)) return 1;
        if (o.value().equals(varName)) return 2;
        if (c != null && c.equals(varName)) return 3;
        return -1;
    }
}
