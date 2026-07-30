# Workflow IR — Overview

This document defines the **normalized workflow IR** for deterministic workflow
steps.

The workflow IR is the canonical runtime execution model for authored workflow
files compiled from `doc/workflow-grammar.md`.

It is intentionally not a user-facing authoring grammar. It is the normalized
execution boundary used by runtime execution.

## Purpose

The workflow IR exists to make the runtime independent of authored syntax.

It gives execution one canonical model for:

- step identity
- execution form
- control flow
- judge execution
- data references
- session construction
- delegated boundaries
- step-local outputs
- yielded values
- workflow result composition

The runtime should execute IR, not raw authored workflow documents.

## Design properties

The IR should have these properties:

- **Canonical** — one execution model for all authored workflow files
- **Explicit** — execution form, references, outputs, and yielded value are visible in data
- **Typed by tag** — execution form and yielded-value semantics use explicit `:type` discrimination
- **Observable** — runtime can record inspectable effective boundary inputs for each step form
- **Small** — only execution-relevant concepts belong here

## IR overview

A normalized workflow IR is a map with ordered steps.

Illustrative top-level shape:

```clojure
{:version :workflow-ir/v1
 :steps [ir-step+]}
```

The normalized IR boundary requires at least one step. Empty workflows are invalid IR and should be rejected before execution.

This IR is the **compiled execution model**, not an authored workflow surface.
It is intentionally close to the workflow grammar while remaining free to carry
runtime-oriented normalization detail.

The ordered `:steps` vector is the canonical authored/program order.

The runtime may derive a name index or graph index as needed, but those are execution-time conveniences rather than authored meaning.
