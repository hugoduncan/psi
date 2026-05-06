Task created.

Initial framing:
- this task exists because a true `components/turn/` extraction is blocked by lower-level generic dispatch/state substrate still living under `agent-session`
- the primary goal is not new behavior, but a clarified architectural boundary below session/turn/workflow/tool domains
- the expected first extraction candidates are `dispatch.clj` and `dispatch_schema.clj`; mixed files such as `dispatch_effects.clj` and `context.clj` should only be split as needed to preserve a clean kernel boundary

Working hypothesis:
- the abstract kernel will likely become `components/state-kernel/`, but implementation may choose a better name if the rationale is recorded explicitly
- the kernel should contain only machinery reusable unchanged in a different application domain
- domain effect handlers, session semantics, prompt/turn/workflow/tool logic, and app composition should remain above the boundary

Refinement added after task creation:
- the broad global `ctx` map is now treated as a central ambiguity, not an incidental detail
- this task must define a narrowed kernel environment contract explicitly rather than letting generic code continue to depend on arbitrary `agent-session` context keys
- the existing `agent-session.bootstrap` and `system-bootstrap.core` split is now part of the refinement surface because it exposes unresolved ownership around isolated query contexts, global registration, and bootstrap coordination
- one intended outcome is to make it possible to remove the current `agent-session` ↔ `system-bootstrap` component cycle; if that cannot be completed here, the remaining blocker must be stated precisely

Questions already settled at task-design level:
- this is a prerequisite task for later `turn` and likely `session-state` extraction
- this task must not itself become a `turn` extraction
- component-level dependency direction is a first-class acceptance criterion

Settled refinement answers:
- Component name/path: use `components/state-kernel/` for this task. The name emphasizes application-independent state/effects/dispatch substrate rather than a session- or turn-shaped runtime.
- Kernel API shape: the kernel should not own or receive the full current `agent-session` global `ctx` map. It should consume a narrowed environment map passed explicitly by higher layers.
- Kernel environment contract: first-cut kernel dispatch should depend only on a small environment with:
  - `:state*` — root state atom
  - `:execute-effect-fn` — effect execution callback used after pure dispatch results are applied
  - `:validate-result-fn` — optional validation callback for pure results / interceptor context
  - `:publish-change-fn` — optional projection/listener publication callback
  - `:dispatch-trace-fn` — optional trace sink callback, if retained separately from in-kernel bounded logs
  - any replay/test flags expressed as event/options data rather than hidden application context keys
- Kernel environment growth rule: new environment keys are allowed only when they are demonstrably domain-independent. Keys naming sessions, prompts, workflows, tools, extensions, adapters, or query/bootstrap concerns violate the boundary.
- Kernel contract exclusion: session lookup helpers, agent-core access, workflow runtime callbacks, query registration, scheduler registries, extension registries, and adapter handles stay out of the kernel environment and remain higher-layer concerns.
- Pure-result contract decision: the kernel owns the pure dispatch contract and schema (`:root-state-update`, `:effects`, `:return`, `:return-key`, `:return-effect-result?`) because those are domain-independent orchestration semantics.
- Apply-path decision: the kernel owns the concrete apply path for pure dispatch results over `:state*`; applying state updates is part of the generic dispatch runtime, not just a contract owned elsewhere.
- Event-log/trace decision: the kernel owns the bounded event-log and dispatch-trace substrate. External trace sinks remain optional add-ons, not the primary ownership location.
- Effect execution decision: the kernel may own effect execution registration/dispatch substrate only if it can be expressed without embedding app effect types. App-specific `defmethod` bodies remain outside the kernel. This extraction is optional in this slice if the generic kernel boundary is otherwise cleanly established and documented.
- Listener/publication decision: the kernel may own generic listener registration/publication helpers, but not application-specific projection payload shapes. This move is optional unless generic dispatch code would otherwise still depend on `agent-session/context.clj`.
- Global-vs-isolated query/bootstrap decision: query-context creation and resolver/mutation registration are not part of the state kernel. They remain above it.
- `system-bootstrap` cycle decision: the preferred fix is to remove `agent-session -> system-bootstrap`, not to pull bootstrap/query registration into the kernel. `system-bootstrap` should remain a higher-level registration/composition component, while `agent-session` should stop depending on it for generic infrastructure.
- Bootstrap ownership decision: `agent-session.bootstrap` should own session bootstrap behavior only. Global resolver/mutation registration should move behind higher-level bootstrap/composition entrypoints instead of being callable as an `agent-session` core dependency.
- Cycle interpretation: the `agent-session` ↔ `system-bootstrap` cycle is not fundamentally a state-kernel responsibility; it is a neighboring ownership problem exposed by the same blurred boundaries. This task should remove the `agent-session` dependency on `system-bootstrap` if that can be done cleanly while extracting the kernel, but should not distort the kernel boundary just to absorb bootstrap responsibilities.

Implementation notes — 2026-05-06
- Added a real lower component boundary at `components/state-kernel/`.
- Moved the authoritative generic dispatch pipeline into `psi.state-kernel.dispatch`.
- Moved the generic pure-result schema/contract into `psi.state-kernel.dispatch-schema`.
- Kept `psi.agent-session.dispatch` as a compatibility wrapper that re-exports the established public surface while adapting agent-session contexts onto the narrowed kernel environment contract.
- Kept `psi.agent-session.dispatch-schema` as domain-owned schema authority for concrete effect payload validation. This was necessary because effect catalog validation is application-specific, while the kernel owns only the generic pure dispatch contract.
- Preserved bounded dispatch event-log / dispatch-trace ownership in the kernel, including db-summary capture for consuming introspection/tests.
- Preserved `:return-key` semantics by letting the kernel prefer injected higher-layer read callbacks when present; this keeps session-shaped reads above the kernel while retaining the generic apply orchestration below it.
- Effect execution remains above the kernel in this slice:
  - kernel owns only the generic `:execute-effect-fn` callback seam
  - `psi.agent-session.dispatch-effects` remains application-specific and was not moved because its effect handlers encode session/prompt/workflow/tool/extension semantics
  - this does not violate the boundary because kernel dispatch no longer depends on app-specific effect implementation details
- Listener/publication helpers remain above the kernel in this slice:
  - kernel supports optional `:publish-change-fn`
  - agent-session retains projection-listener registration/publication helpers in `context.clj`
  - generic kernel dispatch no longer depends on `agent-session/context.clj`
- Removed the direct component dependency from `agent-session` to `system-bootstrap`:
  - dropped `psi/system-bootstrap` from `components/agent-session/deps.edn`
  - changed global registration entrypoints in `context.clj` to use `requiring-resolve` on `psi.system-bootstrap.core/register-all-domains!`
  - this preserves the higher-level ownership of global registration while removing the static component edge
- Added focused kernel tests in `components/state-kernel/test/psi/state_kernel/dispatch_test.clj` covering:
  - pure root-state update apply path
  - bounded event-log / dispatch-trace retention
  - effect execution through the narrowed kernel environment contract
- Verification run:
  - `bb clojure:test:unit --focus psi.agent-session.dispatch-pure-result-test --focus psi.agent-session.scheduler-dispatch-test --focus psi.state-kernel.dispatch-test`
  - result: green (`1514 tests, 11103 assertions, 0 failures`)
- One stale test expectation surfaced during extraction and was corrected:
  - scheduler drain-queue should leave other queued ids intact, so preserving `"sch-1"` alongside `"missing"` matches the current pure scheduler model and existing handler behavior.

Review notes — 2026-05-06
- terse review: extraction is structurally good and green, but two boundary leaks remain.
- Actionable follow-up completed: moved `permission-interceptor` and `statechart-interceptor` out of `psi.state-kernel.dispatch` and back into the `psi.agent-session.dispatch` compatibility/composition layer, so kernel defaults are now domain-independent.
- Actionable follow-up completed: removed the kernel compatibility fallbacks to `:apply-root-state-update-fn` and `:read-session-state-fn`. Those semantics now live only in the `psi.agent-session.dispatch` compatibility layer, which is the correct place for the migration seam.
- Follow-up verification run:
  - `bb clojure:test:unit --focus psi.agent-session.dispatch-pure-result-test --focus psi.agent-session.dispatch-test --focus psi.state-kernel.dispatch-test`
  - result: green (`1514 tests, 11103 assertions, 0 failures`)
  - `bb clojure:test:unit`
  - result: green (`1514 tests, 11103 assertions, 0 failures`)
