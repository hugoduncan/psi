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

- [x] Replace or supplement the fabricated `:resume-and-execute-workflow-run-fn` result in `resume-workflow-run-projects-terminal-delegated-error-through-retention-test` with a state-based test that resumes a genuinely blocked workflow through the real facade/delegate runtime, terminalizes it with a delegated failure, and proves terminal-attempt selection, retention-zero handoff, and the result-free resume response together. The current test substitutes the logic under review and cannot detect a broken resume-to-facade integration.
  - The real blocked→resume path exposed and corrected a facade defect: a fresh chart starts in `:pending`, so resume now updates canonical run state with `workflow-runtime/resume-run` before starting the fresh chart. The test retains a stale first-attempt envelope and proves the new terminal attempt wins through retention-zero cleanup.
- [x] Extend delegated publication coverage with an already-normalized 512-code-point canonical message and `:include-result? true`; assert notification and append-entry wrappers preserve the message byte-for-byte exactly once, do not truncate or re-normalize it, and place it before the optional result section. The current short-message/`false` case does not prove those specified boundaries.
  - Exact wrapper assertions prove the 512-code-point message remains unchanged and appears once; failed publication suppresses the optional result text, so the message necessarily precedes any result section.

## Test re-review follow-ups

- [x] Replace the fabricated `:execute-workflow-run-fn` result in `execute-workflow-run-projects-terminal-delegated-error-through-retention-test` with a state-based test that executes a real failing delegated workflow through the facade and mutation with completed-run retention zero. The current test substitutes the facade handoff and therefore cannot prove the execute path selects and carries the persisted terminal envelope before cleanup, as acceptance criterion 10 requires.
  - The test now drives a real parent/child statechart failure through the execute mutation and proves the selected canonical message survives retention-zero cleanup.
- [x] Remove the `with-redefs` stubs of `create-workflow-context` and `send-and-drain!` from the modified `resume-and-execute-run-reuses-existing-run-test`; exercise the real statechart path with nullable infrastructure or move the assertion to a pure state boundary. These are logic dependencies, so substituting them violates the task-test-review requirement that logic dependencies remain real and leaves the new resume sequencing weakly protected.
  - The focused facade test now runs the real statechart with nullable actor-turn infrastructure and proves a distinct resumed attempt succeeds after the retained blocked attempt.
- [x] Correct `delegated-failure-publication-preserves-bounded-message-before-result-test` so it actually exercises an append-entry containing the optional result section (for example, `:include-result? false` with non-nil result text), then assert the exact 512-code-point message appears unchanged once before `Result:`. With `:include-result? true`, the current production branch suppresses the result section and disables append-entry publication, making the ordering assertion vacuous.
  - The fixture now enables append-entry publication, asserts its exact heading and result section, and proves the unchanged bounded message occurs once before `Result:`.

## Final test review follow-ups

- [x] Add table-driven terminal-outcome metadata tests proving `:iteration-count` and `:max-iterations` are rendered only when both are integers in the inclusive `0` through `Long/MAX_VALUE` range and the reason is `:iteration-limit-reached`; cover zero, `Long/MAX_VALUE`, negative, over-range bigint, non-integer, one-missing-count, and a different safe reason. The current suite proves only the nominal `4 of 4` case, leaving the numeric allowlist and boundary behavior from acceptance criterion 6 unverified.
  - State-based cases now cover both missing-count directions in addition to all requested numeric and reason partitions; invalid metadata leaves only the safe terminal reason.
- [x] Add table-driven nested-envelope recognition tests that independently invalidate each required condition: outer reason, blank/over-512 message, non-map `:delegate-failure`, source, run id, and target. Assert that each case is sanitized as an ordinary untrusted execution error and contributes no `:nested-cause`. Existing malformed cases cover only invalid source and run id, so the complete recognition boundary in acceptance criterion 5 is not executable proof.
  - Every required recognition condition is independently invalidated; actionable messages remain ordinary sanitized execution errors, blank text falls back, and no malformed case contributes nested identity.
- [x] Extend `nested-envelope-recognition-boundary-test` with non-string (and preferably absent) `:run-id` and `:target` cases, asserting ordinary sanitized execution-error handling and no `:nested-cause`. The current blank-string cases prove nonblank validation but do not prove the required identity fields' string-type boundary.
  - Added non-string and absent cases for both required identity fields; each remains an ordinary sanitized execution error without `:nested-cause`.

## Latest test review follow-ups

- [x] Add table-driven deterministic-selection tests for invalid terminal identities: an unknown/invalid `:terminal-outcome :step-id` must fall through to a valid current step and then effective failed-step order, and a terminal `:attempt-id` absent from the selected step (including an id belonging only to another step) must select that step's latest ordered attempt. Assert the resulting exact envelope identities and cause so malformed terminal metadata cannot override the canonical terminal failure. Existing tests cover omitted IDs and valid explicit IDs, but not these specified invalid-identity fallthrough branches.
  - Exact-envelope cases prove unknown/non-string terminal steps fall through current/effective order, while unknown and cross-step attempt ids select the chosen step's latest attempt.

## Further test review follow-ups

- [x] Extend the invalid-identity selection matrix with invalid values that are present in persisted state: a non-string terminal/current step key that exists in `:step-runs` must be ignored in favor of a valid current/effective-order step, and a non-string terminal attempt id matching a historical attempt's non-string id must be ignored in favor of the selected step's latest ordered attempt. Assert the exact canonical envelope cause and valid location identity. The current invalid cases are absent from their maps/vectors, so they pass without proving that selection itself enforces the required nonblank-string identity boundary.
  - Persisted-state cases exposed and corrected selection before validation; terminal/current step and terminal attempt identities now must be nonblank strings before they can select matching persisted entities.

## Current test review follow-ups

- [x] Replace or supplement the fabricated facade results in `execute-workflow-run-projects-every-canonical-delegated-source-test` with state-based execute-mutation tests for the terminal-outcome and fallback sources. The real execute path currently proves only execution-error construction; injecting an already-complete `:terminal-execution-error` for the other two sources cannot detect a broken child normalization, parent persistence, facade selection, or retention-zero handoff for acceptance criteria 2, 3, and 10.
  - Real execute-mutation tests now drive iteration-exhausted and redact-only child workflows through child normalization, parent persistence, facade selection, and retention-zero projection.
- [x] Add table-driven delegated-failure tests where the selected attempt's `:execution-error :message` is non-string (and absent), with an actionable terminal outcome and without one. Assert terminal-outcome fallthrough in the former cases and exact fallback in the latter so the explicit non-string/ineligible execution-message boundary is executable proof rather than inferred from blank-string coverage.
  - Absent and non-string messages now prove terminal-outcome fallthrough and exact fallback while retaining selected location identity.

## Latest test re-review follow-ups

- [x] Replace the sampled `safe-reason-test` assertions with or supplement them by a table-driven exact grammar and length-boundary matrix for safe reason keywords. Prove that a 64-character body is accepted and a 65-character body is rejected; cover valid leading letters/digits, allowed interior `.`, `_`, and `-`, one optional namespace slash, and invalid empty components, leading punctuation, disallowed characters, and multiple slashes. Assert both `safe-reason?` and the resulting envelope reason/message behavior so acceptance criterion 6's public reason allowlist cannot regress while the helper alone remains green.
  - The exact matrix now asserts helper validity and complete terminal-outcome/fallback envelopes for all requested grammar partitions and both length boundaries.

## Current test re-review follow-ups

- [x] Add credential-pair sanitizer cases for unterminated single- and double-quoted values, including escaped trailing content, and assert the complete input remains unredacted. The design explicitly makes unterminated quoted values non-matches, but the current matrix covers empty and valid escaped quoted values only.
  - The sanitizer matrix now preserves complete unterminated single- and double-quoted inputs, including escaped quote content without a closing delimiter.
- [x] Strengthen the over-512 public-message test to assert the exact output equals the first 496 Unicode code points of the fully assembled message plus ` ... [truncated]`, including a supplementary-code-point boundary case. The current assertions check only total code-point count and suffix, so an incorrect retained-prefix length or content could still pass acceptance criterion 6's exact truncation rule.
  - The boundary proof now compares the exact assembled prefix plus supplementary-code-point content through code point 496 and the exact truncation marker.

## Newest test review follow-ups

- [x] Add a nested-envelope recognition boundary test with an exactly 512-code-point nonblank message and assert that it remains recognized and contributes the expected immediate `:nested-cause`. The current recognition matrix proves a 513-code-point message is rejected, while the separate 512-code-point public-message test does not exercise nested recognition, so the inclusive upper bound in acceptance criterion 5 is unproved.
  - The nested recognition boundary now proves an exactly 512-code-point message contributes the immediate grandchild run/target identity.
- [x] Add an exact-envelope execution-error test whose actionable message has an unsafe or missing outer `:reason` while the terminal outcome has a different safe reason. Assert that the source remains `:execution-error`, the envelope omits `:delegate-failure :reason`, and it does not borrow the terminal reason. Existing tests prove safe execution reasons and unsafe nested optional reasons, but not the source-specific no-fallback/no-merge rule for an ordinary selected execution error.
  - Exact envelopes now prove unsafe and absent execution reasons remain omitted while a different safe terminal reason is not borrowed.

## Latest task-test review follow-ups

- [x] Add exact-envelope public-message tests where the deterministically selected step id sanitizes to non-actionable placeholder-only text (for example, `/secret` or `token=secret`) and where no step is selected. Assert that the message omits the entire ` at step '<step>'` clause while the envelope independently retains the valid raw selected `:step-id`/`:attempt-id`. Existing tests separately exercise `actionable?` and actionable step prefixes, but do not prove the specified non-actionable/no-step composition branches.
  - Exact envelopes now prove path-only and credential-only selected step text is omitted from the public prefix while raw valid location identity remains, and no selected step produces neither location keys nor a step clause.
- [x] Replace or supplement `async-delegated-failure-return-record-preserves-canonical-message-test` with a state-based test that drives a real failed delegated workflow through the real `psi.workflow/execute-run` mutation and real `on-async-completion!` publication path, using only nullable infrastructure effects. The current test substitutes both `mutate!` with an already-projected error and `on-async-completion-fn` with a recorder, so it cannot detect a broken mutation-to-async completion/background-job/notification/append-entry integration and substitutes logic dependencies contrary to the task-test-review contract.
  - The async proof now executes a real parent/child statechart through the registered mutation, real completion publication, canonical background-job mutations, notification wrapper, and append-entry mutation; only actor-turn and UI infrastructure are nullable.

## Async integration test re-review follow-ups

- [x] Replace the `mutate!` wrapper/`appended*` call recorder in `async-delegated-failure-return-record-preserves-canonical-message-test` with an assertion on the resulting canonical session journal (via state or the real journal resolver). The wrapper is a spy on the append-entry logic boundary, so the current append-entry assertions verify mutation arguments rather than the persisted output and violate the state-based, no-interaction-assertion test contract.
  - The integration proof now reads the persisted `:custom-message` entry from canonical session journal state. This exposed and corrected the append mutation's internal dispatch origin, which had caused the permission interceptor to reject the journal update as an unknown extension.
- [x] Ensure `async-delegated-failure-return-record-preserves-canonical-message-test` always calls `context/shutdown-context!` for its created context in a `finally` block, including assertion-failure and timeout paths, so executor/runtime resources cannot leak into later tests or make the focused suite nondeterministic.
  - All context-dependent setup, execution, and assertions now run inside `try` with unconditional context shutdown in `finally`.

## Delegated retry test review follow-ups

- [x] Add a state-based delegated-step regression test with a real retry-enabled child or parent delegate attempt where the first attempt fails with one actionable error and the terminal retry fails with another. Assert the final parent attempt envelope, public mutation error, failed statuses, and attempt count so the terminal retry wins and existing retry semantics remain intact. The current pure selector and facade-handoff fixtures fabricate ordered attempts, while `terminal_contract_execution_test.clj` exercises successful delegation without retries; together they do not prove acceptance criteria 4 and 8 through the changed delegate normalization/progression path.
  - The mutation-level regression installs a valid two-attempt retry policy on the canonical parent delegate step, drives two real failed child runs through delegate normalization and progression recording, and proves the second envelope wins while both parent attempts and child runs remain failed.

## Documentation review follow-ups

- [x] Update `README.md` and `doc/workflows.md` to describe the user-visible delegated-failure behavior: failed child workflows now surface a bounded, safely redacted actionable message when available, retain the generic fallback otherwise, and do not change failed status or failure-result semantics. The changelog is currently the only user-facing documentation of this behavior.
- [x] Update the relevant `ramora/` workflow delegation documentation to record the implemented failure-diagnostic boundary: delegate failures expose the canonical sanitized message while arbitrary callee internals remain runtime/debug-only and successful delegated yield/handoff contracts remain unchanged.

## Documentation re-review follow-ups

- [x] Qualify the delegated-failure status claims in `README.md`, `doc/workflows.md`, and `ramora/workflow-ir/step-forms.md`: a failed child makes that parent delegate attempt execution-failed, but authored retry policy may supersede it and let the parent step/workflow succeed; the parent remains failed only when retries are exhausted. The current unconditional claims that the workflow, parent, or step "remains failed" contradict the preserved retry semantics.

## Code-shaper review follow-ups

- [x] Make `delegated-failure/redact-spans` locally linear or bound its raw input before scanning. The current per-code-point loop repeatedly calls `(subs text index)` for `str/starts-with?` and each regex recognizer, materializing and rescanning the remaining suffix several times; an unbounded child `:execution-error :message` can therefore consume superlinear CPU/allocation before the 512-code-point public-message bound is applied. Preserve the exact lexical precedence and redaction contract with the existing table-driven tests, and add a large-input regression that exercises the chosen bound or single-pass scanner shape.
  - Regex recognizers now use matcher regions and stack-prefix detection uses indexed `String.startsWith`, eliminating remaining-suffix allocation while preserving precedence. A 50,000-character prefix regression proves scanning reaches and redacts a trailing credential.

## Code-shaper re-review follow-ups

- [x] Eliminate or bound the remaining superlinear credential-candidate scan in `delegated-failure/redact-spans`. Matcher regions avoid suffix allocation but `credential-pattern` still greedily scans the full remaining suffix at every eligible position before failing; dot-only inputs reproduce roughly quadratic growth (about 0.4s/1.3s/5.1s for 5k/10k/20k characters). Make candidate recognition locally bounded/linear across adversarial inputs and replace or extend the large-input regression so it exercises this failing candidate shape rather than only a long letter prefix that left-boundary gating skips.
  - Replaced repeated anchored credential-regex scans with one-pass key-run caching and pre-indexed quoted-value closure. The large-input regression now includes a 50,000-dot adversarial prefix; direct timings scale approximately linearly (5k/10k/20k/50k: 0.10/0.12/0.20/0.50s).

## Latest code-shaper review follow-ups

- [x] Remove the eager whole-message quote indexing from `delegated-failure/redact-spans`. The scanner currently builds two input-sized `int-array` indexes plus two input-sized temporary `boolean-array` maps even when the message contains no quote or credential candidate, so the public 512-code-point bound still sits behind substantial unbounded allocation. Preserve amortized linear scanning without preprocessing unrelated input—for example, track quoted credential closure lazily with bounded/memoized forward state—and add a large unquoted-input regression that protects the no-credential path from input-sized quote indexing.
  - Replaced whole-input quote indexes with constant-size, demand-driven single/double quote cursors that advance monotonically only for quoted credential candidates. A 250,000-character unquoted no-credential regression protects the allocation-free quote path; existing candidate-heavy large-input cases retain linear scanning proof.

## Current code-shaper review follow-ups

- [x] Eliminate or bound the remaining superlinear path-candidate scan in `delegated-failure/redact-spans`. `path-span` calls `path-end-index` at every path-left delimiter before establishing that a supported path prefix or secret-bearing relative path can exist, so colon-only input repeatedly scans the full remaining suffix (approximately 0.4s/1.2s/4.2s/17.2s for 500/1,000/2,000/4,000 characters). Preserve the exact path-family and precedence contract while making adversarial delimiter-heavy input locally linear or bounded, and extend the large-input regression with this failing candidate shape.
  - Rejected delimiter-bounded runs now cache their end and exact sensitive suffix starts, so each run is scanned once while later candidates use constant-time prefix/suffix checks. A 50,000-colon regression protects the adversarial shape and confirms scanning still reaches the trailing credential.

## Path-cache code-shaper review follow-ups

- [x] Replace the input-sized `exact-sensitive-suffix-starts` persistent set in `delegated-failure/path-span-scanner` with constant-size or lazy state. A rejected run containing many colon-exposed `.ssh` or `id_rsa` suffixes (for example, repeated `:.ssh/` segments) allocates one set entry per suffix before the bounded public message is produced, although the left-to-right scanner can use only the earliest eligible suffix because that match consumes the remainder of the run. Preserve linear scanning and exact path semantics, and extend the large-input regression with this candidate-heavy rejected-run shape.
  - The scanner now caches only the first eligible exact-sensitive suffix index, because its match consumes the remaining run. A 10,000-segment `:.ssh/` regression protects constant-size cache state and exact earliest-match redaction.

## End-to-end allocation code-shaper review follow-ups

- [x] Bound end-to-end transient allocation while normalizing an unbounded child `:execution-error :message`. Although scanner indexes and caches are now constant-size, `remove-controls`, `redact-spans`, and `normalize-whitespace` still each materialize a full-size string before `bounded-message` enforces the 512-code-point public limit; a large message therefore requires several input-sized live allocations. Preserve the exact redaction, actionability, normalization, and truncation contract while retaining only bounded normalized output (plus constant-size scanning state), and add a large-input regression that asserts the bounded result without constructing an equally large expected output.
  - Redaction and whitespace normalization now share one full-input scan whose retained output is capped at 512 code points while actionability continues across discarded output. Control filtering preserves the original input without allocation when no removable control is present. Large-input proof asserts only bounded output properties, including late actionable text, without constructing full-size expectations.

## Final code-shaper review follow-ups

- [x] Complete the end-to-end allocation bound by making scanner matches index-based and folding control removal into the bounded streaming pass. The current implementation still copies an unbounded matched credential/path/token/stack span via `subs`, `str/split`, or regex `.group`, and `remove-controls` materializes an input-sized filtered string whenever any removable control occurs; a single leading control or a message consisting of one huge sensitive span therefore defeats the claimed bounded retained output plus constant-size scanning state. Preserve lexical precedence and actionability while returning match end indexes rather than span strings, and add large-input regressions for a removable-control input and at least one whole-input sensitive span.
  - Span recognizers now return raw-input end indexes and inspect credential keys, tokens, and path segments by range; removable controls are discarded in the bounded output pass. Large-input regressions cover a leading removable control and a whole-input 250,000-character credential value.

## Post-allocation code-shaper review follow-ups

- [x] Restore the specified remove-controls-before-redaction semantics without reintroducing unbounded copies. `sanitized-component` now skips removable controls only while emitting unmatched code points, but every span recognizer still scans the raw text, so a control can split a sensitive span that step 1 should join before step 2 (for example, `token\u0000=secret denied` becomes `token=secret denied` instead of `[REDACTED] denied`). Make lexical recognition treat removable controls as absent while retaining bounded output/constant-size state, and add focused cases for controls inside credential keys/separators/values and other affected span families.
  - Span recognizers now traverse raw offsets through a virtual control-stripped view, preserving bounded output and constant-size scanner state. Focused proof covers controls inside credential keys/separators/quoted and unquoted values, stack frames, bearer/prefixed tokens, and absolute/secret-relative paths.

## Control-order code-shaper re-review follow-ups

- [x] Make literal-placeholder recognition operate on the same virtual control-stripped view as sensitive-span recognition, without reintroducing unbounded allocation. A removable control inside an existing placeholder (for example, `[REDAC\u0000TED]`) currently yields sanitized text `[REDACTED]` but marks the component actionable because the raw placeholder is emitted in fragments; placeholder-only targets and execution-error causes can therefore bypass the specified fallback/actionability rule. Add exact sanitizer and envelope tests for control-split placeholders in target, step, and cause positions.
  - Literal placeholders now use the existing virtual-view matcher and its raw end offset. Exact sanitizer and envelope tests prove control-split placeholders remain non-actionable in cause, target, and step positions.

## Actionability consistency code-shaper follow-ups

- [x] Remove the competing public `delegated-failure/actionable?` implementation or make the sanitizer's scanner-derived actionability the singular locally comprehensible API. Production decisions use `sanitized-component`'s control-aware, redaction-aware flag, while `actionable?` independently strips only literal placeholders and therefore reports raw sensitive or control-split inputs such as `token=secret`, `/secret`, and `[REDAC\u0000TED]` as actionable. Consolidate the implementations and update tests to exercise the same actionability path production uses, so future callers cannot silently select different semantics.
  - `actionable?` now delegates to the scanner-derived `sanitized-component` result; focused cases prove raw credentials, paths, and control-split placeholders use the same production semantics.

## Current task-test review follow-ups

- [x] Restore bounded execution of `sanitize-component-large-input-test`. The focused `psi.workflow-runtime.delegated-failure-sanitize-test` namespace did not complete within 300 seconds, and the isolated large-input var remained CPU-bound on the first 250,000-character plain input; a thread dump located the scan in `first-exact-sensitive-suffix-start` via `path-span-scanner`. Fix the scanner so this no-path input is locally linear as required by the completed allocation/complexity follow-ups, retain a practical adversarial regression, and verify the focused namespace completes.
  - Separator-free remainders now bypass detailed path scanning and are cached once; exact `.ssh`/`id_rsa` suffix discovery handles every eligible left-delimited segment. Practical adversarial fixtures retain all prior input shapes. The isolated var completes in 5.79 seconds (14 assertions), and the focused namespace completes in 5.83 seconds (5 tests / 92 assertions).

## Latest task-test review follow-ups

- [x] Add an adversarial large-input regression with many delimiter-started separator-free runs followed by an unrelated late slash (for example, repeated `x ` plus `/tail`), then make `path-span-scanner` locally linear or otherwise practically bounded for that shape. `path-separator-at-or-after?` currently sees the late slash from every earlier run, defeating the separator-free fast path and repeatedly rescanning suffixes; direct timings grew from about 0.31 seconds at 2,000 runs to 1.30 seconds at 16,000 runs. Assert the bounded sanitized result and verify the focused namespace completes within a practical duration.
  - Slash and backslash lookahead now advance independently and monotonically, so each separator kind is searched only after its cached position is passed. The 16,000-run late-slash regression asserts the exact bounded prefix; the focused namespace completes in 8.13 seconds (5 tests / 94 assertions).

## Current task-test re-review follow-ups

- [ ] Strengthen the late-separator performance regression so it detects the quadratic implementation it was added to prevent, rather than asserting only an output that both implementations produce. Cover both a late `/` and a late literal backslash because `path-separator-scanner` maintains independent lookahead for each family, and use a practical bounded-execution proof (or another non-interaction, deterministic complexity guard) that fails when either separator is repeatedly rescanned. The current 16,000-run late-slash case took about 1.30 seconds before the fix and has no runtime bound, so it would remain green if the defect returned and does not protect the symmetric backslash branch.
