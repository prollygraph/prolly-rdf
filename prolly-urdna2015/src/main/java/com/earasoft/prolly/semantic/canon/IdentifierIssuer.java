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
package com.earasoft.prolly.semantic.canon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Canonical-identifier issuer for URDNA2015 / RDFC-1.0.</h3>
 *
 * <p>Stateful helper that issues sequential canonical blank-node names with the form {@code
 * _:<prefix><counter>}: {@code _:c14n0}, {@code _:c14n1}, … for a prefix of {@code "c14n"}.
 *
 * <h4>Required properties (see URDNA2015_IMPLEMENTATION_GUIDE §3)</h4>
 *
 * <ol>
 *   <li><strong>Idempotent</strong> — {@link #issue(String)} returns the same name for the same
 *       input id on every call. Issuing an already-issued id is a no-op.
 *   <li><strong>Issuance-ordered</strong> — {@link #issuedOrder()} returns ids in the order they
 *       were first issued, not lexicographic order. {@link LinkedHashMap} is the backing store
 *       specifically to preserve this; using a {@link java.util.HashMap} would produce
 *       non-deterministic output.
 *   <li><strong>Deep-cloneable</strong> — {@link #copy()} produces an independent issuer whose
 *       mutations do not affect the original. Used by HashNDegreeQuads when trying a permutation
 *       without committing.
 * </ol>
 *
 * <h4>Threading</h4>
 *
 * <p>Not thread-safe. URDNA2015's permutation loop is single-threaded per canonicalize() call;
 * concurrent use would require external synchronisation.
 */
public final class IdentifierIssuer {

    private final String prefix;
    private final LinkedHashMap<String, String> issued;
    private int counter;

    /**
     * Construct an issuer with the given prefix. Output names will have the form {@code
     * _:<prefix>0}, {@code _:<prefix>1}, ….
     *
     * @param prefix the issuance prefix (must be non-null and non-blank; typical values are {@code
     *     "c14n"} for the global canonical issuer and {@code "b"} for per-call temporary issuers).
     */
    public IdentifierIssuer(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is required and must be non-blank");
        }
        this.prefix = prefix;
        this.issued = new LinkedHashMap<>();
        this.counter = 0;
    }

    /**
     * Issue a canonical name for {@code id} (or return the existing name if {@code id} has been
     * issued before).
     *
     * @return the canonical name (always begins with {@code "_:" + prefix})
     */
    public String issue(String id) {
        Objects.requireNonNull(id, "id");
        String existing = issued.get(id);
        if (existing != null) return existing;
        String name = "_:" + prefix + counter++;
        issued.put(id, name);
        return name;
    }

    /** True if {@code id} has been issued; false otherwise. */
    public boolean hasIssued(String id) {
        return id != null && issued.containsKey(id);
    }

    /**
     * Returns the canonical name previously issued to {@code id}, or {@code null} if {@code id} has
     * not been issued.
     */
    public @Nullable String nameOf(String id) {
        return issued.get(id);
    }

    /** Ids in their issuance order (not lexicographic). */
    public List<String> issuedOrder() {
        return new ArrayList<>(issued.keySet());
    }

    /** Unmodifiable view of the {@code id → name} mapping. */
    public Map<String, String> idMap() {
        return Collections.unmodifiableMap(issued);
    }

    /** Issuance prefix supplied at construction. */
    public String prefix() {
        return prefix;
    }

    /** Number of ids issued so far. */
    public int size() {
        return issued.size();
    }

    /**
     * Deep clone: the returned issuer has the same prefix, same issued mapping (copied), and same
     * counter. Mutations to the clone do not affect this issuer.
     *
     * <p>Used by HashNDegreeQuads in the permutation try-block: each permutation gets its own
     * clone, and only the winning permutation's clone is merged back into the outer issuer (per
     * URDNA2015_IMPLEMENTATION_GUIDE §6.3).
     */
    public IdentifierIssuer copy() {
        IdentifierIssuer c = new IdentifierIssuer(prefix);
        c.issued.putAll(this.issued);
        c.counter = this.counter;
        return c;
    }

    @Override
    public String toString() {
        return "IdentifierIssuer{prefix='" + prefix + "', size=" + issued.size() + "}";
    }
}
