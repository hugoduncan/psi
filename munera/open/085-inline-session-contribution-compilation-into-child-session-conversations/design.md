Goal: implement compilation of workflow IR session contributions into canonical child-session conversation state so `:type :session` workflow steps execute through explicit conversation assembly rather than compatibility-era prompt shortcuts.

## Intent

Task `077` defined inline session steps as explicit child-session construction with ordered `:contributions`, not as a hidden `:prompt`/`$INPUT` convention. This task makes that model real by compiling IR session contributions into the canonical child-session conversation surfaces consumed by runtime prompt/session execution.

## Problem statement

The target workflow grammar and IR now model session steps in terms of:

- session configuration such as `:model`, `:tools`, and `:skills`
- ordered `:contributions`
- shared source refs/projections
- explicit template rendering

But those semantics are only complete once the workflow runtime can materialize them into the same canonical child-session conversation state used by normal session execution.

Without this slice:

- `:type :session` remains partly conceptual even if it compiles to IR
- contribution order and contribution kinds may drift from actual child-session prompt/message assembly
- workflow-specific session execution may continue to rely on older compatibility shortcuts
- prompt/session introspection may diverge from authored workflow intent

## Scope

In scope:

- compile IR `:session` contributions into canonical child-session conversation state
- support first-cut contribution kinds `:type :source` and `:type :template`
- resolve contribution refs/projections against workflow input, workflow original, prior step outputs, and prior step yields
- preserve authored contribution order during materialization
- render template contributions deterministically from resolved vars
- ensure the resulting child-session conversation integrates with the canonical prompt/session preparation path
- add focused tests for representative contribution compilation behavior

Out of scope:

- inventing new contribution kinds beyond the first cut
- broad redesign of prompt composition beyond what session-step contribution materialization requires
- delegated workflow boundary work except where it shares reusable rendering/reference helpers

## Desired outcome

A workflow IR `:type :session` step can produce a canonical child-session conversation from its authored contributions, and that conversation becomes the actual execution substrate for the child session.

## Acceptance

- IR session contributions compile into canonical child-session conversation state
- `:source` and `:template` contributions both work for representative cases
- contribution refs resolve correctly against workflow and prior-step sources
- authored contribution order is preserved
- template rendering is deterministic and explicit-variable-based
- focused tests prove representative session-step contribution compilation and execution-path coherence
- the implemented behavior matches task `077`, `doc/workflow-ir.md`, and the existing converged child-session prompt-composition direction
