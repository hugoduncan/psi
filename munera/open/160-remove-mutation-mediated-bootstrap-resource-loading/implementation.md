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
