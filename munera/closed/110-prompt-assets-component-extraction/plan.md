Approach:
- implement this as a bounded component extraction, following the ownership map from `105` and the live namespace dependency graph review
- keep the extraction narrow: move only `prompt-templates`, `skills`, and `system-prompt`
- treat `conversation`, `tool-defs`, and `message-text` as explicit non-members of this component boundary
- preserve behavior first; do not redesign prompt runtime semantics during the move

Initial migration strategy:
1. use `components/prompt-assets/` with authoritative namespaces under `psi.prompt-assets.*`
2. inspect `prompt_templates.clj`, `skills.clj`, and `system_prompt.clj` for any hidden dependencies or shared helper pressure
3. move the three namespaces into the new component with minimal semantic change
4. move associated focused tests into `components/prompt-assets/test/` and rename them to their final authoritative `psi.prompt-assets.*-test` namespaces
5. update all live consumers listed in `design.md`, treating that inventory as the minimum known set rather than a scope limit; any additional direct consumers discovered during implementation are also in scope
6. if temporary forwarding shims in `psi.agent-session.*` are needed for incremental safety, keep them strictly transient during the migration only
7. remove all forwarding shims before task completion
8. run focused verification for the extracted component and key consuming paths

Boundary decisions to preserve during implementation:
- `psi.agent-session.conversation` stays outside this task
- `psi.agent-session.tool-defs` stays outside this task
- `psi.agent-session.message-text` stays outside this task
- `discover-context-files` moves with `system-prompt` into prompt-assets unchanged in the first cut unless a concrete blocker forces a better lower split
- keep `skills -> prompt-templates` reuse intact in the first cut unless extraction exposes a concrete blocker that requires a lower helper split

Verification intent:
- focused tests for prompt templates, skills, and system-prompt remain green after relocation; the required final-state authoritative suites are:
  - `clojure -M:test --focus psi.prompt-assets.prompt-templates-test`
  - `clojure -M:test --focus psi.prompt-assets.skills-test`
  - `clojure -M:test --focus psi.prompt-assets.system-prompt-test`
- focused consuming-path verification covers all relevant prompt-building and child-session prompt-shaping consumers, with the required minimum proofs:
  - `clojure -M:test --focus psi.agent-session.child-session-state-test`
  - `clojure -M:test --focus psi.agent-session.child-session-mutation-test`
- indirect app-runtime coverage via the relocated prompt-assets tests and migrated shared prompt-building call paths is acceptable unless implementation reveals a distinct app-runtime-only regression surface
- no user-visible behavior drift in prompt template discovery, skill discovery/invocation, or system-prompt assembly
