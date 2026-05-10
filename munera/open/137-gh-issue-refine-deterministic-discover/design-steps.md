# Design follow-up steps

Unchecked items added by design review pass 1 (2026-05-10).

- [x] **A. Resolve `:tool` vs `:invoke` architectural overlap.** Decided: use `:invoke` + deterministic-operation-registry. `gh-find-issue` is a workflow-internal synchronous operation, never exposed to AI agents as a tool. `:invoke` requires zero new step types, zero IR changes, zero execution-adapter keys. Phase 2 (`:tool` step type) removed from scope entirely.

- [x] **B. Specify the IR schema for `:tool` steps (or confirm compile-to-`:invoke`).** Confirmed: no IR schema changes needed. The operation registers as a deterministic operation under id `"github/find-issue"`. The workflow step uses the existing `:invoke` step type directly. The target-IR compiler already handles `:invoke` steps.

- [x] **C. Fix tool registration contract.** Moot — `gh-find-issue` is a deterministic operation, not a tool. Extension registers via `(:register-operation api)` with `{:id "github/find-issue" :description "..." :handler fn}` matching `operation-definition-schema` in `psi.deterministic-operation-registry.defs`. Design updated accordingly.

- [x] **D. Replace `clojure.data.json` with `cheshire`.** Design updated: `cheshire/cheshire "5.13.0"` declared in `components/github/deps.edn`. All references to `clojure.data.json` removed.

- [x] **E. Specify `:yields` shape for the discover step.** Specified: the `:invoke` step declares `{:outputs {:summary {:source :invoke/summary}} :yields {:type :text :text :summary}}`. The operation handler serializes the result to a Markdown handoff string and returns it as `:summary` in the operation result. `step-yield-field-value` for `:text` resolves to the `:summary` output value. Downstream `{:from {:step "discover" :yield :text}}` requires no change.

- [x] **F. Create `plan.md`.** Written at `munera/open/137-gh-issue-refine-deterministic-discover/plan.md`.

Unchecked items added by design review pass 2 (2026-05-10).

- [ ] **H. Clarify `:outputs` partial override intent.** The authored step specifies `{:outputs {:summary {:source :invoke/summary}}}`. The target-IR compiler replaces defaults entirely (`(or outputs (step-default-outputs type))`), so `:data` and `:result` outputs are dropped. Confirm in design.md that no downstream step references `{:output :data}` or `{:output :result}` from `discover`, OR recommend omitting `:outputs` entirely to retain all three compiler defaults (`{:data ... :summary ... :result ...}`).

- [ ] **I. Resolve `psi/github` placement: `extensions/` vs `components/`.** Design says `components/github/` but the extension registration path (`.psi/extensions.edn`, `(:register-operation api)`) is the extension pattern, not the component pattern. All domain-specific extensions live in `extensions/`. Decide: if `psi/github` is domain-specific (GitHub-only), place it in `extensions/github/`; if it is intended as reusable core infrastructure, place it in `components/github/` and remove the `.psi/extensions.edn` entry. Update design.md with the decision.

- [ ] **J. Specify root `deps.edn` wiring.** Design omits required changes to root `deps.edn`: add `psi/github {:local/root "extensions/github"}` (or `components/github`) under `:deps`, and add the `src/` path to the `:run` and `:psi` aliases. Without this the extension is not on the classpath. Add to Phase 1 steps.

- [ ] **K. Align `tests.edn` suite with placement decision.** Plan says "Wire `components/github/` into Kaocha `tests.edn`" without specifying the suite. If placed in `extensions/`, add to the `:extensions` suite; if in `components/`, add to the `:unit` suite. Update plan.md and steps.md after resolving issue I.
