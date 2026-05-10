2026-05-07

Task created from prompt/turn compatibility cleanup review.

Creation rationale:
- `psi.agent-session.prompt-control` is now only a direct wrapper over `psi.agent-session.turn`
- current production consumers can be rewired to the authoritative namespace directly
- removing the facade completes a narrow leftover compatibility seam from the earlier turn ownership migration
- this cleanup fits naturally under broader compatibility and prompt-lifecycle convergence umbrellas without reopening those larger tasks

Implementation notes:
- rewired production consumers (`core`, `workflow-statechart-runtime`, `workflow-judge`, and `compaction-runtime`) from `psi.agent-session.prompt-control` to direct `psi.agent-session.turn` usage
- rewired workflow-oriented tests and direct `with-redefs` callsites to target `psi.agent-session.turn/*`
- removed the facade-only delegation test from `prompt_lifecycle_test.clj`; higher-level prompt lifecycle and workflow consuming-path tests remain the proof surfaces instead of preserving a test for a retired namespace
- deleted `components/agent-session/src/psi/agent_session/prompt_control.clj` rather than leaving a forwarding shim
- updated active task text that still described `prompt_control.clj` as a live compatibility seam

Final boundary notes:
- `psi.agent-session.turn` remains the authoritative higher orchestration surface
- lower prepared-turn mechanics remain under `psi.turn-runtime.*`
- this task removed an obsolete compatibility name, not a real subsystem boundary
- no replacement shim was introduced
