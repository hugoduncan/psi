# Implementation notes

- architectural review added 5 new design steps

- ambiguity review added 8 new design steps

- no inconsistency review feedback

- design-step handoff: resolve follow-ups by preserving core-owned dispatch/effects-as-data/replay boundaries; augmentation data should become canonical turn-scoped state before pure request preparation consumes it. Relevant project files: `AGENTS.md`, `ramora/META.md`, `doc/architecture.md`, `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`, `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/child_session_state.clj`, `components/agent-session/src/psi/agent_session/extensions/api.clj`.

- design follow-up completion: latest review batch identified as `597c0accf`..`c6b6a8583` with baseline `597c0accf^`; all 13 current unchecked added design steps resolved. New implementation-critical choices: user-role augmentation context before current user message, atomic per-augmenter acceptance, unsupported ops rejected, replay fails closed.

- no architectural review feedback
