# Implementation notes

- architectural review added 5 new design steps

- ambiguity review added 8 new design steps

- no inconsistency review feedback

- design-step handoff: resolve follow-ups by preserving core-owned dispatch/effects-as-data/replay boundaries; augmentation data should become canonical turn-scoped state before pure request preparation consumes it. Relevant project files: `AGENTS.md`, `ramora/META.md`, `doc/architecture.md`, `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`, `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/child_session_state.clj`, `components/agent-session/src/psi/agent_session/extensions/api.clj`.

- design follow-up completion: latest review batch identified as `597c0accf`..`c6b6a8583` with baseline `597c0accf^`; all 13 current unchecked added design steps resolved. New implementation-critical choices: user-role augmentation context before current user message, atomic per-augmenter acceptance, unsupported ops rejected, replay fails closed.

- no architectural review feedback

- ambiguity review (second pass) added 4 new design steps

- no inconsistency review feedback (second pass)

- second-pass design-step handoff: specify concrete contracts without widening extension authority; keep registration/result/child-session APIs data-shaped, capability-gated, and testable through prepared-request summaries/resolvers rather than extension-local handles. Additional relevant files: `components/agent-session/src/psi/agent_session/dispatch.clj`, `components/agent-session/src/psi/agent_session/dispatch_schema.clj`, `components/agent-session/src/psi/agent_session/dispatch_effects.clj`, `components/agent-session/src/psi/agent_session/resolvers/session.clj`, `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`.

- second-pass design follow-up completion: latest review batch identified as `fdd602ecf`..`6c0ed17ac` with baseline `6f9a0f16c`; the 4 current unchecked checklist lines added by that batch were resolved. Implementation should add concrete extension API entries for `:register-turn-augmenter` and `:create-turn-augmentation-child-session`, validate explicit result envelopes, and expose the exact `:turn/augmentation-context` prepared-request layer/summary fields.

- no architectural review feedback (design-review architecture turn)

- ambiguity review (design-review turn) added 7 new design steps

- no inconsistency review feedback (design-review turn)

- design-step handoff (design-review batch): resolve the 7 open ambiguity steps as closed core-owned data contracts, not policy expansion; prefer core-normalized provenance/status over extension-supplied authority, make registration gating independent of ambient active-session state unless explicitly modelled, and give history snippets fixed testable bounds. Relevant files: `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/prompt_recording.clj`, `components/agent-session/src/psi/agent_session/message_text.clj`, `components/agent-session/src/psi/agent_session/context.clj`, `components/agent-session/src/psi/agent_session/context_index.clj`, `components/agent-session/src/psi/agent_session/extensions/api.clj`.

- design follow-up completion (third review batch): latest task-scoped design-review batch identified as `4ae9bea9b`..`1ee9c586f` with baseline `6501d40f9`; the 7 current unchecked checklist lines added by that batch were resolved. Implementation should treat turn-augmenter registration as manifest/effective-permission gated only, with per-session capability gating at invocation; core normalizes append-block provenance, permits duplicate block ids, invalidates bad child-session ids, uses fixed history projection bounds (last 8 prior messages, 200-char normalized snippets), and records suppressed child turns with `:status :suppressed`, no providers, and no inserted request message.

- no new architectural review feedback after resolved design-review follow-ups

- ambiguity review added 1 new design step

- no new inconsistency review feedback after latest ambiguity follow-up

- design-step handoff (invocation-scoped child API): resolve the open ambiguity by keeping augmentation child creation core-owned and invocation-scoped, not ambient-session-scoped; prefer an explicit runtime-held active augmentation invocation/provenance guard around `:create-turn-augmentation-child-session`, with predictable unauthorized/outside-invocation failure and verification that created child ids match the active provider/session/turn before result acceptance. Relevant files: `components/agent-session/src/psi/agent_session/extensions/api.clj`, `components/agent-session/src/psi/agent_session/mutations/session.clj`, `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj`, `components/agent-session/src/psi/agent_session/child_session_state.clj`, `components/agent-session/src/psi/agent_session/context.clj`.

- design follow-up completion (invocation-scoped child API): latest task-scoped design-review batch identified as `ebd63069a`..`ead94f6f4` with baseline `3b809157c`; the 1 current unchecked checklist line added by that batch was resolved. Implementation should make `:create-turn-augmentation-child-session` a stable guarded API closure whose authority comes only from a runtime-held active provider invocation context, cleared after the handler returns; outside-invocation calls throw `:no-active-turn-augmentation-invocation` and create no session.

- ambiguity review added 5 new design steps

- no new inconsistency review feedback after current ambiguity review

- design-step handoff (current ambiguity batch): resolve the 5 open AMB steps by keeping augmentation a single core-owned pre-prepare barrier: every live non-suppressed turn should either have one canonical augmentation record before request preparation or fail/diagnose explicitly. Model cancellation, extension unload/reload, and child-session option checks through dispatch/session/extension lifecycle state, not ad hoc runtime flags; treat workflow-run-id as provenance only if existing prompt lifecycle already threads it. Relevant files: `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`, `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/dispatch_effects.clj`, `components/agent-session/src/psi/agent_session/extensions/api.clj`, `components/agent-session/src/psi/agent_session/extensions/loader.clj`, `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj`.
- design follow-up completion (current ambiguity batch): latest task-scoped design-review batch identified as `d6e3975ff`..`3cffacf82` with baseline `482ff803f`; the 5 current unchecked checklist lines added by that batch were resolved. Implementation should treat pre-turn augmentation as a terminal barrier: live prepare requires a closed canonical record, cancellation closes with `:canceled` and no request execution, workflow run id is provenance only, extension reload/unload owns registration cleanup, and augmentation child sessions may only narrow parent tools/model authority.

- no new architectural review feedback after current resolved follow-ups

- ambiguity review added 2 new design steps

- inconsistency review added 1 new design step

- design-step handoff (trust/child-run/stale-status): resolve the latest 3 open steps by keeping authority core-owned: `:trust` should be validated/normalized as request-rendering metadata, not extension-granted privilege; child-session execution should run through existing core session/prompt lifecycle and cancellation/replay boundaries, not hidden extension handles; stale overall status needs one terminal-state rule with tests proving late results cannot rewrite prepared-request input. Relevant files: `components/agent-session/src/psi/agent_session/extensions/api.clj`, `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`, `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj`, `components/agent-session/src/psi/agent_session/prompt_request.clj`, `components/agent-session/src/psi/agent_session/prompt_recording.clj`, `components/agent-session/src/psi/agent_session/dispatch_effects.clj`, `components/agent-session/src/psi/agent_session/child_session_state.clj`.

- design follow-up completion (trust/child-run/stale-status): latest task-scoped design-review batch identified as `0886778a7`..`52302f529` with baseline `79d506e5a`; the 3 current unchecked checklist lines added by that batch were resolved. Implementation should core-normalize append-block `:trust` to `:project-derived`, treat child-session creation as allocation-only with a guarded run API through the canonical child prompt lifecycle, and never use `:stale` as an overall terminal record status.

- design scope update after user clarification: remove the v1 `:trust` model entirely. Implementation should not accept, inject, normalize, validate, or render based on `:trust` for `:append-context-block`. Accepted extension-returned block content is injected as turn augmentation context; provenance remains core-normalized separately.

- design scope update after user clarification: remove the dedicated v1 augmentation child-session API. Do not implement `:create-turn-augmentation-child-session` or a special paired child-run API for this task. Context-manager may create/run helper sessions with the existing extension session APIs, as `auto-session-name` does; recursion avoidance is extension-owned by tracking helper session ids and returning `:no-op` for them. Provider-supplied child/helper ids in augmentation results are provenance only and receive shape validation, not dedicated-origin validation.

- architectural review added 1 new design step: live augmenter invocation must sit on the dispatch effect/runtime boundary, not in a pure handler, because handlers may perform arbitrary extension/helper-session work before recorded operations influence request preparation.

- ambiguity review added 5 new design steps: concrete extension identity/capability schemas, diagnostic reason/query surfaces, and minimal context-manager scaffold behavior remain underspecified.
