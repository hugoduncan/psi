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

- 2026-05-22: Created Munera task 167 for frontend tool details: Emacs `C-c C-t` and TUI tool-detail expansion should include full tool call details alongside responses.
- 2026-05-22: Fixed Emacs prompt-completion cursor fallback so finalization places point in the editable input area instead of the input divider line; added regression coverage; `bb emacs:test --focus psi-streaming-transcript-test` ran 308/308 green.
- 2026-05-22: Executed task 166 code-shaper follow-up: extracted shared assistant streaming optimization instrumentation helper while preserving distinct behavior assertions; focused streaming suite 36/36 and `bb emacs:test` 307/307 green.
- 2026-05-22: Executed task 166 test-shaper follow-up: split the combined assistant append optimization proof into separate incremental-delta, cumulative-snapshot, and divergent-merge tests; focused streaming suite 36/36 and `bb emacs:test` 307/307 green.
- 2026-05-22: Executed task 166 repeated test-review follow-up after no-action review; no new unchecked steps existed, so only implementation.md was updated.
- 2026-05-22: Executed task 166 test-review follow-up after no-action test review; no new unchecked steps existed, so only implementation.md was updated and committed (6f0a3822).
- 2026-05-22: Executed task 166 implementation-review follow-up: added `psi-streaming-render-optimization-test.el` to `bb emacs:test`, marked the step done, and verified focused streaming + full Emacs suites green (34/305 tests).
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no new unchecked design-steps existed, so only implementation.md was updated.
- 2026-05-22: Executed task 166 ambiguity follow-up clarifying thinking append-only proof: assistant property ranges are suffix-only, while thinking proof uses inserted suffix mutation range plus no redraw/overlay recreation; committed e8057a3a.
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

Focused OAuth routing tests ✅. bb tests previously ✅. 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback).

## Suggested next step
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items

## Latest session notes

- 2026-05-22: Executed task 167 ambiguity follow-ups: design now specifies full-call data precedence with raw fallback, shared Emacs/TUI expanded layout, and extension renderer constraints; all ambiguity design-steps marked done.
- 2026-05-22: Reviewed task 167 design artifacts for ambiguities; added design follow-ups for full-call data precedence, expanded-detail layout contract, and extension renderer interaction.
- 2026-05-22: Task 166 code-shaper follow-up complete: assistant optimization tests now share `psi-test-with-assistant-streaming-instrumentation`, removing repeated redraw/prefix/property `cl-letf` setup while keeping separate incremental, cumulative snapshot, and divergent merge assertions.
- 2026-05-22: Task 166 test-shaper follow-up complete: assistant append optimization proof is now three behavior-local tests (incremental delta, cumulative snapshot suffix, divergent merge preservation), each asserting suffix-only properties and no redraw/prefix recreation.
- 2026-05-22: Task 166 mandatory assistant/thinking streaming optimization implemented: append-only assistant/thinking updates now use suffix insertion, avoid post-creation full redraw/prefix overlay recreation, assistant stream properties apply only to suffix ranges; focused Emacs tests and `bb emacs:test` green.
- 2026-05-22: Executed task 166 test-review follow-up after no-action review; no newly added unchecked steps existed, so implementation.md records the no-op pass and commit 6f0a3822 captures it.
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no new design-steps were present, no design/plan/steps changes were required, and implementation.md records the pass.
- 2026-05-22: Reviewed task 166 design/plan/steps for inconsistencies; found no new actionable inconsistency feedback; appended implementation note only.
- 2026-05-22: Executed task 166 ambiguity follow-up after no-action ambiguity review; no newly added unchecked design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Re-reviewed task 166 design/plan/steps for ambiguities; found no new actionable ambiguity feedback; appended implementation note and left design-steps unchanged.
- 2026-05-22: Executed task 166 inconsistency follow-up after no-action inconsistency review; no newly added unchecked design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Executed task 166 ambiguity follow-up after no-action ambiguity review; no newly added unchecked design-steps were present, so no task artifact changes beyond implementation note were needed.
- 2026-05-22: Executed task 166 inconsistency follow-up after thinking-proof clarification; no newly added unchecked design-steps were present, so no task artifact changes beyond implementation note were needed.
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
