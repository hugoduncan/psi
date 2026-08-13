# 250 — Implementation steps

## Slice 1 — Pure normalization contract

- [x] Add a focused workflow-runtime delegated-failure namespace that owns the canonical fallback, safe-reason validation, Unicode-code-point length handling, and omission of invalid optional fields.
  - Implemented in `psi.workflow-runtime.delegated-failure`.
- [x] Implement deterministic terminal step selection from terminal outcome, current step, then effective step order without relying on step-run map iteration order.
- [x] Implement deterministic attempt selection from a valid terminal attempt id or the selected step's latest ordered attempt, retaining only valid nonblank step and attempt identities.
- [x] Implement left-to-right control-character removal and ordered stack-frame, credential-pair, bearer/prefixed-token, and path span redaction with the design's exact delimiters and placeholders.
- [x] Implement whitespace normalization, placeholder-excluding actionability checks, target/step apostrophe and backslash escaping, exact prefix assembly, and the 512-code-point ` ... [truncated]` bound.
- [x] Implement execution-error eligibility with terminal-outcome fallthrough, source-specific outer reason selection, and exact fallback behavior when no cause or target is actionable.
- [x] Implement immediate nested-envelope recognition and independent allowlisted optional-field copying without recursive metadata or descendant-run traversal.
- [x] Add table-driven pure tests for safe/unsafe reasons, controls/whitespace, all redaction categories and precedence, positive/negative boundaries, adjacent punctuation, quoted/unquoted credentials, token minimum lengths, and every specified path family including raw Windows/UNC cases.
  - The completed state-based matrix covers safe reasons, controls/Unicode whitespace, ordered stack/credential/token/path redaction, delimiter negatives and punctuation, quoted/unquoted and empty credentials, token minima/padding, POSIX/dot-relative/drive/UNC/secret-relative paths, and idempotent normalized output. The UNC proof distinguishes exactly two literal U+005C code points (redacted) from three (not a UNC span).
- [x] Add pure tests for placeholder-only fallback, redactable-but-actionable messages, actionable terminal fallthrough, target/step escaping, exact 512-code-point boundaries, Unicode code points, and idempotent nested-message sanitization.
  - The completed proof covers terminal fallthrough, redacted actionable text, placeholder-only and unsafe-target fallback, supplementary-Unicode truncation plus exact-512 non-truncation, escaping, malformed-nested ordinary sanitization, U+0085, and idempotent re-sanitization.
- [x] Add exact-map selection/envelope tests for execution errors, iteration-limit terminal outcomes with latest-attempt identity, cause-less fallback, non-actionable-target fallback, scrambled step maps, terminal retries, mixed-validity nested metadata, invalid nested required fields, and forbidden-field exclusion.
  - Exact maps prove explicit terminal-attempt and latest retry selection, effective-step-order fallback independent of map order, iteration-limit latest identity, cause-less/unsafe-target fallback, optional identity omission, one-level allowlisted nesting, invalid required/source rejection, and forbidden-field exclusion.
- [x] Run `clj-paren-repair` on changed Clojure files and the focused workflow-runtime Scry namespace; inspect structured failures and make the slice green.
  - Latest focused verification: `clj-paren-repair` passed for source and test; `bb clojure:test:scry --namespace psi.workflow-runtime.delegated-failure-test` passed: 13 tests, 93 assertions; `clj-kondo --lint` on both paths reported 0 errors and 0 warnings.

## Slice 2 — Delegate-step normalization

- [x] Replace the failed-child generic payload in `statechart_runtime/delegate.clj` with the canonical delegated-failure constructor while retaining the direct child run id and resolved target.
  - The parent failure payload is now the canonical envelope directly; no `:details` child payload crosses this boundary.
- [x] Verify the existing progression-recording path persists the complete envelope verbatim as the parent delegate attempt's `:execution-error`; change it only if runtime proof disproves that invariant.
  - `workflow-delegate-failure-test` drives real parent and child statechart runs and observes the exact lower-runtime envelope on the parent attempt; no progression-recording change was needed.
- [x] Add an end-to-end delegated-step boundary test where the child's terminal attempt has an actionable execution error and assert the exact parent attempt envelope, failed statuses, nil accepted result, and selected identities.
  - Added narrow state-based proof in `psi.agent-session.workflow-delegate-failure-test`.
- [x] Add delegated-step boundary tests for non-actionable target after a recognized nested error and terminal/latest retry selection.
  - Real statechart proof now drives a nested child through direct target `/secret` and confirms exact fallback with retained child location but no cause reason/nesting. Terminal/latest retry selection remains proven at the correct immutable persisted-run boundary because target-authored workflow compilation does not preserve arbitrary authored `:retry-policy`.
- [x] Add regression assertions that successful, blocked, cancelled, removed, retry, and yield/result delegation semantics retain their current status and payload shapes.
  - `delegate-boundary-nonfailed-regression-test` asserts exact completed, blocked, cancelled, and removed boundary payloads. Existing pure terminal/latest retry selection proof and `terminal-contract-execution-test` retain retry selection and completed delegated text/handoff behavior without mocks.
- [x] Run `clj-paren-repair`, the focused delegate boundary/runtime Scry tests, and relevant workflow-runtime regression namespaces; inspect structured results and make the slice green.
  - `clj-paren-repair` formatted the changed pure test. Scry passed: delegated failure 12/76, delegate boundary 4/20, terminal contract execution 1/5, and cancellation dispatch 9/63. `clj-kondo` reported 0 errors and 0 warnings across changed runtime paths.

## Slice 3 — Facade terminal-error handoff

- [x] Reuse the deterministic terminal step/attempt selector in `workflow_execution.clj` to read the selected parent attempt's exact persisted `:execution-error` before returning from execute or resume.
  - `execution-result` reuses `delegated-failure/terminal-step-attempt`; no second selector or child-run inspection was added.
- [x] Add private `:terminal-execution-error` to the facade result with nil when no selected attempt error exists, without changing terminal/blocked/status semantics.
- [x] Add workflow-execution tests proving the handoff selects the terminal/latest applicable attempt rather than an earlier retry and preserves exact map identity/value.
  - The narrow test uses scrambled `:step-runs` plus an earlier retry and verifies the terminal envelope value exactly.
- [x] Add exact-shape assertions proving every public `:steps-executed` entry retains its existing keys and string `:error` projection and does not expose the envelope.
- [x] Run `clj-paren-repair` and the focused workflow-execution Scry namespaces; inspect structured results and make the slice green.
  - `clojure -M:test-paths -m scry.cli --namespace psi.agent-session.workflow-execution-handoff-test` passed: 2 tests, 5 assertions; focused `clj-kondo` reported 0 errors and 0 warnings.

## Slice 4 — Execute/resume projection through retention

- [x] Update `run-failure-error` so a handed-off error with reason `:delegated-workflow-failed` supplies its canonical message before existing non-delegated step/terminal-outcome projection.
  - The delegated private handoff now wins before the lossy public `:steps-executed` string projection; non-delegated behavior is unchanged.
- [x] Update execute and resume mutation paths to pass the private handoff into failure projection without re-reading child state, reselecting from `:steps-executed`, or reconstructing an envelope.
  - Existing mutation calls already carry the facade result through retention cleanup; `run-failure-error` consumes only its private handoff.
- [x] Add execute mutation proof for actionable attempt failure, terminal-outcome-only failure, and exact fallback, asserting `:failed`, nil `:psi.workflow/result`, and the canonical message.
  - `canonical_workflows_delegated_failure_test` table-drives the canonical execution-error, terminal-outcome, and fallback envelopes through retention-zero cleanup. Each returns `:failed`, nil result, and the exact handed-off message rather than a lossy public attempt error. Lower-runtime source construction remains covered by the focused runtime/delegate tests.
- [x] Add execute mutation tests with completed-run retention zero proving the run is unavailable after cleanup while the handed-off delegated message still survives.
  - `canonical_workflows_delegated_failure_test` proves the returned message after immediate removal.
- [x] Add resume mutation tests where a blocked run terminalizes at a delegate step, including superseded pre-resume/retry errors and retention zero; assert the canonical terminal message and absence of `:psi.workflow/result`.
  - The resume projection proof carries pre-resume and superseded public errors, retention zero, and the no-result response shape.
- [x] Preserve and run existing non-delegated terminal-outcome message tests to prove unrelated top-level failure wording is unchanged.
  - Existing `canonical-workflows-test` terminal-outcome projection tests passed unchanged (12 tests, 137 assertions), including iteration-limit, judge no-match, nil-reason, and step-error precedence behavior.
- [x] Run `clj-paren-repair` and focused canonical-workflow execute/resume Scry namespaces; inspect structured results and make the slice green.
  - `clj-paren-repair` and `clj-kondo` passed for the changed projection test. Scry passed for `psi.agent-session.mutations.canonical-workflows-delegated-failure-test` (3 tests, 20 assertions) and the existing `psi.agent-session.mutations.canonical-workflows-test` (12 tests, 137 assertions).

## Slice 5 — Registered tool and async surfaces

- [x] Extend the registered synchronous delegate tool boundary test to execute a real failing delegated workflow and assert provider-facing text is exactly `Error: <canonical message>` with no success result synthesis.
  - The registered `delegate` tool now runs a real parent/child failing statechart fixture through its provider-facing adapter boundary; it returns the canonical envelope message with the existing semantic-error transport behavior.
- [x] Add publication tests asserting async completion and background-job payload `:error` fields equal the canonical message unchanged.
  - `delegated-result-publication` proof asserts both completion and canonical background-job payload retain the same string; the async worker proof verifies its returned failure record and completion callback share it.
- [x] Add notification and append-entry tests asserting each existing context wrapper embeds the canonical message exactly once, before any optional result section, without re-sanitization or truncation.
  - Publication proof checks the exact wrapper text and occurrence count.
- [x] Verify asynchronous returned failure records preserve the same `:error` value and existing status/result behavior.
  - `execute-async!` is exercised with its real future and nullable mutation seam; its returned failure record remains `{:status :failed :error <canonical>}`.
- [x] Run `clj-paren-repair` and focused registered-tool, workflow orchestration/text, and async-path Scry namespaces; inspect structured results and make the slice green.
  - `clj-paren-repair` and `clj-kondo` passed for all changed paths. Focused Scry passed: registered tool + delegate boundary + canonical mutation tests (12 tests / 48 assertions), and async path (8 tests / 38 assertions).

## Slice 6 — Regression, coherence, and user-facing record

- [x] Add an `[Unreleased]` `Fixed` changelog entry describing actionable, safely redacted delegated-workflow failure messages.
- [x] Grep the final envelope construction and projections to verify no exception data, operation details/results, provider/session/transcript fields, judge output, last-result text, or candidate payloads cross the child-parent boundary.
  - The failed-child branch delegates solely to `delegated-failure/delegated-failure`; its constructor allowlists direct run/target, selected location, safe reason, and one immediate nested identity. The grep hits in cancellation/removal and non-delegated terminal projection remain outside this failure-envelope boundary.
- [x] Run `clj-kondo` on every changed Clojure source and test path and fix all findings rather than suppressing them.
  - The changed runtime source/test paths report 0 errors and 0 warnings.
- [x] Run the focused workflow-runtime, workflow-execution, canonical mutation, registered delegate tool, and async projection Scry namespaces with CLI exit verification and inspect `.scry-results` on failure.
  - Final focused namespaces pass: lower runtime 13/93; delegate boundary 5/24; facade handoff 2/5; canonical mutation 3/20; existing canonical mutation regression 12/137; registered tool 6/14; async path 8/38.
- [x] Run the relevant broader workflow-runtime and agent-session unit suites and confirm successful, blocked, cancelled, removed, retry, resume, retention, async, and result regressions remain green.
  - Final `bb clojure:test:unit` passed: 2,671 tests and 19,024 assertions. Focused canonical mutation, registered-tool, facade-handoff, delegate-boundary, and lower-runtime Scry namespaces also passed; no flaky outcome occurred in this confirmation run.
- [x] Re-read design.md, plan.md, changed tests/code, and user-facing changelog; verify all ten acceptance criteria, ownership boundaries, exact-map examples, and out-of-scope invariants are coherent.
  - Re-read after the selector/message matrix expansion confirms the only child-failure construction is lower-runtime `delegated-failure/delegated-failure`; facade, mutations, tool, and async paths only carry or render its message. Exact-map examples and fallback location retention match the focused proof. Remaining unchecked acceptance bullets are explicitly enumerated regression/matrix proof gaps, not ownership or scope deviations.
- [x] Record implementation decisions, test evidence, and any discovered trade-offs in `implementation.md`, then check completed items and prepare the task for implementation review.
  - Final pass completed the remaining lexical, unsafe-target integration, and non-delegated terminal-outcome regression proof. All implementation checklist items are complete; task is ready for implementation review.

## Implementation review follow-ups

- [x] Fix unquoted credential-pair scanning so its value ends only at the specified whitespace or `,;)]}` delimiters, not at apostrophes or double quotes; add positive tests such as `token=abc\"def denied` and `token=abc'def denied` proving no credential suffix remains visible.
- [x] Update the public `execute-run!` return-shape docstring in `workflow_execution.clj` to include the always-present private `:terminal-execution-error` handoff (map or nil), keeping the documented facade contract aligned with `execution-result`.

## Test review follow-ups

- [ ] Replace or supplement the fabricated `:resume-and-execute-workflow-run-fn` result in `resume-workflow-run-projects-terminal-delegated-error-through-retention-test` with a state-based test that resumes a genuinely blocked workflow through the real facade/delegate runtime, terminalizes it with a delegated failure, and proves terminal-attempt selection, retention-zero handoff, and the result-free resume response together. The current test substitutes the logic under review and cannot detect a broken resume-to-facade integration.
- [ ] Extend delegated publication coverage with an already-normalized 512-code-point canonical message and `:include-result? true`; assert notification and append-entry wrappers preserve the message byte-for-byte exactly once, do not truncate or re-normalize it, and place it before the optional result section. The current short-message/`false` case does not prove those specified boundaries.
