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

- 2026-05-23: Executed task 169 ambiguity follow-up repeat 14 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.

- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 14; found no new actionable ambiguity feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model files. Commit 78f435e5.

- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 12; found no new actionable inconsistency feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model/provider files.

- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 13; found no new actionable ambiguity feedback after rechecking task artifacts, workflow docs, and current AI model/provider/schema/user-model files.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 11 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated. Commit 31fc49d4.
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
- 2026-05-23: Reviewed task 169 design/plan/steps for ambiguities repeat 8; found non-streaming structured-output metadata root ambiguity: design says provider execution returns/associates `:structured-output`, but current `execute-response` returns `{:assistant-message ... :logprobs ...}` with no exact root; added design-step and committed 08e6446d.
- 2026-05-23: Reviewed task 169 design/plan/steps for inconsistencies repeat 7; found auth-path representation inconsistency: design requires final `:auth` in effective capability resolution and examples show `:auth :chatgpt-oauth`, but closed model schemas/plan/steps do not allow or populate it; added design-step and committed c1ff047a.
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
- 2026-05-23: Executed task 169 inconsistency follow-up repeat 2 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Re-reviewed task 169 design/plan/steps for inconsistencies after validation-boundary clarification; found no new actionable inconsistency feedback; appended implementation note and committed 35a4a62d.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 2: resolved validation-test ambiguity by specifying task 169 proves extracted/raw payload + strategy metadata handoff preservation for workflow/runtime validation, without adding an AI-level Malli validation seam.
- 2026-05-23: Executed task 169 inconsistency follow-up repeat after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated. Commit 5c48d582.
- 2026-05-23: Executed task 169 ambiguity follow-ups repeat: specified provider-extracted structured payload surface (`[:structured-output :payload]` and streaming `:structured-output-result`/equivalent capture), hid Anthropic synthetic tool input from ordinary tool calls, and clarified AI adapters extract/report payload metadata while workflow/runtime validation remains authoritative.
- 2026-05-23: Reviewed task 168 code with code-shaper; added follow-up to consolidate duplicate structured-output predicate/source helper logic between workflow-runtime and workflow-step-materialization.
- 2026-05-23: Executed task 168 test-shaper follow-up: added focused boundary coverage that syntactically valid non-object JSON (array/scalars) is rejected as an invalid single-object structured envelope with parsed value/errors retained and no exposed `:value`.
- 2026-05-23: Reviewed task 168 tests with task-test-review; added follow-ups for text-mode compatibility, IR structured-output semantic rejection, invalid/negative judge routing, and structured source-ref failure coverage.
- 2026-05-23: Executed task 168 implementation-review follow-up: invalid session structured-output fail-fast now preserves raw output and invalid structured envelope by blocking before output-surface normalization; focused workflow tests green.
- 2026-05-23: Completed task 168 broader verification pass: marked final implementation step done after `bb clojure:test:unit` passed across the unit suite; no deviations discovered.
- 2026-05-23: Implemented task 168 first runtime structured-output slice: reusable judge-review schema, prompted-JSON canonical envelopes, IR validation, session fail-fast, structured judge routing, downstream valid-value refs; focused workflow tests green.
- 2026-05-23: Executed task 168 inconsistency follow-up repeat 3: aligned `doc/workflow-ir.md` compact suggested grammar so `llm-judge` includes `outputs?`, matching documented judge-local structured outputs; design-steps all checked.
- 2026-05-23: Executed task 168 ambiguity follow-up repeat 3: specified one structured-output key per session step or LLM judge, one raw response -> one structured envelope for prompted/provider-native strategies, and workflow-runtime ownership/export for `:psi.workflow/judge-review-result`; design-steps all checked.
- 2026-05-23: Executed task 168 inconsistency follow-up repeat 2: specified judge-local structured outputs as transition-evaluation-only data, not implicit parent step outputs; aligned design, grammar, IR, and user guide; design-steps all checked.
- 2026-05-23: Executed task 168 ambiguity follow-up repeat 2: aligned `doc/workflow-ir.md` with normalized session/judge structured output IR shape, canonical envelope metadata, provider strategy/coercion notes, and downstream reference semantics; design-steps all checked.
- 2026-05-23: Executed task 168 inconsistency follow-up repeat: aligned `doc/workflows.md` with session/judge structured `:outputs`, representative structured field refs, and grammar/IR pointers; design-steps all checked.
- 2026-05-23: Executed task 168 ambiguity follow-ups repeat: aligned workflow grammar docs for session/judge structured `:outputs` and specified prompted JSON fallback with schema-guided Malli coercion; design-steps all checked.
- 2026-05-23: Created follow-on Munera task designs 169 and 170: model/provider structured-output capabilities for OpenAI/Anthropic native enforcement first, then workflow wiring to request provider-native structured outputs with explicit fallback/required-native policy.
- 2026-05-23: Created Munera task 168 design for workflow structured output schemas: optional step/judge schemas, raw+validated result storage, explicit downstream structured references, provider strategy visibility, and fail-fast validation semantics. Commit 3106da5f.
- 2026-05-23: Wired `bb emacs:tool-details:e2e` into CI/release Emacs jobs as an explicit step; local e2e green.
- 2026-05-23: Added dedicated task 167 Emacs tool-details e2e harness (`bb emacs:tool-details:e2e`) using a deterministic rpc-edn fake backend; e2e and focused Emacs tests green.
- 2026-05-23: Executed task 167 code-shaper follow-up: Emacs parsed/raw tool-call equivalence now canonicalizes argument maps across string/symbol/keyword keys; equivalent complete raw+parsed args suppress `Raw arguments:`, projected/lossy parsed args still include it. Focused Emacs suite green; committed 2660283c.
- 2026-05-23: Reviewed task 167 implementation with code-shaper; added a follow-up to canonicalize parsed/raw argument equivalence before suppressing Emacs raw fallback.
- 2026-05-23: Executed task 167 repeated test-shaper follow-up after no-action review; no unchecked implementation steps remained, so implementation.md records the pass.
- 2026-05-23: Executed task 167 test-shaper follow-up after no-action review; no unchecked implementation steps remained, so implementation.md records the pass.
- 2026-05-23: Reviewed task 167 tests with test-shaper; found no new actionable test-shaping feedback after existing Emacs full-call, raw fallback, nil/empty, invalid raw, and toggle coverage.
- 2026-05-23: Reviewed task 167 tests with task-test-review; added follow-ups for missing Emacs coverage of explicit nil/empty argument markers and invalid raw argument fallback in expanded `Call` details.
- 2026-05-23: Executed task 167 implementation-review follow-up: Emacs expanded `Call` details now include raw `arguments` when `parsed-args` cannot be proven equivalent; projected `psi-tool` coverage added; focused Emacs suite green.
- 2026-05-23: Scoped task 167 back to Emacs-only full tool-call details; TUI equivalence and TUI extension-renderer gaps are out of scope.
- 2026-05-23: Executed task 167 inconsistency follow-up repeat 2 after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for inconsistencies (repeat 2); found no new actionable inconsistency feedback and appended the implementation note.
- 2026-05-23: Executed task 167 ambiguity follow-up repeat 2 after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for ambiguities (repeat 2); found no new actionable ambiguity feedback and appended the implementation note.
- 2026-05-23: Executed task 167 inconsistency follow-up repeat after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Re-reviewed task 167 design/plan/steps for inconsistencies after spec alignment; found no new actionable inconsistency feedback and appended implementation note.
- 2026-05-23: Executed task 167 ambiguity follow-up repeat after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Executed task 167 ambiguity follow-up after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Executed task 167 inconsistency follow-up after no-action review; no unchecked design-steps remained, no implementation steps were executed, and implementation.md records the pass.
- 2026-05-23: Executed task 167 ambiguity follow-up: design now preserves existing global Emacs `C-c C-t` and TUI `ctrl+o` tools-expanded semantics; no row-local expansion is required.
- 2026-05-23: Reviewed task 167 design/plan/steps for inconsistencies; found TUI verification gap versus close-toggle acceptance (TUI supports ctrl+o close but plan/steps only mention collapsed+expanded coverage); added design-step and implementation note.
- 2026-05-22: Created task 167 for Emacs `C-c C-t` tool details to include full tool call details alongside responses; TUI equivalence later dropped from scope.
- 2026-05-22: Fixed Emacs prompt-completion cursor fallback so finalization places point in the editable input area instead of the input divider line; added regression coverage; `bb emacs:test --focus psi-streaming-transcript-test` ran 308/308 green.
- 2026-05-22: Executed task 166 code-shaper follow-up: extracted shared assistant streaming optimization instrumentation helper while preserving distinct behavior assertions; focused streaming suite 36/36 and `bb emacs:test` 307/307 green.
- 2026-05-22: Executed task 166 test-shaper follow-up: split the combined assistant append optimization proof into separate incremental-delta, cumulative-snapshot, and divergent-merge tests; focused streaming suite 36/36 and `bb emacs:test` 307/307 green.
- 2026-05-22: Executed task 166 repeated test-review follow-up after no-action review; no new unchecked steps existed, so only implementation.md was updated.
- 2026-05-22: Executed task 166 test-review follow-up after no-action test review; no new unchecked steps existed, so only implementation.md was updated and committed (6f0a3822).
- 2026-05-22: Executed task 166 implementation-review follow-up: added `psi-streaming-render-optimization-test.el` to `bb emacs:test`, marked the step done, and verified focused streaming + full Emacs suites green (34/305 tests).
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no new design-steps existed, so only implementation.md was updated.
- 2026-05-22: Executed task 166 ambiguity follow-up clarifying thinking append-only proof: assistant property ranges are suffix-only, while thinking proof uses inserted suffix mutation range plus no redraw/overlay recreation.
- 2026-05-21: Fixed OpenAI OAuth `gpt-5.5` routing (commit 009aed51).
  - Root cause: `gpt-5.5` used platform chat-completions while active OpenAI auth was ChatGPT OAuth; platform path returned `insufficient_quota`.
  - Runtime resolution now maps OAuth-backed `openai/gpt-5.5` to `:openai-codex-responses` / `https://chatgpt.com/backend-api`, preserving visible id `gpt-5.5`.
  - Updated prompt-request, app-runtime, RPC, TUI action, and `/model` command resolution seams.
  - Focused tests green: `clojure -M:test --focus psi.provider-auth.core-test --focus psi.ai.model-registry-test --focus psi.agent-session.runtime-test --focus psi.agent-session.prompt-request-test` => 32 tests, 162 assertions, 0 failures.
  - Live reload/eval verified and user successfully switched `/model openai gpt-5.5`.
- Bootstrap simplification arc (159–163) complete:
  - 159: in-process bootstrap simplification
  - 160: removed mutation-mediated bootstrap resource loading
  - 161: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
  - 162: `bootstrap-runtime-session!` collapsed to single `(ctx ai-model opts)` arity
  - 163: `start-tui-runtime!` refactored — dead `ai-ctx` removed
- Tasks 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 also complete and closed

## Test health

Focused OAuth routing tests ✅. bb tests previously ✅. 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback). Task 167 focused Emacs tool-output suite ✅ (`bb emacs:test --focus psi-tool-output-mode-test`, 313/313).

## Suggested next step
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items

## Latest session notes

- 2026-05-23: Executed task 169 ambiguity follow-up repeat 14 after no-action review; design-steps already fully checked, so implementation.md records the no-op pass and no implementation steps were executed.
- 2026-05-23: Executed task 169 ambiguity follow-up repeat 13 after no-action review; no newly added unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-23: Task 169 ambiguity review repeat 13 found no new actionable ambiguity feedback; design remains clear on explicit `:json-schema`, capability normalization, auth/transport-resolved capability, provider request/result surfaces, and validation boundary.
- 2026-05-23: Task 169 inconsistency follow-up repeat 11 executed as no-op: preloaded review had no actionable feedback and all design-steps were already checked; implementation.md records the pass and commit 31fc49d4 captures it.
- 2026-05-23: Task 169 ambiguity review repeat 8 found one actionable ambiguity: non-streaming structured-output metadata/payload must name the exact `execute-response` result root so implementation/tests do not diverge.
- 2026-05-23: Task 169 ambiguity review repeat 7 found one actionable ambiguity: structured-output capability source must be defined after runtime auth/transport resolution so ChatGPT OAuth/Codex `gpt-5.5` cannot accidentally inherit platform-native OpenAI Chat Completions capability.
- 2026-05-23: Task 169 inconsistency review repeat 6 found no new actionable inconsistency feedback; omitted structured-output capability data remains load-valid but effectively unsupported, prompted JSON fallback is explicit opt-in, streaming metadata uses first-class events, and workflow/runtime remains final validation authority.
- 2026-05-23: Task 169 inconsistency review repeat 5 found no new actionable inconsistency feedback; design/plan/steps/docs remain aligned on first-class streaming structured-output events, OpenAI Chat Completions native-only support, Codex fallback-only, Anthropic synthetic forced-tool extraction, and workflow-owned final validation.
- 2026-05-23: Task 169 no-action inconsistency review repeat 2: design/plan/steps remain aligned around OpenAI Chat Completions native-only support, Codex fallback-only, Anthropic synthetic forced-tool extraction, explicit metadata/payload handoff, and workflow-owned final validation.
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
## 2026-05-24 task 166 scheduler mandatory time source
- Implemented mandatory scheduler time-source slice for task 166: production `psi.agent-session.scheduler-time`, context `:scheduler-time-source`, explicit scheduler create instants, deterministic psi-tool/timer/deliver/drain time boundaries, and scheduler test helper support.
- Added fail-fast proof for missing/invalid `:scheduler-time-source` at psi-tool create, scheduler timer effect, deliver, and drain boundaries (commit 1d54bf66).
- Full scheduler proof green: 32 tests, 342 assertions, 0 failures.
- 2026-05-24: Task 166 implementation review pass 3 follow-up complete: `:scheduler/deliver` now validates schedule existence/deliverability before resolving `:scheduler-time-source`; missing/non-deliverable schedule errors preserve precedence. Full scheduler proof green: 33 tests, 344 assertions, 0 failures.
- 2026-05-24: Code-shaper review for task 166 found one actionable robustness issue: session-kind `:scheduler/deliver` catches scheduler time-source validation failures and records failed schedules instead of fail-fast boundary behavior. Added follow-up to `steps.md` and committed review note (`ff6793da`).
- 2026-05-24: Code-shaper review pass 2 for task 166 found no new actionable feedback after session-kind delivery time-source fail-fast fix; only review note appended.
