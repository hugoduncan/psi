# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — psi meta model (internal)
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state

- 2026-05-24: Reviewed task 171 implementation with task-implementation-review; found one actionable test gap: Anthropic JSON Schema native parse-failure behavior is implemented but lacks focused non-streaming/streaming invalid-output coverage. Added follow-up step and committed 9afb13e9.

- 2026-05-24: Task 171 live Anthropic OAuth smoke passed after correcting the beta JSON Schema request shape: Anthropic rejects `output_format.name` and `output_format.strict`, so Psi now sends only `{:type "json_schema" :schema ...}` plus the structured-output beta header; focused Anthropic/model/user tests and live smoke are green.

- 2026-05-24: Implemented task 171 Anthropic JSON Schema native structured output: capability enum/helper, Claude 4.5+ catalog assignment, request `output_format` + beta/header, strict semantics, forced-tool separation, Anthropic non-streaming execute, streaming/non-streaming extraction, docs/task-170 wording, guarded live skip path, and focused verification green.

## Test health

Focused Anthropic/model/user structured-output tests ✅ during review (`clojure -M:test --focus psi.ai.providers.anthropic-structured-output-test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 33 tests, 199 assertions). Task 171 live smoke previously ✅ after request-shape correction. Task 169 focused structured-output/model tests ✅. Focused OAuth routing tests ✅. bb tests previously ✅. 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback). Task 167 focused Emacs tool-output suite ✅ (`bb emacs:test --focus psi-tool-output-mode-test`, 313/313).

## Suggested next step
- Execute task 171 implementation-review follow-up: add focused Anthropic JSON Schema native parse-failure tests for non-streaming and streaming invalid/non-object output.
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items

## Latest session notes

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
