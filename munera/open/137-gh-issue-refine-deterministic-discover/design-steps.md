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
