# 160 — Implementation notes

(append-only)

## Review: ambiguity pass (design/plan/steps)

1. **Extension-path loading via mutations is unaddressed.** `load-startup-resources-via-mutations-in!` also calls `run-mutation-in!` for `extension-paths` (via `psi.extension/add-extension`). Design says "Extension loading via `ext/load-extension-init-in!` is already direct" — true only for `:init-var` extension-targets. The `extension-paths` loop also round-trips through mutations. Plan step 1 only converts templates/skills/tools. Production passes `extension-paths []` so low-risk, but the code path remains and the summary's `:mutations` vector includes `'psi.extension/add-extension`. Design/plan should state whether extension-path loading is converted to direct `ext-rt/add-extension-in!` or left as dead code.

2. **`add-extension` mutation is not dispatch-mediated.** Unlike template/skill/tool mutations that delegate to `dispatch/dispatch!`, `add-extension` calls `ext-rt/add-extension-in!` directly. The design frames the problem as "round-trips through the entire Pathom graph layer to perform what are direct dispatch calls" — this is accurate for templates/skills/tools but not for extension-paths. Clarify in design that extension-path loading is a Pathom round-trip to a direct runtime call (not dispatch).

3. **`:origin` for new direct dispatch calls is unspecified.** Mutations currently pass `{:origin :mutations}` to `dispatch!`. The replacement direct calls need an `:origin` value. Existing bootstrap dispatch calls use `{:origin :core}`. Plan should specify `:origin :core` (or `:bootstrap`) for the replacement calls.

4. **Dispatch return values are discarded — confirm intentional.** The current code uses `doseq` for template/skill/tool loops, discarding `run-mutation-in!` return values. The dispatch handlers return `{:return {:added? ... :count ...}}`. Plan should confirm the replacement direct dispatch calls also discard returns (they're only used for the final count read from session-data).

## Follow-up execution: ambiguity design-steps

All 4 design-steps completed:

1. **Extension-paths → direct `ext-rt/add-extension-in!`**: design.md scope item 2 added; constraints updated; plan.md Decisions documents rationale. The `add-extension` mutation wraps `ext-rt/add-extension-in!` with no dispatch — replacement calls the same function directly.
2. **Framing corrected**: design.md Intent now distinguishes template/skill/tool mutations (Pathom→dispatch round-trip) from `add-extension` mutation (Pathom→direct runtime call).
3. **`:origin :core`**: plan.md Decisions specifies `:origin :core` for replacement dispatch calls, consistent with existing bootstrap dispatch calls in `bootstrap-in!`.
4. **Return discard confirmed**: plan.md Decisions documents intentional `doseq` discard; steps.md step 1 updated with explicit note.

## Review: inconsistency pass (design/plan/steps)

1. **Dispatch function mismatch: `dispatch/dispatch!` vs `session/dispatch-in!`.** Plan says "direct `dispatch/dispatch!` calls" and steps say to add require `psi.agent-session.dispatch`. But `bootstrap-in!` exclusively uses `session/dispatch-in!` (from `psi.agent-session.core`, already required as `session`). The existing calls in `bootstrap-in!` — `:session/bootstrap-prompt-state`, `:session/refresh-system-prompt`, `:session/set-active-tools`, `:session/set-startup-bootstrap-summary` — all go through `session/dispatch-in!`. Using `dispatch/dispatch!` directly for the new template/skill/tool calls would be inconsistent with the same function's existing pattern. Plan/steps should specify `session/dispatch-in!` and drop the `psi.agent-session.dispatch` require addition.

2. **Missing require for `ext-rt`.** Steps say "Replace extension-path loop: `ext-rt/add-extension-in!`" but `bootstrap.clj` does not require `psi.agent-session.extension-runtime`. Steps list removing `psi.agent-session.mutations` and `psi.query.core`, and adding `psi.agent-session.dispatch`, but omit adding `psi.agent-session.extension-runtime` (as `ext-rt`). This require is needed for the extension-path replacement to compile.

## Follow-up execution: inconsistency design-steps

Both design-steps completed:

1. **Dispatch function → `session/dispatch-in!`**: plan.md approach and decisions updated to specify `session/dispatch-in!` instead of `dispatch/dispatch!`; removed the `psi.agent-session.dispatch` require addition from plan and steps; steps.md step 1 now uses `session/dispatch-in!` for all three resource loops; design.md acceptance criterion 2 updated. No new require needed — `psi.agent-session.core` already required as `session`.
2. **Added `ext-rt` require**: steps.md step 1 now lists adding `psi.agent-session.extension-runtime` (as `ext-rt`); plan.md decisions documents this require addition alongside the removals.

## Review: implementation review (pre-execution)

1. **Extension-path return key mismatch.** `bootstrap-in!` reads extension results using namespaced keys (`:psi.extension/loaded?`, `:psi.extension/path`, `:psi.extension/error`) — see lines 146–148 and 156. The mutation `add-extension` returns these namespaced keys. But `ext-rt/add-extension-in!` returns **unnamespaced** keys (`{:loaded? ... :path ... :error ...}`). Step 1 replaces the mutation call with direct `ext-rt/add-extension-in!` but doesn't address the key translation. The init-var path already manually constructs namespaced keys (lines 68–70), so it's unaffected. The extension-path replacement must either: (a) wrap `ext-rt/add-extension-in!` results to produce namespaced keys matching the init-var path shape, or (b) normalize both paths to unnamespaced keys and update `bootstrap-in!` to read unnamespaced keys. Option (a) is simpler and consistent with the existing init-var pattern.
