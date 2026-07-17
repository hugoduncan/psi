# Implementation

- no architectural review feedback (design aligns with ¬mock/¬stub standard; migrates off `with-redefs` of a logic boundary onto the injectable per-ctx `:provider-registry` provider seam; behaviour-preserving, no shims, preserves focus-gate invariants)

- ambiguity review added 1 new design step (undefined done-condition for the "re-evaluate flakiness" acceptance criterion)
- no inconsistency review feedback

## Notes for the design-step task (design-review slices)

- Resolving the one open design-step (undefined done-condition for the
  "re-evaluate parallel `with-redefs` flakiness" acceptance criterion) is a
  design.md acceptance-wording sharpening, not a scope change — keep the two
  frozen call sites and behaviour-preserving constraint intact.
- The flakiness referenced is the parallel `with-redefs` test-isolation issue
  recorded against task 242; primary sources to consult when specifying the
  concrete outcome: `munera/closed/242-retry-footer-focus-gate-regression/`
  (implementation.md, esp. Slice 6 seam-existence + the flakiness notes) and
  the test file under change `components/rpc/test/psi/rpc_prompt_test.clj`.
- The provider seam authority is `psi.ai.core/create-context` (`:provider-registry`);
  the retry-key propagation path to verify lives in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`
  (`make-provider-event-consumer` `:error` case). Retry-footer text authority is
  `psi.app-runtime.retry-display/retry-status-text`.

## Design-follow-up pass (ambiguity design-step resolved)

- Resolved the ambiguity design-step by sharpening the flakiness acceptance
  criterion into a verifiable record-and-compare done-condition (split into two
  acceptance bullets: comment removal, and recorded before/after `bb test`
  count comparison against the task-242 baseline).
- Chosen done-condition is record-not-fix, because task 242's own verification
  (implementation.md, "Verification" note) observed the *same* failing test
  names with and without its change — the pre-existing parallel-isolation
  failure set (2450 passed / 24 failed / 38 errored) is broad and not confirmed
  to originate in these two retry-footer sites. Requiring a green full suite
  would over-scope this behaviour-preserving migration; the plan author's
  measurable target is the recorded comparison in implementation.md.
- Concrete task-242 baseline numbers to compare against are in
  `munera/closed/242-retry-footer-focus-gate-regression/implementation.md`
  ("Verification" bullet: baseline 2450/24/38, with-change 2451/24/38 — the +1
  pass being the added test).

## Plan-review ambiguity pass

- ambiguity review added 1 new design step: plan conflates the two
  `execute-prepared-request! [ai-ctx ctx …]` context params. Verified against
  source that provider resolution uses only the first `ai-ctx` arg
  (`do-stream!` → `stream-response-in` → `context-provider-registry ai-ctx`),
  currently `{:provider-registry (atom {})}`; the second `ctx` carries session
  state / `:provider-retry-sleep-fn` and is not consulted for resolution. The
  stub `:provider-registry` (from `create-context`) must replace the first
  `ai-ctx` arg, not be merged into the app-runtime `ctx`.

- inconsistency review added 1 new design step: design.md Scope (and plan/steps
  Slice 3–4) assume two `with-redefs` sites incl. an inline one in the sibling
  test; code has a single shared `with-redefs` inside
  `drive-provider-retry-through-progress-loop!`, reached by all retry tests
  (verified in rpc_prompt_test.clj). Slice 4's "remove its inline with-redefs"
  is a no-op; the real migration removes one driver-level site (Slice 3).

## Design-review session outcome (arch + ambiguity + inconsistency)

- Shared design-review session (all three turns) found no new actionable
  feedback: the design is architecturally clean (¬mock/¬stub via injectable
  per-ctx `:provider-registry` seam, behaviour-preserving, no shims, focus-gate
  invariants intact) and internally consistent. The only design-step is the
  already-resolved flakiness done-condition.
- Principles to hold when addressing the design-step: keep the two frozen call
  sites and behaviour-preserving constraint; resolve via record-and-compare, not
  by chasing a green full suite (record-not-fix).
