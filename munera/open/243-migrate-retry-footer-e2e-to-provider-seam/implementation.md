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

## Notes for the plan-review design-step task

- Both plan-review design-steps are wording/scope sharpenings of task files, not
  scope changes: keep the behaviour-preserving constraint and the frozen
  assertions intact when reconciling them.
- Provider-resolution seam facts (verified this pass), for the ai-ctx-vs-ctx step:
  - `execute-prepared-request! [ai-ctx ctx session-id prepared-request progress-queue]`
    is in `components/turn-runtime/src/psi/turn_runtime/core.clj` (~line 513);
    provider resolution flows `execute-provider-attempt!` → `do-stream!` (core ~28)
    → `stream/do-stream!` (`components/turn-runtime/src/psi/turn_runtime/stream.clj` ~15)
    → `ai/stream-response-in` → `context-provider-registry ai-ctx`
    (`components/ai/src/psi/ai/core.clj` ~162/166).
  - `create-context` (ai/core.clj ~45) returns exactly `{:provider-registry (atom providers)}`,
    i.e. the correct shape to pass as the **first `ai-ctx`** arg (drop-in for the
    current `{:provider-registry (atom {})}` literal at the call site).
  - `resolve-provider` matches `(:provider model)` then `(:api model)`; prepared
    model is `{:provider :anthropic :id "stub"}`, so register the stub under `:anthropic`.
- For the single-with-redefs step: the only `execute-live-turn!` `with-redefs` is
  inside `drive-provider-retry-through-progress-loop!` (rpc_prompt_test.clj ~430);
  removing it there migrates all four retry sub-tests at once. The unrelated
  `session/query-in` `with-redefs` (~line 91) is a different test and out of scope.

## Plan-follow-up pass (both plan-review design-steps executed)

- Executed both plan-review follow-up items (design-steps.md items 2 & 3):
  - Provider-seam wiring: plan.md migration-shape steps 1–2 and Slice-order
    Slice 2/3, plus steps.md Slice 2/3, now state explicitly that the stub
    `{:provider-registry …}` (from `create-context {:anthropic stub}`) is passed
    as the **first `ai-ctx` arg** of `execute-prepared-request!` (the only param
    provider resolution consults), replacing `{:provider-registry (atom {})}`,
    leaving the second app-runtime `ctx` unchanged.
  - Single-with-redefs reconciliation: plan.md migration-shape step 3 and
    Slice-order Slice 4, plus steps.md Slice 4, now reflect the real
    single-shared-driver shape — Slice 3 removes the one driver-level
    `with-redefs` and thereby migrates the sibling test; Slice 4 is
    verify-assertions + task-242 comment removal, not a second `with-redefs`
    removal (the previous "remove its inline `with-redefs`" wording was a no-op).
- RESIDUAL for a design-level pass (out of scope here — design.md is read-only
  in the plan-follow-up profile): design.md **Scope** still asserts two
  `with-redefs` sites and that the sibling test "inlines its own identical
  `with-redefs`". Code has exactly one site (in the shared driver); the sibling
  has none. design.md Scope should be corrected to the single-shared-driver
  shape to fully close the inconsistency. plan.md/steps.md are already reconciled.

## Plan-review session — ambiguity turn (batch first turn)

- no ambiguity review feedback: plan.md/steps.md already reconciled by the prior
  plan-review ambiguity + plan-follow-up passes (ai-ctx-vs-ctx param sharpened;
  single-shared-driver `with-redefs` shape). Verified against rpc_prompt_test.clj:
  one driver-level `with-redefs` (~430), first `execute-prepared-request!` arg is
  `{:provider-registry (atom {})}` (~453), `(= 3 attempts)` asserted at 4 sites,
  rate-limit values pinned by `changed-retry-remaining`/`retry-rate-limit`
  constants. Stub-shape and sleep-fn sync are framed as Slice-1 verification, not
  left underspecified. Residual design.md Scope "two with-redefs sites" is an
  inconsistency-turn concern (design.md), not a plan/steps ambiguity.

## Plan-review session (re-run) — ambiguity turn (batch first turn)

- no ambiguity review feedback: plan.md/steps.md remain fully reconciled.
  Re-verified against rpc_prompt_test.clj — single driver-level `with-redefs`
  (~430); first `execute-prepared-request!` arg `{:provider-registry (atom {})}`
  (~454); attempts/rate-limit/delays pinned by constants; `(= 3 attempts)` at 4
  sites; stub stream-event shape framed as Slice-1 verification; recovery turn
  and flakiness record-and-compare done-condition already specified. Residual
  design.md Scope "two with-redefs sites" is an inconsistency-turn concern
  (design.md), tracked by the open design-step, not a plan/steps ambiguity.

## Plan-review session (re-run) — inconsistency turn (batch second turn)

- no inconsistency review feedback: plan.md ↔ steps.md ↔ code ↔ implementation.md
  are mutually consistent (single-shared-driver `with-redefs`, first `ai-ctx`
  arg, Slice 3/4 split, `(= 3 attempts)`). The one remaining cross-file
  inconsistency — design.md Scope's "two with-redefs sites" / sibling "inlines
  its own" framing — is already captured by the open unchecked design-step
  (design-steps.md, item 4); not re-added to avoid duplication. Its correction
  requires a design-editable pass (out of scope for the plan-review profile).

## Plan-review session — inconsistency turn (batch second turn, prior)

- inconsistency review added 1 new design step: design.md Scope still asserts
  "two `with-redefs` sites" (sibling "inlines its own") — contradicts the
  already-reconciled plan/steps and the verified single-driver code. Prior
  design-step 3 was marked done after reconciling only plan/steps; the design.md
  Scope correction remained an unactioned residual (design.md read-only that
  pass), so a fresh unchecked step now targets it for a design-editable pass.
  Not a duplicate: item 3 is checked and its design.md portion was never executed.

## Notes for the design-step task (plan-review inconsistency slice)

- The new inconsistency design-step is a design.md Scope wording correction only —
  not a scope change. Hold the behaviour-preserving constraint and the two frozen
  call sites; do not let the rewording imply a second migration site.
- Concrete shape to write into design.md Scope: one `with-redefs
  [turn-runtime/execute-live-turn! …]` lives in the shared driver
  `drive-provider-retry-through-progress-loop!` (`components/rpc/test/psi/rpc_prompt_test.clj`
  ~line 430; the `execute-prepared-request!` call passing `{:provider-registry (atom {})}`
  is ~line 453). All retry sub-tests (focus-gate boundary ~521, background ~576,
  pre-gate sibling ~644/707) reach the stub only by calling that driver, so
  migrating the one driver-level site migrates them all.
- design.md Acceptance/Approach already speak in the correct (single-seam) terms;
  only the **Scope** section's "two `with-redefs` sites" / sibling "inlines its own"
  framing needs correcting. plan.md and steps.md are already consistent — do not
  re-touch them.

## Plan-follow-up pass (preceding plan-review batch — no attributed steps.md work)

- Batch baseline: previous plan-follow-up completion `650992100`; the immediately
  preceding plan-review batch is `7c7ce6651` (ambiguity turn) + `b207ac329`
  (inconsistency turn) + `565f34746` (notes), run back-to-back. Baseline =
  parent of oldest batch commit = `650992100`.
- `git diff 650992100..HEAD -- steps.md` is **empty**: the batch added no new
  checklist lines to steps.md. Candidate work set for this plan-follow-up is
  therefore empty — nothing executed, plan.md/steps.md unchanged.
- The one checklist line the batch added went to **design-steps.md** (open
  inconsistency: design.md Scope still asserts "two `with-redefs` sites" /
  sibling "inlines its own", contradicting reconciled plan/steps + code). That
  is a design-editable step, out of scope for the plan-follow-up profile
  (design.md read-only here). Next actor: a design-editable pass must correct
  design.md **Scope** to the single-shared-driver shape to fully close it.

## Design-review session outcome (arch + ambiguity + inconsistency)

- Shared design-review session (all three turns) found no new actionable
  feedback: the design is architecturally clean (¬mock/¬stub via injectable
  per-ctx `:provider-registry` seam, behaviour-preserving, no shims, focus-gate
  invariants intact) and internally consistent. The only design-step is the
  already-resolved flakiness done-condition.
- Principles to hold when addressing the design-step: keep the two frozen call
  sites and behaviour-preserving constraint; resolve via record-and-compare, not
  by chasing a green full suite (record-not-fix).
