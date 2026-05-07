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
