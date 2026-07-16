
# OWL vs RDFS — when each helps

> **TL;DR:** Use **RDFS** when you just want *"this thing is a kind of
> that thing, with these properties."* Reach for **OWL** when you need
> set-theoretic class construction, property characteristics
> (functional / transitive / inverse / etc.), cardinality constraints,
> identity assertions, or reasoning beyond simple type / subclass
> inference.

The prolly-rdf4j Schema viewer
renders both vocabularies. This doc is the in-depth companion — a
polished HTML version is served by the running SPA at
`http://localhost:8080/OWL_VS_RDFS.html`.

## 1 · What RDFS gives you

RDFS ([W3C RDF Schema 1.1](https://www.w3.org/TR/rdf-schema/)) is the
minimum-viable vocabulary for declaring "this exists":

- `rdfs:Class` — declares that an IRI names a class
- `rdfs:subClassOf` — subset relation; every instance of A is also of B
- `rdf:Property` — declares an IRI is a predicate
- `rdfs:subPropertyOf` — subset relation on triples
- `rdfs:domain` — type-infer the subject of a property
- `rdfs:range` — same on the object slot
- `rdfs:label` / `rdfs:comment` — human-readable annotations (no
  reasoning weight)
- `rdfs:seeAlso` / `rdfs:isDefinedBy` — soft links

RDFS entailment is small: subclass / subproperty transitive closure +
domain / range type inference. PTIME, no profile tradeoffs, no
surprises.

## 2 · Class construction — RDFS has none of this

OWL lets you *define* classes from other classes using set-theoretic
operations:

| OWL term                  | Meaning                                                 |
|---------------------------|---------------------------------------------------------|
| `owl:equivalentClass`     | Two classes have exactly the same members                |
| `owl:disjointWith`        | No instance can be in both                               |
| `owl:unionOf`             | Class = instances of any listed class                    |
| `owl:intersectionOf`      | Class = instances of all listed classes                  |
| `owl:complementOf`        | Class = everything not in another                        |
| `owl:oneOf`               | Enumerate exact members (`{Mon, Tue, Wed, ...}`)         |

## 3 · Restrictions — class = "things satisfying property constraint"

The single most powerful OWL feature without an RDFS analog.

| Restriction                  | Says                                                     |
|------------------------------|----------------------------------------------------------|
| `owl:someValuesFrom` (∃)     | Instance has at least one P-value of the given class     |
| `owl:allValuesFrom` (∀)      | Every P-value of the instance is of the given class      |
| `owl:hasValue`               | Instance has this specific P-value                       |
| `owl:hasSelf`                | Instance is its own P-value (reflexive)                  |
| `owl:minCardinality`         | Instance has at least N P-values                         |
| `owl:maxCardinality`         | Instance has at most N P-values                          |
| `owl:cardinality`            | Instance has exactly N P-values                          |

Example — a married couple has exactly two members:

```turtle
:MarriedCouple a owl:Class ;
  rdfs:subClassOf [
    a owl:Restriction ;
    owl:onProperty :hasMember ;
    owl:cardinality 2 ;
    owl:onClass :Person
  ] .
```

## 4 · Property semantics

RDFS gives you `rdfs:subPropertyOf` / `rdfs:domain` / `rdfs:range`.
OWL adds a vocabulary for the algebra of properties:

| Term                              | Says                                                              |
|-----------------------------------|-------------------------------------------------------------------|
| `owl:ObjectProperty`              | Range is IRIs                                                     |
| `owl:DatatypeProperty`            | Range is literals                                                 |
| `owl:FunctionalProperty`          | At most one value per subject (e.g., `foaf:birthDate`)            |
| `owl:InverseFunctionalProperty`   | Value uniquely identifies the subject (e.g., `foaf:mbox`)         |
| `owl:TransitiveProperty`          | `a P b ∧ b P c ⇒ a P c` (e.g., `:ancestorOf`)                    |
| `owl:SymmetricProperty`           | `a P b ⇒ b P a` (e.g., `foaf:knows`)                              |
| `owl:AsymmetricProperty`          | `a P b ⇒ ¬(b P a)`                                                |
| `owl:ReflexiveProperty`           | Every individual P itself                                         |
| `owl:IrreflexiveProperty`         | No individual P itself                                            |
| `owl:inverseOf`                   | Paired inverses: `:parentOf` ↔ `:childOf`                         |
| `owl:propertyChainAxiom`          | `:hasUncle ≡ :hasParent ∘ :hasBrother`                            |
| `owl:propertyDisjointWith`        | Two properties never overlap on the same triple                   |

## 5 · Identity assertions

RDF and RDFS make no unique-name assumption — two IRIs *could* refer
to the same thing, and there's no vocabulary to say so either way.
OWL adds:

- `owl:sameAs` — two IRIs denote the same individual
- `owl:differentFrom` — two IRIs denote different individuals
- `owl:AllDifferent` — bulk pairwise-distinct declaration

> ⚠ Without explicit `differentFrom`, a reasoner may unify two IRIs
> you intended to be distinct. State it when correctness depends on
> it.

## 6 · Annotations & meta

Both RDFS (`rdfs:label`, `rdfs:comment`) and OWL ship annotation
vocabularies. OWL adds lifecycle hooks:

- `owl:deprecated` — mark as obsolete
- `owl:versionInfo` — free-form version string
- `owl:imports` — bring another ontology's axioms into scope
- `owl:AnnotationProperty` — mark a property as informational (skipped
  by reasoners) — preserves the data-vs-metadata distinction

## 7 · Reasoning — the qualitative jump

RDFS entailment:
- Subclass transitive closure: `A ⊑ B ⊑ C ⇒ A ⊑ C`
- Subproperty transitive closure: same shape
- Type from `rdfs:domain`: `?s P ?o ∧ P rdfs:domain C ⇒ ?s a C`
- Type from `rdfs:range`: same for the object

That's it. Computable in a single pass.

OWL reasoning catches a lot more:

```
:John foaf:knows :Mary
foaf:knows a owl:SymmetricProperty
  ⇒ :Mary foaf:knows :John
```

```
:Alice :parentOf :Bob
:Bob :parentOf :Carol
:hasAncestor owl:propertyChainAxiom (:parentOf :parentOf)
  ⇒ :Alice :hasAncestor :Carol
```

```
:Person owl:disjointWith :Document
:foo a :Person
:foo a :Document
  ⇒ contradiction (reasoner flags inconsistency)
```

```
:Parent ≡ :Person ⊓ ∃ :hasChild .:Person
:Alice a :Person ; :hasChild :Bob
:Bob a :Person
  ⇒ :Alice a :Parent
```

The product feature: restriction-based classes mean instances get
classified into classes they were never explicitly typed as.

## 8 · OWL 2 profiles

OWL Full is undecidable in general. OWL 2 defines decidable
sub-languages, each trading expressivity for tractability:

| Profile       | Complexity              | Tuned for                                |
|---------------|-------------------------|------------------------------------------|
| **OWL 2 EL**  | PTIME                   | Large terminologies (SNOMED CT)          |
| **OWL 2 QL**  | AC⁰ in data complexity  | Query rewriting over relational DBs      |
| **OWL 2 RL**  | PTIME                   | Rule-based reasoners (forward-chaining)  |
| **OWL 2 DL**  | N2EXPTIME-complete      | Maximum expressivity, still decidable    |
| **OWL 2 Full**| Undecidable             | Whatever you want, no termination guarantee |

Pick a profile up front — picking later forces ontology rewrites.

## 9 · Practical decision guide

| If you want to…                                              | Use                                |
|--------------------------------------------------------------|------------------------------------|
| Declare "X is a kind of Y" / "P is a property of X"          | **RDFS**                           |
| Constrain how many P-values an instance can have             | OWL — restrictions + cardinality   |
| Combine classes algebraically                                | OWL — class constructors           |
| Get automatic symmetric / transitive / inverse inference     | OWL — property characteristics     |
| Enforce "only one email per person"                          | OWL — `FunctionalProperty`         |
| State two IRIs are the same individual                       | OWL — `owl:sameAs`                 |
| Auto-classify instances by their properties                  | OWL — restriction-based classes    |
| Detect when your data contradicts itself                     | OWL — `disjointWith` + reasoner    |
| Browse a vocabulary without a reasoner in the loop           | **RDFS**                           |
| Stay PTIME on large data with no profile decisions           | **RDFS** — or OWL 2 EL / RL        |

**Rule of thumb:** RDFS for cataloguing what exists. OWL when your
application's correctness depends on logic the engine can apply for
you (consistency, classification, identity resolution).

## 10 · Spec references

- [RDF Schema 1.1](https://www.w3.org/TR/rdf-schema/) — the RDFS spec
- [OWL 2 Primer](https://www.w3.org/TR/owl2-primer/) — gentlest intro
- [OWL 2 Document Overview](https://www.w3.org/TR/owl2-overview/) — map
- [OWL 2 Profiles](https://www.w3.org/TR/owl2-profiles/) — EL / QL / RL / DL definitions
- [OWL 2 Quick Reference](https://www.w3.org/TR/owl2-quick-reference/) — single-page cheatsheet
