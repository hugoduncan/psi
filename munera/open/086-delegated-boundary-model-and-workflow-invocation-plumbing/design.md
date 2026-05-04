Goal: implement the delegated workflow boundary and runtime invocation plumbing for workflow IR `:type :delegate` steps.

## Intent

Task `077` defined delegated execution as a first-class workflow step form distinct from both inline child-session execution and deterministic invoke execution.

A delegated step should:

- target an existing named workflow
- render an explicit `:prompt-string`
- forward explicit ordered `:context`
- establish a new delegated workflow invocation boundary
- yield the callee workflow's yielded value unchanged by default

For this task's first cut, the delegated boundary payload reaching the callee runtime is explicit and minimal:

- callee local `:workflow-input` is the final rendered delegated `:prompt-string` string
- callee local `:workflow-original` is the ordered forwarded delegated `:context` vector exactly as materialized from the caller workflow run
- forwarded `:context` remains a bare ordered vector at the callee boundary; it is not wrapped in an additional envelope carrying target/prompt/caller metadata in this slice
- any richer delegated-boundary recording for debugging belongs in caller-side execution/introspection surfaces, not in the callee's local `:workflow-input` / `:workflow-original` values themselves

This task makes that delegated boundary executable and explicit in runtime plumbing.

Dependency/orchestration note:

- the older task breakdown referenced a future dedicated shared source/reference/projection task `088`
- the active task inventory no longer contains a standalone `088` task directory, and the relevant semantics are instead treated as already-defined by task `077` and exercised by the currently landed/active workflow runtime slices (`081`, `083`, `085`)
- this task therefore depends on those existing semantics and implementation surfaces rather than waiting on a non-present standalone `088` task

## Problem statement

The target grammar and IR now model `:type :delegate`, but that execution form remains incomplete until runtime can:

- resolve the target workflow
- render/materialize the delegated boundary payload
- create a callee workflow invocation with correct local `:workflow-input` and `:workflow-original` semantics
- preserve explicit caller-supplied context separately from prompt string
- record enough boundary information for execution and debugging
- propagate the callee yielded value back to the caller step

Without this slice:

- delegation remains partly conceptual or relies on older ad hoc delegation behavior
- target-authored delegate steps cannot execute through the canonical IR path
- workflow composition remains biased toward session-only execution
- delegated data-flow and yielded-value semantics cannot stabilize in real runtime behavior

## Scope

In scope:

- execute IR `:type :delegate` steps through an explicit workflow invocation boundary
- resolve target workflow definitions from the runtime/worktree scope
- render delegated `:prompt-string` to a final string before invocation
- resolve delegated `:context` items from workflow and prior-step sources while preserving author order
- establish callee local `:workflow-input` and `:workflow-original` semantics consistent with task `077`
- propagate the callee workflow's yielded value back as the delegating step's yielded value by default
- add focused tests for representative delegate execution flows

Out of scope:

- delegated session overrides beyond first cut
- redesigning unrelated slash-command `/delegate` UX beyond what canonical delegated workflow execution needs
- broad built-in workflow migration to delegate style

## Desired outcome

A workflow IR `:type :delegate` step can call another workflow through a well-defined boundary, and the callee workflow's yielded value and execution outcome integrate coherently back into the caller workflow.

## Acceptance

- IR `:type :delegate` steps execute through explicit workflow invocation plumbing
- delegated `:prompt-string` renders to a final string before callee invocation
- delegated `:context` resolves and preserves authored order
- callee local `:workflow-input` and `:workflow-original` semantics are explicit and correct
- the delegating step yields the callee workflow's yielded value unchanged by default
- focused tests prove representative delegate-only and mixed workflow execution flows
- implemented behavior matches task `077`, `doc/workflow-ir.md`, and the converged delegated-boundary design
