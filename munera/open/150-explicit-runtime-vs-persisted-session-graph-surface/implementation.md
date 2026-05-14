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
- ambiguity review (2026-05-14): actionable gaps remained before implementation:
  - `design.md` left `:psi.agent-session/context-session-summaries` open as Key question 3, but `plan.md`/`steps.md` did not decide whether the compact summary surface also needs a preferred `:psi.runtime-session/*` counterpart now or is intentionally out of scope
  - migration discoverability was underspecified: artifacts required old attrs to remain available while new attrs become authoritative/preferred, but did not say whether compatibility attrs should still appear in `:psi.graph/root-queryable-attrs` during the migration window
  - the required in-task migration set was not enumerated even though existing prompt/docs/UI query surfaces already teach or consume the old names (`system_prompt`, Emacs `/resume`, TUI resume action, graph docs/tests)
+
+## 2026-05-14 — ambiguity follow-up execution
+
+- completed the newly added ambiguity design-steps by resolving the task-file gaps directly in the task artifacts
+- fixed scope boundary: `:psi.agent-session/context-session-summaries` stays out of scope for this task and does not gain a mirrored `:psi.runtime-session/*` counterpart here
+- fixed migration discoverability contract: new explicit attrs must appear in `:psi.graph/root-queryable-attrs`; compatibility attrs may remain listed there during migration if they stay mechanically root-queryable; this task will prefer the new attrs through migrated docs/examples/tests rather than by adding special introspection filtering
+- fixed the minimum required migration set for implementation: Emacs `/resume`, TUI `/resume`, app-runtime resume selector shaping, and focused resolver/graph teaching surfaces must move to the explicit names in this task
+- inspected current consumers to ground that migration set:
  - Emacs `/resume` currently queries `:psi.session/list` in `components/emacs-ui/psi-session-commands.el`
  - TUI `/resume` currently queries `:psi.session/list` in `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj`
  - app-runtime resume selector currently shapes `:psi.session/list` results in `components/app-runtime/src/psi/app_runtime/ui_actions.clj`
  - focused graph proofs live in `components/agent-session/test/psi/agent_session/resolvers_test.clj`
+- no blocking reason found for the ambiguity follow-up items; all newly added `design-steps.md` items are now complete

## 2026-05-14 — inconsistency review

- actionable inconsistency found: `plan.md` still contains literal patch markers and a duplicated/renumbered “Prove behaviour and migration compatibility” section, while `steps.md` and `implementation.md` treat the ambiguity follow-up as already resolved. Clean the task artifact so the implementation plan has one canonical ordered step list.

## 2026-05-14 — inconsistency follow-up execution

- removed the stale patch-marker artefact and duplicate plan entry from `plan.md`
- `plan.md` now has one canonical ordered implementation sequence aligned with `steps.md` and the recorded ambiguity follow-up state in `implementation.md`
- no blocking reason remained; the newly added inconsistency follow-up item is complete
