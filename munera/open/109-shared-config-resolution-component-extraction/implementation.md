2026-05-07

Task created after reviewing the current shared config dependency picture.

Initial findings motivating the extraction:
- current downward tree under `psi.agent-session.config-resolution` is already small and component-like:
  - `psi.agent-session.config-resolution`
    - `psi.agent-session.project-preferences`
    - `psi.agent-session.user-config`
- the stronger architectural signal is upward/sideways duplication rather than dependency depth
- `psi.project-nrepl.config` currently duplicates:
  - user config file lookup
  - project shared/local config file lookup
  - EDN best-effort read policy
  - layered merge behavior
  - malformed-file warning/fallback behavior
- that duplication exists because the extracted `project-nrepl` component correctly avoided depending upward on `agent-session`
- `psi.app-runtime` is the current main direct consumer of `psi.agent-session.config-resolution`
- `psi.agent-session.dispatch-effects` currently writes user/project config directly through `psi.agent-session.user-config` and `psi.agent-session.project-preferences`

Architectural conclusion:
- shared config file mechanics are owned too high today
- this is a good lower-component extraction candidate
- the extracted component should own generic config file reading/writing/layering, while project-nREPL-specific validation and agent-session runtime override policy stay with their owning domains

Refinement pass completed to remove initial ambiguities:
- first cut should preserve current file paths and persisted key layout exactly
- first cut should preserve `app-runtime`'s current typed accessor contract via the extracted resolution namespace rather than forcing a consumer redesign
- first cut should keep `psi.project-nrepl.config` as the project-nREPL-owned interpretation/validation surface and only remove its duplicated lower-level read/merge mechanics
- current malformed-project-file warning behavior from `agent-session.project-preferences` is treated as the authoritative policy to consolidate around
- the task does not attempt to invent a fully generic arbitrary-subtree config framework; it should instead extract the shared mechanics needed by the current real consumers cleanly

Implementation pass:
- created new lower component `components/shared-config/` with authoritative namespaces:
  - `psi.shared-config.user`
  - `psi.shared-config.project`
  - `psi.shared-config.resolution`
- moved the old user-global config file ownership into `psi.shared-config.user` unchanged in semantics:
  - `~/.psi/agent/config.edn`
  - best-effort full-map read
  - `:version` preservation/defaulting
  - `update-agent-session!` write helper
- moved the old layered project config file ownership into `psi.shared-config.project` unchanged in semantics:
  - `<cwd>/.psi/project.edn`
  - `<cwd>/.psi/project.local.edn`
  - deep merge semantics preserved
  - malformed-file warning/fallback policy preserved
  - local override writing preserved through `update-agent-session!`
- moved the shared flat effective `:agent-session` resolution contract into `psi.shared-config.resolution`:
  - authoritative `agent-session-map` extraction from full config maps
  - preserved precedence `system < user < project-shared < project-local`
  - preserved typed accessors consumed by `app-runtime`
- updated component/repo classpath wiring so shared-config is available as a first-class lower component
- updated consumers:
  - `components/app-runtime/src/psi/app_runtime.clj` now depends on `psi.shared-config.resolution`
  - `components/agent-session/src/psi/agent_session/dispatch_effects.clj` now writes through `psi.shared-config.user` / `psi.shared-config.project`
  - `components/project-nrepl/src/psi/project_nrepl/config.clj` now consumes shared-config user/project reads plus shared `:agent-session` extraction instead of carrying its own copied file/merge substrate
- preserved project-nREPL ownership of domain-specific validation and target discovery:
  - `resolve-target-worktree`
  - `absolute-directory-path!`
  - `resolved-start-command`
  - `resolved-attach-endpoint`
  - `.nrepl-port` discovery
- removed the old `psi.agent-session.config-resolution`, `psi.agent-session.project-preferences`, and `psi.agent-session.user-config` authoritative namespaces instead of leaving compatibility shims behind
- moved focused ownership tests to `components/shared-config/test/psi/shared_config/`
- updated higher-level consuming tests in app-runtime and agent-session to depend on the extracted shared-config owner

Focused verification run after extraction:
- `clojure -M:test --focus psi.shared-config.user-test --focus psi.shared-config.project-test --focus psi.shared-config.resolution-test --focus psi.project-nrepl.config-test --focus psi.app-runtime-test/bootstrap-runtime-session-applies-project-preferences-test --focus psi.agent-session.model-dispatch-test/thinking-level-test`
- result: `9 tests, 50 assertions, 0 failures`
- `clj-kondo` lint on touched sources/tests/deps files: `0 errors, 0 warnings`
