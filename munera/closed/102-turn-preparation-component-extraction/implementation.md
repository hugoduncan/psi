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

Review note — proposed extraction still depends materially on `agent-session`:
- `psi.agent-session.prompt-recording` remains a strong extraction candidate: it is small and depends only on `psi.session-state.state`
- `psi.agent-session.prompt-request` is not yet cleanly below `agent-session`; moving it as-is to `components/turn-preparation/` would still leave the extracted namespace depending directly on:
  - `psi.agent-session.conversation`
  - `psi.agent-session.prompt-templates`
  - `psi.agent-session.provider-auth`
  - `psi.agent-session.skills`
  - `psi.agent-session.system-prompt`
- those dependencies fan out further:
  - `conversation` depends on `system-prompt` and `tool-defs`
  - `system-prompt` depends on `skills`
  - `skills` depends on `prompt-templates`
  - `provider-auth` depends on `oauth.core`
- so the first-cut extracted request namespace would still have a downward dependency slope into a substantial `psi.agent-session.*` cluster
- this means the current design overstates how fully “below `agent-session`” the request-preparation layer would become; for the request half, the current task is closer to relocating the assembly-root namespace than to completing a clean lower-layer ownership extraction

Revision decision:
- rather than requiring auth, skill, template, and prompt component extraction first, the smaller architecture change is to split current request preparation into:
  - a session-owned projection/resolution step that stays under `agent-session`
  - a lower pure prepared-request assembly step that moves to `components/turn-preparation/`
- chosen first-cut boundary:
  - new `psi.agent-session.turn-preparation-inputs` owns `session->prepared-request-inputs`
  - new `psi.turn-preparation.request` owns lower pure `build-prepared-request` from normalized inputs
  - new `psi.turn-preparation.recording` owns response classification / record-response shaping
- this keeps auth resolution, skill/template expansion, prompt-asset selection/filtering, and other session-owned policy above the extracted lower layer for now
- this makes task `102` an honest intermediate boundary improvement:
  - lower pure request assembly moves below `agent-session`
  - session-owned projection remains explicit rather than being silently dragged down into the new component
- follow-on component extraction for prompt/auth/expansion/conversation remains optional future work rather than a prerequisite for this slice

Supersession note:
- this task is now superseded by `105-agent-session-component-extraction-map`
- reason: the turn-preparation extraction target proved too narrow and structurally premature when considered outside the broader component map just above `session-state`
- follow-on work should proceed from the umbrella component map, especially the prompt-composition and turn component candidates, rather than continuing this task in isolation
