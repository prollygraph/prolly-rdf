# Releasing prolly-rdf

The ring versions in **lockstep with the engine ring**
([prollygraph/prolly-core](https://github.com/prollygraph/prolly-core)) — both are
`0.2.0-BETA`; a version bump is coordinated across the two repos, engine first (this
ring's builds resolve the engine's artifacts at `${project.version}`).

Publication paths mirror the engine repo's `RELEASING.md` (GitHub Packages workflow +
the Maven Central Publisher Portal recipe) and inherit its gates — Portal registration,
GPG signing key, and the visibility flip are **owner-held**; no publication is a side
effect of a push. The engine ring must be publicly resolvable BEFORE any public artifact
of this ring can be consumed (a published pom referencing unresolvable dependencies is
broken for every consumer).

Pre-publication checklist (beyond the engine repo's list):
- Generalize the consumer-name javadoc residue (`prolly-rdf4j-enterprise`, rest-face
  mentions) — extension-point docs should name the SPI, not private modules.
- Re-run the publication-hygiene scans (secrets, internal hosts, private-marker
  strings) from the extraction plan.
