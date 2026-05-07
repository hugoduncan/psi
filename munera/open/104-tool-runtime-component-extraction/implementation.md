2026-05-07

Task created from follow-on extraction analysis after the turn-runtime thread (`101` and `103`) was treated as complete.

Initial design rationale:
- `101` and `103` are treated as complete and both left tool-related refactoring residue
- that residue appears to be a better fit for a dedicated `tool-runtime` component than for continued turn-runtime-local cleanup
- crucial boundary decision: the target component must sit structurally below `agent-session`
- extracted target chosen: `components/tool-runtime/` with authoritative namespaces `psi.tool-runtime.args`, `psi.tool-runtime.core`, and `psi.tool-runtime.batch`

Refactoring-skill guardrails adopted:
- aim for a clean refactor
- compatibility shims allowed only temporarily and must be removed before completion
- tests should reflect the refactored code
- minimize the namespace dependency tree
- maximize orthogonality

`clj-surgeon` findings used in the design:
- `clj-surgeon -op :deps -file components/turn-runtime/src/psi/turn_runtime/tool_args.clj`
  - showed a compact generic parser seam (`parse-args-strict`, `parse-args`)
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_execution.clj`
  - showed one coherent single-tool runtime namespace with execute/record public roots and helper-level content/lifecycle shaping
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/tool_batch.clj`
  - showed one coherent batch-runtime namespace with executor access, file-key extraction, per-file locking, and ordered execution

Observed consumer surfaces from repo search at task creation time:
- production consumers include `prompt_turn.clj`, `conversation.clj`, and dispatch handlers in `session_mutations.clj`
- current ownership already crosses `agent-session` and `turn-runtime`, which strengthens the case for a dedicated `tool-runtime` component
- current `tool_execution.clj` also depends on `agent-session`-owned services such as dispatch/state/post-tool/tool-output, so the task must split mixed ownership rather than move the entire namespace wholesale if the result is to sit below `agent-session`
- revised seam decision: `psi.tool-runtime.*` must not depend on `psi.turn-runtime.*`; tool-runtime should instead deliver generic tool events/data upward, with turn-runtime adapting those generic events into turn accumulation/progress semantics above the boundary
- first-cut task explicitly leaves `post_tool.clj` and `tool_output.clj` outside the extraction boundary to avoid widening into all tool-adjacent ownership at once

Open note:
- this task is intentionally narrower than a general “tool component” extraction; it targets runtime/execution machinery only, not tool definitions, tool UI, or every tool-adjacent namespace
