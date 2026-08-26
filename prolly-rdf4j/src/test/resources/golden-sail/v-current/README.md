# Golden persisted-sail fixture (v-current)

Written by GoldenSailFixtureRegen against the code at regen time; opened and
semantically asserted by GoldenSailOpenTest on every build. A failing open test
means THE ON-DISK FORMAT CHANGED: fix the code, or for a deliberate pre-1.0
format break regenerate (mvn -pl prolly-rdf4j test -Dtest=GoldenSailFixtureRegen
-Dgolden.regen=true -Djacoco.skip=true), review the open-test assertions (commit
ids embed timestamps — bytes churn every regen; semantics are the review
surface), and write the CHANGELOG format note. Contents: FileNodeStore chunks/,
RootMetaTree + CommitLog + Refs sidecars; ten statements covering the newest
format surface (an RDF-star quoted triple, a custom-datatype literal, a
lang-string, a named graph).
