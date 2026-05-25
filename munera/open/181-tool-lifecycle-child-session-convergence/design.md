# 181 tool lifecycle and child-session convergence

## Intent

Migrate bootstrap, new/resume/fork, and workflow child-session shaping so tool inheritance and filtering run from the authoritative tool membership/selection field introduced in task 180, rather than from embedded `:tool-defs` payloads.

After this task, every session lifecycle surface that narrows or inherits tools does so by operating on membership/selection authority first, then re-materializing `:tool-defs` as a derived execution payload.

## Context

Follow-on B from `178-registry-session-membership-unification`.

Task 180 introduced the authoritative session field for tool membership/selection and aligned direct mutations so they update membership authority first and `:tool-defs` second. This task extends that authority model into the lifecycle and child-session shaping surfaces that were left operating on `:tool-defs` directly.

## Scope

Lifecycle surfaces to migrate:

- **bootstrap/default session construction** — seed tool membership/selection from canonical registry definitions; derive `:tool-defs` from that membership
- **new session** — inherit tool membership from defaults; derive `:tool-defs`
- **resume session** — restore tool membership from persisted state; re-derive `:tool-defs` if needed
- **fork session** — inherit parent tool membership; derive `:tool-defs`
- **child session creation** (`child_session_state.clj`) — inherit or narrow parent tool membership/selection per workflow step instructions; re-materialize `:tool-defs` from narrowed membership plus canonical registry lookup
- **workflow child-session shaping** — express tool narrowing through membership/selection vocabulary, not by filtering `:tool-defs` maps directly

## Desired outcome

- every lifecycle surface that shapes tool availability operates on the authoritative membership/selection field first
- `:tool-defs` is always re-derived from canonical registry definitions plus the authoritative membership/selection, never treated as input authority for narrowing decisions
- parent→child tool inheritance is expressed as membership inheritance/filtering, with `:tool-defs` rebuilt afterward
- focused tests prove parent/child tool selection semantics through the membership authority path

## Constraints

- preserve the existing `:tool-defs` execution payload — downstream consumers (provider request shaping, agent runtime tool installation, prompt assembly) still read it
- do not change the external behaviour of tool availability; only change the internal authority path
- keep resume backward-compatible with persisted sessions that predate tool membership fields

## Acceptance criteria

- bootstrap seeds tool membership/selection from canonical registry definitions
- new/resume/fork session lifecycle derives `:tool-defs` from membership/selection plus registry lookup
- child-session creation narrows tools by membership/selection, then re-materializes `:tool-defs`
- workflow child-session shaping uses membership/selection vocabulary for tool narrowing
- no lifecycle surface treats `:tool-defs` as the input authority for tool narrowing or inheritance
- focused tests cover parent→child tool selection and re-derivation semantics
- resume handles sessions persisted before tool membership fields exist (backward compatibility)
