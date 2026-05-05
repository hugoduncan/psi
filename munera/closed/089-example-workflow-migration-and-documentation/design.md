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

- choose a small authoritative set of representative workflows to migrate or dual-express in target grammar
- ensure the authoritative example set covers invoke/session/delegate semantics and the currently teachable shared data-flow semantics, allowing that coverage to be distributed across a small coherent workflow set rather than forced into one monolithic example
- update or add user/developer-facing docs that explain the preferred target authoring model
- add notes where helpful about how current-grammar concepts map to target-grammar concepts
- add focused tests or verification for migrated example workflows as needed

Out of scope:

- migrating every existing workflow immediately
- retiring the current authored grammar entirely
- broad unrelated documentation cleanup

## Desired outcome

The project has concrete, working examples of the target grammar in use, and documentation makes the preferred authoring path obvious.

## Authoritative minimum example set

The minimum authoritative migrated example set for this task is:

- `plan-build` as the compact target-grammar session/invoke teaching example for the common planning→execution chain
- `plan-build-review` as the compact target-grammar session/invoke multi-step example that also shows downstream review wiring
- `gh-bug-triage-modular` as the richer target-grammar example that demonstrates delegate composition, shared references, and preloaded/reference context across a realistic orchestration

Coverage does not need to be concentrated into one executable example. It may be distributed across this small workflow set plus the supporting docs, as long as the set collectively demonstrates:

- target-grammar step authoring shape
- invoke/session/delegate semantics
- shared data references plus contextual `:session` input/reference/preload flow where those surfaces are currently implemented and teachable

If implementation reality in task `077` still leaves one of those surfaces partially unavailable in authored examples, the docs must name that boundary explicitly rather than silently implying broader support.

## Authoritative documentation surface

The primary documentation surface for teaching the preferred target authoring path is `doc/workflows.md`.

Required secondary documentation updates may include:

- workflow example file descriptions under `.psi/workflows/*.md` when they need to explain why an example is representative
- cross-links or concise clarifications in the workflow grammar docs when example-led guidance should point readers to the formal grammar/reference

`README.md` may link to the workflow docs, but this task does not require making `README.md` the primary authoring guide. The implementation should treat `doc/workflows.md` as the canonical example-led workflow authoring guide for this slice.

## Acceptance

- at least one representative workflow using the target grammar exists and runs
- examples/documentation cover invoke, session, and delegate semantics directly or through a small coherent set of migrated workflows
- docs bound example-led authoring guidance to the currently taught surfaces in `doc/workflows.md`; they do not implicitly claim concrete `outputs`/`yields` authoring coverage unless that guidance is added explicitly
- docs explain the preferred target authoring model clearly enough for future workflow authors
- any current->target mapping guidance included in docs is concise and practical rather than compatibility-heavy
- migrated examples remain aligned with executable runtime behavior
