Approach:
- treat this as an ownership clarification task, not a query-engine redesign
- first map the current registration surfaces into three shapes:
  - session-facing local registration helpers
  - assembled isolated registration entrypoints
  - whole-system/global assembly entrypoints
- then keep or refine the local helper meaning while making one explicit composition root own both assembled-isolated and whole-system registration
- finally remove the temporary `requiring-resolve` seam from `agent-session.context`

Likely steps:
1. inspect current registration call sites in `agent-session.context`, `agent-session.bootstrap`, `system-bootstrap`, launcher/app-runtime startup, and any tests that rely on implicit global registration
2. record the chosen composition-root namespace/path explicitly in implementation notes; for this task that root is `psi.system-bootstrap.core` unless implementation forces a better replacement
3. classify each registration helper as either session-facing-local, assembled-isolated, or global-assembly and note any mixed surfaces that need splitting
4. decide whether current `agent-session` `register-*-in!` helpers remain the intended session-facing local surface, including history if needed for current isolated session behavior, or should be split further in this task
5. remove `psi.agent-session.core/register-resolvers!` and `psi.agent-session.core/register-mutations!`, unless removal would force unrelated broad startup/runtime churn outside this task’s scope; if that happens, record the temporary shim explicitly as follow-on debt and do not preserve dynamic bootstrap discovery
6. refactor or preserve local `register-*-in!` helpers so their meaning is domain-local or session-facing-local only, never whole-system assembly
7. create/update the explicit composition-root assembly function(s) that own:
   - all-domains registration into the global query registry
   - canonical fully assembled registration into an isolated query context
8. update startup/bootstrap/composition call paths so production startup uses only composition-root-owned registration entrypoints
9. remove `requiring-resolve` global registration discovery from `agent-session.context`
10. update focused tests for both:
   - local/isolated agent-session registration
   - globally assembled registration
11. migrate any tests/helpers that actually require assembled multi-domain registration to an explicit composition-owned helper
12. document the final ownership split and any remaining edge cases
13. run focused verification, then broader verification as needed

Decision criteria:
- if a function means “register this session-facing local surface into the provided qctx”, it belongs in the domain component
- if a function means “register multiple domains into an isolated qctx”, it belongs in the composition root
- if a function means “register all domains for the assembled application”, it belongs in the composition root
- if `agent-session` still needs to discover whole-system bootstrap behavior dynamically, the task is not done
- if local tests or isolated contexts become harder because of whole-app assembly coupling, the split is wrong
- if composition root depends on domains, that is fine; if domains depend back on composition root, that is wrong

Risks:
- accidentally broadening this into a startup/runtime overhaul
- preserving hidden implicit registration paths while adding an explicit one
- conflating session-facing local registration, assembled isolated registration, and globally assembled app registration
- leaving tests dependent on old implicit registration assumptions
