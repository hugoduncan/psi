# 230 — Implementation notes

Append-only local memory: decisions, discoveries, review notes.

## Reviews

### plan-review / ambiguity (turn 1)

Two actionable ambiguities filed to `design-steps.md`:

1. DI-3 invocation-key mismatch: gate runs as the `:invoke`-step **judge**, whose
   invocation supplies `:parent-session-id` (no `:session-id`); DI-3 reads
   `:session-id` → nil session on the production path → gate silently fails-open.
   Direct-invoke test harness masks it (provides `:session-id`).
2. DI-1 `:open-questions` detail content underspecified (full line vs post-marker
   concern substring).

Verified against runtime: `known-pass-status->route` (REVIEW_COMPLETE→DONE,
ACTIONABLE_FEEDBACK→REPEAT) confirms the DI-5 design-gate-vs-scope-gate
precedence reasoning is sound; `execute-invoke-judge!` and `build-invocation`
confirm the two distinct invocation-map key sets.

### plan-review / inconsistency (turn 2)

One actionable inconsistency filed to `design-steps.md`: design.md D1 (lines
111/124) states the `SCOPE_QUESTION` handback **wins** on a both-non-converged-
and-open run, while plan.md DI-5/R4 (line 273) decides the 229 design handback
wins (scope handback does not fire) and re-interprets D1 as intent-only —
authority (design) vs plan disagree on observable routing.
