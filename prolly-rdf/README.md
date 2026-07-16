# prolly-rdf — the versioned RDF engine

RDF quads on prolly trees: four maintained SPOC permutation indexes, dictionary-encoded
terms, diff/merge over the commit DAG, reachability garbage collection, and the
worst-case-optimal `LeapfrogTriejoin` (cyclic basic graph patterns route through it; see
ADR-0065 in [`../prolly-rdf4j/docs/adr/`](../prolly-rdf4j/docs/adr/)). This is the
engine the RDF4J Sail fronts — usable embedded, without RDF4J, if you speak its API
directly. Background reading: [`../docs/the-chunk-store.md`](../docs/the-chunk-store.md)
and [`../docs/prior-art.md`](../docs/prior-art.md).
