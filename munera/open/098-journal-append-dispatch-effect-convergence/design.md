Goal: converge journal append ownership on the canonical dispatch/effects model and remove the `journal-append-in!` compatibility seam instead of relying on the current context callback wiring.

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
- remove dependence on `ctx :journal-append-fn` as the authoritative append mechanism
- preserve behavior while clarifying ownership and dependency direction

In scope:
- converge on one authoritative append surface: a dispatch-owned journal-append effect that carries a canonical journal entry and is executed by the higher-level `agent-session` layer
- use a lower-level pure helper in `session-state` for in-memory journal vector/root-state mutation
- keep optional persistence-to-disk as an explicit higher-level side-effect executed from the dispatch-owned append effect path
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

Chosen ownership boundary:
- the authoritative append API after this task is a dispatch-owned journal-append effect carrying a canonical journal entry
- the effect executor owns two responsibilities explicitly:
  - apply the pure in-memory journal append through a lower-level `session-state` helper or root-state update function
  - perform optional persistence side-effects using the existing higher-level persistence machinery when persistence is enabled
- existing typed append effects may remain temporarily if they are reduced to thin shapers over the canonical generic append-entry effect, but the generic append-entry effect is authoritative
- `psi.session-state.state/journal-append-in!` may remain temporarily only during migration; the compatibility seam must be removed by the end of the task and must not remain in production ownership

Minimum migration set required for acceptance:
- migrate the session-lifecycle initial journal writes to the canonical dispatch-owned append path
- migrate prompt-runtime assistant journal append to the canonical dispatch-owned append path
- migrate runtime raw user journal append helper to the canonical dispatch-owned append path
- migrate the extension `append-entry` mutation to the canonical dispatch-owned append path
- tests may continue to use narrow helpers where convenient, but production ownership must be proven through the canonical path in the representative surfaces above

Persistence semantics to preserve and prove:
- the canonical append path must always update the in-memory session journal
- when persistence is enabled, the canonical append path must continue to drive the existing on-disk append/write machinery immediately enough that current session persistence behavior is preserved
- this task does not need to redesign flush-state policy, but focused tests must prove that the canonical append effect reaches the existing persistence boundary rather than silently becoming memory-only

Acceptance:
- journal append ownership is explicit and aligned with the dispatch/effects architecture
- direct callback ownership via `ctx :journal-append-fn` is removed; any temporary migration seam must be eliminated before task closure
- pure in-memory journal state updates are clearly separated from persistence side-effects
- no new component cycle is introduced
- the minimum production caller migration set listed above uses the new canonical append path
- focused tests prove both state update and persistence/effect behavior sufficiently
- task notes explain the chosen ownership boundary and any temporary compatibility left behind

Focused verification required for sign-off:
- one focused proof for the pure in-memory append helper/root-state update behavior
- one focused proof that the canonical append effect appends into in-memory session state
- one focused proof that the canonical append effect reaches the existing persistence boundary when persistence is enabled
- one focused proof covering at least one migrated representative production path end-to-end enough to show it now relies on the canonical append effect rather than direct callback ownership

Concrete done criteria:
- the authoritative journal append path no longer depends primarily on direct `ctx` callback wiring
- state mutation for journal append is pure or dispatch-owned in the canonical path
- persistence happens through an explicit higher-level effect boundary or equivalent explicit orchestration seam
- `session-state` remains below `agent-session` in dependency direction
- the minimum representative production callers listed above still work through the new canonical path
- `psi.session-state.state/journal-append-in!` compatibility ownership seam is removed by the end of the task
- focused verification is green
