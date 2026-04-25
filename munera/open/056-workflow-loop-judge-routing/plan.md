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
- `judge-schema` — `{:prompt string :system-prompt string? :projection projection-schema}`
- `routing-directive-schema` — `{:goto (:next | :previous | :done | string) :max-iterations pos-int?}`
- `routing-table-schema` — `{string routing-directive-schema}`
- Extend `workflow-step-definition-schema` with optional `:judge` and `:on`
- Extend `workflow-step-run-schema` with `:iteration-count`
- Extend `workflow-step-attempt-schema` with `:judge-session-id`, `:judge-output`, `:judge-event`

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
- `resolve-goto-target` — given a directive's `:goto` value, the current step-id, and the step-order vector, return `{:action :goto :target step-id}` or `{:action :complete}` or `{:action :fail :reason ...}`. The `:done` keyword and `:next` from the last step both return `{:action :complete}`. `:next`, `:previous`, and string step-ids return `{:action :goto :target concrete-step-id}`. `:previous` on the first step returns `{:action :fail :reason :no-previous-step}`.
- `check-iteration-limit` — given the **target** step-run's `:iteration-count` and the directive's `:max-iterations`, return `:within-limit` or `:exhausted`.
- `evaluate-routing` — signature: `(evaluate-routing signal routing-table current-step-id step-order step-runs)`. Compose the above: match signal → resolve target → look up target step-run's iteration count → check limit → return `{:action :goto :target step-id}` or `{:action :complete}` or `{:action :fail :reason ...}` or `{:action :no-match}`. The `:no-match` action is consumed only by `execute-judge!` (slice 4) for its retry loop — it never reaches the progression layer.

Test: each function individually, plus `evaluate-routing` integration — match, no-match, `:next`/`:previous`/`:done`/named, `:next` from last step (= complete), `:previous` on first step (= fail), within-limit, exhausted.

### Slice 4 — Judge session execution

Impure functions in `workflow_judge.clj`:
- `execute-judge!` — given ctx, parent-session-id, actor-session-id, judge-spec, and routing-table:
  1. Read actor session messages via `prompt-control/messages-from-entries-in` or equivalent
  2. Apply `project-messages` with the judge's projection spec
  3. Create judge child session with projected messages as preloaded context, optional `:system-prompt` from judge-spec, and **empty tool-defs** (no tools)
  4. Prompt judge with judge `:prompt` (user-turn message) via `prompt-in!`
  5. Extract judge output text (last assistant message, trimmed)
  6. Match against routing table via `evaluate-routing` (passing step-runs for iteration count lookup)
  7. On no-match: send a new user message to the **same** judge session via `prompt-in!` with feedback ("Your response 'XYZ' did not match any expected signal. Expected exactly one of: ..."). The session is idle after each response so this is safe. Retry up to 2 times (3 total attempts).
  8. Return `{:judge-session-id :judge-output :judge-event :routing-result}`

Test: with-redefs on session creation and prompting — successful match, no-match with retry then match, no-match exhaustion.

### Slice 5 — Progression: iteration tracking and routing

Extend `workflow_progression.clj`:
- `record-actor-result` — new helper that writes the `:ok` envelope and `:accepted-result` onto the step-run **without** touching `current-step-id` or run status. Extracted from the recording part of `submit-result-envelope`. Used by slice 6 for judged steps.
- `increment-iteration-count` — bump `:iteration-count` on a step-run on every entry (including first). Starts at 0.
- Add a new `submit-judged-result` progression path (does **not** modify `submit-result-envelope`) that:
  - Records the judge result (`:judge-session-id`, `:judge-output`, `:judge-event`) on the **attempt**
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
  - If yes: call `record-actor-result` (writes envelope + accepted-result on step-run without advancing). Then call `execute-judge!`, then call `submit-judged-result` (records judge fields on attempt, applies routing). The judge routing **replaces** the normal `submit-result-envelope` advancement.
  - If no: existing path — call `submit-result-envelope` (advances to next step)
- The `execute-run!` loop continues to work — it just sees different `current-step-id` values when gotos fire
- The loop's terminal/blocked status checks are unchanged

Test: `execute-current-step!` with judged step — actor completes, judge runs, routing fires. `execute-run!` with a loop — plan→build→review where review loops back to build once then approves.

### Slice 7 — Compiler: thread judge and routing from file format

Extend `workflow_file_compiler.clj`:
- `compile-multi-step`: when a step config has `:judge`, thread it into the canonical step definition
- `compile-multi-step`: when a step config has `:on`, resolve `:goto` workflow names to compiled step-ids (using the same workflow-name→step-id mapping built during compilation), then thread the resolved `:on` into the canonical step definition
- Validate: `:on` without `:judge` is a compilation error
- Validate resolved `:goto` targets reference known compiled step-ids (extend `validate-step-references`)
- `:goto :next`, `:goto :previous`, `:goto :done` are keywords and pass through without resolution

Test: compile a workflow file with `:judge` and `:on`, verify `:goto` workflow names resolved to step-ids. Compile without — unchanged. Invalid goto target — validation error. `:on` without `:judge` — validation error.

### Slice 8 — End-to-end integration and backward compatibility

- Wire a `plan-build-review` style workflow definition with a judge on the review step
- Execute end-to-end: planner → builder → reviewer → judge says REVISE → builder (loop) → reviewer → judge says APPROVED → completed
- Verify iteration counts, judge results, step-run state at each stage
- Run the full existing test suite — zero regressions

## Decisions captured in plan

- Iteration counts are on `step-run`, not on the routing directive or the run. The limit (`:max-iterations`) is declared on the directive, but the counter is shared per-step. Count starts at 0, incremented on every entry including first. `:max-iterations 3` = at most 3 total entries.
- Judge fields (`:judge-session-id`, `:judge-output`, `:judge-event`) are on the **attempt**, not the step-run. Consistent with existing `:execution-error` placement.
- Judge retry feedback is injected into the **same** judge session via `prompt-in!` (multi-turn). Not a new session. Session is idle after each response so this is safe.
- Judge session gets **no tools** — empty tool-defs explicitly.
- `:on` **requires** `:judge` — `:on` without `:judge` is a validation error at compilation.
- `:goto :previous` on first step returns `{:action :fail :reason :no-previous-step}`.
- `evaluate-routing` takes the full `step-runs` map to look up the **target** step's iteration count.
- `record-actor-result` is a new helper in `workflow_progression.clj` — writes envelope + accepted-result without advancing. Used for judged steps instead of `submit-result-envelope`.
- The `execute-run!` loop is unchanged — it already handles arbitrary `current-step-id` progression.
- For judged steps, the judge routing **replaces** the normal `submit-result-envelope` call — the actor result is recorded via `record-actor-result`, and `current-step-id` is set by the routing directive, not by `next-step-id-fn`.
- No new statechart events are strictly required for Phase B (status tracking suffices), but adding `:verdict/advance`, `:verdict/goto`, `:verdict/exhausted` to the event catalog as **history markers** for observability is low-cost and done in Slice 5. Phase A will promote these to actual statechart transition events.
- In the file format, `:goto` values use **workflow names** (e.g. `"builder"`). The compiler resolves these to compiled step-ids (e.g. `"step-2-builder"`). Keywords (`:next`, `:previous`, `:done`) pass through without resolution.
- Judge `:prompt` is the user-turn message (the decision question). Judge `:system-prompt` is optional and sets the judge session's system prompt.
