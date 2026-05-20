# Design review follow-ups — 161

- [ ] A1: Fix Intent section — correct "three times" to match actual count (2 builds, 4 persists); fix "rebuilds from build-opts" annotation on PERSIST #3 (no build-opts exist at that point)
- [ ] A2: Decide prompt-contribution application strategy — target flow uses `:session/bootstrap-prompt-state` which skips prompt contributions; clarify whether to use `:session/set-system-prompt`, add a `:session/refresh-system-prompt` after, or another approach
- [ ] A3: Decide whether individual `:session/add-tool` dispatches in `load-startup-resources-in!` are kept or removed when `:session/set-active-tools` overwrites the full set
- [ ] A4: Decide the fate of `bootstrap-in!` — keep as test-oriented subset, eliminate and update 7 test redefs, or restructure; state the choice in design
- [ ] A5: Clarify what "load resources" means in the target flow — which parameters of `load-startup-resources-in!` are used, and whether tools/extension-paths/extension-targets are included or excluded
- [ ] A6: Confirm that `developer-prompt` and `developer-prompt-source` are explicitly passed in the target flow's `:session/bootstrap-prompt-state` dispatch
