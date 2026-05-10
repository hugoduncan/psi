Approach:
- implement this as a bounded component extraction guided by `105`, with a sharper first-cut focus than a general tool-domain move
- extract the canonical tool-definition ownership surface together with the extension tool-registration/catalog ownership surfaces
- preserve the split established by `104`: execution remains in `tool-runtime`, registration/canonical defs move to a new lower `tool-registry` component
- keep extension mutation/API entrypoints and session tool-selection policy above the boundary unless implementation reveals a smaller clearer cut

Initial migration strategy:
1. create `components/tool-registry/` with authoritative namespaces under `psi.tool-registry.*`
2. inspect `psi.agent-session.tool-defs` and the tool-specific parts of `psi.agent-session.extensions` for hidden dependency or split pressure
3. extract canonical tool-def normalization/projection into the new component
4. extract tool-name validation and extension tool-registration/query helpers into the new component
5. update all known direct production consumers of the canonical tool-definition surface and re-scan for any additional direct consumers discovered during implementation
6. keep `mutations/extensions.clj` and `extensions/api.clj` as thin adapters unless there is a clearly better bounded split
7. move or add focused extracted-component tests under `components/tool-registry/test/`
8. add `components/tool-registry/test` explicitly to the top-level `tests.edn` component test-path lists used by the standard root test runner
9. remove any temporary forwarding shims before task completion
10. run focused verification for both the new component and at least one higher-level registration/consumer path

Boundary decisions to preserve during implementation:
- `components/tool-runtime/` remains the owner of tool execution/runtime mechanics
- `tool-registry` owns canonical tool definitions and extension registration only
- the `tool-registry` name is accepted for this slice and should be treated as the chosen first-cut public component name
- tool-specific registry operations may work directly over the current extension-registry state shape in this task; no prerequisite generic extension-registry extraction is required
- session `:tool-defs` policy and inheritance/selection remain in higher-level session/workflow code
- generic extension registration for commands/flags/shortcuts/handlers stays outside this task
- UI tool renderer registration stays outside this task
- registered-tool query semantics must remain unchanged: `tool-names-in` is the cross-extension name set and `all-tools-in` remains first-registration-wins by tool name
- canonical normalized tool-def maps remain rich internal maps; projection helpers continue to own external boundary shaping
- canonical tool-name validation remains scoped to extension registration in this slice
- test movement should separate lower-level tool-def/registration proofs from higher-level mutation/API/resolver integration proofs

Verification intent:
- component-owned tests should prove canonical normalization, projection, validation, registration, and listing behavior from the new `psi.tool-registry.*` boundary
- focused higher-level tests should prove that at least one extension registration or consumer path still works through the thin `agent-session` seam after migration
- final repo search should confirm the authoritative ownership shift away from `psi.agent-session.tool-defs` and the tool-registration portions of `psi.agent-session.extensions`
