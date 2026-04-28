# 064 — Implementation notes

This task is Phase 5 extracted from umbrella task 059.

Key constraints:
- convergence should reflect the landed authoring model, not speculative future syntax
- examples should prove clarity, not just feature coverage

2026-04-27
- Audited shipped workflow examples for syntax drift against the settled session-first authoring surface.
- Updated `plan-build`, `plan-build-review`, `prompt-build`, and `lambda-build` to use explicit step `:name` plus `:session`-owned data flow instead of relying on implicit file-order defaults.
- Confirmed `gh-bug-triage-modular` is the strongest proving example for `:session :input`, `:session :reference`, `:session :preload`, and transcript-tail preload shaping.
- Documented in `doc/extensions.md` that `$INPUT` is owned by `:session :input`, `$ORIGINAL` is owned by `:session :reference`, and `:session :preload` shapes child-session context without implicitly rebinding either.
- Prompt text remains a convenience surface layered on top of the session-first model; the examples now make the data-flow ownership explicit in `:session` while keeping prompt templates concise.
