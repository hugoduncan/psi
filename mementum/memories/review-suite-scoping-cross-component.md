🔁 When reviewing/validating a task, run the test suite that OWNS each touched area, not only the suite for the new/primary component. Focused suites scoped to "the component I'm building" silently skip guards living elsewhere that cover a cross-component or core touch.

Concretely (task 229, dev-http): the build/review ran dev-http-extension-scoped Scry suites and reported all green, but the change also touched core `agent-session` (a new mutation + the runtime extension-install catalog). The `agent-session` suite was never run — so an existing guard test (`psi-owned-extension-catalog-parity-with-launcher`) that was RED the whole time went unnoticed, shipping a broken extension that silently failed to load. The safety net existed; it just wasn't executed.

Rule: enumerate every component/base a change edits (`git diff --name-only`), then run each owning suite — `bb clojure:test:unit` (components/bases), `:extensions`, `:integration` — or the focused `bb clojure:test:scry --namespace <ns>` for the specific guard. A green "my component" suite is not evidence the cross-component contract holds.

Corollary: when adding a coupling that a parity/guard test enforces, document the constraint AT both coupled sites (docstring naming the other site + the test), so the next editor sees it without rediscovery.
