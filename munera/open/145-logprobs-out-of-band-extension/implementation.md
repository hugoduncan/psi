# Implementation Notes

## Design ambiguity review — pass 1 (2026-05-12)

Six ambiguities found and resolved:

1. **Duplicated logprob formatting in `step_execution.clj` not addressed.** → **Resolved**: remove the duplicate. `:transcript` output becomes assistant-message-only. Updated design.md "Conversation injection removal" and "What is removed from core"; updated steps.md with new step.

2. **`logprobs/perplexity` session-id sourcing in workflow invoke step.** → **Resolved**: add `:session-id` to session-step raw outputs in `execute-session-step!`. Value is `(:session-id execution-session)`, already available. Invoke step references `{:from {:step "run" :output :session-id}}`. Updated design.md with new "Session step `:session-id` output" section; updated steps.md and plan.md.

3. **`:assistant-message` structure in event payload.** → **Resolved**: explicitly stated as structured block-array form from execution-result (`{:role "assistant" :content [{:type :text :text "..."}]}`). Updated design.md turn-finished event section.

4. **`:reply-text` derivation in `logprobs/perplexity` output.** → **Resolved**: derived via `turn-execution-contract/assistant-message-text`. Updated design.md perplexity operation section and steps.md.

5. **Workflow `report` step variable bindings unspecified.** → **Resolved**: `report` step vars reference `run`'s `:final-llm-reply` for reply-text, and `perplexity`'s `:result` envelope via `:path` for perplexity/token-count. Updated design.md with explicit `report` step variable bindings section; updated steps.md.

6. **Extension namespace convention.** → **Resolved**: `extensions.logprobs` (majority convention). `psi.github.*` is the outlier. Updated design.md constraints and plan.md.

## Design inconsistency review — pass 1 (2026-05-12)

One inconsistency found:

1. **`prompt_request.clj` removal list omits `format-token-str` and `format-prob`.** → **Resolved**: added both private helpers to design.md "What is removed from core" and steps.md step 1. Now matches the completeness of the `step_execution.clj` enumeration.

## Implementation review — pass 1 (2026-05-12)

Three issues found:

1. **`:token-count` in `perplexity-result` counts all token maps, not tokens used in perplexity calculation.** `perplexity-result` reports `(count logprobs)` (all token maps in the stored vector). `calculate-perplexity` uses `(count (keep :logprob tokens))` — only tokens with non-nil `:logprob`. When tokens have nil logprobs, `:token-count` diverges from the N used in the formula. The test for nil-logprob tokens verifies perplexity correctly but doesn't catch this `:token-count` mismatch. Fix: use the filtered count in `perplexity-result`, or return both total and effective counts.

2. **Missing CHANGELOG entry.** This task has user-visible changes: removed synthetic logprob messages from conversation context, added `logprobs/perplexity` deterministic operation, updated `local-logprobs` workflow. Per project conventions (`λ changelog(δ)`), user-visible changes need a CHANGELOG entry under `[Unreleased]` before commit.

3. **Stale `mementum/state.md` line 104.** Still says `journal->provider-messages` projects `:logprobs` entries as synthetic user messages. This is now incorrect — logprobs entries are no longer projected. Working memory should be updated to reflect current behavior.

## Test review — pass 1 (2026-05-12)

Four gaps found against design behaviours and test-review skill criteria:

1. **No multi-session isolation test.** Extension stores per-session data in an atom keyed by session-id. No test verifies that storing/querying logprobs for session "s1" is independent of session "s2". Basic correctness property of the per-session storage design.

2. **No `calculate-perplexity` test for all-nil-logprob tokens.** When every token has `:logprob nil`, `(keep :logprob tokens)` yields empty seq, `n=0`, returns nil. This boundary between "tokens present but unusable" and "no tokens" is untested.

3. **No single-token perplexity test.** Only 2-token cases tested. Single token is a boundary: `perplexity = exp(-logprob)`. Verifies N=1 path.

4. **No test for `:session-id` as session-step raw output.** Design adds `:session-id` to `execute-session-step!` raw outputs (line 151 of `step_execution.clj`). The workflow depends on this surface (`{:from {:step "run" :output :session-id}}`), but `step_execution_test.clj` has no assertion for it. This is integration-level — the function requires full runtime context — so a lightweight assertion in an existing integration test or a note acknowledging the gap is appropriate.

## Test-shaper review — pass 1 (2026-05-12)

Two broken tests and one accuracy issue found.

1. **`prompt-finish-dispatches-extension-turn-finished-event-test` broken by event enrichment.** `prompt_lifecycle_test.clj:531` asserts the `session_turn_finished` payload is `{:session-id sid :turn-id "turn-1"}` but the enriched payload now includes `:assistant-message`. This test was not updated when `prompt-finish-base-result` was changed to carry `:assistant-message` in the event payload. The test fails on every run — this is a regression introduced by this task, not a pre-existing failure.

2. **`invoke-to-session-workflow-executes-and-exposes-cross-form-results-test` broken by raw output changes.** `workflow_invoke_runtime_test.clj:186` asserts the session-step `report-accepted` outputs contain only `:text`, `:final-llm-reply`, and `:transcript nil`. The new raw outputs include `:logprobs`, `:session-id`, and `:transcript` as `[assistant-message]` instead of nil. This test was not updated for the expanded output surface. Also a regression from this task.

3. **Steps.md verification step claims "9 pre-existing failures" but only 3 exist.** The verify step says "9 pre-existing failures (workflow-execution-test dynamic delegate), 0 new failures". Actual count: 3 `workflow-execution-test` dynamic-delegate failures (pre-existing) + 2 failures from this task = 5 total. The "0 new failures" claim is incorrect — the 2 failures above are directly caused by this task's changes.
