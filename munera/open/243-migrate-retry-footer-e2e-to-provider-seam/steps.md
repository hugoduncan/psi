# Steps

## Slice 1 — Baseline + seam contract

- [ ] Run full-suite `bb test` (pre-migration); record passed/failed/errored counts in implementation.md as the local baseline, noting the task-242 reference baseline (2450/24/38)
- [ ] Read `psi.ai.streaming/stream-response` (and `stream-response-seq` if relevant) to pin the provider-impl map contract the stub must satisfy
- [ ] Read `turn-runtime/make-provider-event-consumer`'s `:error` case to pin the exact stream `:error` event keys (`:http-status`, `:provider-error/headers`) and the successful-stream event vocabulary for the recovery turn
- [ ] Record the pinned contract (map shape + event shapes for 429 and recovery) in implementation.md

## Slice 2 — Stub provider + ctx helper

- [ ] Add a stub-provider constructor in `components/rpc/test/psi/rpc_prompt_test.clj` with an internal attempt counter: attempt 1 → `:error` event with `:http-status 429`, headers `Retry-After` = activation delay, `RateLimit-Limit`, `RateLimit-Remaining "0"`; attempt 2 → `:error` with `Retry-After` = changed delay and changed `RateLimit-Remaining`; attempt 3+ → successful recovery stream (text "recovered", stop)
- [ ] Expose the attempt counter (or return it alongside the provider) so both tests keep asserting attempt counts as today
- [ ] Add a ctx helper using `psi.ai.core/create-context {:anthropic stub}`; verify stub resolution via `resolve-provider` path works for model `{:provider :anthropic :id "stub"}`
- [ ] Commit

## Slice 3 — Migrate the shared driver + focus-gate test

- [ ] Rewrite `drive-provider-retry-through-progress-loop!`: remove `with-redefs [turn-runtime/execute-live-turn! ...]`; pass the stub-provider ctx into `execute-prepared-request!` (real registry, not `(atom {})`); keep `start-progress-loop!` wiring, THREAD-AFFINITY invariant (drive on test thread), and `await-retry-footer-text!` sync via `:provider-retry-sleep-fn`
- [ ] Verify `rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test` still asserts all three retry frames for the focused session and zero frames for the background session, unchanged
- [ ] Run `bb test --focus psi.rpc-prompt-test`; green
- [ ] Run `clj-paren-repair` on the edited file; re-read; `clj-kondo` clean
- [ ] Commit

## Slice 4 — Migrate the sibling test

- [ ] Rewrite `rpc-prompt-provider-retry-state-publishes-footer-updated-test` onto the shared stub driver; remove its inline `with-redefs`
- [ ] Verify it still asserts the same activation/changed/clear footer sequence at the pre-gate `emit!`
- [ ] Remove the task-242 "deliberate bounded exception to ¬mock/¬stub" comment block (and any other now-stale comments referencing the deferred seam migration)
- [ ] Confirm `grep with-redefs` in the retry-footer tests shows no logic-boundary redefinition remains (the unrelated `session/query-in` site at ~line 91 is out of scope — leave it, note it in implementation.md)
- [ ] Run `bb test --focus psi.rpc-prompt-test`; green
- [ ] Commit

## Slice 5 — Harness consolidation (forwarded from task 242)

- [ ] Replace `active-retry-text-prefix` length-subtraction derivation with a direct derivation from `psi.app-runtime.retry-display/retry-status-text` or an explicit literal
- [ ] Collapse `focus-gated-emitter!` and `default-focus-emitter!` into one parameterized `focus-emitter! [session-id focus]` builder; update both call-site tests
- [ ] Prune matcher/format/config-coupling helpers made redundant by the provider-seam harness (no churn beyond redundancy)
- [ ] Run `bb test --focus psi.rpc-prompt-test`; green; lint clean
- [ ] Commit

## Slice 6 — Flakiness re-evaluation + close-out

- [ ] Run full-suite `bb test` (post-migration); record passed/failed/errored counts in implementation.md
- [ ] Record the before/after comparison against the baseline and state whether the parallel-isolation failure set is unchanged, reduced, or eliminated (unchanged ⇒ recorded finding: these two sites were not the isolation cause)
- [ ] Verify all design.md acceptance criteria; note verification in implementation.md
- [ ] Commit
