# Plan — 243 Migrate retry-footer E2E tests off `with-redefs` onto the provider-registry seam

## Approach

Test-only change in `components/rpc/test/psi/rpc_prompt_test.clj`. No product
code changes.

Key mechanics (verified in source):

- `psi.ai.core/create-context {providers}` → `{:provider-registry (atom providers)}`.
  `resolve-provider` looks up `(:provider model)` then falls back to `(:api model)`
  from that per-ctx atom, so a stub registered under `:anthropic` (the provider
  key both tests already use in their prepared-request/model) is resolved with
  zero global-state mutation.
- The provider impl is a map consumed by `psi.ai.streaming/stream-response`
  (`stream` fn, optionally `:execute`). The stub only needs the streaming entry
  used by `turn-runtime/do-stream!` → `psi.ai.core/stream-response-in`.
- `turn-runtime/make-provider-event-consumer`'s `:error` case propagates
  `:http-status` and `:provider-error/headers` from a stream `:error` event
  into the assistant-message, which drives `mark-active-retry!` and the
  existing retry → progress-queue → `footer/updated` pipeline. So a stub
  provider that emits, per attempt:
  1. `:error` with `:http-status 429`, headers `Retry-After 8`,
     `RateLimit-Limit`, `RateLimit-Remaining "0"`
  2. `:error` with `:http-status 429`, headers `Retry-After 4`, changed
     `RateLimit-Remaining`
  3. a successful recovery stream (text content, `:stop`)
  reproduces the exact activation → changed → clear footer sequence the
  current `with-redefs` stub fabricates directly as turn maps.

Migration shape:

1. Build one stub-provider constructor (attempt-counter atom inside), returning
   the provider-impl map, plus a `retry-test-ctx` helper that calls
   `psi.ai.core/create-context {:anthropic stub}` and yields its
   `{:provider-registry (atom {:anthropic stub})}` map. This map is the **first
   `ai-ctx` argument** of `execute-prepared-request! [ai-ctx ctx session-id
   prepared-request progress-queue]` — the only param provider resolution
   consults (`do-stream!` → `ai/stream-response-in` → `context-provider-registry
   ai-ctx`). It replaces the current `{:provider-registry (atom {})}` literal
   passed there; the second app-runtime `ctx` (session state,
   `:provider-retry-sleep-fn`, retry state) is left unchanged and its
   `:provider-registry`, if any, is not consulted.
2. Rewrite `drive-provider-retry-through-progress-loop!` to drop `with-redefs`
   and instead run `execute-prepared-request!` against the real path, passing the
   stub-provider `ai-ctx` (from step 1) as the first arg and the unchanged
   app-runtime `ctx` as the second, keeping the LinkedBlockingQueue /
   `start-progress-loop!` wiring, the THREAD-AFFINITY invariant (drive runs
   synchronously on the test thread), and the `await-retry-footer-text!`
   deterministic sync via `:provider-retry-sleep-fn`.
3. There is exactly one `with-redefs turn-runtime/execute-live-turn!` site — the
   one inside `drive-provider-retry-through-progress-loop!` (step 2). The sibling
   `rpc-prompt-provider-retry-state-publishes-footer-updated-test` has no inline
   `with-redefs`; it reaches the stub solely by calling that shared driver, so
   rewriting the driver (step 2) migrates it automatically. No second
   `with-redefs` removal is needed.
4. Remove the task-242 "deliberate bounded exception to ¬mock/¬stub" comment
   block.
5. Forwarded harness cleanup (design Notes, in-scope while reconstructing):
   - Replace `active-retry-text-prefix` length-subtraction derivation with a
     direct derivation from `psi.app-runtime.retry-display/retry-status-text`
     (or an explicit literal), and collapse now-unneeded driver-vs-matcher
     config-coupling helper authorities.
   - Collapse `focus-gated-emitter!` / `default-focus-emitter!` onto one
     parameterized `focus-emitter! [session-id focus]` builder.
6. Flakiness re-evaluation: run full-suite `bb test` before and after the
   migration; record both passed/failed/errored counts in implementation.md
   against the task-242 baseline (2450/24/38) and state whether the
   parallel-isolation failure set is unchanged, reduced, or eliminated.
   Criterion is the recorded comparison, not a green full suite.

## Decisions

- Register the stub under `:anthropic` (matches existing prepared-request
  model) rather than inventing a new provider key — minimal diff, exercises
  the real resolution path.
- Attempt sequencing lives in the stub provider (atom), mirroring the current
  `attempts*` counter, so both call sites keep returning/asserting the same
  attempt count.
- Keep assertions byte-for-byte equivalent: three retry frames (activation
  `retry in 8s`; changed `retry in 4s` + `remaining 2/5000`; clear with no
  stale `retry in`), same `emit-frame!` boundary assertions, same
  focused/background focus-gate behaviour.
- Helper consolidation happens in the same rewrite (not deferred again), per
  design Notes, but only where the provider-seam harness makes helpers
  redundant — no gratuitous churn beyond the two forwarded items.

## Risks

- Provider-impl contract mismatch: the exact map shape / stream-event vocabulary
  expected by `psi.ai.streaming/stream-response` and
  `make-provider-event-consumer` must be read precisely before writing the stub
  (first step). If the `:error` event path does not carry headers as assumed,
  fall back to whatever real event shape the anthropic provider emits on 429.
- Timing/sync: moving from a synchronous fabricated turn to a real streamed
  path may change when retry state becomes observable; the
  `await-retry-footer-text!` pattern must be preserved (no sleeps).
- Full-suite baseline drift: the pre-existing 24 failed / 38 errored set may
  have moved since task 242; record actual before-run numbers as the local
  baseline rather than assuming 2450/24/38 still holds exactly.
- Recovery-turn shape: the stub's third attempt must produce a turn whose
  assistant-message clears retry state the same way the current fabricated
  `:stop` turn does.

## Slice order

1. **Slice 1 — Baseline + seam contract.** Run full-suite `bb test`, record
   counts in implementation.md. Read `psi.ai.streaming/stream-response` and
   `make-provider-event-consumer` to pin the exact provider-impl map shape and
   `:error` event keys.
2. **Slice 2 — Stub provider + ctx helper.** Implement the stub-provider
   constructor and `create-context`-based ctx helper in the test namespace.
3. **Slice 3 — Migrate the shared driver.** Rewrite
   `drive-provider-retry-through-progress-loop!` off its single `with-redefs`;
   pass the stub `ai-ctx` as the first `execute-prepared-request!` arg; this
   migrates every retry sub-test that calls the driver (focus-gate boundary,
   background, and the sibling test) at once; green under
   `bb test --focus psi.rpc-prompt-test`.
4. **Slice 4 — Verify the sibling test + comment removal.** The sibling
   `rpc-prompt-provider-retry-state-publishes-footer-updated-test` is already
   migrated by Slice 3 (no inline `with-redefs` exists to remove); verify it
   still asserts the same activation/changed/clear footer sequence at the
   pre-gate `emit!`, remove the task-242 exception comment, confirm no
   logic-boundary `with-redefs` remains; green.
5. **Slice 5 — Harness consolidation.** `active-retry-text-prefix` direct
   derivation; collapse the two emitter builders into `focus-emitter!`; prune
   helpers made redundant by the seam; green.
6. **Slice 6 — Flakiness re-evaluation + close-out.** Full-suite `bb test`
   after; record comparison and finding in implementation.md; verify all
   acceptance criteria.
