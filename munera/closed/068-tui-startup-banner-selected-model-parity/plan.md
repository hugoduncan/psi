Approach:
- Fix this as a source-of-truth convergence task, not as a one-off string replacement.
- Make the startup banner consume the same canonical live model surface the TUI already receives via `:footer-model-fn`.
- Define the banner model line contract as exactly `[:footer/model :text]`.
- Remove stale banner-model state so the TUI no longer carries incorrect local model information.
- Keep the rest of the banner intentionally mixed for now: model is live/canonical, while prompt/skill/extension summaries remain startup snapshots.
- Prove the banner follows the effective current session model via canonical footer data rather than init-time launch data.

Execution shape:
1. confirm the current stale path end-to-end
- verify the startup banner model line is currently driven from TUI-local `:model-name`
- verify the live canonical model surface is already available through `:footer-model-fn`
- identify whether `:model-name` has any remaining legitimate use outside banner rendering

2. converge banner rendering on canonical model data
- update the banner render path to derive model text from `(:footer-model-fn state)`
- use the canonical footer-model model text as the banner model line source
- keep the render path simple and obvious: one authoritative source for the banner model line

3. remove incorrect local state
- remove banner dependence on `:model-name`
- remove `:model-name` from TUI state if it is unused after the rendering change
- keeping or removing the external `make-init` / `start!` `model-name` parameter is an implementation choice, but it must no longer influence rendered banner model output
- do not keep a stale local fallback source just because it existed before

4. add focused proof
- add a render/view test showing the banner displays exactly canonical `[:footer/model :text]`
- during the transition, if needed, use a conflicting init `model-name` in the test to prove the local value is ignored; then remove that local state/assertion once cleanup lands
- add a test showing expected default/effective current session model rendering still works
- add a stronger proof that mutating the footer-model source changes the banner without rebuilding init state

5. regression check
- keep existing startup banner content intact
- run relevant TUI tests covering banner/view behavior
- ensure no new parallel model-label logic has been introduced

Implementation notes:
- Prefer removing incorrect information over preserving compatibility with stale local state.
- Current inspection suggests `:model-name` is only used inside the TUI banner path plus tests/helpers that pass it through; if that remains true during implementation, remove it rather than retaining dead state.
- Do not add new model-resolution logic to the TUI.
- Do not rebuild provider/model/thinking labels locally when canonical footer projection already exists.
- Do not expand this slice into live prompt/skill/extension banner projection work; those remain startup snapshot lines for now.
- Completion should leave one obvious answer to “where does the banner model line come from?”

Risks:
- accidentally leaving `:model-name` in place and silently reintroducing drift later
- mixing banner model text from the footer path with other banner metadata still sourced locally in a way that obscures ownership
- preserving a fallback that becomes the de facto path again

Verification shape:
- focused TUI render/view tests first
- then relevant TUI test suite execution for regression confidence
