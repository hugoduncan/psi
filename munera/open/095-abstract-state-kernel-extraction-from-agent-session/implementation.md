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
