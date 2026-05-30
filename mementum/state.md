# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state

- 2026-05-30: Task 190 docs review follow-ups completed and committed (`accfd3d8`): CHANGELOG [Unreleased] now documents user-visible conditional per-reviewer follow-up behavior and plan-review `steps.md` targeting; `doc/workflow-ir.md` now reflects executed invoke-judge runtime support. All docs follow-up items are checked.
- 2026-05-30: Task 190 docs review applied `review-task-docs`: found actionable documentation follow-ups for CHANGELOG user-visible conditional design/plan review behavior plus `doc/workflow-ir.md` invoke-judge runtime support note; added unchecked `steps.md` items and recorded review note.
- 2026-05-30: Task 190 third test-shaper follow-up executed: workflow definition tests now assert the full actual `clarity-status` `:on` map for both design/plan workflows (`REPEAT` → `ambiguity-review` with `:max-iterations 6`, `DONE` → `final-summary`); focused workflow tests and targeted lint are green, and the new `steps.md` item is checked.
- 2026-05-30: Task 190 second test-shaper follow-up executed: workflow definition tests now assert each design/plan follow-up step uses the full `workflow/constant-routing` judge shape with `:args {:route "DONE"}`; focused workflow tests and targeted lint are green, and the new `steps.md` item is checked.
- 2026-05-30: Task 190 test-shaper follow-up executed: workflow definition tests now assert each design/plan per-reviewer `workflow/pass-status-routing` judge reads that same step's own `:final-llm-reply`; focused workflow tests and targeted lint are green, and the new `steps.md` item is checked.
- 2026-05-30: Task 190 plan inconsistency review pass re-read plan/steps/implementation/design artifacts plus workflow docs/definitions/prompts/tests and found no new actionable plan inconsistencies. Recorded review note only; no new `design-steps.md` follow-up items were needed.
- 2026-05-30: Task 190 design ambiguity follow-up execution completed after the latest ambiguity-review pass: `design-steps.md` now exists, but its only item predates the preceding ambiguity review and is already checked from the earlier inconsistency follow-up; no newly added unchecked ambiguity items were executed. Recorded in `implementation.md`.
- 2026-05-30: Task 190 implementation pass completed: design/plan review workflows now conditionally run per-reviewer follow-ups via deterministic `workflow/pass-status-routing`, plan-review prompts target `steps.md`, focused routing/definition tests and targeted lint are green. Ready for implementation review/closure consideration.
- 2026-05-30: Task 190 design inconsistency follow-up execution completed as a blocked/no-op after the latest inconsistency-review pass: `design-steps.md` is absent, so there were no newly added unchecked design inconsistency items to execute or mark done. Recorded in `implementation.md`; commit `e08009b3`.
- Registry unification arc through task 177 is complete in implementation/review terms:
  - 164: registry semantics audit is the migration-rules source of truth and now includes post-migration guidance from 169–173
  - 165: root-registry target architecture captured
  - 166: standalone `root-registry` component built
  - 167: command-registry migrated onto root-registry storage
  - 168: tool-registry migrated onto root-registry storage; higher extension-detail projections read canonical tool-registry data
  - 169: workflow-registry migrated onto root-registry storage while preserving canonical compatibility path `[:workflows :definitions]`; higher semantic seams route through workflow-registry
  - 170: root-registry semantic alignment added distinct duplicate-rejecting `insert` versus replace-capable `register`, clarifying future adopter contracts
  - 171: deterministic-operation-registry migrated to shared root-registry storage; canonical operations live in root-registry, while invoke-miss throwing, duplicate-throw translation, and extension projection synchronization remain adapter-owned
  - 172: deterministic-operation registration-order semantics removed; public operation listing now preserves unordered membership/count coherence rather than insertion order
  - 173: skill registration-order semantics removed; registry/projection/model-visible skill-list surfaces use canonical exact skill-name ordering while duplicate-ignore and `:added?` / `:changed?` remain preserved
  - 174: skill-registry migrated to adapter-backed root-registry storage; canonical skill definitions now live in `root-registry` while sessions own membership through `:skill-ids`
  - 176: prompt-registry simplified toward root-registry semantics — canonical identity is now string-coerced `id` alone, cross-owner same-id coexistence is disallowed via explicit ownership conflict, and higher prompt-contribution projections were aligned to shared canonical ordering
  - 177: prompt-registry migrated onto shared `root-registry` storage via `prompt-registry.root-storage`; sessions now own canonical prompt membership through `:prompt-contribution-ids`, while `:prompt-contributions` vectors remain derived compatibility projection only across new/resume/fork/child flows and higher read/introspection seams
- Bootstrap simplification arc (159–163) complete:
  - 159: in-process bootstrap simplification
  - 160: removed mutation-mediated bootstrap resource loading
  - 161: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
  - 162: `bootstrap-runtime-session!` collapsed to single `(ctx ai-model opts)` arity
  - 163: `start-tui-runtime!` refactored — dead `ai-ctx` removed, nullable exec mode extracted
- Structured-output arc status:
  - task 169 is complete and closed: model/provider structured-output capability surface implemented
  - task 171 is complete and closed: Anthropic JSON Schema native output implemented and live-verified
  - task 170 remains the active adjacent workflow adoption slice; latest follow-ups clarified unsupported/fallback-forbidden behavior, top-level `:structured-output` turn-result seams, and success-path envelope propagation
- Tasks 188, 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 also complete and closed

## Test health

- `bb test` is green after closing task 177.
- `bb test` was green after closing task 173.
- Focused registry/projection tests passed during tasks 169–173.
- Task 176 recorded focused prompt-registry / projection verification passing:
  - `clojure -M:test --focus psi.prompt-registry.contributions-test --focus psi.agent-session.query-graph-tools-test --focus psi.agent-session.model-dispatch-test`
  - `clj-kondo --lint components/prompt-registry/src components/prompt-registry/test components/agent-session/src components/agent-session/test components/extension-test-helpers/src`
- Task 176 review loops (implementation review, test review, test-shaper, code-shaper) recorded no new actionable feedback.
- Task 177 recorded focused prompt/session/workflow/nullable-helper verification, targeted lint, and a final full `bb test` pass before close.
- Tasks 176 and 177 review loops (implementation review, test review, test-shaper, code-shaper) recorded no new actionable feedback.
- Focused structured-output/model tests passed during tasks 169 and 171.
- Task 170 follow-up verification recorded focused workflow tests green for structured-output envelope propagation and failure-surface behavior.
- Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback).

## Suggested next step
- Registry unification arc: use task `164-registry-semantics-unification-audit` plus completed outcomes through `177` to choose the next cleanup target.
- Likely next registry cleanup candidates:
  - close or move any remaining Munera tasks whose implementation/review state is already complete
  - `skill-registry` shared-substrate/helper cleanup, now that insertion order is no longer semantic
  - prompt-registry normalization/shared collection-helper audit
  - remaining root-registry adopter polish if `164` identifies unresolved seams
  - decide whether out-of-scope registries (`model-registry`, memory provider registry, extension handler registry) need a separate audit rather than direct adoption
- Structured-output arc: task 170 remains the active adjacent workflow adoption slice; its test-shaper follow-up is complete and the remaining work is within that task.
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items, `186-built-in-skill-for-developing-extensions`

## Latest session notes

- 2026-05-30: Task 190 independent implementation verification pass found no unchecked task/design follow-up steps and no further concrete implementation slice. Re-verified workflow topology, prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation verification pass re-read task artifacts/workflow definitions/tests, found no unchecked task/design follow-up steps and no concrete implementation work remaining, and reran focused workflow tests plus targeted lint green. Implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation verification pass found no unchecked task/design follow-up steps and no concrete implementation work remaining. Re-verified plan/design prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation pass found no remaining concrete implementation work. Re-verified plan/design prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-29: Task 188 is complete and ready in PR #132: built-in packaged `workflow` skill added at `bases/main/resources/psi/skills/workflow/SKILL.md`, project-local `.psi/skills/workflow/SKILL.md` removed in the same slice to avoid precedence shadowing, and focused proof added across built-in skill discovery/materialization, `:psi.agent-session/skills`, `:psi.skill/by-source`, `/skills`, and `/help` (`96 tests, 497 assertions`).
- 2026-05-29: Task 188 requested independent implementation review re-read task artifacts/code/tests/docs/changelog, reran focused model/provider/turn-runtime tests and targeted AI lint green, and found no new actionable implementation feedback; remaining unchecked Codex non-streaming `:execute` test item remains conditional future work.
- 2026-05-29: Fixed the live `/delegate review-task-implementation 189-deterministic-review-step-routing` failure on this branch. Root cause was built-in workflow bootstrap not registering the new deterministic review-step operations into the runtime deterministic-operation registry, so live delegated `review-step` runs failed with missing invoke operations even though focused unit tests passed by locally registering test ops. Added built-in `:register-operation` support in workflow bootstrap, registered `workflow/pass-status-routing` and `workflow/constant-routing` during built-in workflow init, and added a focused bootstrap proof that those operations are present after init. Verification: `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test --focus psi.agent-session.workflow-review-step-routing-test --focus psi.agent-session.workflow-invoke-runtime-test --focus psi.agent-session.workflow-delegate-review-step-live-test`; targeted `clj-kondo` green.

- 2026-05-29: Task 189 inconsistency follow-up complete: deterministic `follow-up` loopback is now in scope through built-in `workflow/constant-routing`, returning the literal configured route (`"REPEAT"` for `review-step`) and using the same invoke-judge error surface for invalid route input; all current design-steps are checked.

- 2026-05-29: Task 189 ambiguity follow-up complete: parser cardinality now rejects duplicate `PASS_STATUS:` lines (including identical duplicates) and known+malformed extra status lines as deterministic failures, and invoke-judge `workflow/pass-status-routing` errors now map to recorded judge output plus terminal workflow failure without executing follow-up.

- 2026-05-29: Task 189 ambiguity follow-up complete: design now keeps `review-step` structured/text mismatch validation in this slice by requiring same-step invoke-judge source resolution for `:structured-status {:from {:step "review" :output :review-result} :path [:status]}`, wiring it into `workflow/pass-status-routing`, and adding mismatch/self-ref tests.

- 2026-05-29: Task 189 inconsistency follow-up complete: native structured-output/prose `PASS_STATUS` conflict resolved by removing same-response model-produced `:review-result` from this slice; review actor remains prose/text, `PASS_STATUS` is authoritative, and `workflow/pass-status-routing` provides the structured deterministic control result.

- 2026-05-29: Task 189 inconsistency follow-up complete: review-step max-iteration protection now belongs on deterministic `follow-up` → `review` loopback (`:max-iterations 6`) because runtime iteration counts are target-step keyed; `review` → `follow-up` routes actionable feedback without the loop guard.

- 2026-05-29: Task 189 ambiguity follow-up complete: design now requires `review-step` `:review-result` to be provider-native only with `:fallback :none` / `:require-provider-native? true`, preserving the prose final reply plus legacy `PASS_STATUS:` token and failing unsupported native structured output before routing/follow-up.

- 2026-05-29: Task 189 ambiguity review found one new actionable ambiguity: the `review` actor must keep a prose final reply ending with `PASS_STATUS`, but adding `:review-result` without an explicit strategy can use prompted-JSON fallback whose JSON-only final text conflicts with that token/prose routing surface. Added an unchecked `design-steps.md` follow-up and committed the review.

- 2026-05-29: Task 189 inconsistency follow-up complete: aligned `review-step` structured status routing to source the validated `:review-result` value via `:path [:status]`, documented that raw structured-output envelope internals are not part of the workflow ref contract, and marked the design-step done.
- 2026-05-30: Task 190 design inconsistency review pass re-read `design.md`, task-design/workflow guidance, current design/plan review workflow definitions and prompts, final-summary/clarity prompts, workflow grammar docs, workflow-loader tests, and PASS_STATUS routing code. No new actionable design inconsistencies found. Commit `42b07b9b` recorded the review note only; `design-steps.md` remains absent because no follow-up items were needed.
- 2026-05-30: Task 190 design inconsistency review pass re-read `design.md`, task-design/workflow guidance, current design/plan review workflow definitions/prompts, workflow grammar docs, workflow-loader definition tests, review-step routing tests, and PASS_STATUS routing implementation. No new actionable inconsistencies found; committed review note `ec86b825`. `design-steps.md` remains absent because no follow-up items were needed.
- 2026-05-30: Task 190 plan ambiguity review pass re-read plan.md, steps.md, implementation.md, design.md, workflow-authoring docs, current design/plan review workflow definitions/prompts, workflow definition tests, review-step routing tests, and PASS_STATUS routing implementation. No new actionable plan ambiguities found; committed review note only. `design-steps.md` remains absent because no follow-up items were needed.
- 2026-05-30: Task 190 design ambiguity follow-up execution completed as blocked/no-op after the latest ambiguity-review pass: `design-steps.md` is absent, so there were no newly added unchecked ambiguity follow-up items to execute or mark done. Recorded in `implementation.md`; task design/plan/steps unchanged.
- 2026-05-30: Task 190 design inconsistency follow-up execution completed after the latest inconsistency-review pass: `design-steps.md` contains no newly added unchecked items; the only item predates the pass and is already checked, so no follow-up was re-executed. Recorded in `implementation.md`.
- 2026-05-30: Task 190 independent implementation pass found no unchecked `steps.md` or `design-steps.md` items and no remaining concrete implementation slice. Re-verified prompt artifact targets plus focused workflow tests and targeted lint green; implementation appears complete pending review/closure.
- 2026-05-30: Task 190 design ambiguity follow-up execution completed after the latest ambiguity-review pass: `design-steps.md` contains no newly added unchecked items; the only item predates the pass and is already checked, so no follow-up was re-executed. Recorded in `implementation.md`.
- 2026-05-30: Task 190 independent completion check found no remaining implementation steps or design follow-ups; focused workflow tests and targeted lint are green. Implementation is complete pending review/closure.
- 2026-05-30: Task 190 independent implementation pass re-confirmed implementation complete: no unchecked `steps.md` or `design-steps.md` items remain, prompt artifact targets and conditional routing still match design, focused workflow tests and targeted lint green. Remaining work is review/closure only.
- 2026-05-30: Task 190 independent implementation verification pass found no unchecked task/design follow-up steps and no further concrete implementation slice. Re-verified prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 test-shaper review found one actionable test-shaping gap: workflow definition tests should assert each per-reviewer PASS_STATUS invoke judge sources its own step's `:final-llm-reply`; added unchecked follow-up to `steps.md` and committed `d7ebfe9c`.
