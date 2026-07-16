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

/**
 *
 *
 * <h3>Internationalized Resource Identifier (IRI)</h3>
 *
 * <p>Represents a unique resource in a Knowledge Graph.
 */
public record Iri(String value) {
    public static Iri of(String value) {
        return new Iri(value);
    }

    public boolean isVar() {
        return value != null && value.startsWith("?");
    }

    @Override
    public String toString() {
        return isVar() ? value : "<" + value + ">";
    }
}
