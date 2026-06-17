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

### plan-review / follow-up (batch: ambiguity + inconsistency turns)

Executed all three items filed by the immediately-preceding plan-review batch
(commits `cffaffde2` ambiguity + `83f761341` inconsistency; baseline `8d7a36b3f`).
Slices 1–5 are still unimplemented, so these are plan-stage fixes to plan.md +
steps.md only (no code yet).

1. **DI-3 invocation-key mismatch (resolved).** Verified in source: judge path
   (`workflow_judge/execute-invoke-judge!`) passes `:parent-session-id` and **no
   `:session-id`**; direct path (`deterministic_operation_action/build-invocation`)
   passes `:session-id` (+ conditional `:parent-session-id`). Pinned DI-3/steps to
   resolve the session id as `(or parent-session-id session-id)` (prefer
   `:parent-session-id` — the production judge path). Added a required Slice-3
   judge-path test that drives the gate through the real `:invoke`-step judge
   invocation so the direct-invoke harness can't mask a `:session-id`-only read.

2. **DI-1 `:open-questions` shape (resolved).** Pinned to a single shape: each
   entry is the **trimmed concern substring** after the `marker` (`str/trim`),
   not the raw matched line. Updated plan.md Scanner-semantics notation
   `[<line…>]`→`[<concern…>]` and steps.md Slice-1 scanner logic.

3. **design.md D1 ↔ plan.md DI-5/R4 precedence contradiction (resolved via the
   reviewer's option b).** Changed the plan to *genuinely make the scope handback
   win when both conditions hold*: the scope gate is now inserted at `:steps`
   index 1 (immediately after `review-task-design`, **before**
   `check-design-review-status`), routing `DONE → check-design-review-status` and
   `SCOPE_QUESTION_OPEN → final-summary-scope-question-open`;
   `check-design-review-status` `:on` is left unchanged (no repoint). This aligns
   the plan's observable routing with design.md D1's authoritative, twice-stated
   governing decision ("the `SCOPE_QUESTION` handback wins"), eliminating the
   reviewer's flagged "plan says not the scope handback" contradiction.

   **Residual (human, design.md):** design.md D1 (and the "Relationship to task
   229" restatement) also phrase placement as "after `check-design-review-status`",
   which is jointly unsatisfiable with its own governing "scope handback wins"
   under linear routing (a non-converged run terminates at the 229 handback before
   ever reaching a gate placed after it). The follow-up is forbidden to edit
   design.md, so the plan implements the governing precedence decision and flags
   the placement phrasing for human reconciliation in design.md (subordinate
   wording vs governing decision). This is a design-authority wording fix, not a
   plan defect.
