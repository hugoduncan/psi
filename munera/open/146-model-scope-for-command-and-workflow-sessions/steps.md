# Steps

- [x] Audit all canonical model-set entrypoints and workflow-owned model-set call sites; record the authoritative surfaces that must carry optional scope.
- [x] Extend canonical model-setting helper/API surfaces to accept optional scope and forward it unchanged to `:session/set-model`.
- [x] Update RPC `set_model` to accept optional `:scope`, validate `session|project|user`, and preserve omitted-scope behavior.
- [x] Extend `/model` parsing to accept zero args, two args, or three args with `session|project|user`; reject invalid arities and invalid scope values with clear messages.
- [x] Update `/model` help/usage text and any adjacent command help/completion surfaces that describe the command.
- [x] Update workflow-owned child-session model switching to use explicit `:scope :session`, including ranked fallback model switching.
- [x] Add focused tests proving explicit `session` scope does not persist config, explicit `project` scope persists project-local prefs, and explicit `user` scope persists user config.
- [x] Add a workflow regression test proving workflow-owned model changes do not persist project or user config.
- [x] Add an end-to-end workflow child-session creation persistence regression proving the initial concrete-model setup path leaves project preferences and user config untouched, not just that the model-set seam receives `:scope :session`.
- [x] Verify focused suites and lint; record results in `implementation.md`.
- [x] Update the task’s recorded focused verification command/results to include `psi.agent-session.workflow-execution-test`, since the new end-to-end workflow initial-model persistence regression now lives there.
- [ ] Update `munera/plan.md` to include this task in backlog order if it should remain open after creation.
