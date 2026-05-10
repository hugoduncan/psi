Goal: extract an application-independent abstract state kernel from `agent-session` so lower-level state/effect/dispatch machinery no longer lives inside the session domain component, and so that generic dispatch/runtime code depends on an explicit narrow kernel environment contract rather than the current broad application `ctx` map.

Context:
- current `agent-session` still mixes domain behavior with a lower-level runtime substrate
- recent turn-extraction work showed that a real `components/turn/` extraction is blocked by circular dependencies and by hidden dependence on generic dispatch/state machinery that currently lives under `agent-session`
- the deepest architectural prerequisite is not first extracting `turn`, but separating the generic state/effects/dispatch kernel from application-specific session, turn, workflow, tool, and extension logic
- this task is about creating that lower-level kernel boundary and moving only the truly application-independent machinery into it

Problem:
- `agent-session` currently owns both domain logic and a reusable runtime substrate:
  - handler registry
  - interceptor pipeline
  - event normalization
  - pure-result contract
  - root-state update application
  - dispatch tracing / event log
  - replay-time effect suppression
  - validation hooks around dispatch results
  - projection-listener publication helpers
- the current runtime contract is also blurred by the broad global `ctx` map: generic dispatch/state machinery can currently reach through a large application-specific context rather than depending on a narrow kernel-owned environment contract
- because these mechanisms are embedded in `agent-session`, higher-level domain extractions such as `turn` cannot become real components without dragging `agent-session` back in as a dependency
- the same blurred ownership contributes to the current `agent-session` ↔ `system-bootstrap` cycle, where registration/bootstrap concerns and domain/runtime concerns are not cleanly separated
- this blurs architectural ownership and makes `agent-session` the accidental home of infrastructure that should be reusable across domains

Intent:
- create one explicit abstract state-kernel component boundary below `agent-session`
- move application-independent state/effects/dispatch machinery into that component
- leave session/turn/workflow/tool/extension behavior above that boundary as domain consumers
- make later extraction of `session-state`, `turn`, and workflow/tool components structurally possible without introducing new circular dependencies
- replace implicit dependence on the current monolithic `agent-session` global `ctx` shape with an explicit narrow kernel environment contract
- keep query/bootstrap/global registration concerns out of the kernel
- use the clarified ownership split to remove the existing `agent-session` → `system-bootstrap` dependency if that can be done cleanly within the slice; otherwise make the remaining blocker explicit in task notes

In scope:
- define the abstract kernel boundary explicitly
- define the kernel-owned runtime environment contract explicitly, replacing implicit dependence on the broad global `ctx` map with a narrowed environment map passed by higher layers
- identify the current `agent-session` namespaces and namespace parts that belong in the kernel
- extract the reusable kernel into `components/state-kernel/`
- move the generic dispatch pipeline and its generic schema/contract into the new component
- keep the pure-result dispatch contract in the kernel as the authoritative domain-independent orchestration contract
- split mixed files where needed so only the abstract machinery moves and application-specific logic remains in domain components
- update callers so `agent-session` and other consumers depend on the new kernel instead of owning it locally
- preserve current behavior while changing ownership boundaries
- add or update focused tests proving the kernel boundary and preserving dispatch/runtime behavior during extraction
- document the new boundary well enough that a future `turn` extraction can refer to it as established infrastructure
- if feasible within this slice, remove the `agent-session` → `system-bootstrap` dependency by moving global registration/bootstrap ownership to a higher-level composition layer rather than into the kernel

Out of scope:
- extracting `turn` into its own component in this task
- extracting `session-state` into its own component in this task
- redesigning session, workflow, tool, or extension semantics
- broad rewriting of all domain effect handlers just because they currently sit near dispatch code
- changing adapter/UI behavior except where imports or callback wiring must follow the new boundary
- replacing the dispatch architecture wholesale; this task should extract and clarify the current architecture, not invent a different one

Boundary definition for this task:
The abstract state kernel may own only machinery that could be reused unchanged in a different domain such as a workflow engine, job scheduler, or todo app.

That includes, if present after shaping:
- handler registration
- interceptor definition/execution
- event normalization
- dispatch orchestration
- pure-result schema/validation contract
- root-state-update application logic over `:state*` as part of the domain-independent dispatch runtime
- dispatch trace / bounded event-log substrate
- replay/effect-suppression substrate
- a narrow kernel environment contract for dispatch/effect execution, if that contract can be stated without session/prompt/workflow assumptions
- optionally, effect execution registration/dispatch substrate if it can be expressed without app effect assumptions
- optionally, projection-listener registration/publication substrate if required to keep generic dispatch code from depending on `agent-session/context.clj`

That does not include:
- session-id semantics
- prompt or turn lifecycle events
- workflow attempt semantics
- tool execution behavior
- extension dispatch behavior
- provider auth/model logic
- journaling semantics specific to chat/session history
- app-runtime, RPC, TUI, or Emacs orchestration logic
- the full current `agent-session` global `ctx` map as an implicit kernel API

Kernel environment contract for this task:
- the current broad application context map is not itself the kernel boundary and must not be moved wholesale into the kernel
- the kernel will receive a narrowed environment map from higher layers rather than owning the current application context shape
- first-cut kernel environment contract:
  - `:state*` — root state atom
  - `:execute-effect-fn` — post-apply effect execution callback
  - `:validate-result-fn` — optional validation callback for pure results / interceptor context
  - `:publish-change-fn` — optional listener/projection publication callback
  - `:dispatch-trace-fn` — optional external trace sink callback if needed in addition to in-kernel bounded logs
- replay/test control should travel through dispatch event/options data rather than through hidden application context keys
- the kernel environment may only grow with keys that are demonstrably domain-independent; any key naming sessions, prompts, workflows, tools, extensions, adapters, or query/bootstrap concerns violates the boundary
- done-ness for this task requires that generic kernel machinery depend only on this narrowed contract, not on incidental `agent-session`-specific `ctx` keys
- if some domain-specific callbacks remain injected through the environment map, task notes must list them and explain why they stay above the kernel boundary
- explicitly excluded from the kernel environment:
  - session lookup helpers
  - agent-core access
  - workflow runtime callbacks
  - query registration/bootstrap
  - scheduler registries
  - extension registries
  - adapter/UI handles

Likely source material:
- high-confidence kernel candidates:
  - `components/agent-session/src/psi/agent_session/dispatch.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_schema.clj`
- split-before-move candidates:
  - `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
  - `components/agent-session/src/psi/agent_session/context.clj`
- expected non-kernel consumers that should remain above the boundary:
  - `dispatch_handlers/*`
  - `prompt_*` / turn lifecycle code
  - `workflow_*`
  - `tool_*`
  - `extensions/*`
  - `mutations/*`
  - `resolvers/*`
  - app/runtime adapter layers

Acceptance:
- a new abstract state-kernel component exists in the repo with a clear name and purpose
- the kernel contains only application-independent machinery
- the kernel runtime contract is explicit and narrower than the current broad `agent-session` global `ctx` shape
- the kernel owns the domain-independent apply path for pure dispatch results over `:state*`
- the kernel owns bounded event-log / dispatch-trace substrate
- `agent-session` no longer owns the canonical generic dispatch pipeline locally
- any mixed files needed for extraction are split so the abstract machinery is below the domain-specific handlers/effects
- the dependency direction is one-way: `agent-session` depends on the kernel, but the kernel does not depend on `agent-session`
- the extraction does not introduce new component-level circular dependencies
- focused proof covers the extracted kernel behavior sufficiently to preserve confidence during the move
- docs/comments/task notes explain what belongs in the kernel and what remains intentionally domain-specific
- the resulting structure makes a future `session-state` and `turn` component extraction more plausible rather than less
- either:
  - the `agent-session` ↔ `system-bootstrap` cycle is removed in this task by removing `agent-session -> system-bootstrap`, or
  - the task records a precise remaining blocker and why it stays out of scope for this slice

Concrete done criteria:
- the task records the chosen component name/path explicitly
- the task records the chosen kernel environment contract explicitly, including which inputs are kernel-owned vs injected by higher layers
- the authoritative generic dispatch/pure-result/schema machinery no longer resides under `components/agent-session/`
- the authoritative apply path for pure dispatch results over `:state*` resides in the kernel
- the authoritative bounded event-log / dispatch-trace substrate resides in the kernel
- generic effect execution substrate is optional for task completion; if not extracted, task notes must say explicitly that app-specific effect execution remains above the kernel and why that does not violate the new boundary
- generic listener/publication substrate is optional for task completion; if not extracted, task notes must say explicitly that generic dispatch code no longer depends on `agent-session/context.clj` and why leaving those helpers above the kernel is acceptable in this slice
- generic kernel code no longer directly assumes arbitrary `agent-session` context keys outside the declared kernel contract
- `agent-session` compiles and focused kernel/dispatch verification is green while consuming the extracted kernel through explicit dependencies
- minimum focused proof for this task covers:
  - dispatch of a pure result through the kernel apply path
  - bounded event-log / dispatch-trace behavior at the kernel boundary
  - at least one consuming `agent-session` path still working through the extracted kernel
- no new dependency edge from the kernel back into `agent-session` exists
- if the `agent-session` ↔ `system-bootstrap` cycle remains, the remaining dependency edge and reason are named explicitly in task notes
- task notes explain which candidate files were moved whole, which were split, which optional kernel-adjacent pieces were deferred, and why

Design constraints:
- prefer a crisp architectural boundary over a maximal extraction
- only move machinery that is truly application-independent
- preserve current behavior; ownership changes are the goal
- keep the dependency slope obvious and one-way
- split mixed namespaces when needed instead of moving domain-specific behavior into the kernel
- avoid creating a kernel that is generic in name only but still embeds session/prompt assumptions
- do not let this task quietly expand into `turn` or `session-state` extraction

Related work:
- this task is a prerequisite for a true `components/turn/` extraction
- a likely later follow-on is extraction of a lower `session-state` component once the abstract kernel exists
- query-context creation plus resolver/mutation registration remain above the kernel and are not part of this extraction target
- the current `agent-session.bootstrap` and `system-bootstrap` registration/bootstrap split is a concrete ambiguity source for this task: bootstrap currently uses both isolated query contexts and global registration paths, and those ownership boundaries should be clarified while removing the `agent-session` → `system-bootstrap` dependency if possible
- the intended cycle fix is upward: global registration ownership moves to higher-level composition/bootstrap entrypoints rather than downward into the kernel
- this task should ideally remove the existing `agent-session` ↔ `system-bootstrap` component cycle; if not, it must at least leave a precise explanation of the remaining coupling and the next slice needed to remove it
