# Design follow-up steps

Unchecked items added by design review pass 1 (2026-05-10).

- [x] **A. Resolve `:tool` vs `:invoke` architectural overlap.** Decided: use `:invoke` + deterministic-operation-registry. `gh-find-issue` is a workflow-internal synchronous operation, never exposed to AI agents as a tool. `:invoke` requires zero new step types, zero IR changes, zero execution-adapter keys. Phase 2 (`:tool` step type) removed from scope entirely.

- [x] **B. Specify the IR schema for `:tool` steps (or confirm compile-to-`:invoke`).** Confirmed: no IR schema changes needed. The operation registers as a deterministic operation under id `"github/find-issue"`. The workflow step uses the existing `:invoke` step type directly. The target-IR compiler already handles `:invoke` steps.

- [x] **C. Fix tool registration contract.** Moot — `gh-find-issue` is a deterministic operation, not a tool. Extension registers via `(:register-operation api)` with `{:id "github/find-issue" :description "..." :handler fn}` matching `operation-definition-schema` in `psi.deterministic-operation-registry.defs`. Design updated accordingly.

- [x] **D. Replace `clojure.data.json` with `cheshire`.** Design updated: `cheshire/cheshire "5.13.0"` declared in `components/github/deps.edn`. All references to `clojure.data.json` removed.

- [x] **E. Specify `:yields` shape for the discover step.** Specified: the `:invoke` step declares `{:outputs {:summary {:source :invoke/summary}} :yields {:type :text :text :summary}}`. The operation handler serializes the result to a Markdown handoff string and returns it as `:summary` in the operation result. `step-yield-field-value` for `:text` resolves to the `:summary` output value. Downstream `{:from {:step "discover" :yield :text}}` requires no change.

- [x] **F. Create `plan.md`.** Written at `munera/open/137-gh-issue-refine-deterministic-discover/plan.md`.

Unchecked items added by design review pass 2 (2026-05-10).

- [x] **H. Clarify `:outputs` partial override intent.** Confirmed: no downstream step in `gh-issue-refine.md` references `{:output :data}` or `{:output :result}` from `discover` — all downstream steps consume `{:yield :text}` only. Explicitly declaring `{:outputs {:summary ...}}` is correct and intentional. Design.md updated with explanation of the override behavior.

- [x] **I. Resolve `psi/github` placement: `extensions/` vs `components/`.** Decided: `extensions/github/`. `psi/github` is domain-specific (GitHub-only) and uses the extension registration pattern. All domain-specific extensions live in `extensions/`. Design.md, plan.md, and steps.md updated to use `extensions/github/` throughout.

- [x] **J. Specify root `deps.edn` wiring.** Specified: add `psi/github {:local/root "extensions/github"}` under `:deps`; add `extensions/github/src` to `:run`, `:psi`, `:tui-demo`, `:test-paths`, `:test` aliases; add `extensions/github/test` to `:test-paths` and `:test` aliases. Design.md, plan.md, and steps.md updated with explicit wiring requirements.

- [x] **K. Align `tests.edn` suite with placement decision.** Resolved: `extensions/github/` → `:extensions` suite in `tests.edn`. Add `extensions/github/test` to `:test-paths` and `extensions/github/src` to `:source-paths` of the `:extensions` suite. Plan.md and steps.md updated.

Unchecked items added by design review pass 3 (2026-05-10).

- [x] **L. Specify how `psi.github.extension/init` is tested.** Decided: two-layer approach. (1) `psi.github.find-issue/invoke` unit tests call the fn directly with a stub ctx carrying `:github-shell-fn` — no extension API needed. (2) `psi.github.extension/init` registration test uses `create-extension-api` (from `psi.agent-session.extensions`) with a captured `register-deterministic-operation-fn` override, matching the pattern in `extensions_test.clj`. The nullable API (`create-nullable-extension-api`) cannot be used for `init` tests — it has no `:register-operation` key. Design.md and steps.md updated.

- [x] **M. Specify slug truncation rule precisely.** Decided: lower-case the title, extract `[a-z0-9]+` words, join with `-`, hard-truncate the joined string at 40 chars, strip any trailing `-`. Result is `[a-z0-9-]`, ≤ 40 chars, never ends with `-`. Word-boundary truncation is NOT used — hard truncation at 40 on the joined string is simpler and deterministic. Design.md updated with precise rule and example.

- [x] **N. Fix `:input` arg schema for nil case.** Decided: change schema to `[:maybe :string]`. When `:input` is absent from `workflow-input`, `resolve-invoke-args` resolves `{:from :workflow-input :path [:input]}` to `nil`; `[:maybe :string]` accepts `nil` and treats it as "no narrowing". The authored step keeps `:input {:from :workflow-input :path [:input]}` in `:args` — no conditional omission needed. Design.md updated.

- [x] **O. Add `extensions/github/src` to `tests.edn` `:unit` suite `:source-paths`.** Confirmed: all other extension `src` paths appear in `:unit` `:source-paths` for compilation parity. `extensions/github/src` must be added. Plan.md and steps.md updated.

- [x] **P. Confirm `extensions/tests.edn` requires no change.** Confirmed: `extensions/tests.edn` is a standalone kaocha config using relative paths (`src`/`test`) for running tests within the `extensions/` directory. It is not the authoritative suite config. Root `tests.edn` owns all suite definitions. No existing extension is listed by absolute path in `extensions/tests.edn`. `psi/github` tests are fully covered by root `tests.edn` `:extensions` suite. No change to `extensions/tests.edn` needed.

Unchecked items added by design review pass 5 (2026-05-10).

- [ ] **Q. Fix `deps.edn` `:test-paths` alias wiring instruction.** Design and plan say add `extensions/github/src` to `:test-paths` alias. Actual `:test-paths` contains only test paths for extensions (e.g. `extensions/work-on/test`), never extension `src` paths. Extension `src` paths appear in `:test` alias only. Correct instruction: add `extensions/github/src` to `:test` alias (not `:test-paths`); add `extensions/github/test` to both `:test-paths` and `:test` aliases. Update design.md, plan.md, and steps.md to reflect the corrected wiring.

- [ ] **R. Specify Phase 2 integration test file location.** Design and steps call for a "focused workflow-runtime integration test" but do not specify where the test file lives (`extensions/github/test` vs `components/workflow-runtime/test`). Placement determines which Kaocha suite runs it and whether additional source-paths wiring is needed. Decide and document in design.md and steps.md before Phase 2 execution.
