# 126 — Workflow execution façade narrowing

## Goal

Narrow `psi.agent-session.workflow-execution` so it is only the higher public/session-facing workflow execution façade, not a mixed façade-plus-helper forwarding namespace.

## Why

Post tasks `123`, `124`, and `125`, the lower workflow runtime ownership is now largely clean:

- pure judge/routing lives in `psi.workflow-judge`
- bounded workflow turn execution lives in `psi.workflow-runtime.turn-execution-contract`
- runtime/statechart/progression ownership lives in `psi.workflow-runtime.*`

`psi.agent-session.workflow-execution` still exists for a legitimate reason: higher session/public orchestration needs a canonical execution entrypoint that runs and resumes workflow runs and shapes a stable result for higher callers.

But the namespace currently also re-exports lower workflow-runtime helper functions such as step materialization and step session-config shaping. That makes the boundary less legible and preserves historical indirection after the runtime extraction is complete.

## Problem

`psi.agent-session.workflow-execution` currently mixes two roles:

- higher façade ownership:
  - execute a run
  - resume and execute a blocked run
  - shape the higher returned execution summary
- lower helper forwarding:
  - binding/source resolution exposure
  - step input materialization
  - step conversation materialization
  - prompt/preload splitting
  - step prompt derivation
  - step session-config resolution

This makes the namespace look more authoritative than it really is and obscures the dependency direction established by task `125`.

## Intent

Keep `psi.agent-session.workflow-execution` as the canonical higher execution façade, but remove lower helper ownership from it.

This task should:

- preserve a clear higher execution surface for callers that want to run or resume workflow runs
- stop using `workflow-execution` as the place to reach lower step-prep/materialization helpers
- rewire direct helper consumers to the current lower authoritative workflow-runtime owner as it exists today
- remove public forwarding vars for lower workflow-runtime helpers rather than merely hiding them behind another alias, unless implementation proves one very small temporary compatibility seam is necessary and records it explicitly
- preserve workflow behavior exactly

This task should not:

- remove the higher execution façade entirely
- redesign workflow public APIs
- redesign runtime/statechart ownership
- introduce a new adapter seam for session-bound workflow effects
- split `step-prep` by role beyond the minimum wiring changes needed here
- preempt task `127` by redesigning or renaming lower owners beyond what is required to stop the forwarding
- preempt task `128` by redesigning the callback cluster into a named adapter seam

## In scope

- `psi.agent-session.workflow-execution`
- current direct consumers of helper vars re-exported from that namespace, including:
  - ordinary namespace callers
  - callback wiring sites
  - dynamic lookup/backfill sites
  - tests that currently prove or consume the forwarded helper surface
- test rewiring needed to reflect the narrower façade role and clearer proof ownership
- recording the final intended ownership of the façade in `implementation.md`

## Out of scope

- step-prep role decomposition itself
- named workflow execution adapter/session adapter introduction
- changes to workflow mutations/resolvers/`psi-tool`
- workflow behavior redesign
- redesigning the existing callback cluster beyond rewiring current callback targets away from `workflow-execution` to the lower authoritative owner
- renaming callback keys; in this task callback key names stay stable and only their targets may change

## Desired boundary

### Stays in `psi.agent-session.workflow-execution`

Only higher façade behavior such as:

- `execute-run!`
- `resume-and-execute-run!`
- shaping the returned higher execution summary/result
- directly adjacent façade-local helpers needed only to support those entrypoints

Decision rule:
- this namespace may expose higher execution entrypoints and directly adjacent result-shaping helpers
- it should not expose workflow definition, step shaping, materialization, conversation shaping, or session-config derivation helpers
- a façade-local helper may remain only if it exists to support the run/resume entrypoints and is not a lower workflow helper surface intended for reuse by other namespaces

Expected final public shape:
- `execute-run!` and `resume-and-execute-run!` are expected to remain public
- any remaining public other than those two must be explicitly justified in `implementation.md` as a façade-local result-shaping or entrypoint-support helper

### Leaves `psi.agent-session.workflow-execution`

Lower workflow-runtime helper exposure such as:

- `binding-source-value`
- `materialize-step-inputs`
- `materialize-step-session-conversation`
- `split-step-session-conversation`
- `step-prompt`
- `resolve-step-session-config`

Those should be consumed from the authoritative lower owner instead of through this wrapper.

For this task, “authoritative lower owner” means the lower namespace shape that already exists today after task `125`; rewiring to that current owner is in scope, while redesigning that lower owner belongs to later tasks.

Search expectation:
- implementation should use code search to identify all current references to the forwarded helper vars across production code, callback wiring, dynamic lookup/backfill sites, and tests, rather than relying only on known callers encountered incidentally during editing

## Implementation shape

1. review `psi.agent-session.workflow-execution` and enumerate which publics are true façade entrypoints versus lower helper forwards
2. use code search to identify all current references to the forwarded helper vars across ordinary callers, callback wiring, dynamic lookup/backfill sites, and tests
3. remove public forwarding vars for lower helper forwards from the namespace, or keep one tiny temporary compatibility seam only if the necessity threshold below is met
4. rewire higher code and tests that currently reach those helpers through `workflow-execution` so they use the lower authoritative namespace directly
5. preserve a simple, explicit higher execution façade centered on running and resuming workflow runs
6. record the final façade ownership decision in `implementation.md`

## Acceptance

- `psi.agent-session.workflow-execution` is clearly a higher execution façade, not a mixed façade-plus-helper forwarding namespace
- `execute-run!` and `resume-and-execute-run!` remain public, and any additional remaining public is explicitly justified in `implementation.md` as façade-local entrypoint support or result-shaping support
- public forwarding vars for lower workflow step-prep/materialization helpers are removed from `workflow-execution`, unless one tiny temporary compatibility seam is proven necessary and recorded explicitly
- callers that need lower helper behavior depend on the current lower authoritative workflow-runtime owner directly
- rewired consumers include ordinary callers, callback wiring sites, dynamic lookup/backfill sites, and affected tests within task scope
- absent a justified temporary compatibility seam, tests no longer prove or consume the old forwarded helper public surface
- higher execution entrypoints remain obvious and behavior remains unchanged
- lower helper behavior proofs point at lower owners, while higher run/resume façade proofs remain with the higher owner
- tests/proof surfaces reflect the narrowed ownership cleanly

## Compatibility exception threshold

A tiny temporary compatibility seam is acceptable only if removing it within this task would:

- require crossing into task `127` or `128` scope,
- force redesign of the lower owner rather than rewiring to its current shape, or
- force workflow behavior/API redesign instead of a boundary cleanup.

When such a seam is kept, implementation must:

- keep it minimal,
- record exactly why it was necessary,
- identify the blocking consumer or boundary issue,
- and record the intended follow-on cleanup.

## Related work

- `123-workflow-judge-routing-component-extraction`
- `124-turn-execution-contract-extraction`
- `125-workflow-runtime-core-component-extraction`
- `127-workflow-step-prep-role-split`
- `128-workflow-execution-adapter-seam`
