2026-05-07

Task created to extract registry-style command ownership into a lower component.

Creation rationale:
- recent registry extractions clarified a useful split between lower registration/query ownership and higher orchestration
- commands still appear to have the same missing lower seam that tools recently had
- current command registration and command listing/query helpers live inside `agent-session.extensions`, which is broader than command ownership alone
- this task isolates that seam without broadening into command dispatch or a generic extension-registry redesign

Initial boundary hypothesis:
- new lower owner: `command-registry` for extension-owned command registration/query semantics
- higher owners retained: `agent-session`, RPC, and UI layers for orchestration, invocation, and presentation
- mutation/API seams retained above the boundary as thin adapters unless implementation reveals a clearly better bounded split

Open design point to resolve during implementation:
- confirm and record the exact duplicate/query semantics for commands, especially across multiple extensions registering the same command name
- do not assume the final contract; extract it from the live code/tests and preserve it intentionally

Relationship to umbrella work:
- this should become a concrete child under `105-agent-session-component-extraction-map`
- it is the command-side parallel to `111-tool-registration-component-extraction`

2026-05-08

Implementation landed.

What changed:
- created new lower component `components/command-registry/`
- added authoritative namespace `psi.command-registry.registry`
- moved canonical command registration, validation, name-query, listing, and lookup semantics below `agent-session`
- rewired `agent-session.extensions` command helpers into thin compatibility seams that delegate downward
- rewired direct higher-level consumers to depend on `psi.command-registry.registry` where appropriate:
  - `agent_session.commands`
  - `agent_session.mutations.extensions`
  - `agent_session.resolvers.extensions`
  - `psi.turn.handlers`
  - `psi.rpc.events`
- wired the new component into root/component deps and into `tests.edn`

Final first-cut command contract made explicit in code/tests:
- `ext-path` must already be registered before command registration
- `:name` is required and must be a non-blank string
- invalid registration throws structured `ex-info`
- command identity is exact `:name` string equality
- no slash-prefix normalization is applied
- same-extension duplicate registration replaces the prior stored entry
- cross-extension duplicate registration is allowed
- cross-extension lookup and full listing remain first-registration-wins by extension registration order
- command-name query remains set-like across all registered command names
- missing lookup returns `nil`
- the registry preserves extra command-map keys; it does not introduce broader command-completeness validation for fields like `:handler`

Registration result contract decision:
- kept parity with the existing tool-registry seam and existing callers: registration returns the mutated registry value, not a richer report map
- explicit behavior is instead pinned through focused tests over resulting registry state and query helpers

Verification notes:
- added focused component-local registry tests in `components/command-registry/test/psi/command_registry/registry_test.clj`
- one early Kaocha invocation using `--focus unit --focus <ns>` was misleading because focus terms OR together, so it exercised unrelated unit tests; that surfaced an unrelated pre-existing failure in `psi.agent-session.runtime-test`
- after wiring `tests.edn` to include the new component, an affected targeted unit namespace run passed cleanly:
  - `clojure -M:test --focus unit --focus psi.agent-session.extensions-test --focus psi.agent-session.commands-test --focus psi.agent-session.extensions-io-test --focus psi.bootstrap-extension-invariant-test --focus psi.rpc-events-test`
  - result: `1567 tests, 11981 assertions, 0 failures`

Notable tradeoff:
- kept `agent-session.extensions` command helpers as thin compatibility seams for this cut rather than deleting them immediately, matching the project’s recent extraction pattern and keeping the mutation/API seam stable while moving authoritative ownership downward
