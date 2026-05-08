2026-05-07

Task created to extract prompt-contribution registration semantics into a lower component.

Creation rationale:
- prompt contribution registration is currently embedded in `agent-session` prompt handlers alongside system-prompt rebuild side effects
- this mixes pure registration semantics with higher-level orchestration
- recent registry extractions suggest a cleaner split where lower pure components own registration/query semantics and `agent-session` keeps orchestration and effects

Initial boundary hypothesis:
- new lower owner: `prompt-registry` for pure prompt-contribution collection semantics
- lower component remains a pure API over stored contribution vectors rather than a new stateful runtime registry in the first cut
- higher owners retained: `agent-session` for dispatch entrypoints, session-state updates, prompt refresh, and runtime effects; `prompt-assets.system-prompt` for prompt assembly semantics

Settled scope choice at task creation:
- focus on extension-owned prompt contributions only
- do not include prompt template registration in this first cut
- do not widen into prompt lifecycle or turn ownership

Important implementation note to confirm during execution:
- verify and record the current exact count/reporting behavior in register/update/unregister handlers before migration
- preserve that behavior intentionally or document any discovered defect if a correction becomes necessary

Relationship to umbrella work:
- this should become a concrete child under `105-agent-session-component-extraction-map`
- it sharpens the remaining prompt boundary similarly to how `112` sharpens the session-local skill-registration boundary
