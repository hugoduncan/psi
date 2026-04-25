Approach:
- keep ownership in shared backend slash-resolution semantics
- avoid independent RPC-local and app-runtime-local fallback logic
- minimize architecture change by introducing the smallest shared resolution seam that can express:
  - built-in command
  - extension command
  - loaded prompt template
  - unknown slash input
- treat the session's loaded prompt-template set as the authoritative match surface for both execution fallback and in-scope completion wiring

Implementation shape:
1. Inspect current slash entry paths and choose the narrowest shared resolution point.
   - Compare the current roles of:
     - `psi.agent-session.commands`
     - shared prompt entry / request-preparation boundary
     - RPC command handling
     - app-runtime interactive prompt handling
   - Prefer a shared helper or shared boundary decision over duplicated per-adapter checks.

2. Introduce or refine a shared slash-resolution seam/result as needed.
   - Resolve slash-prefixed input in this order:
     1. built-in command
     2. extension command
     3. loaded prompt template
     4. unknown slash input
   - Keep command precedence unchanged.
   - Ensure the result shape is sufficient for callers to distinguish:
     - execute command now
     - treat as prompt/template input
     - report unknown slash input

3. Thread the shared result through execution entry paths.
   - Adjust RPC command/prompt entry behavior only as needed to consume the shared resolution result.
   - Adjust app-runtime/non-RPC prompt submission behavior only as needed to consume the same shared result.
   - Preserve existing behavior for real commands.
   - Remove or bypass the current `[not a command]` outcome for loaded prompt-template names.

4. Preserve prompt-template expansion ownership.
   - Do not duplicate template expansion mechanics in transport code.
   - Ensure fallback routes template-backed slash input into the existing prompt/template expansion path rather than inventing a second expansion path.

5. Complete completion/discoverability wiring.
   - Inspect TUI and Emacs slash completion sources.
   - Keep surfaces that already use backend/session prompt-template state aligned with the canonical loaded-template set.
   - Only change completion surfaces in scope: existing slash-completion surfaces already consuming backend/session prompt-template state.
   - Ensure collision handling stays deterministic and avoids duplicate indistinguishable candidates.

6. Prove loaded-session-state semantics.
   - Add focused tests showing runtime-registered/reloaded prompt templates participate in slash resolution.
   - Add focused tests showing completion also reflects loaded session prompt-template state where applicable.

7. Verify convergence and regressions.
   - Focused tests around:
     - command/template precedence
     - template fallback execution
     - unknown slash input
     - collision execution behavior
     - collision completion behavior
     - runtime-registered/reloaded template behavior
     - non-RPC prompt-submission convergence on the shared backend resolution order
   - Re-run relevant RPC command/prompt tests and relevant TUI/Emacs completion tests.

Decision points / constraints:
- prefer one shared backend resolution path over adapter-local branching
- preserve the existing invariant that commands win over templates
- keep completion work narrowly scoped to existing slash-completion surfaces
- if a surface already behaves correctly (for example TUI), prefer proving and preserving rather than reshaping

Risks:
- accidentally introducing two resolution paths: command-only vs prompt-template-aware
- accidentally moving behavior ownership into RPC transport
- collision handling becoming ambiguous in completion UIs
- proving runtime-loaded template behavior may require a small test seam rather than relying only on startup bootstrap

Expected outcome:
- loaded prompt templates are executable via `/name ...` through shared backend semantics
- true commands still win on collisions
- true unknown slash input still reports unknown
- supported slash-completion surfaces expose loaded prompt templates consistently
- RPC and non-RPC paths converge on the same backend resolution order
