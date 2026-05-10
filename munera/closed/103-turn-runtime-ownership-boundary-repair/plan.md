Approach:
- treat this as an ownership-repair slice, not a behavioral redesign
- do not add callback seams; prefer explicit lower-owned state/mutation/data surfaces
- break the component cycle first so the target dependency slope stays visible throughout the work
- classify each current `turn-runtime` dependency on `agent-session` as turn-runtime-owned, tool-domain-owned, or session-owned before moving code
- keep journal append and prompt lifecycle orchestration above the boundary
- keep tool-accounting separation explicit; do not quietly absorb it into `turn-runtime`
- default repair target is to land the re-homed lower ownership inside `components/turn-runtime/`; use a tiny lower shared namespace only if needed and record that exception explicitly

Primary repair targets:
- remove `psi/agent-session` from `components/turn-runtime/deps.edn`
- eliminate `psi.agent-session.state-accessors` requires from:
  - `psi.turn-runtime.core`
  - `psi.turn-runtime.accumulator`
- eliminate `psi.agent-session.conversation` require from `psi.turn-runtime.accumulator`
- re-home lower mutation/state ownership for:
  - turn-context state
  - tool-call-attempt telemetry
  - provider request captures
  - provider reply captures
- leave `record-tool-output-stat` out of `turn-runtime` ownership; if not moved fully in this slice, record explicit deferral toward tool-domain ownership

Allowed replacement style:
- lower-owned mutation/state APIs
- explicit data-returning lower functions
- explicit lower data surfaces applied by the owning lower layer

Disallowed replacement style:
- callback injection from `agent-session`
- runtime behavior passed downward as function hooks
- new callback registration seams introduced solely to avoid re-homing ownership

Implementation sequence:
1. inspect current dependencies and enumerate every `turn-runtime -> agent-session` edge
2. identify the lowest authoritative home for each borrowed concern
3. move generic helpers downward where they are truly lower-owned
4. move lower turn state/mutation vocabulary downward out of `agent-session`, preferably into `components/turn-runtime/`
5. update `turn-runtime` namespaces to use those lower-owned surfaces
6. update any higher-level consumers/tests/config impacted by the ownership move
7. verify no callback seams were introduced
8. run focused tests and repo/deps searches
9. record any deferred tool-accounting follow-on explicitly

Minimum verification targets:
- `psi.turn-runtime.core-test`
- `psi.turn-runtime.accumulator-test`
- `psi.agent-session.prompt-lifecycle-test`
- repo search shows no `psi.agent-session.*` requires under `components/turn-runtime/src/`
- deps/config inspection shows no `agent-session <-> turn-runtime` cycle remains

Notes:
- this task is intentionally narrower than a full tool-component extraction
- this task should land before treating task `101` as architecturally complete
- exact focused commands used must be recorded in `implementation.md`
