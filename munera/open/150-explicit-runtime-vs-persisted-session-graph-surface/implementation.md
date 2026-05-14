# Implementation notes

- Append-only notes during execution.
- Record naming decisions, compatibility choices, migration edges, and any graph-surface surprises discovered while implementing.

## 2026-05-14 — design refinement

- fixed the preferred naming choice instead of leaving it open:
  - runtime surface: `:psi.runtime-session/list`, `:psi.runtime-session/count`, `:psi.runtime-session/active-id`
  - persisted surface: `:psi.persisted-session/list`, `:psi.persisted-session/list-all`
- explicitly rejected `:psi.agent-session/runtime-*` as the new preferred public surface; keep old `psi.agent-session/*` attrs only as migration compatibility where needed
- explicitly rejected `:psi.session-store/*` for this task; `persisted-session` better captures the semantic distinction callers need than a lower implementation owner
- preferred resolver naming direction fixed as `runtime-session-*` and `persisted-session-*`
- ambiguity review (2026-05-14): actionable gaps remain before implementation:
  - `design.md` leaves `:psi.agent-session/context-session-summaries` open as Key question 3, but `plan.md`/`steps.md` do not decide whether the compact summary surface also needs a preferred `:psi.runtime-session/*` counterpart now or is intentionally out of scope
  - migration discoverability is underspecified: artifacts require old attrs to remain available while new attrs become authoritative/preferred, but do not say whether compatibility attrs should still appear in `:psi.graph/root-queryable-attrs` during the migration window
  - the required in-task migration set is not enumerated even though existing prompt/docs/UI query surfaces already teach or consume the old names (`system_prompt`, Emacs `/resume`, TUI resume action, graph docs/tests)
