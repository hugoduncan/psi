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

- 2026-05-29: Task 188 is complete and ready in PR #132: built-in packaged `workflow` skill added at `bases/main/resources/psi/skills/workflow/SKILL.md`, project-local `.psi/skills/workflow/SKILL.md` removed in the same slice to avoid precedence shadowing, and focused proof added across built-in skill discovery/materialization, `:psi.agent-session/skills`, `:psi.skill/by-source`, `/skills`, and `/help` (`96 tests, 497 assertions`).
- 2026-05-29: Task 188 follow-up execution after no-action review found no newly added actionable unchecked steps; the remaining unchecked non-streaming `:execute` test item is conditional on future verified Codex non-streaming support and remains blocked by current `stream: false` 400 evidence.

- 2026-05-29: Task 188 post-closeout implementation review complete: re-read Codex structured-output task artifacts/code/tests/docs, reran focused model/provider/turn-runtime tests and targeted AI lint green, and found no new actionable feedback.

- 2026-05-29: Task 188 closeout follow-up complete: removed the untracked Codex live-probe scratch file, documented the finalized ChatGPT/Codex native streaming structured-output mechanism in custom-provider docs and changelog, checked closeout steps, and re-verified focused model/provider/turn-runtime tests plus AI code/test lint green.

- 2026-05-24: Task 170 task-test-review follow-up complete: added end-to-end session-step and LLM-judge tests for `:fallback :none` unsupported structured output with `:require-provider-native?` omitted, proving both paths use the stable `:unsupported-structured-output` blocked/fail surfaces and pass fallback-forbidden opts; focused tests and clj-kondo green.

- 2026-05-24: Task 170 implementation pass completed remaining persisted/replay metadata behavior coverage. Provider-native structured-output envelopes are now proven preserved exactly in accepted result state, latest attempt result envelope, and workflow history; all implementation steps checked; focused workflow tests green (`31 tests, 148 assertions`).

- 2026-05-24: Task 170 implementation pass added direct structured-output workflow coverage for unsupported fallback-forbidden session-step/judge failure surfaces, ranked fallback opts preservation, and downstream provider-native source refs; fixed ranked fallback terminal error propagation to retain top-level `:structured-output`; focused tests green (`24 tests, 123 assertions`).

- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities repeat 7; found no new actionable ambiguity feedback after rechecking task artifacts, current turn-execution contract/aliases, session-step and LLM-judge structured-output paths, workflow structured-output runtime/IR/docs, and task-169/171 AI structured-output surfaces. Existing unchecked implementation steps remain intentional code/docs work. Commit 618b0a78.
- 2026-05-24: Executed task 170 inconsistency follow-up repeat 6 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the no-op pass.
- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities repeat 6; found no new actionable ambiguity feedback after rechecking task artifacts, current turn-execution contract/aliases, workflow structured-output grammar/IR/docs, and task-169/171 AI structured-output surfaces. Existing unchecked implementation steps remain intentional code/docs work.
- 2026-05-24: Executed task 170 inconsistency follow-up repeat 5 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the no-op pass.
- 2026-05-24: Executed task 170 ambiguity follow-up repeat 4: defined the turn-execution request input seam as optional fourth opts arities on `execute-actor-turn!` / `execute-judge-turn!`, forwarding provider-neutral `{:structured-output ...}` unchanged to `execute-session-turn!`; ranked fallback preserves opts, structured judges avoid prose retry loops for structured contract failures. Design-step checked; implementation steps untouched.
- 2026-05-24: Executed task 170 inconsistency follow-up repeat 3 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the no-op pass.
- 2026-05-24: Reviewed task 170 design/plan/steps for inconsistencies repeat 3; found no new actionable inconsistency feedback after rechecking task artifacts, workflow structured-output helpers, step/judge execution paths, turn-execution contract, workflow docs, and task-169/171 AI structured-output surfaces. Existing unchecked implementation steps already cover intentional code/docs work.
- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities repeat 3; found no new actionable ambiguity feedback after rechecking task artifacts, workflow IR/grammar/docs, turn-execution contract, step/judge structured-output code, and task-169/171 AI structured-output surfaces. Existing unchecked implementation steps cover intentional code/docs work.
- 2026-05-24: Executed task 170 ambiguity follow-up repeat 3 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the no-op pass.

- 2026-05-24: Reviewed task 170 design/plan/steps for inconsistencies repeat 2; found no new actionable inconsistency feedback after rechecking task artifacts, prior notes, task-168 docs/current IR code, turn-execution contract code, workflow step/judge code, and task-169/171 AI structured-output surfaces. Existing unchecked implementation steps already cover code/docs gaps.
- 2026-05-24: Executed task 170 ambiguity follow-up repeat 2: turn-execution structured-output metadata seam is now explicit. Workflow runtime reads top-level `:structured-output` from bounded actor/judge turn results, copied only from canonical `:execution-result/structured-output`; turn runtime owns non-streaming provider-result and streaming event accumulation. Design-step checked, implementation steps untouched.
- 2026-05-24: Executed task 170 inconsistency follow-up repeat: `:fallback :none` now shares the same fallback-forbidden `:unsupported-structured-output` failure surface as `:require-provider-native? true` when native support is unavailable; design-step checked, implementation steps untouched.
- 2026-05-24: Reviewed task 170 design/plan/steps for inconsistencies repeat; found one actionable inconsistency: `:fallback :none` encodes fallback-forbidden behavior and tests call for unsupported/no-fallback coverage, but failure-surface wording only names `:require-provider-native? true`. Added design-step to choose same `:unsupported-structured-output` surface or distinct no-fallback reason.
- 2026-05-24: Executed task 170 ambiguity follow-up repeat: structured-output failure surfaces are now explicit. Session steps use blocked pending actor results for `:missing-json-schema`, `:unsupported-structured-output`, and `:invalid-structured-output`; LLM judges use `:routing-result {:action :fail}` with matching reasons and no prose no-match retries. Design-step checked; implementation steps untouched.
- 2026-05-24: Executed task 170 inconsistency follow-ups: required-native unsupported detection now belongs to turn execution / AI strategy selection after resolved model/provider capability, while workflow request building only encodes fallback-forbidden; envelope naming now uses `:payload` for parsed/native validation input and `:raw-payload` only for raw provider diagnostics. Design-steps fully checked.
- 2026-05-24: Executed task 170 ambiguity follow-ups: added plan/steps, fixed canonical workflow structured-output policy keys (`:json-schema`, `:strategy-preference`, `:fallback`, `:require-provider-native?`), chose explicit JSON Schema boundary with no Malli conversion, specified AI metadata-to-envelope mapping, and checked all design-steps.
- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities; found actionable gaps: missing plan/steps artifacts, exact workflow structured-output policy/request keys, task-168 Malli-to-task-169 JSON Schema source boundary, and AI-result-to-workflow-envelope metadata mapping. Added design-steps and committed 1060d281.
- 2026-05-24: Executed task 171 code-shaper follow-up: fixed 400 compatibility fallback to drop Anthropic JSON Schema `:output_format` when the structured-output beta is stripped; added focused retry coverage; focused Anthropic/model/user tests green (`37 tests, 238 assertions`).
- 2026-05-24: Executed task 171 test-shaper follow-up: non-streaming Anthropic JSON Schema native `:execute` test now captures outbound request body, asserts `:stream` is omitted, and still proves top-level `:structured-output`; focused Anthropic/model/user tests green (`36 tests, 232 assertions`).
- 2026-05-24: test-shaper review for task 171 found one actionable test-quality gap and added an unchecked `steps.md` follow-up; no code/tests executed in this review pass.
- 2026-05-24: Re-reviewed task 171 implementation with task-implementation-review; found no new actionable implementation feedback, reran focused Anthropic/model/user tests green (`35 tests, 221 assertions`), appended implementation.md no-action note, and added no steps.md items.
- 2026-05-24: task-implementation-review for task 171 read skill/task/code/tests/docs, reran focused Anthropic/model/user tests green, appended implementation.md note, added one unchecked steps.md follow-up, and committed.
- 2026-05-23: Task 169 test-review follow-up complete: no code change needed; current Anthropic provider compile/load path is healthy, focused structured-output/model verification is refreshed, and task step is checked.
- 2026-05-23: Task 169 implementation slice 3 completed remaining streaming result surfaces: OpenAI emits `:structured-output-result` from accumulated assistant JSON; Anthropic suppresses synthetic forced-tool ordinary toolcall events and emits `:structured-output-result` from tool input. Focused and full unit verification green; commit 6e673424.
- 2026-05-23: Task 169 implementation slice 2 added structured-output request strategy helpers, OpenAI Chat Completions native response_format, Codex prompted-JSON fallback shaping, Anthropic synthetic forced-tool request composition, streaming strategy events, OpenAI non-streaming structured-output metadata/payload handoff, docs, and focused provider tests.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 13 after no-action review; design-steps already fully checked, so implementation.md records the no-op pass and no implementation steps were executed. Commit 0e83b280.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 14 after no-action review; design-steps already fully checked, so implementation.md records the no-op pass and no implementation steps were executed.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 13 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Task 169 ambiguity review repeat 13 found no new actionable ambiguity feedback; design remains clear on explicit `:json-schema`, capability normalization, auth/transport-resolved capability, provider request/result surfaces, and validation boundary.
- 2026-05-23: Task 169 inconsistency follow-up repeat 11 executed as no-op: preloaded review had no actionable feedback and all design-steps were already checked; implementation.md records the pass and commit 31fc49d4 captures it.
- 2026-05-23: Task 169 ambiguity review repeat 8 found one actionable ambiguity: non-streaming structured-output metadata/payload must name the exact `execute-response` result root so implementation/tests do not diverge.
- 2026-05-23: Task 169 ambiguity review repeat 7 found one actionable ambiguity: structured-output capability source must be defined after runtime auth/transport resolution so ChatGPT OAuth/Codex `gpt-5.5` cannot accidentally inherit platform-native OpenAI Chat Completions capability.
- 2026-05-23: Task 169 inconsistency review repeat 6 found no new actionable inconsistency feedback; omitted structured-output capability data remains load-valid but effectively unsupported, prompted JSON fallback is explicit opt-in, streaming metadata uses first-class events, and workflow/runtime remains final validation authority.
- 2026-05-23: Task 169 inconsistency review repeat 5 found no new actionable inconsistency feedback; design/plan/steps/docs remain aligned on first-class streaming structured-output events, OpenAI Chat Completions native-only support, Codex fallback-only, Anthropic synthetic forced-tool extraction, and workflow-owned final validation.
- 2026-05-23: Task 169 no-action inconsistency review repeat 2: design/plan/steps remain aligned around OpenAI Chat Completions native-only support, Codex fallback-only, Anthropic synthetic forced-tool extraction, explicit metadata/payload handoff, and workflow-owned final validation.
- 2026-05-23: Test-shaper review for task 169 found fallback streaming structured-output coverage gap: native streaming events are tested, but prompted-JSON fallback streaming lacks first-class strategy/result assertions; added follow-up step.
- 2026-05-24: Reviewed task 171 design/plan/steps for inconsistencies; found no new actionable inconsistency feedback beyond existing unchecked design-steps covering absent plan/steps plus Anthropic request/response/model/mechanism/live-smoke mismatches.
- 2026-05-24: Executed task 171 ambiguity follow-up repeat 2: design/plan/steps now require adding an Anthropic Messages provider `:execute` non-streaming path returning top-level `:structured-output`; design-steps fully checked.

- 2026-05-24: Reviewed task 171 design/plan/steps for ambiguities repeat 2; found one actionable gap: Anthropic JSON Schema native non-streaming behavior is specified, but the current Anthropic provider has no `:execute` path, so task scope must clarify add-execute vs helper-only extraction. Commit 3f2fe74a.
- 2026-05-24: Reviewed task 170 design/plan/steps for inconsistencies. Found two actionable inconsistencies: required-native unsupported detection was assigned to a workflow helper that lacks resolved model capability context, and workflow envelope `:raw-payload` wording conflicted with task-169/171 AI metadata where parsed/native data is `:payload` and raw provider text is `:raw-payload`. Added design follow-ups and committed f1b5382a.
- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities repeat 4; found one actionable ambiguity: artifacts specify provider-neutral `:structured-output` request contents and result seam, but not the exact input API/arity/options seam for passing those options through `execute-actor-turn!` / `execute-judge-turn!`, including ranked fallback and judge retry behavior. Added design-step and committed 2c4e83f8.
- 2026-05-24: Reviewed task 170 design/plan/steps for inconsistencies repeat 4; found no new actionable inconsistency feedback after rechecking task artifacts, current turn-execution/step/judge code paths, workflow IR/docs, and task-169/171 structured-output surfaces. Existing unchecked implementation steps already cover intended code/docs work.
- 2026-05-24: Reviewed task 170 design/plan/steps for ambiguities repeat 5; found no new actionable ambiguity feedback after rechecking task artifacts, current turn-execution aliases/opts seam, ranked fallback, LLM-judge retry path, structured-output envelope/IR code, workflow docs, and task-169/171 AI surfaces. Commit 01c2c313.
- 2026-05-24: Executed task 171 ambiguity follow-up repeat 2: design/plan/steps now require adding an Anthropic Messages provider `:execute` non-streaming path returning top-level `:structured-output`; design-steps fully checked.
- 2026-05-24: Reviewed task 171 design/plan/steps for ambiguities repeat 2; found one actionable gap: Anthropic JSON Schema native non-streaming behavior is specified, but the current Anthropic provider has no `:execute` path, so task scope must clarify add-execute vs helper-only extraction. Commit 3f2fe74a.
- 2026-05-23: Task 168 judge-local structured outputs are now explicitly transition-evaluation-only; downstream `{:step ... :output ...}` refs address session structured parent outputs, not hidden judge output promotion.
- 2026-05-23: Executed task 168 ambiguity follow-ups repeat: grammar docs now allow session and LLM-judge structured outputs; design clarifies JSON wire format, schema-guided enum/key coercion, and invalid parse/coercion recording.
- 2026-05-23: Reviewed task 168 design for ambiguities; added design follow-ups for absent plan/steps, unresolved concrete structured-output IR/authored syntax, and unspecified first standard schema/example.
- 2026-05-23: Executed task 167 code-shaper follow-up and committed 2660283c: canonical argument-map comparison now prevents unnecessary Emacs raw fallback when complete parsed/raw args are equivalent despite key-shape differences.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for ambiguities; found no new actionable ambiguity feedback; appended implementation note and committed 9e7d4153.
- 2026-05-23: Executed task 167 ambiguity follow-up: accepted current global tool-detail toggle semantics; updated design acceptance, layout contract, plan decisions, and implementation checklist so tests assert global collapsed/expanded/toggled-closed behavior instead of row-local state.
- 2026-05-23: Reviewed task 167 design/plan/steps for inconsistencies; added follow-up to align TUI close-toggle verification with acceptance criterion 7.
- 2026-05-23: Reviewed task 167 design/plan/steps for ambiguities after plan/steps creation; found one remaining actionable ambiguity around parsed-vs-raw argument completeness detection for expanded `Call` rendering; added design-step and committed c2390ebf.
- 2026-05-22: Executed task 167 ambiguity follow-ups: design now specifies full-call data precedence with raw fallback, shared Emacs/TUI expanded layout, and extension renderer constraints; all ambiguity design-steps marked done.
- 2026-05-22: Reviewed task 167 design artifacts for ambiguities; added design follow-ups for full-call data precedence, expanded-detail layout contract, and extension renderer interaction.
- 2026-05-22: Task 166 code-shaper follow-up complete: assistant optimization tests now share `psi-test-with-assistant-streaming-instrumentation`, removing repeated redraw/prefix/property `cl-letf` setup while keeping separate incremental, cumulative snapshot, and divergent merge assertions.
- 2026-05-22: Task 166 test-shaper follow-up complete: assistant append optimization proof is now three behavior-local tests (incremental delta, cumulative snapshot suffix, divergent merge preservation), each asserting suffix-only properties and no redraw/prefix recreation.
- 2026-05-22: Task 166 mandatory assistant/thinking streaming optimization implemented: append-only assistant/thinking updates now use suffix insertion, avoid post-creation full redraw/prefix overlay recreation, assistant stream properties apply only to suffix ranges; focused Emacs tests and `bb emacs:test` green.
- 2026-05-22: Executed task 166 test-review follow-up after no-action review; no newly added steps existed, so implementation.md records the no-op pass and commit 6f0a3822 captures it.
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no new design-steps were present, no design/plan/steps changes were required, and implementation.md records the pass.
- 2026-05-22: Reviewed task 166 design/plan/steps for inconsistencies; found no new actionable inconsistency feedback; appended implementation note only.
- 2026-05-22: Executed task 166 ambiguity follow-up after no-action ambiguity review; no newly added design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Re-reviewed task 166 design/plan/steps for ambiguities; found no new actionable ambiguity feedback; appended implementation note and left design-steps unchanged.
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no newly added design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Executed task 166 ambiguity follow-up after no-action ambiguity review; no newly added design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Executed task 166 inconsistency follow-up after thinking-proof clarification; no newly added design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Executed task 166 ambiguity follow-up: clarified thinking append-only proof uses inserted suffix mutation range and no post-creation full redraw/prefix overlay recreation, not nonexistent assistant-style property ranges.
- 2026-05-22: Executed task 166 inconsistency follow-up: corrected divergent assistant snapshot acceptance to preserve existing merge/append semantics after effective-next-text calculation; design-step completed.
- 2026-05-22: Executed task 166 ambiguity follow-up: pre-optimization instrumentation proof must use named helper wrappers (full redraw, stream property range, prefix overlay), with primitive advice only as temporary diagnostics; task artifacts updated and design-step completed.
- 2026-05-22: Reviewed Munera task 166 for ambiguities after implementation planning; added one actionable design follow-up to define the pre-optimization instrumentation seam and committed it as c3f8ebdb.
- 2026-05-21: OpenAI OAuth-backed `gpt-5.5` now works through Codex transport.
- 2026-05-20: oriented on bootstrap-simplification branch; 159–163 arc confirmed complete; test errors confirmed fixed
- 2026-05-22: Reviewed task 166 design/plan/steps for inconsistency after implementation planning; found assistant divergent snapshot acceptance wording conflicts with explicit payload contract/plan/steps, added follow-up, committed 59127ad2.
- 2026-05-22: Re-reviewed task 166 design/plan/steps for ambiguities after instrumentation-seam follow-up; no new actionable ambiguity feedback; committed review note c2f3f71f.
- 2026-05-22: Executed task 166 inconsistency follow-up: aligned implementation-shaping notes with assistant divergent merge preservation after effective-next-text calculation; design-step completed.
- 2026-05-22: Reviewed task 166 design/plan/steps for inconsistencies; added follow-up for stale implementation-shaping wording that still said divergent payloads redraw, conflicting with assistant divergent merge preservation contract; committed 2fbce22e.
- 2026-05-22: Re-reviewed task 166 design/plan/steps for inconsistencies after thinking-proof clarification; no new actionable inconsistency feedback; committed review note 2ad29302.
- 2026-05-23: Executed task 167 inconsistency follow-up: created missing `plan.md` and `steps.md` with Emacs/TUI implementation and verification checklist; marked the design-step done.
- 2026-05-23: Executed task 167 inconsistency follow-up: aligned TUI verification with close-toggle acceptance by updating design/plan/steps to require toggled-closed TUI coverage; marked design-step done.
- 2026-05-23: Reviewed task 167 design/plan/steps for ambiguities; added follow-up to clarify global-vs-row-local tool-detail toggle granularity before implementation.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for inconsistencies after global-toggle clarification; found no new actionable inconsistency feedback; appended implementation note and committed 77201055.
- 2026-05-23: Executed task 167 inconsistency follow-up: aligned `spec/tool-output-rendering.allium` with the finalized expanded full-call contract and marked the design-step done; did not execute implementation `steps.md` items.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for ambiguities after spec alignment; found no new actionable ambiguity feedback and appended the implementation note.
- 2026-05-23: Executed task 168 ambiguity follow-ups: created plan.md/steps.md, resolved structured output authoring/IR surface to existing `:outputs` with `:session/structured-output` and `:judge/structured-output`, and selected `:psi.workflow/judge-review-result` as first standard schema without migrating existing workflows.
- 2026-05-23: Executed task 169 inconsistency follow-up after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated. Commit e70c1f96.
- 2026-05-23: Executed task 169 ambiguity follow-ups: created plan/steps, chose OpenAI Chat Completions JSON Schema `response_format` for explicit `:openai-completions` capabilities, kept Codex fallback-only, specified strategy metadata surface, and defined Anthropic synthetic forced-tool semantics. Commit 732ce06f.
- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 4; found workflow structured-output docs still describe prompted-JSON as runtime-owned prompting, conflicting with task 169's finalized adapter-owned fallback instruction boundary; added design-step.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 5 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 6 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Implemented task 169 capability foundation slice: structured-output schemas/helpers, built-in capability declarations, user-model capability parsing/defaulting, OAuth `gpt-5.5` Codex fallback capability replacement, and focused model/user-model tests green.
## 2026-05-24 task 166 scheduler mandatory time source
- Implemented mandatory scheduler time-source slice for task 166: production `psi.agent-session.scheduler-time`, context `:scheduler-time-source`, explicit scheduler create instants, deterministic psi-tool/timer/deliver/drain time boundaries, and scheduler test helper support.
- Added fail-fast proof for missing/invalid `:scheduler-time-source` at psi-tool create, scheduler timer effect, deliver, and drain boundaries (commit 1d54bf66).
- Full scheduler proof green: 32 tests, 342 assertions, 0 failures.
- 2026-05-24: Task 166 implementation review pass 3 follow-up complete: `:scheduler/deliver` now validates schedule existence/deliverability before resolving `:scheduler-time-source`; missing/non-deliverable schedule errors preserve precedence. Full scheduler proof green: 33 tests, 344 assertions, 0 failures.
- 2026-05-24: Code-shaper review for task 166 found one actionable robustness issue: session-kind `:scheduler/deliver` catches scheduler time-source validation failures and records failed schedules instead of fail-fast boundary behavior. Added follow-up to `steps.md` and committed review note (`ff6793da`).
- 2026-05-24: Code-shaper review pass 2 for task 166 found no new actionable feedback after session-kind delivery time-source fail-fast fix; only review note appended.
- 2026-05-21: task 167 follow-up execution pass found no newly added unchecked implementation items in `steps.md`; appended a no-op execution note to `implementation.md` and committed the pass
- 2026-05-21: task 168 follow-up execution added the missing focused `get-tool-in` built-in `:ext-path` provenance assertion, marked the final unchecked step done, and re-verified focused tests + lint
- 2026-05-21: task 168 later follow-up execution passes found no newly added unchecked implementation items after test review and test-shaper review; recorded no-op execution notes and committed each pass
- 2026-05-21: task 168 initially failed full `bb test` after implementation because `extension-detail-in` still read legacy extension-local `:tools` state after tool ownership moved to `tool-registry`/`root-registry`; fixed by sourcing extension tool detail from `tool-registry/all-tools-in`, then closed 168
- 2026-05-21: task 164 was updated with explicit registry migration guidance: future registry moves must inventory write seams plus all higher read/introspection/projection seams, add a higher-surface coherence test, and run full-suite verification before close; `workflow-registry` is now the recommended next root-registry-style migration target while `deterministic-operation-registry` remains deferred
- 2026-05-22: task 173 ambiguity review follow-up found one additional design ambiguity: plan/steps only described the ordering-removal path despite design outcome A allowing keep-order if a real insertion-order dependency exists; added a `design-steps.md` follow-up and committed `66d6076b`.
- 2026-05-22: task 173 ambiguity follow-up execution completed all `design-steps.md` items, clarified canonical skill ordering and affected surfaces, added explicit remove/keep branches to plan and steps, and committed `c366edc3`.
- 2026-05-22: task 173 inconsistency review found one actionable mismatch: design outcome C remains viable, but plan/steps/task-164 update guidance only cover canonical name-sorted removal or keep-order branches; added `design-steps.md` follow-up and committed `9d417550`.
- 2026-05-22: task 173 inconsistency follow-up execution completed the new `design-steps.md` item by keeping outcome C viable and adding matching plan/steps/task-164 guidance for registry-order-insensitive behavior with presentation-owned canonical sorting.
- 2026-05-22: task 173 ambiguity review found no new actionable ambiguity feedback after checking the refined design/plan/steps plus representative skill registry, resolver, prompt, and TUI surfaces; appended a no-op review note and left `design-steps.md` unchanged.
- 2026-05-22: task 173 ambiguity follow-up execution found no newly added unchecked `design-steps.md` items after the no-feedback ambiguity review; recorded the no-op pass in `implementation.md`.
- 2026-05-22: task 173 inconsistency follow-up execution completed the newly added `design-steps.md` item by clarifying the design-review follow-up surface covers both ambiguity and inconsistency items; committed `0e6a6df8`.
- 2026-05-22: task 173 ambiguity follow-up execution found no newly added unchecked `design-steps.md` items after the preloaded no-feedback ambiguity review; recorded the no-op pass and committed `732d2b15`.
- 2026-05-22: task 173 inconsistency follow-up execution found no newly added unchecked `design-steps.md` items after the preloaded no-feedback inconsistency review; recorded the no-op pass.
- 2026-05-22: task 173 ambiguity review pass re-read the task artifacts plus representative skill-order call sites and found no new actionable ambiguity feedback; appended a no-op review note and left `design-steps.md` unchanged.
- 2026-05-22: task 173 ambiguity follow-up execution found no newly added unchecked `design-steps.md` items after the latest no-feedback ambiguity review; recorded the no-op pass.
- 2026-05-22: task 173 inconsistency review found one new actionable mismatch: `design.md` classifies `psi.agent-session.prompt_request` with prompt/display ordered skill-list surfaces, but code shows it only does exact `/skill:name` lookup; added a `design-steps.md` follow-up and committed the pass.
- 2026-05-22: task 173 inconsistency follow-up execution completed the new `design-steps.md` item: `prompt_request` is now classified only as exact `/skill:name` lookup expansion, while prompt lifecycle / `system_prompt` remain ordered skill-list render surfaces.
- 2026-05-22: task 173 ambiguity review found one new actionable ambiguity: prompt-component / workflow `:skill-names` subset filtering order is unclear (caller-declared vs canonical name order vs inherited session order) and its branch B/C scope is unspecified; added a `design-steps.md` follow-up and committed `35d257dd`.
- 2026-05-22: task 173 ambiguity follow-up execution completed the new `design-steps.md` item: prompt-component / workflow `:skill-names` is now specified as an allowlist, not an ordering directive; model-visible filtered skill subsets should use canonical skill-name order for branch B/C; committed `531dfda3`.
- 2026-05-22: task 173 inconsistency review found one new actionable mismatch: workflow step `:session :skills` selection is not covered by the exact-lookup-only / `:skill-names` allowlist guidance even though it can determine child-session/model-visible skill order; added a `design-steps.md` follow-up.
- 2026-05-22: task 173 inconsistency follow-up execution completed the workflow step `:session :skills` ordering item: workflow skill selection is allowlist/exact-name input, not an ordering directive; branch B/C must canonicalize selected subsets before model-visible child prompt rendering.
- 2026-05-22: task 173 implementation selected branch B: `skill-registry` no longer treats registration order as behavior; registry read/result surfaces, prompt skill rendering/summaries, session skill resolver output, prompt-component selected subsets, and workflow-selected skill subsets now canonicalize by exact skill `:name`; focused verification and affected regressions passed, while full `bb lint` remains blocked by pre-existing root-registry warning.
- 2026-05-22: task 173 implementation review found one actionable gap: `skills-by-source` / `:psi.skill/by-source` still expose per-source groups in raw session vector order and lack focused proof; added an unchecked `steps.md` follow-up and committed `149095cb`.
- 2026-05-22: task 173 test-review follow-up execution added focused TUI coverage and safeguards for skill banner/autocomplete ordering with unsorted session skills; marked the final unchecked `steps.md` item done after focused TUI + resolver/prompt-assets tests and TUI lint passed.
- 2026-05-22: task 173 code-shaper follow-up execution made `:session/register-skill` persist canonicalized duplicate/no-change registry results without prompt refresh, added focused dispatch proof, and marked the follow-up complete.
- 2026-05-22: task 173 task-test-review found one new actionable test gap: `/skills` output and `/help` embedded Skills section are user-visible ordered skill-list command surfaces without focused proof against raw session-vector ordering; added a `steps.md` follow-up and committed `884e0bb4`.
- 2026-05-22: task 173 task-test-review follow-up execution added focused `/skills` and `/help` command-surface ordering tests for unsorted raw session `:skills`; focused commands test and commands-test lint passed.
- 2026-05-22: task 173 task-test-review re-read the branch B ordered-skill proof set (registry, prompt-assets, prompt-component/workflow selection, session resolver, dispatch duplicate canonicalization, TUI, and command surfaces), ran focused verification (133 tests, 656 assertions), and found no new actionable test feedback.
- 2026-05-22: task 173 test-shaper review re-read the ordered-skill proof set and ran focused verification (154 tests, 783 assertions); no new actionable test-shaping feedback found, so `steps.md` remained unchanged.
- 2026-05-22: task 173 test-shaper follow-up execution found no newly added unchecked `steps.md` items after the preloaded no-feedback test-shaper review; recorded the no-op pass.
- 2026-05-22: task 173 code-shaper follow-up execution replaced TUI-local skill sorting in banner/autocomplete with shared `skill-registry/all-skills`, added the TUI dependency, verified focused TUI tests + TUI lint, and committed `7fe60e11`.
- 2026-05-22: task 173 closed after review loops completed and full `bb test` passed; skill registration order is no longer semantic, canonical skill-name ordering now owns registry/projection/model-visible skill-list surfaces while duplicate-ignore and change reporting remain preserved.
- 2026-05-22: task 174 ambiguity follow-up execution completed both newly added `design-steps.md` items: root-registry-backed skill adapter ownership/API boundary now lives in `components/skill-registry`, and lifecycle hydration timing is synchronous inside agent-session root-state updates for new/resume/fork/child paths.
- 2026-05-24: task 176 created to simplify prompt-registry identity before any root-registry migration: remove composite `ext-path + id` identity and disallow cross-owner same-id coexistence.
- 2026-05-24: task 176 design/review loops converged on the refined contract: canonical prompt contribution identity is string-coerced `id` alone; nil/blank ids remain accepted in this pass; same-owner duplicate registration replaces; cross-owner duplicate registration becomes explicit ownership conflict; lower-level seams may temporarily accept `ext-path` only as ownership/provenance metadata rather than as identity.
- 2026-05-24: task 176 implementation completed and review loops recorded no actionable follow-up: prompt-registry helpers, lower dispatch/mutation seams, test helpers, and higher prompt-contribution projections now align to the single-id contract and shared canonical ordering. Focused prompt-registry / projection tests and targeted lint passed.
- 2026-05-24: task 177 follow-up execution used the preloaded review result and found no newly added unchecked actionable `steps.md` items; recorded a no-op execution pass in the task `implementation.md` and left task steps unchanged.
- 2026-05-24: task 177 later follow-up execution re-checked the preloaded review result plus current task artifacts and again found no newly added unchecked actionable `steps.md` items; recorded another no-op execution pass and left checklist state unchanged.
- 2026-05-24: task 177 is now closed in Munera. The task directory moved to `munera/closed/`, `munera/plan.md` now records the completed prompt-registry root-registry migration, and `mementum/state.md` was updated to reflect prompt-registry root-backed authority plus a green post-close `bb test`.
