# 056 — Plan (Phase B: Progression-layer extension)

## Approach

Bottom-up, test-first. Each slice adds one concept with focused tests, building toward the end-to-end loop. The imperative `execute-run!` loop is preserved and extended — Phase A (statechart-driven execution) follows as a separate task.

The dependency order is: schemas → pure functions → impure execution → compiler → integration.

## Risks

- **Judge signal extraction**: the judge is an LLM — it may produce surrounding text around the signal. Exact-match-after-trim is the decision, but tests should prove the retry-with-feedback path handles common failure modes.
- **Iteration count placement**: per-step counts live on the step-run in workflow-run state. Must not collide with the existing attempt-count (which is for failure retries). These are distinct concepts.
- **Projection message extraction**: depends on the shape of persisted session messages. Must handle the existing content block format (`:type :text`, `:type :tool_use`, `:type :tool_result`).

## Slice order

### Slice 1 — Model schemas

Add to `workflow_model.clj`:
- `projection-schema` — `:none | :full | {:type :tail :turns N :tool-output bool}`
- `judge-schema` — `{:prompt string :projection projection-schema}`
- `routing-directive-schema` — `{:goto (:next | :previous | :done | string) :max-iterations pos-int?}`
- `routing-table-schema` — `{string routing-directive-schema}`
- Extend `workflow-step-definition-schema` with optional `:judge` and `:on`
- Extend `workflow-step-run-schema` with `:judge-session-id`, `:judge-output`, `:judge-event`, `:iteration-count`

Test: schema validation for valid/invalid judge, projection, routing table, step definitions with and without judge.

No behavioral change — existing tests must pass unmodified.

### Slice 2 — Projection extraction

New namespace `workflow_judge.clj`. Pure functions only in this slice:
- `project-messages` — given a message sequence and a projection spec, return projected messages
  - `:none` → empty
  - `:full` → all messages
  - `{:type :tail :turns N}` → last N user+assistant turn pairs
  - `{:type :tail :turns N :tool-output false}` → same, with tool_use/tool_result content blocks stripped
- Turn counting: a "turn" is a user message followed by an assistant message. Tool messages between them are part of the same turn.

Test: projection of realistic message sequences — full history, tail-1, tail-3, tool-output stripping, empty history, `:none`, `:full`.

### Slice 3 — Routing evaluation

Pure functions in `workflow_judge.clj`:
- `match-signal` — given a signal string and a routing table, return the matched directive or nil. Exact match after `str/trim`.
- `resolve-goto-target` — given a directive's `:goto` value, the current step-id, and the step-order vector, return the concrete target step-id. Handles `:next`, `:previous`, `:done`, and string step-id.
- `check-iteration-limit` — given a step-run's `:iteration-count` and the directive's `:max-iterations`, return `:within-limit` or `:exhausted`.
- `evaluate-routing` — compose the above: match signal → resolve target → check limit → return `{:action :goto :target step-id}` or `{:action :complete}` or `{:action :fail :reason ...}` or `{:action :no-match}`.

Test: each function individually, plus `evaluate-routing` integration — match, no-match, `:next`/`:previous`/`:done`/named, within-limit, exhausted.

### Slice 4 — Judge session execution

Impure functions in `workflow_judge.clj`:
- `execute-judge!` — given ctx, parent-session-id, actor-session-id, judge-spec, and routing-table:
  1. Read actor session messages via `prompt-control/messages-from-entries-in` or equivalent
  2. Apply `project-messages` with the judge's projection spec
  3. Create judge child session with projected messages as preloaded context
  4. Prompt judge with judge `:prompt`
  5. Extract judge output text (last assistant message, trimmed)
  6. Match against routing table via `match-signal`
  7. On no-match: inject feedback ("Your response did not match any expected signal. Expected one of: ..."), continue judge session, retry (up to 2 retries)
  8. Return `{:judge-session-id :judge-output :judge-event :routing-result}`

Test: with-redefs on session creation and prompting — successful match, no-match with retry then match, no-match exhaustion.

### Slice 5 — Progression: iteration tracking and routing

Extend `workflow_progression.clj`:
- `increment-iteration-count` — bump `:iteration-count` on a step-run when entering a step via goto
- Modify `submit-result-envelope` or add a new `submit-judged-result` path that:
  - Records the judge result on the step-run
  - Applies the routing result to determine the next step
  - On `:goto` to a named step: set `current-step-id`, increment target step's iteration count
  - On `:complete`: transition to `:completed`
  - On `:fail` (exhausted iterations or judge failure): transition to `:failed`
- The existing `:ok` → advance-to-next-step path remains unchanged for steps without a judge

Test: pure state transitions — judged step with goto, with advance, with done, with iteration exhaustion, without judge (unchanged).

### Slice 6 — Execution: wire judge into step execution

Extend `workflow_execution.clj`:
- After actor step completes with `:ok` envelope:
  - Check if step definition has `:judge`
  - If yes: call `execute-judge!`, then apply routing result via progression
  - If no: existing path (advance to next step)
- The `execute-run!` loop continues to work — it just sees different `current-step-id` values when gotos fire
- The loop's terminal/blocked status checks are unchanged

Test: `execute-current-step!` with judged step — actor completes, judge runs, routing fires. `execute-run!` with a loop — plan→build→review where review loops back to build once then approves.

### Slice 7 — Compiler: thread judge and routing from file format

Extend `workflow_file_compiler.clj`:
- `compile-multi-step`: when a step config has `:judge`, thread it into the canonical step definition
- `compile-multi-step`: when a step config has `:on`, thread it into the canonical step definition
- Validate `:goto` targets in `:on` directives reference known step ids (extend `validate-step-references`)

Test: compile a workflow file with `:judge` and `:on`, verify canonical definition shape. Compile without — unchanged. Invalid goto target — validation error.

### Slice 8 — End-to-end integration and backward compatibility

- Wire a `plan-build-review` style workflow definition with a judge on the review step
- Execute end-to-end: planner → builder → reviewer → judge says REVISE → builder (loop) → reviewer → judge says APPROVED → completed
- Verify iteration counts, judge results, step-run state at each stage
- Run the full existing test suite — zero regressions

## Decisions captured in plan

- Iteration counts are on `step-run`, not on the routing directive or the run
- Judge retry feedback is injected into the existing judge session (continue it), not a new session
- The `execute-run!` loop is unchanged — it already handles arbitrary `current-step-id` progression
- No new statechart events are strictly required for Phase B (status tracking suffices), but adding `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` to the event catalog for history/observability is low-cost and done in Slice 5
