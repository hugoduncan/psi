# Implementation notes

- architectural review added 5 new design steps

- ambiguity review added 8 new design steps

- no inconsistency review feedback

- design-step handoff: resolve follow-ups by preserving core-owned dispatch/effects-as-data/replay boundaries; augmentation data should become canonical turn-scoped state before pure request preparation consumes it. Relevant project files: `AGENTS.md`, `ramora/META.md`, `doc/architecture.md`, `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`, `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/child_session_state.clj`, `components/agent-session/src/psi/agent_session/extensions/api.clj`.

- design follow-up completion: latest review batch identified as `597c0accf`..`c6b6a8583` with baseline `597c0accf^`; all 13 current unchecked added design steps resolved. New implementation-critical choices: user-role augmentation context before current user message, atomic per-augmenter acceptance, unsupported ops rejected, replay fails closed.

- no architectural review feedback

- ambiguity review (second pass) added 4 new design steps

- no inconsistency review feedback (second pass)

- second-pass design follow-up completion: latest review batch identified as `fdd602ecf`..`6c0ed17ac` with baseline `6f9a0f16c`; the 4 current unchecked checklist lines added by that batch were resolved. Implementation should add concrete extension API entries for `:register-turn-augmenter` and `:create-turn-augmentation-child-session`, validate explicit result envelopes, and expose the exact `:turn/augmentation-context` prepared-request layer/summary fields.

- no architectural review feedback (design-review architecture turn)

- ambiguity review (design-review turn) added 7 new design steps

- no inconsistency review feedback (design-review turn)
