# Steps

## Slice 1 — Baseline + seam contract

- [x] Run full-suite `bb test` (pre-migration); record passed/failed/errored counts in implementation.md as the local baseline, noting the task-242 reference baseline (2450/24/38)
- [x] Read `psi.ai.streaming/stream-response` (and `stream-response-seq` if relevant) to pin the provider-impl map contract the stub must satisfy
- [x] Read `turn-runtime/make-provider-event-consumer`'s `:error` case to pin the exact stream `:error` event keys (`:http-status`, `:provider-error/headers`) and the successful-stream event vocabulary for the recovery turn
- [x] Record the pinned contract (map shape + event shapes for 429 and recovery) in implementation.md

## Slice 2 — Stub provider + ctx helper

- [x] Add a stub-provider constructor in `components/rpc/test/psi/rpc_prompt_test.clj` with an internal attempt counter: attempt 1 → `:error` event with `:http-status 429`, headers `Retry-After` = activation delay, `RateLimit-Limit`, `RateLimit-Remaining "0"`; attempt 2 → `:error` with `Retry-After` = changed delay and changed `RateLimit-Remaining`; attempt 3+ → successful recovery stream (text "recovered", stop)
- [x] Expose the attempt counter (or return it alongside the provider) so both tests keep asserting attempt counts as today
- [x] Add a ctx helper using `psi.ai.core/create-context {:anthropic stub}` yielding `{:provider-registry (atom {:anthropic stub})}`; this is the value to pass as the **first `ai-ctx` arg** of `execute-prepared-request!` (provider resolution consults only that arg), not merged into the app-runtime `ctx`; verify stub resolution via `resolve-provider` path works for model `{:provider :anthropic :id "stub"}`
- [x] Commit

## Slice 3 — Migrate the shared driver + focus-gate test

- [x] Rewrite `drive-provider-retry-through-progress-loop!`: remove the single `with-redefs [turn-runtime/execute-live-turn! ...]` site (all retry sub-tests reach the stub through this shared driver, so this migrates them all at once); pass the stub `ai-ctx` (`{:provider-registry ...}` from `create-context`) as the **first `execute-prepared-request!` arg**, replacing `{:provider-registry (atom {})}`, leaving the second app-runtime `ctx` unchanged; keep `start-progress-loop!` wiring, THREAD-AFFINITY invariant (drive on test thread), and `await-retry-footer-text!` sync via `:provider-retry-sleep-fn`
- [x] Verify `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test` still asserts all three retry frames for the focused session and zero frames for the background session, unchanged
- [x] Run `bb test --focus psi.rpc-prompt-test`; green
- [x] Run `clj-paren-repair` on the edited file; re-read; `clj-kondo` clean
- [x] Commit

## Slice 4 — Verify the sibling test + comment removal

- [x] Confirm the sibling `rpc-prompt-provider-retry-state-publishes-footer-updated-test` is already migrated by Slice 3 — it has no inline `with-redefs` to remove; it reaches the stub only via the shared `drive-provider-retry-through-progress-loop!` driver (~line 707)
- [x] Verify it still asserts the same activation/changed/clear footer sequence at the pre-gate `emit!`
- [x] Remove the task-242 "deliberate bounded exception to ¬mock/¬stub" comment block (and any other now-stale comments referencing the deferred seam migration)
- [x] Confirm `grep with-redefs` in the retry-footer tests shows no logic-boundary redefinition remains (the unrelated `session/query-in` site at ~line 91 is out of scope — leave it, note it in implementation.md)
- [x] Run `bb test --focus psi.rpc-prompt-test`; green
- [x] Commit

## Slice 5 — Harness consolidation (forwarded from task 242)

- [x] Replace `active-retry-text-prefix` length-subtraction derivation with a direct derivation from `psi.app-runtime.retry-display/retry-status-text` or an explicit literal
- [x] Collapse `focus-gated-emitter!` and `default-focus-emitter!` into one parameterized `focus-emitter! [session-id focus]` builder; update both call-site tests
- [x] Prune matcher/format/config-coupling helpers made redundant by the provider-seam harness (no churn beyond redundancy) — none found redundant; the retained helpers each still have a distinct single-authority role
- [x] Run `bb test --focus psi.rpc-prompt-test`; green; lint clean
- [x] Commit

## Slice 6 — Flakiness re-evaluation + close-out

- [x] Run full-suite `bb test` (post-migration); record passed/failed/errored counts in implementation.md
- [x] Record the before/after comparison against the baseline and state whether the parallel-isolation failure set is unchanged, reduced, or eliminated (unchanged ⇒ recorded finding: these two sites were not the isolation cause)
- [x] Verify all design.md acceptance criteria; note verification in implementation.md
- [x] Commit
