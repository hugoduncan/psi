Task created.

Initial framing:
- this task exists because task 095 intentionally used `requiring-resolve` as a temporary cycle-breaking seam when removing the static `agent-session -> system-bootstrap` dependency
- that seam was a good intermediate move, but not the desired final ownership shape
- the deeper issue is that whole-system registration is still partially discoverable from within a domain component rather than being owned explicitly by a higher-level composition root

Working hypothesis:
- each domain should expose only domain-local registration helpers
- one higher-level composition/bootstrap root should own whole-system registration assembly
- local/isolated query-context construction and global/app assembly should become explicit, separate responsibilities

Ambiguity review decisions recorded:
- chosen composition root for this task: `psi.system-bootstrap.core`
- this task treats `psi.system-bootstrap.core` as the authoritative composition root, not merely a temporary staging location
- unless implementation uncovers a stronger replacement path, both global registry assembly and fully assembled isolated query-context assembly stay owned there
- intended cleanup shape: remove `psi.agent-session.core/register-resolvers!` and `psi.agent-session.core/register-mutations!` rather than preserving them as compatibility wrappers
- blocking caller criterion for a temporary compatibility shim: removal would otherwise force unrelated broad startup/runtime churn outside this task’s scope
- key boundary to verify during implementation: whether current `agent-session` local registration helpers are strictly domain-local or instead encode a broader session-facing local surface that includes other domains such as history
- this task accepts preserving the current session-facing local helper shape if needed for behavior preservation, but it does not accept keeping whole-system “register everything” ownership inside `agent-session`
- any test or helper that really needs assembled multi-domain registration should move to an explicit composition-owned entrypoint rather than depending on a domain-owned global wrapper

Inspection notes:
- `psi.agent-session.context` currently mixes the acceptable session-facing local helpers (`register-resolvers-in!`, `register-mutations-in!`) with the temporary global wrappers (`register-resolvers!`, `register-mutations!`) and a `requiring-resolve` seam back into `psi.system-bootstrap.core/register-all-domains!`
- `psi.agent-session.bootstrap/bootstrap-in!` is the only production caller of those global wrappers; it can depend on `psi.system-bootstrap.core` directly without reintroducing the old cycle because composition already sits above `agent-session`
- the current session-facing local surface intentionally includes adjacent session-centric domains: resolver registration uses `resolvers/session-resolver-surface`, which already folds in history, memory, and recursion resolvers; mutation registration appends `psi.history.resolvers/all-mutations` to the passed agent-session mutation surface
- `psi.system-bootstrap.core/register-domains-in!` already owns the assembled isolated registration role, while `register-all-domains!` owns global assembly; this task mainly needs to make those ownership boundaries explicit and remove the domain-to-composition back-edge
- `psi.system-bootstrap.core/register-all-domains!` currently resolves agent-session mutations through `psi.agent-session.core/all-mutations`; the canonical mutation aggregate actually lives in `psi.agent-session.mutations/all-mutations`, so composition ownership should point there directly
- existing isolated tests in `agent-session` legitimately exercise the session-facing local surface; any test that needs whole assembled registration should target `psi.system-bootstrap.core` instead

Implementation progress:
- removed the temporary global-registration seam from `psi.agent-session.context`; the namespace now exposes only isolated/session-facing local registration helpers and context construction
- removed `psi.agent-session.core/register-resolvers!` and `psi.agent-session.core/register-mutations!`; no blocking compatibility shim was needed
- removed `register-global-query?` from `psi.agent-session.bootstrap/bootstrap-in!`; whole-application registration is now owned by higher-level composition (`app-runtime`, `introspection`, and explicit `system-bootstrap` tests) instead of the domain bootstrap helper
- clarified `psi.system-bootstrap.core` docs so `register-all-domains!` is the authoritative global assembly entrypoint and `register-domains-in!` is the authoritative assembled-isolated entrypoint
- corrected composition-owned agent-session mutation registration to use `psi.agent-session.mutations/all-mutations` directly instead of trying to resolve a non-canonical aggregate through `psi.agent-session.core`
- added explicit composition-root tests under `components/system-bootstrap/test` covering both global assembled registration and assembled isolated registration with a live session context
- adjusted tests that only needed session bootstrap behavior so they no longer pass a now-removed global-registration toggle

Final ownership split:
- `psi.agent-session.context` owns only the session-facing local registration helpers used to build isolated qctxs for session-centric execution and tests
- `psi.system-bootstrap.core/register-domains-in!` owns assembled isolated multi-domain registration
- `psi.system-bootstrap.core/register-all-domains!` owns whole-application global registration
- higher-level composition (`app-runtime`, `introspection`, or explicit tests) invokes composition-root registration; domain bootstrap helpers no longer own or proxy whole-system registration

Focused verification completed:
- `clojure -M:test --focus psi.agent-session.model-dispatch-test` → `8 tests, 96 assertions, 0 failures`
- `clojure -M:test --focus psi.introspection.agent-session-test` → `5 tests, 31 assertions, 0 failures`
- attempted `clojure -M:test --focus psi.system-bootstrap.core-test`, but this Kaocha focus selector skipped all tests in this environment; dedicated coverage for that surface was added under `components/system-bootstrap/test/psi/system_bootstrap/core_test.clj`

Remaining implementation checks:
- run a broader verification pass once convenient
