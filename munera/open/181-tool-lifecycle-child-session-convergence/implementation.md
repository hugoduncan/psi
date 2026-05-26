# 181 implementation notes

## Ambiguity review — 2026-05-25

### Finding: task scope substantially overlaps with already-completed work in tasks 179 and 180

Task 181 describes itself as "Follow-on B from 178" and lists six lifecycle surfaces to migrate. Inspection of the codebase after tasks 179 (closed) and 180 (closed) shows that five of the six are already completed:

1. **bootstrap/default session construction** — `init.clj` already copies `:tool-ids` via `select-keys` for new/resume/fork; `:tool-defs` is removed from session schema and `initial-session`.
2. **new session** — `initialize-new-session-state` already includes `:tool-ids` in its `select-keys` baseline.
3. **resume session** — `initialize-resumed-session-state` already includes `:tool-ids` in its `select-keys` baseline; `session_lifecycle.clj` derives `resolved-tool-defs` via `resolve-tool-defs` from `:tool-ids`.
4. **fork session** — `initialize-forked-session-state` already includes `:tool-ids`; `session_lifecycle.clj` derives `resolved-tool-defs` from `:tool-ids`.
5. **child session creation** — `child_session_state.clj` already accepts `:tool-ids`, derives tool-defs from registry lookup via `resolve-tool-defs`; dispatch event `:session/create-child` uses `:tool-ids`; `child_session_contract.clj` schema uses `[:tool-ids ...]`.

The **only remaining seam** where `:tool-defs` maps flow as an intermediate is the internal workflow step-config pipeline:
- `workflow_step_session_config/core.clj` outputs `:tool-defs` (resolved maps) in step-config
- `statechart_runtime.clj:90` passes `:tool-defs (:tool-defs step-config)`
- `attempts.clj:59,70` destructures `:tool-defs` and converts to `:tool-ids (when tool-defs (mapv :name tool-defs))` at the contract boundary

This was **explicitly documented** in task 180's design as intentional: "Step-config continues to output `:tool-defs` (derived maps) — this is a local data structure, not session state."

### Ambiguity 1: Is this task still needed, or should it be closed/rescoped?

The design states the intent is to migrate lifecycle surfaces so they "run from the authoritative tool membership/selection field." Five of six surfaces already do. The remaining workflow step-config pipeline is a local data-passing concern (step-config → statechart → attempts), not a session authority violation — the `:tool-defs` there are derived on-demand from `:tool-ids` + registry and never persisted to session state.

The design should either:
- (a) be rescoped to the narrow remaining workflow step-config internal pipeline cleanup (changing step-config to output `:tool-ids` and having `attempts.clj` derive tool-defs itself), or
- (b) be closed as already satisfied by 179+180, with the step-config internal pipeline left as a documented intentional local data structure.

### Ambiguity 2: "backward compatibility for resume with pre-tool-membership sessions"

AC 7 says "resume handles sessions persisted before tool membership fields exist." The design does not specify the backward-compatibility mechanism. In the current code, `initialize-resumed-session-state` copies `:tool-ids` from `current-sd` (the in-memory session defaults), not from the persisted journal. If `current-sd` has `:tool-ids []` (from `initial-session`), resume of a pre-`:tool-ids` session will get empty tool-ids and then `session_lifecycle.clj` will call `resolve-tool-defs` with `[]`, yielding no tools — which is then corrected by the subsequent `ensure-base-system-prompt` / `retarget-runtime-prompt-metadata` / bootstrap dispatch sequence that runs after resume. But the design should clarify whether this implicit bootstrap-after-resume re-population is the intended backward-compatibility path or whether explicit migration logic is needed.

### Ambiguity 3: "focused tests prove parent/child tool selection semantics through the membership authority path"

The design lists this as a desired outcome and AC 6, but does not specify what test scenarios are needed beyond what already exists. Task 180 already added focused tests for `resolve-tool-defs` and migrated child-session mutation tests. The design should clarify what additional test coverage is expected, or acknowledge that existing coverage from 179/180 satisfies this criterion.
