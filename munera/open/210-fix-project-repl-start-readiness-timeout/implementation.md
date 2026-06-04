# Implementation

## Design review — architectural fit (ψ)

Scope: design.md only; fit vs AGENTS.md / META.md / doc/architecture.md +
doc/project-nrepl.md. Not ambiguity/clarity/correctness.

Good fit:
- Q1 config key under `[:agent-session :project-nrepl]` with system<user<project
  precedence matches doc/project-nrepl.md precedence and existing
  `resolved-attach-endpoint` range-validation idiom.
- Subprocess launch + `.nrepl-port` polling correctly stay in the runtime handle
  (project nREPL registry) per the `:state*` boundary: runtime handles own
  side-effecting I/O; runtime-owned subprocess execution is the documented
  current state (Layer Map / Frontier), so staying outside dispatch effects is
  consistent — no misfit there.
- Uses existing `process-launcher` / `nrepl-connector` seams, no `with-redefs`.

Actionable architectural-fit gaps:
1. Stale-port guard placement. Design lets the guard touch the *shared*
   discovery helper while "not changing attach semantics". Started-mode
   acquisition policy (launch-time/mtime gate) should live in the started-mode
   layer, keeping the shared discovery primitive orthogonal (single
   responsibility) and attach-mode's documented `.nrepl-port` fallback intact.
2. Observable status projection. New observable outcomes (stale-port rejection
   diagnostics; effective configured timeout) are status; META principle =
   project observable status into `:state*` via dispatch as canonical data
   rather than surfacing only as ad-hoc op-return payload. Design should state
   whether/how the stale-port-rejection outcome is projected (the registry
   already projects `readiness`/`:last-error`).
