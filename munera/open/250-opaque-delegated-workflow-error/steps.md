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
- [ ] Add table-driven pure tests for safe/unsafe reasons, controls/whitespace, all redaction categories and precedence, positive/negative boundaries, adjacent punctuation, quoted/unquoted credentials, token minimum lengths, and every specified path family including raw Windows/UNC cases.
  - Expanded state-based coverage adds quoted credentials, unquoted redactable credentials, missing-value and short-token negatives, punctuation preservation, forward-slash drive/home/dot-relative paths, and ordinary-relative-path negative cases; the remaining exact lexical-boundary matrix is open.
- [ ] Add pure tests for placeholder-only fallback, redactable-but-actionable messages, actionable terminal fallthrough, target/step escaping, exact 512-code-point boundaries, Unicode code points, and idempotent nested-message sanitization.
  - Initial proof covers terminal fallthrough and a recognized immediate nested envelope; this pass adds non-actionable-target fallback and supplementary-Unicode code-point truncation. Expand the remaining boundary matrix.
- [ ] Add exact-map selection/envelope tests for execution errors, iteration-limit terminal outcomes with latest-attempt identity, cause-less fallback, non-actionable-target fallback, scrambled step maps, terminal retries, mixed-validity nested metadata, invalid nested required fields, and forbidden-field exclusion.
  - Initial proof covers terminal/latest retry selection, iteration-limit latest identity, cause-less fallback, non-actionable-target fallback, and one-level allowlisted nesting. Expand the remaining exact-map cases.
- [x] Run `clj-paren-repair` on changed Clojure files and the focused workflow-runtime Scry namespace; inspect structured failures and make the slice green.
  - Latest focused verification: `clj-paren-repair` passed for source and test; `clojure -M:test-paths -m scry.cli --namespace psi.workflow-runtime.delegated-failure-test` passed: 6 tests, 27 assertions; `clj-kondo --lint` on both paths reported 0 errors and 0 warnings.

## Slice 2 — Delegate-step normalization

- [x] Replace the failed-child generic payload in `statechart_runtime/delegate.clj` with the canonical delegated-failure constructor while retaining the direct child run id and resolved target.
  - The parent failure payload is now the canonical envelope directly; no `:details` child payload crosses this boundary.
- [x] Verify the existing progression-recording path persists the complete envelope verbatim as the parent delegate attempt's `:execution-error`; change it only if runtime proof disproves that invariant.
  - `workflow-delegate-failure-test` drives real parent and child statechart runs and observes the exact lower-runtime envelope on the parent attempt; no progression-recording change was needed.
- [x] Add an end-to-end delegated-step boundary test where the child's terminal attempt has an actionable execution error and assert the exact parent attempt envelope, failed statuses, nil accepted result, and selected identities.
  - Added narrow state-based proof in `psi.agent-session.workflow-delegate-failure-test`.
- [ ] Add delegated-step boundary tests for terminal-outcome-only failure, no actionable cause, non-actionable target after a recognized nested error, terminal/latest retry selection, and one-level nested delegated failure.
- [ ] Add regression assertions that successful, blocked, cancelled, removed, retry, and yield/result delegation semantics retain their current status and payload shapes.
- [ ] Run `clj-paren-repair`, the focused delegate boundary/runtime Scry tests, and relevant workflow-runtime regression namespaces; inspect structured results and make the slice green.

## Slice 3 — Facade terminal-error handoff

- [ ] Reuse the deterministic terminal step/attempt selector in `workflow_execution.clj` to read the selected parent attempt's exact persisted `:execution-error` before returning from execute or resume.
- [ ] Add private `:terminal-execution-error` to the facade result with nil when no selected attempt error exists, without changing terminal/blocked/status semantics.
- [ ] Add workflow-execution tests proving the handoff selects the terminal/latest applicable attempt rather than an earlier retry and preserves exact map identity/value.
- [ ] Add exact-shape assertions proving every public `:steps-executed` entry retains its existing keys and string `:error` projection and does not expose the envelope.
- [ ] Run `clj-paren-repair` and the focused workflow-execution Scry namespaces; inspect structured results and make the slice green.

## Slice 4 — Execute/resume projection through retention

- [ ] Update `run-failure-error` so a handed-off error with reason `:delegated-workflow-failed` supplies its canonical message before existing non-delegated step/terminal-outcome projection.
- [ ] Update execute and resume mutation paths to pass the private handoff into failure projection without re-reading child state, reselecting from `:steps-executed`, or reconstructing an envelope.
- [ ] Add execute mutation proof for actionable attempt failure, terminal-outcome-only failure, and exact fallback, asserting `:failed`, nil `:psi.workflow/result`, and the canonical message.
- [ ] Add execute mutation tests with completed-run retention zero proving the run is unavailable after cleanup while the handed-off delegated message still survives.
- [ ] Add resume mutation tests where a blocked run terminalizes at a delegate step, including superseded pre-resume/retry errors and retention zero; assert the canonical terminal message and absence of `:psi.workflow/result`.
- [ ] Preserve and run existing non-delegated terminal-outcome message tests to prove unrelated top-level failure wording is unchanged.
- [ ] Run `clj-paren-repair` and focused canonical-workflow execute/resume Scry namespaces; inspect structured results and make the slice green.

## Slice 5 — Registered tool and async surfaces

- [ ] Extend the registered synchronous delegate tool boundary test to execute a real failing delegated workflow and assert provider-facing text is exactly `Error: <canonical message>` with no success result synthesis.
- [ ] Add publication tests asserting async completion and background-job payload `:error` fields equal the canonical message unchanged.
- [ ] Add notification and append-entry tests asserting each existing context wrapper embeds the canonical message exactly once, before any optional result section, without re-sanitization or truncation.
- [ ] Verify asynchronous returned failure records preserve the same `:error` value and existing status/result behavior.
- [ ] Run `clj-paren-repair` and focused registered-tool, workflow orchestration/text, and async-path Scry namespaces; inspect structured results and make the slice green.

## Slice 6 — Regression, coherence, and user-facing record

- [ ] Add an `[Unreleased]` `Fixed` changelog entry describing actionable, safely redacted delegated-workflow failure messages.
- [ ] Grep the final envelope construction and projections to verify no exception data, operation details/results, provider/session/transcript fields, judge output, last-result text, or candidate payloads cross the child-parent boundary.
- [ ] Run `clj-kondo` on every changed Clojure source and test path and fix all findings rather than suppressing them.
- [ ] Run the focused workflow-runtime, workflow-execution, canonical mutation, registered delegate tool, and async projection Scry namespaces with CLI exit verification and inspect `.scry-results` on failure.
- [ ] Run the relevant broader workflow-runtime and agent-session unit suites and confirm successful, blocked, cancelled, removed, retry, resume, retention, async, and result regressions remain green.
- [ ] Re-read design.md, plan.md, changed tests/code, and user-facing changelog; verify all ten acceptance criteria, ownership boundaries, exact-map examples, and out-of-scope invariants are coherent.
- [ ] Record implementation decisions, test evidence, and any discovered trade-offs in `implementation.md`, then check completed items and prepare the task for implementation review.
