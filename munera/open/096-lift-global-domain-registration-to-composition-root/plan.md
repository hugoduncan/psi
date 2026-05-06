Approach:
- treat this as an ownership clarification task, not a query-engine redesign
- first map the current registration surfaces into two buckets:
  - domain-local registration helpers
  - whole-system/global assembly entrypoints
- then create one explicit composition-root assembly path and move all “register everything” ownership there
- finally remove the temporary `requiring-resolve` seam from `agent-session.context`

Likely steps:
1. inspect current registration call sites in `agent-session.context`, `agent-session.bootstrap`, `system-bootstrap`, launcher/app-runtime startup, and any tests that rely on implicit global registration
2. record the chosen composition-root namespace/path explicitly in implementation notes
3. classify each registration helper as either domain-local or global-assembly and note any mixed surfaces that need splitting
4. refactor or preserve domain-local `register-*-in!` helpers so their meaning is strictly local-to-domain
5. create/update the explicit composition-root assembly function that registers all domains into the global/app query context
6. update startup/bootstrap/composition call paths to use that explicit assembly function
7. remove `requiring-resolve` global registration discovery from `agent-session.context`
8. update focused tests for both:
   - local/isolated agent-session registration
   - globally assembled registration
9. document the final ownership split and any remaining edge cases
10. run focused verification, then broader verification as needed

Decision criteria:
- if a function means “register this domain into the provided qctx”, it belongs in the domain component
- if a function means “register all domains for the assembled application”, it belongs in the composition root
- if `agent-session` still needs to discover whole-system bootstrap behavior dynamically, the task is not done
- if local tests or isolated contexts become harder because of whole-app assembly coupling, the split is wrong
- if composition root depends on domains, that is fine; if domains depend back on composition root, that is wrong

Risks:
- accidentally broadening this into a startup/runtime overhaul
- preserving hidden implicit registration paths while adding an explicit one
- conflating isolated/local query contexts with globally assembled app contexts
- leaving tests dependent on old implicit registration assumptions
