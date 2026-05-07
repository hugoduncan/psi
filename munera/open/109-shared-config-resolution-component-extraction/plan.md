Approach:
- extract the generic file-backed config substrate first, not every domain-specific config interpretation
- preserve existing precedence and malformed-file behavior exactly while moving ownership downward
- treat this as a bounded lower-component extraction similar in spirit to provider-auth/project-nREPL, not a prompt/runtime redesign

Planned implementation slices:
1. create `components/shared-config/` with authoritative namespaces for user config, project config, and layered resolution
2. move/adapt existing `agent-session` user/project config code into the new component with behavior preserved
3. move/adapt the current shared `agent-session` layered resolution API into `psi.shared-config.resolution`
4. update `app-runtime` to require the extracted resolution namespace
5. update `agent-session.dispatch-effects` to use shared-config write helpers
6. update `project-nrepl.config` so it consumes the shared lower config readers/mergers instead of carrying copied logic
7. remove compatibility shims and old authoritative ownership from `agent-session`
8. run focused shared-config, app-runtime, and project-nREPL verification

Key design constraints:
- keep session runtime overrides out of the extracted component
- keep project-nREPL-specific endpoint/start-command validation local to project-nREPL ownership
- do not broaden this into config-schema redesign or cross-domain settings policy work

Expected result:
- one lower shared-config component owns file-backed config mechanics
- `agent-session`, `app-runtime`, and `project-nrepl` all depend downward on that owner
- config duplication introduced by the `107` extraction is retired cleanly
