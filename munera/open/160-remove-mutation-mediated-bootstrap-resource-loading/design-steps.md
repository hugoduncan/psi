# 160 — Design follow-up steps

- [x] Clarify in design.md whether `extension-paths` mutation loading is converted to direct `ext-rt/add-extension-in!` or left as-is (production passes `[]`; code path exists but is untested through bootstrap)
  - Converted: design.md scope item 2 now specifies direct `ext-rt/add-extension-in!` calls; constraints updated; plan.md documents the decision
- [x] Correct design.md framing: `add-extension` mutation calls `ext-rt/add-extension-in!` directly, not `dispatch!` — it's a Pathom round-trip to a direct runtime call, not a dispatch round-trip
  - Corrected: design.md Intent now distinguishes template/skill/tool (dispatch round-trip) from extension-path (direct runtime call via Pathom)
- [x] Specify in plan.md the `:origin` value for the replacement direct dispatch calls (`:core` or `:bootstrap`)
  - Specified: `:origin :core` — consistent with existing bootstrap dispatch calls; documented in plan.md Decisions
- [x] Confirm in plan.md that dispatch return values are intentionally discarded (current code uses `doseq`; final counts read from session-data)
  - Confirmed: plan.md Decisions documents intentional discard via `doseq`; steps.md updated to note this
- [ ] Fix dispatch function inconsistency: plan.md and steps.md specify `dispatch/dispatch!` + adding `psi.agent-session.dispatch` require, but `bootstrap-in!` uses `session/dispatch-in!` throughout. Update plan and steps to use `session/dispatch-in!` and remove the `psi.agent-session.dispatch` require addition.
- [ ] Add missing `psi.agent-session.extension-runtime` require to steps.md step 1: needed for `ext-rt/add-extension-in!` calls. Currently omitted from the require changes list.
