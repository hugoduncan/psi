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

- 2026-05-24: Executed task 171 implementation-review follow-up: added Anthropic JSON Schema native parse-failure tests for non-streaming and streaming invalid/non-object output; focused Anthropic/model/user tests green (`35 tests, 221 assertions`).

- 2026-05-24: Reviewed task 171 implementation with task-implementation-review; found one actionable test gap: Anthropic JSON Schema native parse-failure behavior is implemented but lacks focused non-streaming/streaming invalid-output coverage. Added follow-up step and committed 9afb13e9.

- 2026-05-24: Task 171 live Anthropic OAuth smoke passed after correcting the beta JSON Schema request shape: Anthropic rejects `output_format.name` and `output_format.strict`, so Psi now sends only `{:type "json_schema" :schema ...}` plus the structured-output beta header; focused Anthropic/model/user tests and live smoke are green.

- 2026-05-24: Implemented task 171 Anthropic JSON Schema native structured output: capability enum/helper, Claude 4.5+ catalog assignment, request `output_format` + beta/header, strict semantics, forced-tool separation, Anthropic non-streaming execute, streaming/non-streaming extraction, docs/task-170 wording, guarded live skip path, and focused verification green.

- 2026-05-24: Executed task 171 inconsistency follow-up repeat 4 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the pass.

- 2026-05-24: Reviewed task 171 design/plan/steps for inconsistencies repeat 4; found no new actionable inconsistency feedback after rechecking task artifacts, prior notes, current Anthropic/model/schema structured-output code, AI/custom-provider docs, and task 170 wording. Commit be164b91.

- 2026-05-24: Executed task 171 ambiguity follow-up repeat 4 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the pass.

- 2026-05-24: Reviewed task 171 design/plan/steps for ambiguities repeat 4; found no new actionable ambiguity feedback after rechecking task artifacts, current Anthropic/provider structured-output helpers, core non-streaming seam, model capability code, docs, and task 170 wording. Commit 6c6fec73.

- 2026-05-24: Executed task 171 inconsistency follow-up repeat 3 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the pass.

- 2026-05-24: Executed task 171 ambiguity follow-up repeat 3 after no-action review; design-steps were already fully checked, so no task implementation steps were executed and implementation.md records the pass.

- 2026-05-24: Reviewed task 171 design/plan/steps for ambiguities repeat 3; found no new actionable ambiguity feedback after rechecking task artifacts, current Anthropic/model/core structured-output code, docs, and task 170 dependency wording. Commit 25a22bb7.

- 2026-05-24: Executed task 171 inconsistency follow-up repeat 2: Anthropic JSON Schema `:output_format :strict` now follows normalized request `[:structured-output :strict?]`, defaults true only when omitted, honors explicit false, and design-steps are fully checked.

- 2026-05-24: Executed task 171 inconsistency follow-up repeat: acceptance criterion 11 now matches the provider `:api-key`/`ANTHROPIC_API_KEY` live-smoke seam, with OAuth only when supplied through that seam; design-steps fully checked.

- 2026-05-24: Executed task 171 ambiguity follow-up: design/plan now name exact docs/task-170 dependency targets and define live-smoke credential seam as Anthropic provider `:api-key`/`ANTHROPIC_API_KEY`, with OAuth only via that seam; design-steps fully checked.

- 2026-05-24: Re-reviewed task 171 refined design/plan/steps for ambiguities; found actionable gaps around exact documentation targets/task-170 wording and the concrete live-smoke credential/OAuth seam. Commit 619a4795.

- 2026-05-24: Reviewed task 171 design for ambiguities; found actionable gaps around absent plan/steps plus exact Anthropic JSON Schema request/header, response/stream extraction, model mechanism assignment, JSON Schema vs forced-tool selection, and live smoke criteria. Commit e85104d8.

- 2026-05-24: Created task 171 to update Anthropic structured-output support from task 169's forced-tool-only native assumption to the documented JSON Schema native output mechanism, with forced-tool and prompted-JSON kept as separate paths.

- 2026-05-23: Executed task 169 code-shaper follow-up: Anthropic prompted-JSON fallback streaming now preserves text deltas and emits parsed first-class `:structured-output-result` with `:source :prompted-json/text`; focused structured-output/model tests green.

- 2026-05-23: Executed task 169 test-shaper follow-up: added prompted-JSON fallback streaming coverage; Codex now emits parsed `:structured-output-result` for fallback streaming, fallback-only Anthropic strategy/text behavior covered; focused structured-output/model tests green.

- 2026-05-23: Executed task 169 test-review follow-up: Anthropic provider now loads, focused Anthropic structured-output test passes, combined OpenAI/Anthropic structured-output + model/user-model focused run passes; marked the verification follow-up done and committed 15b4b0ad.

- 2026-05-23: Reviewed task 169 implementation with task-implementation-review; found Anthropic fallback-only structured-output strategy is selected but not request-shaped with adapter-owned prompted-JSON instructions; added implementation follow-up.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 14 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.

- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 14; found no new actionable ambiguity feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model files. Commit 78f435e5.

- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 12; found no new actionable inconsistency feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model/provider files.

- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 13; found no new actionable ambiguity feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model files.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 11 after no-action review; no newly added unchecked design-steps existed, so implementation.md records the pass and commit 31fc49d4 captures it.
- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 11; found no new actionable inconsistency feedback after JSON Schema source, built-in capability assignment, and docs/code alignment checks.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 12 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 10 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 10; found no new actionable inconsistency feedback after JSON Schema source and built-in capability assignment clarifications.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 9: design acceptance/constraints now require caller-supplied `:json-schema`; AI adapters do not convert Malli/domain `:schema` in task 169.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 10: concrete built-in structured-output capability assignment is now explicit: Anthropic Messages native, named modern OpenAI Chat Completions native, Codex Responses fallback-only, unverified OpenAI entries omitted/unsupported.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 10; found built-in capability assignment ambiguity: examples reference non-current model ids and artifacts do not say which existing built-ins should declare native/fallback/unsupported structured-output capability; added design-step and committed 60ad4361.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 9: explicit request `:json-schema` is required as the provider-bound schema source; AI adapters do not convert Malli/domain `:schema`; schema-only structured-output requests report `:unsupported` without fallback prompt injection or native fields.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 9; found JSON Schema source contract ambiguity: request contract still allows supplied `:json-schema`, adapter-derived conversion, or subset conversion/failure, but provider-native construction requires one concrete behavior; added design-step and committed e5fbabe1.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 8 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 8: non-streaming structured-output metadata/payload is authoritative at top-level provider result `:structured-output`, sibling to `:assistant-message`/`:logprobs`, not nested or capture-only.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 8; found non-streaming structured-output metadata root ambiguity: design says provider execution returns/associates `:structured-output`, but current `execute-response` returns `{:assistant-message ... :logprobs ...}` with no exact root; added design-step and committed 6ab439b9.
- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 7; found auth-path representation inconsistency: design requires final `:auth` in effective capability resolution and examples show `:auth :chatgpt-oauth`, but closed model schemas/plan/steps do not allow or populate it; added design-step.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 7: runtime auth/transport resolution is authoritative for structured-output capability; OAuth-backed `openai/gpt-5.5` mapped to Codex must clear/replace platform-native capability so strategy selection cannot inherit Chat Completions native support.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 7; found auth-path/runtime transport override ambiguity for OAuth-backed `openai/gpt-5.5` inheriting platform Chat Completions native capability after `resolve-runtime-model` changes it to Codex; added design-step and committed 6ab439b9.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 6: omitted structured-output capability data remains load-valid but normalizes to effective unsupported; prompted-JSON fallback is explicit opt-in, preventing surprise fallback prompt injection for legacy/custom models.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 6; found absent structured-output capability semantics ambiguity for model descriptions that omit `:capabilities :structured-output` (current built-ins/custom schema omit it); added design-step and committed 54e1803c.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 5: chose first-class AI stream events `:structured-output-strategy` and `:structured-output-result` as the authoritative streaming metadata/result surface; provider captures are diagnostics only.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 5; found streaming structured-output metadata/result surface ambiguity (new stream events vs callback/capture while AI stream schemas lack event types); added design-step.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 4: aligned workflow structured-output docs so prompted-JSON fallback is AI-adapter-owned JSON-only/schema prompt injection, while workflow runtime owns parse/coerce/validate and trusted value exposure.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 4: specified prompted-JSON fallback as adapter-owned schema-guided JSON-only instruction injection, with no provider-native fields, `:fallback-used? true`, and unsupported/no-fallback behavior that does not inject fallback instructions.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 4; found fallback prompted-JSON request behavior ambiguity (adapter prompt injection vs caller-owned prompt vs metadata-only fallback); added design-step and committed ff8d6bd1.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 3 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 3: clarified structured-output capability `:supported?` semantics as any declared path, distinguished provider-native by strategy/native mechanism, and specified fallback-only vs unsupported request-time behavior.
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 3; found capability `:supported?` ambiguity for fallback-only vs unsupported/provider-native semantics; added design-step and implementation note.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 2 after no-action review; no newly added design-steps existed, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Re-reviewed task 169 design/plan/steps for inconsistencies after validation-boundary clarification; found no new actionable inconsistency feedback; appended implementation note and committed 35a4a62d.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 2: resolved validation-test ambiguity by specifying task 169 proves extracted/raw payload + strategy metadata handoff preservation for workflow/runtime validation, without adding an AI-level Malli validation seam.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat after no-action review; no new design-steps existed, so only implementation.md was updated. Commit 5c48d582.
- 2026-05-23: Executed task 169 ambiguity follow-ups repeat: specified provider-extracted structured payload surface (`[:structured-output :payload]` and streaming `:structured-output-result`/equivalent capture), hid Anthropic synthetic tool input from ordinary tool calls, and clarified AI adapters extract/report payload metadata while workflow/runtime validation remains authoritative.

## Test health

Focused OAuth routing tests ✅. bb tests previously ✅. Task 169 focused structured-output/model tests ✅ (`clojure -M:test --focus psi.ai.providers.openai-structured-output-test --focus psi.ai.providers.anthropic-structured-output-test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 32 tests, 199 assertions). 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback). Task 167 focused Emacs tool-output suite ✅ (`bb emacs:test --focus psi-tool-output-mode-test`, 313/313).

## Suggested next step
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
