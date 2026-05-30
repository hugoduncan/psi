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

- 2026-05-30: Task 190 code-shaper follow-up executed: design/plan per-reviewer `workflow/pass-status-routing` judges now pass `:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`; globally known implementation tokens now fail those review steps with deterministic `:invalid-pass-status`, while callers that omit `:allowed-statuses` preserve global token behavior. Focused workflow tests and targeted lint are green; code-shaper follow-up item is checked.
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
- For task 191, structured-output non-object schema implementation/review is complete; PR is open for merge.
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

- 2026-05-30: Task 190 follow-up completed the final unchecked streaming header item: `turn-runtime/make-provider-event-consumer` now forwards normalized `:provider-error/headers` from streaming `:error` events into `:turn/error`, and the accumulator preserves those headers for retry metadata when raw `:headers` is absent. Added focused coverage proving `Retry-After`/rate-limit metadata on provider-header-only background streaming errors drives retry scheduling. Verification: focused new test, full `psi.turn-runtime.response-mode-test` (`18 tests, 123 assertions`), and turn-runtime clj-kondo green. Committed `469f3a9b`.

- 2026-05-30: Task 190 implementation review found one new actionable gap after the async streaming metadata follow-up: `psi.ai.streaming/exception->error-event` now preserves normalized `:provider-error/headers`, but `turn-runtime/make-provider-event-consumer` forwards only `:headers` from streaming `:error` events into `:turn/error`. Added task follow-up to cover background streaming errors carrying only `:provider-error/headers` and forward those headers before retry metadata calculation. Committed `294b836a`.

- 2026-05-30: Task 190 review follow-up completed async streaming exception metadata preservation: `psi.ai.streaming/exception->error-event` now carries `:headers`, `:provider-error/headers`, `:http-status`, and `:status` from provider exception ex-data through the background streaming future path. Focused AI/turn-runtime/prompt telemetry tests and targeted clj-kondo passed.

- 2026-05-30: Task 190 implementation-review follow-up completed terminal telemetry duplication fix: canonical prompt-lifecycle terminal provider failures now prove exactly one `provider_request_finished` event is dispatched to `/ext/provider-telemetry`, and legacy `:on-agent-done` compatibility telemetry is gated when provider-boundary retry outcome metadata is already present. Focused prompt/statechart/turn-runtime/EQL verification passed (`52 tests, 285 assertions`) and targeted clj-kondo passed.

- 2026-05-30: Task 190 follow-up pass completed remaining header/introspection/success-path proof gaps: EQL provider retry attempts now expose `:psi.provider-retry/rate-limit`, first-attempt success telemetry test proves no retry outcome/schedule and active retry cleanup, changelog documents provider-boundary retry reliability and retry-history graph visibility, and app-runtime/RPC retry status tests now expect the intentional active `phase:retrying`. Focused retry/app-runtime/RPC verification passed (`65 tests, 375 assertions`) and targeted clj-kondo passed with only existing info findings; broad `bb test` was attempted before the status-test fixes and not rerun afterward. Remaining task 190 work is cleanup/quarantine of obsolete whole-agent-loop retry policy duplication.
- 2026-05-30: Task 190 implementation pass completed pending-backoff cancellation and streaming partial-output isolation. Provider-boundary retry now supports injected `:provider-retry-cancelled?`, suppresses the scheduled next attempt when cancellation is observed during retry sleep, clears active retry state, emits `provider_request_cancelled` without synthetic start/finish events, and returns structured `:retry-cancelled` outcome metadata. Added streaming proof that failed-attempt partial text is discarded and the successful retry owns final assistant content. Verification passed: focused cancellation/streaming tests (`2 tests, 18 assertions`), full `psi.turn-runtime.response-mode-test` (`15 tests, 100 assertions`), and targeted clj-kondo over turn-runtime plus provider retry resolver code.
- 2026-05-30: Task 190 implementation pass completed remaining non-cancellation provider-boundary retry outcome semantics: terminal provider/client errors and unknown errors do not retry, disabled retry classifies without scheduling as `:retry-disabled`, enabled zero-max retries returns `:retry-exhausted`, repeated retryable failures exhaust after `1 + :auto-retry-max-retries` attempts while preserving last cause, and active retry state now clears both `:retry` and `:retry-attempt` before retry attempt execution and after terminal outcome. Added injectable `:provider-retry-sleep-fn` for deterministic active-backoff tests. Verification passed: focused new retry outcome tests (`6 tests, 46 assertions`), `psi.turn-runtime.response-mode-test` (`11 tests, 70 assertions`), focused retry/EQL/prompt/statechart/session suite (`57 tests, 333 assertions`), and targeted clj-kondo over changed turn-runtime/agent-session files.
- 2026-05-30: Created Munera task 190 (`ai-request-retry-backoff-observability`) to investigate and fix AI request retry/backoff reliability and visibility for transient request/connection failures. Initial audit found retry/backoff is currently session-statechart-terminal-event based rather than wrapped around provider request execution, and the canonical prompt lifecycle likely bypasses the `:session/agent-event` guard path that schedules retries. Added focused failing proof `prompt-execution-result-retryable-error-enters-retrying-and-schedules-retry-test`: retryable connection error leaves phase `:idle` and emits no `provider_retry_scheduled`. Placement decision: retry unit is one prepared provider request owned by `turn-runtime/execute-prepared-request!`; retry tool-result-post provider requests but never rerun local tools or replay whole agent loops. Added requirement that retry history be introspectable via `psi-tool` / EQL at session/turn/request granularity, including counts, retried errors, classifications, backoff delays, and final status; existing TUI/Emacs/app-runtime active retry surfacing must be preserved or explicitly replaced.

- 2026-05-30: Task 190 docs review follow-up completed: `doc/workflow-ir.md` suggested grammar now states both `llm-judge` and `invoke-judge` are current executed runtime IR, matching the invoke-judge runtime support note; the new `steps.md` item is checked.
- 2026-05-30: Task 190 independent implementation verification pass found no unchecked task/design follow-up steps and no further concrete implementation slice. Re-verified workflow topology, prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation verification pass re-read task artifacts/workflow definitions/tests, found no unchecked task/design follow-up steps and no concrete implementation work remaining, and reran focused workflow tests plus targeted lint green. Implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation verification pass found no unchecked task/design follow-up steps and no concrete implementation work remaining. Re-verified plan/design prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 independent implementation pass found no remaining concrete implementation work. Re-verified plan/design prompt artifact targets, focused workflow routing tests, and targeted lint green; implementation remains complete pending review/closure.
- 2026-05-30: Task 190 inconsistency follow-up completed: compaction boundary mid-system preservation now merges any retained post-cut boundary `:mid-system` entries into the coalesced summary-boundary `:mid-system`, preventing rebuilt `summary user → system → system` sequences; design-step checked.

- 2026-05-30: Task 190 verification ambiguity review found no new actionable ambiguities beyond existing captured/latest follow-ups; appended implementation.md note and added no duplicate design-steps.

- 2026-05-30: Task 190 latest ambiguity review pass found three new actionable design ambiguities: missing extension mutation bridge for `inject-mid-system-message!`, explicit `:normal` speed config presence semantics, and current-session state after scoped `/speed normal`. Added unchecked `design-steps.md` follow-ups.

- 2026-05-30: Task 190 latest ambiguity review pass found two new actionable ambiguities: explicit nil `:effort-override` config resolution through merged shared-config, and mid-system capability semantics for custom/runtime OpenAI chat-completions models. Added unchecked follow-ups to `design-steps.md`.
- 2026-05-30: Task 190 latest inconsistency follow-up pass found no unchecked inconsistency design-steps; implementation.md records that no design changes were required.

- 2026-05-30: Task 190 ambiguity follow-up completed: design now specifies startup application for persisted speed/effort, runtime-resolved model lookup for mid-system capability gating, and `:mid-system` source provenance inference/storage; all current ambiguity design-steps are checked.

- 2026-05-30: Task 190 third ambiguity follow-up completed and committed `2fbc7d20`: design now requires Anthropic request schema to admit inline `role: system` messages and prepared-turn assembly to replace `... user, system` tails as `... current-user, system`; all current ambiguity design-steps are checked.

- 2026-05-30: Task 190 third ambiguity review pass found two new actionable ambiguities: Anthropic local request schema must admit inline system messages in `messages`, and prepared-turn current-user replacement must handle a pending `:mid-system` entry after the user while preserving `user → system` order. Added unchecked follow-ups to `design-steps.md` and committed `12cf9fb2`.

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

- 2026-05-30: Task 190 code-shaper review found one actionable robustness follow-up: design/plan per-reviewer PASS_STATUS routing currently accepts implementation-only tokens via the global routing union; added unchecked `steps.md` item and review note.
- 2026-05-30: Task 190 generic review-step code-shaper follow-up completed: `.psi/workflows/review-step.edn` now constrains `workflow/pass-status-routing` to review statuses only (`ACTIONABLE_FEEDBACK` / `REVIEW_COMPLETE`); focused runtime proof shows `IMPLEMENTATION_COMPLETE` fails before follow-up for review-step, while implement-task still accepts implementation statuses. Focused workflow tests and targeted lint are green; follow-up item checked.
- 2026-05-30: Task 190 code-shaper re-review after review-step allowed-status follow-up found no new actionable code-shaping issues. Focused workflow tests and targeted lint green; review note committed as `fb32b792`.

- 2026-05-30: Task 190 design ambiguity review pass found three new actionable ambiguities: `/effort` behaviour for OpenAI Codex/responses, invalid-placement semantics for `inject-mid-system-message!`, and compaction lifetime for pre-cut `:mid-system` instructions. Added follow-ups to `design-steps.md`; note that `design.md` already had uncommitted refinements before this pass.
- 2026-05-30: Task 190 ambiguity follow-up complete: design now applies `/effort` to OpenAI Codex/responses with the same override mapping, rejects invalid `inject-mid-system-message!` placements before journal mutation, and preserves pre-cut mid-system instructions across compaction by coalescing them after the summary user turn.
- 2026-05-30: Task 190 second ambiguity review pass found three new actionable ambiguities: Anthropic request schema must admit or explicitly route `output_config.effort = "highest"`, mid-system provider-style maps need a schema-valid AI conversation representation/normalization path, and scoped persisted clears for `/speed normal` / `/effort none` need explicit lower-precedence config semantics. Added unchecked follow-ups to `design-steps.md`.

- 2026-05-30: Task 190 second inconsistency follow-up complete: design now specifies `:psi.agent-session/speed-mode` resolver coerces nil session state to `:normal` while request shaping still treats nil/`:normal` as provider default, and `:supports-mid-conversation-system-messages` is optional model metadata with absent-as-false semantics. Both second-pass design-steps are checked.
- 2026-05-30: Task 190 third ambiguity review pass found three new actionable ambiguities: persisted speed/effort startup wiring, mid-system capability lookup source under runtime model overrides, and mid-system journal `:source` provenance contract. Added unchecked follow-ups to `design-steps.md`.

- 2026-05-30: Task 190 latest inconsistency follow-up completed and committed `d8b342ac`: design now requires plain adaptive Anthropic `thinking-level :xhigh` (nil `/effort` override) to send `output_config.effort = "highest"`, preserving the task goal that `:xhigh` is distinct from `:high`; `design-steps.md` is checked for the latest inconsistency item.

- 2026-05-30: Task 190 ambiguity follow-up no-op pass: `design-steps.md` had no unchecked ambiguity follow-up items, so no design changes were needed; implementation.md records the pass.

- 2026-05-30: Task 190 source-provenance inconsistency review found one new actionable design inconsistency: `inject-mid-system-message!` source inference requires extension provenance, but the specified Pathom mutation params omit `:ext-path`. Added unchecked follow-up to `design-steps.md` and implementation review note.
