Goal: refactor `journal-append-in!` so journal append ownership converges on the canonical dispatch/effects model instead of relying on the current context callback seam.

Context:
- task `097-session-state-component-extraction-from-agent-session` extracted lower-level session state into `components/session-state/`
- during that extraction, `psi.session-state.state/journal-append-in!` was intentionally kept as a compatibility seam and made to delegate through `ctx :journal-append-fn`
- that avoided a dependency inversion from low-level `session-state` into higher-level `agent-session.persistence`
- however, it also preserved a non-canonical write path: journal append still happens through a direct callback rather than a dispatch-owned effect boundary
- the project architecture explicitly prefers writes and side-effects through dispatch/effects rather than direct stateful callbacks

Problem:
- `journal-append-in!` currently sits in an awkward middle state:
  - low-level callers treat it like a canonical session-state operation
  - but its implementation delegates into a higher-level callback in `ctx`
- this has several downsides:
  - the actual ownership of journal append is unclear
  - persistence and in-memory append semantics are split across callback wiring instead of explicit event/effect modeling
  - the write path is less introspectable and less replay-friendly than dispatch-owned effects
  - future extractions or persistence cleanup will keep inheriting this seam unless it is made explicit and canonical
- task `097` explicitly avoided broad persistence redesign; this task is the focused follow-on to resolve that remaining seam cleanly

Intent:
- make journal append follow the canonical dispatch/effects architecture
- separate pure in-memory journal state updates from higher-level persistence side-effects explicitly
- remove or reduce dependence on `ctx :journal-append-fn` as the authoritative append mechanism
- preserve behavior while clarifying ownership and dependency direction

In scope:
- identify the canonical write surface for session journal append
- decide whether journal append should be represented as:
  - a canonical dispatch event plus pure root-state update plus persistence effect, or
  - a closely related equivalent shape that still keeps state updates pure and persistence effectful
- move callers toward that canonical surface
- keep `session-state` below `agent-session` without reintroducing component cycles
- preserve existing journal semantics:
  - in-memory append
  - optional persistence to disk when enabled
  - compatibility with current session/runtime behavior
- add focused tests proving the new ownership and effect handling

Out of scope:
- redesigning the entire session persistence subsystem
- broad rewrite of all persistence entry constructors
- unrelated prompt lifecycle or turn lifecycle cleanup
- extracting additional components beyond what the journal append convergence requires

Design constraints:
- preserve dependency slope: lower-level `session-state` must not depend on higher-level `agent-session.persistence`
- prefer explicit pure update + effect separation over hidden callback indirection
- preserve replay/introspection friendliness or improve it
- avoid broad churn outside journal append ownership and its direct consumers
- do not regress current persistence semantics or session behavior

Key design question to settle in implementation:
- what is the authoritative append API after this task?
  - likely candidates:
    - dispatch event such as `:session/journal-append`
    - lower-level pure helper for journal vector/state mutation plus higher-level persistence effect
    - a narrow compatibility wrapper over the canonical dispatch-owned path during migration

Acceptance:
- journal append ownership is explicit and aligned with the dispatch/effects architecture
- direct callback ownership via `ctx :journal-append-fn` is removed or clearly demoted to a temporary compatibility seam rather than the canonical mechanism
- pure in-memory journal state updates are clearly separated from persistence side-effects
- no new component cycle is introduced
- representative callers use the new canonical append path
- focused tests prove both state update and persistence/effect behavior sufficiently
- task notes explain the chosen ownership boundary and any temporary compatibility left behind

Concrete done criteria:
- the authoritative journal append path no longer depends primarily on direct `ctx` callback wiring
- state mutation for journal append is pure or dispatch-owned in the canonical path
- persistence happens through an explicit higher-level effect boundary or equivalent explicit orchestration seam
- `session-state` remains below `agent-session` in dependency direction
- representative existing journal append callers still work through the new canonical path
- focused verification is green
