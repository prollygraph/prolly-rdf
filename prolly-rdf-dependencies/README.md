# prolly-rdf-dependencies — the ring's dependency BOM

The Maven Bill of Materials for the RDF ring: the single source of truth for
third-party dependency versions, importable by downstream consumers to pin a coherent
set (the way Spring Boot ships `spring-boot-dependencies`). This is the *Maven
dependency* meaning of "BOM" — not the CycloneDX *Software* Bill of Materials the
build also emits per module. Two deliberate design points (ADR-0042 in
[`../prolly-rdf4j/docs/adr/`](../prolly-rdf4j/docs/adr/)):

- **Parentless, by necessity and by design.** If this module inherited the ring's root
  pom — which imports it — Maven would reject the self-import cycle outright. And a
  publishable BOM should be parentless anyway: an importer inherits only version
  management, never this repo's compiler/surefire/coverage build config. It is listed
  first in the root pom's `<modules>` so it is model-resolvable before the parent's
  import, yet inherits nothing from it.
- **Dependency versions only.** Plugin and build-tool versions stay in the root pom —
  a child module's `<build>` section cannot read an imported BOM's properties.

What it manages: the RDF4J line (the `rdf4j-bom` 5.1.4 import), the engine ring's
`dolthub-java-port` + `prolly-storage` (so a downstream consumer can resolve them
without declaring versions), and the alignment pins a coherent build needs — declared
*before* the `rdf4j-bom` import on purpose, because Maven `dependencyManagement` is
first-wins and `rdf4j-bom` transitively drags an old logging pair (logback 1.2 /
slf4j 1.7) plus older jackson-annotations, mockito, and JUnit coordinates. The
explicit pins (slf4j 2.x + logback 1.5, log4j bridges, jackson-annotations, mockito,
the JUnit BOM, jqwik, caffeine, jspecify) keep the ring converged and match Spring
Boot 4's managed set, per the pom's inline comments, so a Boot-based consumer
converges without fighting the same drag.

`src/it/external-consumer/` is the importability fixture: a foreign-groupId,
parentless pom that imports only this BOM and declares no dependency versions. If the
BOM is a valid externally-importable artifact, resolution succeeds; if not, Maven
model-building fails with "version missing". It is deliberately *not* in the reactor
(it simulates a separate downstream build) — after a `mvn install`, run:

```bash
mvn -f prolly-rdf-dependencies/src/it/external-consumer/pom.xml dependency:tree
```

The pom's inline comments carry the full rationale per pin — this file is the map,
the pom is the territory.
