# Plan

Implement this as one test-isolation vertical slice: make non-persistence the default for non-persistence tests, standardize an explicit isolated temporary session root for persistence tests, and add guardrails so no test silently writes session artifacts into the developer’s real `~/.psi/agent/sessions/` store.

## Review and follow-up surfaces

- `implementation.md` is the append-only review/decision log for this task.
- `design-steps.md` is the actionable ambiguity follow-up surface.
- `steps.md` remains reserved for implementation execution work, not ambiguity-review follow-up capture.

## Approach

1. **Inventory the real leak seams and classify tests by persistence intent**
   - identify helpers and direct call sites that still create persisted sessions from temp cwd/worktree paths
   - separate them into:
     - non-persistence tests that should run with `:persist? false`
     - explicit persistence tests whose assertions require real journal/session-file behavior on disk
   - confirm the most important shared seam(s), especially app-runtime/runtime-bootstrap test helpers

2. **Converge shared non-persistence defaults for ordinary tests**
   - update runtime/bootstrap test helpers so they explicitly propagate `:persist? false` when persistence is not the thing being tested
   - align app-runtime-oriented helpers with the existing `psi.agent-session.test-support` discipline
   - prefer one shared fix over scattered local per-test overrides when possible

3. **Standardize isolated temporary session roots for explicit persistence tests**
   - introduce or refine a shared helper/pattern for tests that intentionally need persistence
   - make that helper allocate an isolated temporary session root rather than using the real default `~/.psi/agent/sessions/`
   - prefer per-test helper-owned isolated temporary session roots unless inventory proves a broader fixture scope is necessary
   - keep the persisted behavior real: session files and directories should still be written, just under a test-owned root
   - ensure explicit persistence tests can inspect files in the isolated temporary session root during the test body before helper-owned cleanup runs

4. **Add cleanup ownership for explicit persistence tests**
   - make the persistence-test helper or pattern responsible for cleaning up the isolated temporary session root after the test lifecycle completes
   - keep cleanup local and comprehensible so test authors can see the ownership model clearly
   - avoid relying on manual cleanup or developer home-directory hygiene
   - prefer helper-owned lifecycle control such as per-test fixture or `try`/`finally` semantics so cleanup remains robust on test failure

5. **Add guardrails against unsafe persisted test contexts**
   - no persisted test context may use the real default session store
   - strengthen guardrails at the chosen test helper / test-support seam so unsafe persisted contexts fail fast
   - preserve an explicit isolated opt-in path for true persistence tests, but require them to name an isolated temporary session root rather than falling through to the production default
   - keep guardrails test-only and avoid affecting production runtime semantics

6. **Record the explicit implementation decisions**
   - make explicit in code/tests or implementation notes:
     - the exact seam used to select an isolated temporary session root
     - the exact helper or lifecycle owner responsible for cleanup
     - the exact fail-fast condition for unsafe persisted test contexts
     - the first shared helper/namespace chosen to converge app-runtime/bootstrap tests onto non-persisting defaults

7. **Prove both sides of the contract**
   - prove that ordinary temp-cwd/bootstrap tests do not allocate real persisted session artifacts
   - prove that explicit persistence tests still work when pointed at an isolated temporary session root
   - prove that explicit persistence tests can inspect files before helper-owned cleanup runs
   - prove that isolated temporary session roots are cleaned up by the standardized helper/pattern
   - prove that unsafe persisted test contexts targeting the real default session store fail fast

## Risks

- The leak may come from multiple test seams, not just one helper, so a too-local fix could leave residual writers behind.
- Guardrails that are too broad could break legitimate persistence tests; they need an explicit isolated opt-in path.
- Cleanup can become flaky if ownership is split across helpers and test bodies; prefer one obvious helper-owned lifecycle.
- It is easy to broaden this into production persistence redesign; keep the slice strictly test-time and isolation-focused.
