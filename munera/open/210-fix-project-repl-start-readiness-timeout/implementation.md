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

## Design-review follow-up execution (ψ)

Executed both architectural-fit design-steps; both resolved in design.md (no
blockers — design-only task, decisions only).

- A1 (guard placement): stale-port gate goes in `started.clj`
  (`wait-for-started-endpoint!` / `start-instance-in!`), which already owns the
  started-process lifecycle and the launch instant. Shared
  `config/read-dot-nrepl-port` stays a mode-agnostic read+validate primitive;
  `attach/resolve-attach-endpoint` fallback unchanged. Verified by reading:
  `read-dot-nrepl-port` is called by both `wait-for-started-endpoint!` (via
  `read-dot-nrepl-port-safe`) and attach `resolve-attach-endpoint` → the gate
  must not live there.
- A2 (status projection): clarified the review's "`:state*` via dispatch"
  framing against the documented reality — the project-nrepl registry is a
  runtime handle, not dispatch state; subprocess + `.nrepl-port` I/O is
  documented runtime-owned (Layer Map / Frontier) and does not move under
  dispatch effects (consistent with the earlier design-review note in this file).
  Canonical status surface = the registry instance map (`runtime.clj`), read via
  `ops/instance-payload`. Decision: stale-port rejection → existing `:last-error`
  failure-path projection with distinct `:phase :started-stale-port`; effective
  timeout → new instance `:readiness-timeout-ms` status field surfaced through
  `instance-payload`; `ops/start` keeps projecting from `instance-payload` (no
  op-only status channel). Acceptance criteria updated to match.

## Design review — ambiguity (ψ)

Scope: design.md only (not plan/steps). Architectural-fit (A1/A2) already
resolved. New actionable ambiguities, all single-interpretation gaps a builder
would otherwise guess at:

- AMB1: Q1–Q4 are still "open … resolve collaboratively before plan", yet
  acceptance criteria assert "the confirmed Q1 surface" and "a reasonable
  default and/or configured timeout". Each open question (Q1 key name/default/
  bounds; Q2 strategy a/b/c; Q3 confirmed cause; Q4 process-exit diagnostics
  scope) must be resolved in design.md before plan, or the criteria reference
  decisions that do not exist.
- AMB2: A1 commits to a launch-time/mtime acceptance gate (last-modified ≥
  launch instant) as the stale-port mechanism, but Q2 still lists that gate as
  only one of three open options (a/b/c) and asks to "confirm which". Design
  simultaneously decides and leaves-open the Q2 strategy — which governs?
- AMB3: A2 names a new `:readiness-timeout-ms` instance status field "surfaced
  through `instance-payload`", but `instance-payload` (`ops.clj:22`) has an
  explicit fixed key list that today lacks it (and lacks any stale-port
  diagnostic key beyond `:last-error`). Design does not state that
  `instance-payload`'s projected key set is extended, so "surfaced through
  instance-payload" is ambiguous about whether the projection itself changes.
- AMB4: The mtime gate "last-modified ≥ launch instant" has unspecified
  precision/tolerance. Filesystem mtime is often coarse (1s) vs. a ms launch
  instant; a stale port touched in the same second as launch could pass, a fresh
  port could be rejected under clock/mtime skew. Design does not specify the
  comparison tolerance nor whether Q2(a) pre-launch removal supplements the gate
  (Q2c "combination").
- AMB5: Acceptance mandates BOTH the timeout raise and the stale-port gate, but
  Q3 (confirmed cause) is unresolved and the design warns "avoid speculative
  changes". If Q3 empirically shows a single cause, the criteria are ambiguous
  about whether the other fix is still required.

PASS_STATUS: ACTIONABLE_FEEDBACK
