Approach:
- treat this as a structural extraction, not a semantic redesign
- create `components/project-nrepl/` with authoritative namespaces under `psi.project-nrepl.*`
- move the `project_nrepl_*` namespace family in one slice so the new component becomes authoritative immediately, except where `project_nrepl_commands.clj` concretely needs a boundary split between subsystem-owned command parsing/dispatch and broader agent-session command-surface integration
- prefer no compatibility shim; introduce a temporary shim only if the edit sequence concretely requires it to keep the tree compiling during migration, and remove it before completion

Authoritative target namespaces:
- `components/project-nrepl/src/psi/project_nrepl/config.clj` -> `psi.project-nrepl.config`
- `components/project-nrepl/src/psi/project_nrepl/runtime.clj` -> `psi.project-nrepl.runtime`
- `components/project-nrepl/src/psi/project_nrepl/client.clj` -> `psi.project-nrepl.client`
- `components/project-nrepl/src/psi/project_nrepl/attach.clj` -> `psi.project-nrepl.attach`
- `components/project-nrepl/src/psi/project_nrepl/started.clj` -> `psi.project-nrepl.started`
- `components/project-nrepl/src/psi/project_nrepl/eval.clj` -> `psi.project-nrepl.eval`
- `components/project-nrepl/src/psi/project_nrepl/ops.clj` -> `psi.project-nrepl.ops`
- `components/project-nrepl/src/psi/project_nrepl/commands.clj` -> `psi.project-nrepl.commands`

Implementation sequence:
1. create `components/project-nrepl/` directories and destination namespaces/files
2. update project configuration so the new component participates in source and test paths
   - root `deps.edn` / `tests.edn`
   - consuming component deps for at least `components/agent-session`
   - any explicit test alias path lists only where needed
3. move the `project_nrepl_*` namespaces into the new component namespace family
4. update direct production consumers in `agent-session`
5. update direct test consumers and move clearly component-owned tests
6. remove any temporary compatibility shims before verification
7. run focused verification for the new component plus at least one higher-level consuming path
8. record final ownership and migration notes in `implementation.md`

Consumer migration expectations:
- `agent-session` callers should depend directly on `psi.project-nrepl.*` namespaces that match the API they actually use
- extracted authoritative namespaces must not depend on `psi.agent-session.*` implementation namespaces directly at completion
- completion requires a final repo search confirming no remaining authoritative uses of the old `psi.agent-session.project-nrepl-*` namespace family

Testing strategy:
- preserve existing proof where possible
- move only tests clearly owned by the extracted project-nrepl component boundary
- rename moved component-owned tests to `psi.project-nrepl.*-test` namespaces so namespace ownership matches component ownership
- keep mixed higher-level agent-session integration tests in place and update their requires only
