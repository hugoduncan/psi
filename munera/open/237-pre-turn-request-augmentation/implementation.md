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

- design follow-up completion (third review batch): latest task-scoped design-review batch identified as `4ae9bea9b`..`1ee9c586f` with baseline `6501d40f9`; the 7 current unchecked checklist lines added by that batch were resolved. Implementation should treat turn-augmenter registration as manifest/effective-permission gated only, with per-session capability gating at invocation; core normalizes append-block provenance, permits duplicate block ids, invalidates bad child-session ids, uses fixed history projection bounds (last 8 prior messages, 200-char normalized snippets), and records suppressed child turns with `:status :suppressed`, no providers, and no inserted request message.

- no new architectural review feedback after resolved design-review follow-ups

- ambiguity review added 1 new design step

- no new inconsistency review feedback after latest ambiguity follow-up

- design follow-up completion (invocation-scoped child API): latest task-scoped design-review batch identified as `ebd63069a`..`ead94f6f4` with baseline `3b809157c`; the 1 current unchecked checklist line added by that batch was resolved. Implementation should make `:create-turn-augmentation-child-session` a stable guarded API closure whose authority comes only from a runtime-held active provider invocation context, cleared after the handler returns; outside-invocation calls throw `:no-active-turn-augmentation-invocation` and create no session.

- ambiguity review added 5 new design steps

- no new inconsistency review feedback after current ambiguity review
- design follow-up completion (current ambiguity batch): latest task-scoped design-review batch identified as `d6e3975ff`..`3cffacf82` with baseline `482ff803f`; the 5 current unchecked checklist lines added by that batch were resolved. Implementation should treat pre-turn augmentation as a terminal barrier: live prepare requires a closed canonical record, cancellation closes with `:canceled` and no request execution, workflow run id is provenance only, extension reload/unload owns registration cleanup, and augmentation child sessions may only narrow parent tools/model authority.

- no new architectural review feedback after current resolved follow-ups
