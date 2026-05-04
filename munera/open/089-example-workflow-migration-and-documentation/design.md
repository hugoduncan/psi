Goal: migrate representative workflows and documentation to the target converged workflow grammar so the new authoring model is proven in realistic examples and becomes the preferred path for new workflow authorship.

## Intent

The earlier child tasks make the target grammar executable. This task proves that authoring experience in practice by migrating a carefully chosen set of representative workflows and updating documentation to show the preferred converged style.

The aim is not only technical compatibility. It is to demonstrate that the new model is readable, teachable, and better aligned with real workflow author needs across:

- invoke steps
- session steps
- delegate steps
- shared data references
- outputs and yields
- control flow and judges

## Problem statement

Even with compilers and runtime support in place, the target grammar will not become real project guidance until:

- some representative workflows actually use it
- docs show how to author it
- examples cover mixed execution forms and data flow
- the migration path from current workflows is concrete rather than abstract

Without this slice:

- the target grammar may remain technically available but socially unused
- design flaws in readability or ergonomics may stay hidden
- future authors may continue copying old current-grammar shapes by inertia
- documentation drift between executable reality and author guidance may persist

## Scope

In scope:

- choose a small set of representative workflows to migrate or dual-express in target grammar
- ensure examples cover invoke/session/delegate composition where practical
- update or add user/developer-facing docs that explain the preferred target authoring model
- add notes where helpful about how current-grammar concepts map to target-grammar concepts
- add focused tests or verification for migrated example workflows as needed

Out of scope:

- migrating every existing workflow immediately
- retiring the current authored grammar entirely
- broad unrelated documentation cleanup

## Desired outcome

The project has concrete, working examples of the target grammar in use, and documentation makes the preferred authoring path obvious.

## Acceptance

- at least one representative workflow using the target grammar exists and runs
- examples/documentation cover invoke, session, and delegate semantics directly or through a small coherent set of migrated workflows
- docs explain the preferred target authoring model clearly enough for future workflow authors
- any current->target mapping guidance included in docs is concise and practical rather than compatibility-heavy
- migrated examples remain aligned with executable runtime behavior
