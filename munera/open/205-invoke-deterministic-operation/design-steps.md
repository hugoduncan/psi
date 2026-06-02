# Design follow-up steps — 205

## Architecture-fit follow-ups

- [x] Resolve the listing read-path tension: operation *listing* is a read but
  the design routes it as a direct `registry/all-operations-in` call rather than
  through resolvers/EQL, contradicting the design's own "reads go through
  resolvers" alignment claim and the runtime-handle read model in
  doc/architecture.md. Either (a) surface listing via a resolver/EQL attribute
  consumed by both surfaces, or (b) document an explicit decision justifying the
  direct registry read as part of the runtime-boundary path; update design.md
  accordingly. (invoke as execution-at-boundary is fine and needs no change.)
