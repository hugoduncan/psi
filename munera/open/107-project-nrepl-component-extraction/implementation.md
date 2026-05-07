2026-05-07

Task created as a concrete child of `105-agent-session-component-extraction-map`.

Creation rationale:
- project nREPL is one of the clearest bounded subsystems currently latent inside `agent-session`
- direct production consumers remain mostly within `agent-session`, which makes this an especially straightforward first extraction after provider-auth, even though practical verification still reaches through commands, `psi-tool`, resolvers, and tests
- this is a lower-ambiguity extraction than turn/prompt decomposition

Initial boundary decision:
- extract the whole `project_nrepl_*` namespace family as one component rather than splitting runtime/config/client/ops into separate tasks
- exception: if `project_nrepl_commands.clj` proves to mix subsystem-owned command parsing/dispatch with broader agent-session command-surface integration, split that boundary rather than forcing the entire namespace below the seam
- keep invocation/orchestration of project nREPL operations in higher-level callers; move the project nREPL implementation itself out of `agent-session`

Known current direct consumer surfaces at task creation time:
- agent-session commands/context/psi-tool/resolvers
- focused project nREPL tests plus higher-level tools/context/resolver/extension-install integration tests

Review pass for ambiguity/tightness:
- overall this task is materially clean: the `project_nrepl_*` family already looks like one subsystem rather than a scattered cross-cutting concern
- found one boundary-sensitive namespace worth keeping explicit:
  - `project_nrepl_commands.clj` belongs in the extracted component only insofar as it is truly subsystem-owned command parsing/dispatch
  - decision rule: if a function primarily parses or dispatches project-nREPL-specific operations, it moves; if it primarily integrates those operations into broader agent-session command routing, it stays above the boundary
- found one adapter split worth making explicit:
  - `psi.project-nrepl.ops` is the preferred lower operational entry surface
  - higher-level adapters such as `psi.agent-session.psi-tool`, broader command routing, and resolver projection remain above the boundary
- found one seam decision worth keeping explicit:
  - `context.clj` should depend on the smallest subsystem surface that satisfies its needs, preferably `psi.project-nrepl.runtime` for runtime-state wiring and `psi.project-nrepl.ops` only where operational entrypoints are genuinely needed
  - avoid a wide `context.clj` fan-out across many `psi.project-nrepl.*` namespaces unless a concrete need is already present
- found one test-movement clarification worth preserving:
  - move pure subsystem tests such as config/runtime/client/attach/started/eval
  - move commands tests only if they prove subsystem-owned
  - keep observability, resolvers, extension-install, tools, and other higher-level integration tests in place
- found one migration risk worth making explicit:
  - context wiring, command/psi-tool adapter wiring, or runtime-state helper ownership can drift back upward during migration if the family is moved mechanically without preserving the intended boundary
  - treat such upward drift as a regression in the extraction shape rather than as acceptable migration noise

Resolved open questions:
- `project_nrepl_commands.clj` ownership:
  - move only the parts whose primary job is parsing/dispatching project-nREPL-specific operations
  - keep broader agent-session command-surface integration above the boundary
- preferred higher-level entry surface:
  - prefer `psi.project-nrepl.ops` for operational callers
  - allow direct dependence on narrower `psi.project-nrepl.*` namespaces only for concrete local/helper-level needs
- preferred `context.clj` seam:
  - prefer `psi.project-nrepl.runtime` for runtime-state wiring
  - use `psi.project-nrepl.ops` only where operational entrypoints are genuinely needed
  - avoid wide fan-out from `context.clj` across many subsystem namespaces without a concrete need
- resolver ownership:
  - extracted component owns raw runtime/config/op behavior
  - resolver projection stays above the boundary in `agent-session` resolver namespaces
- test placement:
  - move pure subsystem tests such as config/runtime/client/attach/started/eval
  - move commands tests only when they are primarily subsystem-owned op parsing/dispatch tests
  - keep observability, resolvers, extension-install, tools, and other higher-level integration tests in their owning components
- session-state dependencies:
  - allowed in this slice
  - the required ownership shift is removal of `psi.agent-session.*` implementation ownership, not elimination of `psi.session-state.state` usage

Implementation result:
- created authoritative component `components/project-nrepl/`
- moved the managed project nREPL namespace family to `psi.project-nrepl.*`
- updated direct higher-level consumers in `agent-session` to require the extracted namespaces
- removed the old `psi.agent-session.project-nrepl-*` source owners instead of leaving compatibility shims
- kept the `commands` namespace inside the extracted component for this first cut because its implementation remained subsystem-owned command parsing/dispatch rather than broader command-router integration

Authoritative moved namespaces:
- `psi.project-nrepl.config`
- `psi.project-nrepl.runtime`
- `psi.project-nrepl.client`
- `psi.project-nrepl.attach`
- `psi.project-nrepl.started`
- `psi.project-nrepl.eval`
- `psi.project-nrepl.ops`
- `psi.project-nrepl.commands`

Component/test wiring changes:
- added `components/project-nrepl/deps.edn`
- added root `deps.edn` component dep and source/test paths for `project-nrepl`
- added `psi/project-nrepl` as a dep in `components/agent-session/deps.edn`
- added `components/project-nrepl` source/test paths in `tests.edn`

Moved focused tests into `components/project-nrepl/test/psi/project_nrepl/`:
- `config_test.clj`
- `runtime_test.clj`
- `client_test.clj`
- `attach_test.clj`
- `started_test.clj`
- `eval_test.clj`
- `commands_test.clj`

Tests intentionally kept under higher-level owning component:
- `components/agent-session/test/psi/agent_session/project_nrepl_resolvers_test.clj`
  - remains above the boundary because it proves resolver projection from agent-session graph surfaces
- `components/agent-session/test/psi/agent_session/project_nrepl_observability_test.clj`
  - remains above the boundary because it proves graph/introspection surfaces rather than raw subsystem behavior
- `components/agent-session/test/psi/agent_session/project_nrepl_extension_install_test.clj`
  - remains above the boundary because it proves integration with extension install and session runtime paths
- `components/agent-session/test/psi/agent_session/tools_test.clj`
  - remains above the boundary because it proves higher-level tool routing into project-nREPL ops

Boundary outcome notes:
- `psi.project-nrepl.*` no longer depends on `psi.agent-session.*` implementation namespaces directly
- the only retained lower dependency from the extracted component is `psi.session-state.state`, which was explicitly allowed by the design
- config loading was localized into `psi.project-nrepl.config` so extracted code no longer depends on `psi.agent-session.project-preferences` or `psi.agent-session.user-config`

Verification:
- focused extracted-component verification green:
  - `bb clojure:test:unit --focus psi.project-nrepl.config-test --focus psi.project-nrepl.runtime-test --focus psi.project-nrepl.client-test --focus psi.project-nrepl.attach-test --focus psi.project-nrepl.started-test --focus psi.project-nrepl.eval-test --focus psi.project-nrepl.commands-test`
  - result: `1514 tests, 11723 assertions, 0 failures`
- focused higher-level consuming-path verification green:
  - `bb clojure:test:unit --focus psi.agent-session.project-nrepl-resolvers-test --focus psi.agent-session.project-nrepl-observability-test --focus psi.agent-session.project-nrepl-extension-install-test --focus psi.agent-session.tools-test`
  - result: `1514 tests, 11051 assertions, 0 failures`

Completion checks:
- no compatibility shim introduced
- old authoritative `components/agent-session/src/psi/agent_session/project_nrepl_*.clj` files removed
- old focused agent-session-owned subsystem tests removed after move into the new component
- repo search after migration showed no remaining production/test requires of `psi.agent-session.project-nrepl-*`; remaining mentions are task design prose and higher-level test namespaces that intentionally keep their `agent-session` test ownership labels
