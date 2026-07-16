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
package com.earasoft.prolly.rdf4j.sail;

import org.eclipse.rdf4j.sail.SailException;

/**
 * Thrown when {@link ProllySailConnection#commitInternal} cannot land a commit after the configured
 * retry cap. Indicates either pathological write contention or a bug in the rebase logic.
 *
 * <p>Callers can catch this specifically to decide whether to retry the entire transaction at the
 * application level, vs treating it as a fatal sail error.
 *
 * <p>v2.0 single-writer: this class is plumbed but never thrown. Phase 4 multi-writer wires the
 * retry loop that may throw this. See {@code docs/cas-rebase.md} for the design and {@code
 * docs/cas-rebase-runbook.md} Step 8 for the planned throw site.
 */
public class SailConflictException extends SailException {

    public SailConflictException(String msg) {
        super(msg);
    }

    public SailConflictException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
