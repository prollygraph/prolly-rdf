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
package com.earasoft.prolly.rdf4j.value;

import org.eclipse.rdf4j.model.Value;

/**
 * Marker interface for prolly-rdf4j {@link Value} implementations backed by {@link
 * java.lang.foreign.MemorySegment} slices.
 *
 * <p>Sealed to {@link ProllyIRI}, {@link ProllyBNode}, {@link ProllyLiteral}. The {@link
 * org.eclipse.rdf4j.model.Triple} variant for RDF-star arrives in a later iter.
 *
 * <p>See {@code ARCHITECTURE.md §4.3} for the lifetime / equality contract.
 */
public sealed interface ProllyValue extends Value
        permits ProllyIRI, ProllyBNode, ProllyLiteral, ProllyTriple {}
