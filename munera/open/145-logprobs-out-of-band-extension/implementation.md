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
