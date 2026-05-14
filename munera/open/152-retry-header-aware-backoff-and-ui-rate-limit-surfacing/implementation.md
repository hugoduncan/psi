2026-05-14 ambiguity review
- Missing implementation.md at review start; created for review log continuity.
- Ambiguity: task requires new actionable follow-up items in `design-steps.md`, but task only had `steps.md`; review uses new `design-steps.md` as requested.
- Ambiguity: design says stale retry metadata must clear when retry waiting ends or is superseded, but does not define whether successful non-retry terminal completion (`on-agent-done`) must always clear it as the canonical owner rather than relying only on later replacement.
- Ambiguity: design requires one canonical retry-metadata surface across backend projections and RPC, but does not specify whether `session-summary`, Pathom resolvers, and RPC `session/updated` must expose the same nested shape/field names or may diverge into UI-specific flattened payloads; define one authoritative shape.
- Ambiguity: design requires Emacs and TUI to render retry timing/rate-limit information, but does not pin the exact backend-owned TUI-visible surface (status line vs footer usage/session-activity vs other summary surface), leaving acceptance subjective.
- Ambiguity: design requires provider headers to reach the retry scheduler, but does not identify the canonical error/result shape that must carry headers across transport → turn/runtime → session; without that contract, "preserve headers" is underspecified.

2026-05-14 ambiguity follow-up execution
- Completed all newly added ambiguity design-steps in `design-steps.md`.
- Updated `design.md` to fix the canonical retry metadata shape as one shared nested `:retry` map, including nested normalized `:rate-limit` fields.
- Defined projection parity explicitly: session summary, Pathom/resolvers, and RPC `session/updated` all preserve the same `:retry` shape/field naming.
- Defined lifecycle ownership explicitly: session/runtime retry owner owns active retry metadata; `on-agent-done` and other terminal non-retrying completion paths must clear `:retry`.
- Defined objective UI acceptance surfaces: TUI uses the existing session summary/status line surface; Emacs uses the existing session/status diagnostics surface.
- Defined the transport-to-session propagation contract explicitly: the retry scheduler consumes provider response headers from `:provider-error/headers` on the terminal error/result map passed to `:on-retry-triggered`.
- Updated `plan.md` to record the resolved ambiguity decisions so implementation can proceed without reopening these questions.
- Did not touch `steps.md` execution items per task instruction.

2026-05-14 inconsistency review
- Inconsistency: design/acceptance now require one explicit tested backend rule for numeric `RateLimit-Reset` interpretation, but plan only lists that rule as a decision to make during implementation and steps only mention implementing/documenting a rule. This leaves task intent, plan, and execution checklist out of sync on whether the rule is already fixed versus still open; resolve by choosing and recording the canonical rule in design/plan and adding proof expectations against that exact rule.

2026-05-14 inconsistency follow-up execution
- Fixed the open `RateLimit-Reset` inconsistency in task artifacts.
- Updated `design.md` to choose one canonical numeric interpretation rule: `>= 1000000000000` → epoch ms; otherwise `>= 1000000000` → epoch seconds; otherwise relative seconds from now.
- Updated `plan.md` so the reset rule is no longer deferred to implementation.
- Tightened proof expectations in task artifacts so focused tests must assert each branch of the canonical reset rule directly.
- No blocking reason; newly added design-step completed.

2026-05-14 implementation
- Narrowest canonical normalization owner chosen: `psi.session-state.model` now owns pure retry/rate-limit header normalization helpers (`normalized-headers`, `header-value`, `retry-after-delay-ms`, `rate-limit-reset->timing`, `retry-metadata`). This keeps provider/header interpretation in backend-owned session/retry semantics rather than UI or transport-local formatting.
- Preserved provider headers through the canonical retry path:
  - turn-runtime now carries provider `:headers` into turn error handling and records them on assistant error messages as `:provider-error/headers`
  - non-streaming provider errors also project `:provider-error/headers`
  - `prompt_loop/finish-agent-loop!` now forwards `:provider-error/headers` onto the `:pending-agent-event` consumed by `:on-retry-triggered`
  - thrown ex-data errors in `prompt_loop` now preserve `:headers` as `:provider-error/headers`
- Session state/schema extended with canonical nested `:retry` metadata and default `nil` ownership.
- Retry scheduling path updated in `statechart_actions.clj`:
  - computes normalized retry metadata from provider headers when present
  - prefers valid `Retry-After` / `X-Retry-After`
  - falls back to existing exponential backoff when retry-after is absent/invalid
  - stores canonical `:retry` metadata while incrementing `:retry-attempt`
  - schedules the retry effect using the chosen delay
- Retry lifecycle clearing implemented:
  - `on-agent-done` clears `:retry`
  - `on-abort` clears `:retry`
  - `on-compact-done` clears `:retry`
  - `on-retry-resume` clears `:retry` before restarting the agent loop
- Shared backend projections updated:
  - `session-summary` now includes nested `:retry` and extends `:status-session-line` with retry timing/source/rate-limit fragments
  - session resolver surface now exposes `:psi.agent-session/retry`
  - RPC `session/updated` requires and emits the canonical `:retry` map unchanged
- Existing UI mechanisms reused as designed:
  - Emacs keeps consuming `status-session-line` via existing status diagnostics projection; only payload/state storage was extended to preserve `:retry`
  - TUI keeps consuming the existing footer/status line surface; footer projection now appends retry timing and normalized rate-limit text to the canonical backend `:status-line`
- Follow-up cleanup/review pass:
  - removed duplicated retry display formatting logic from `session_summary.clj` and `footer.clj`
  - extracted shared app-runtime owner `psi.app-runtime.retry-display` for relative retry/rate-limit formatting
  - re-ran focused tests plus lint on the shared display extraction; no behavior drift observed
- Provider-doc-grounded assumptions used for this slice:
  - treat provider docs as guidance for retryable/rate-limit behavior, but do not require any given header to be present
  - when valid retry timing headers are observed, they are treated as authoritative timing guidance
  - missing headers are not protocol errors; fallback backoff remains canonical
  - both standard and legacy `X-` prefixed rate-limit headers remain supported to accommodate gateways/proxies/compatible endpoints
- Focused verification completed:
  - `clojure -M:test --focus psi.agent-session.retry-headers-test --focus psi.agent-session.statechart-actions-test --focus psi.app-runtime.session-summary-test --focus psi.rpc-events-test --focus psi.app-runtime.footer-test`
  - result: `25 tests, 105 assertions, 0 failures`
- Emacs focused verification completed:
  - `emacs -Q --batch -L components/emacs-ui -L components/emacs-ui/test -l ert -l components/emacs-ui/test/psi-streaming-runtime-test.el -f ert-run-tests-batch-and-exit`
  - result: `21 tests, 0 unexpected`
- Lint status:
  - touched files lint clean except for pre-existing warnings in `turn_runtime/core.clj` about unresolved `ai/execute-response-in` / `ai/execute-response`, with no new lint errors introduced by this slice.

2026-05-14 implementation review
- Good shape overall: canonical backend-owned `:retry` metadata, provider-aware delay selection, and shared UI surfacing all match task intent.
- Follow-up proof/spec gaps were valid and are now addressed.

2026-05-14 implementation review follow-up execution
- Added focused `psi.session-state.model-test` coverage proving `initial-session` defaults `:retry` to `nil`, still validates, and accepts the populated canonical nested retry metadata shape.
- Extended `spec/rpc-edn.allium` so `SessionUpdatedPayload` models the nested canonical retry payload and the session-updated consistency guard now requires `retry` in the payload contract.
- Kept focused RPC proof aligned with the contract by continuing to assert the nested `:retry` payload in `components/rpc/test/psi/rpc_events_test.clj`.
- Added an Emacs regression assertion proving `psi-emacs--handle-session-updated-event` preserves nested retry detail in frontend state, not only the preformatted status line.
- Verification:
  - `clojure -M:test --focus psi.session-state.model-test --focus psi.rpc-events-test`
  - `emacs -Q --batch -L components/emacs-ui -L components/emacs-ui/test -l ert -l components/emacs-ui/test/psi-streaming-runtime-test.el -f ert-run-tests-batch-and-exit`
  - both passed cleanly.
