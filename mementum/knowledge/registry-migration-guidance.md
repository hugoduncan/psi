---
title: Registry migration guidance
status: active
category: architecture
tags: [registries, migration, root-registry, testing]
related: ["munera/open/164-registry-semantics-unification-audit", "munera/closed/168-tool-registry-root-registry-migration", "munera/closed/167-command-registry-root-registry-migration"]
depends-on: []
---

Registry unification work needs an explicit migration checklist beyond storage rewrites.

## Core failure mode

A registry migration can appear complete when:
- the new authoritative storage owner is correct
- primary write paths work
- primary lookup/list APIs work

but still be incomplete because higher seams continue reading legacy local state:
- introspection/detail helpers
- resolver projections
- mutation result projections
- prompt/bootstrap rebuild paths
- derived counts, summaries, provenance, or ordering projections

Tasks 167 and 168 showed this clearly: ownership moved to the shared substrate, but one extension introspection seam still read extension-local state until full-suite verification exposed it.

## Required migration checklist

1. Name the new authoritative owner.
2. Enumerate all write seams.
3. Enumerate all read/projection/introspection seams.
4. Classify compatibility requirements per seam:
   - ordering
   - precedence
   - projected provenance fields
   - miss/throw behavior
5. Add focused migration-guard tests:
   - one for the main registry public API
   - one for a higher consumer seam such as detail/introspection/projection
6. Prove higher seams no longer read legacy local storage.
7. Run focused tests and full `bb test` before closing the task.

## Current sequencing guidance

- Best next root-registry-style migration target: `workflow-registry`
- Defer `deterministic-operation-registry` as a root-registry migration target while it remains runtime-object-owned and lifecycle-coupled.

## Why this matters

The main risk is no longer just semantic drift inside the registry itself. The more subtle risk is stale read ownership above the registry boundary. Migration work should therefore be evaluated as storage migration plus read-surface ownership migration, not storage migration alone.
