# 160 — Design follow-up steps

- [ ] Clarify in design.md whether `extension-paths` mutation loading is converted to direct `ext-rt/add-extension-in!` or left as-is (production passes `[]`; code path exists but is untested through bootstrap)
- [ ] Correct design.md framing: `add-extension` mutation calls `ext-rt/add-extension-in!` directly, not `dispatch!` — it's a Pathom round-trip to a direct runtime call, not a dispatch round-trip
- [ ] Specify in plan.md the `:origin` value for the replacement direct dispatch calls (`:core` or `:bootstrap`)
- [ ] Confirm in plan.md that dispatch return values are intentionally discarded (current code uses `doseq`; final counts read from session-data)
