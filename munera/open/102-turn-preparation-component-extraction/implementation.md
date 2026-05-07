2026-05-07

Task created from follow-on extraction analysis after orienting on the current turn-extraction thread.

Initial design rationale:
- task `101` already extracted the impure live turn runtime seam
- the next residual `psi.turn` downward ownership blur is the pure preparation/recording layer currently living in `psi.agent-session.prompt-request` and `psi.agent-session.prompt-recording`
- extracted target chosen: `components/turn-preparation/` with authoritative namespaces `psi.turn-preparation.request` and `psi.turn-preparation.recording`

Refactoring-skill guardrails adopted:
- aim for a clean refactor
- compatibility shims allowed only temporarily and must be removed before completion
- tests should reflect the refactored code
- minimize the namespace dependency tree
- maximize orthogonality

`clj-surgeon` findings used in the design:
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_request.clj`
  - showed `build-prepared-request` as a coherent assembly root over request-shaping helpers
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_recording.clj`
  - showed `build-record-response` as a coherent assembly root over response-classification helpers
- `clj-surgeon -op :ls -file ...` on both files confirmed they are compact, self-contained namespaces
- `clj-surgeon -op :deps -file components/agent-session/src/psi/turn.clj`
  - confirmed `psi.turn` currently delegates directly to those two namespaces for `build-prepared-request` and `build-record-response`

Observed consumer surfaces from repo search at task creation time:
- `psi.turn` directly requires both old namespaces
- additional direct consumers exist in `prompt_loop.clj`, `prompt_turn.clj`, `workflow_statechart_runtime.clj`, tests, and other higher-level turn/session code
- task design now assumes completed task `101` as a fixed boundary: runtime extraction is treated as already done, and this task should only migrate the remaining pure preparation/recording ownership

Open note:
- this task was added to `munera/plan.md` backlog ahead of task `100` in ordering, but it is structurally a follow-on to the current turn extraction sequence rather than a replacement for `101`

Review pass for ambiguity/tightness:
- found one design ambiguity around consumer routing:
  - the design originally said no other production namespace should require `psi.turn-preparation.*` directly, while also listing lower-level direct consumers like `prompt_loop.clj`, `prompt_turn.clj`, and `workflow_statechart_runtime.clj`
  - tightened the task to make `psi.turn` the required public lifecycle boundary while still allowing minimal direct lower-level helper consumers when that is the cleaner dependency shape
- found one test-planning ambiguity around moving only “portions” of mixed-purpose higher-level test files
  - tightened the task to prefer moving whole focused files, and otherwise leave mixed higher-level files in place or add a small new component-owned focused test
- after those clarifications, and after explicitly treating task `101` as complete, the task is materially tighter and does not currently need a new follow-up artifact
