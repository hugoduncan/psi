# Design steps — architectural review (design-review session, turn 1)

- [ ] Define one canonical owner for delegated-child failure normalization at the
      workflow-runtime delegate-step boundary. The parent workflow attempt should
      receive one structured failure envelope derived from the persisted child
      run's canonical failure surfaces (including step attempt
      `:execution-error` and `:terminal-outcome`), while agent-session/tool/API
      layers only project or render that envelope. Do not let each caller inspect
      child sessions or independently synthesize competing error semantics. This
      preserves the single-source/one-way architecture and keeps generic workflow
      execution semantics in workflow runtime rather than in an adapter.

- [ ] Replace the suggested raw stack-trace propagation with a bounded, redacted,
      deterministic delegated-failure diagnostic contract. Preserve an actionable
      human-readable message plus stable structured cause metadata (for example
      reason and child run/step/attempt identity), retain a generic fallback when
      no safe cause is available, and do not expose arbitrary exception data,
      transcripts, provider payloads, or session internals through the parent
      `:error` surface. Raw stack traces are unstable implementation detail and
      may leak sensitive/runtime-local data across the child-parent boundary.

# Design steps — ambiguity review (design-review session, turn 2)

- [ ] Identify the exact observed execution and caller-visible boundary behind
      `:error "Delegated workflow failed"` and `:result nil`. The literal generic
      message currently originates when a workflow `:delegate` step normalizes a
      failed child run, but the design's phrase "delegation runner/tool" does not
      name whether acceptance is observed at the parent workflow execution
      mutation, the registered `delegate` tool result, or another API projection.
      Name the authoritative end-to-end path and the boundary at which regression
      proof must inspect the result, without moving failure semantics into that
      adapter.

- [ ] Define deterministic cause-selection precedence when a failed delegated run
      exposes more than one candidate diagnostic: child step-attempt
      `:execution-error`, child `:terminal-outcome`, and a nested delegated-child
      failure. State which attempt/terminal step is selected, whether nested
      delegate failures are recursively unwrapped or retained as immediate cause
      metadata, and when the generic fallback is used. "Propagate the specific
      error" is otherwise not singular for common failed-run shapes.

- [ ] Specify the exact parent-visible failure contract after propagation. State
      whether the parent workflow/delegate operation remains `:failed`, whether
      `:result` intentionally remains nil/absent on failure, which part of the
      canonical structured failure envelope is rendered into the public `:error`
      string, and which representative cases must be proven (at minimum an
      attempt execution error, a terminal-outcome-only failure, and a failure
      with no safe actionable cause). The current design mentions both `:error`
      and `:result nil` but only explicitly proposes changing `:error`.
