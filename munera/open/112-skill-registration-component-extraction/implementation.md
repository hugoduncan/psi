2026-05-07

Task created to extract registry-style skill ownership into a lower component.

Creation rationale:
- recent tool-registration work established a useful split between registry ownership and higher-level orchestration
- skills now appear to have the same missing lower seam: discovery/parsing already live below, but registration-by-name still lives inline in `agent-session`
- this task isolates that seam without broadening into a full prompt-assets redesign

Initial boundary hypothesis was reviewed and tightened after a design pass.

Settled first-cut boundary decisions:
- new lower owner: `skill-registry` for pure registered-skill collection operations
- this is not a new long-lived stateful runtime registry in the first cut
- existing lower owner retained: `prompt-assets.skills` for discovery, parsing, validation, prompt-facing enrichment, and invocation helpers
- higher owner retained: `agent-session` for mutation entrypoints, session-state updates, orchestration, and prompt-refresh side effects

Settled first-cut registration contract:
- a registered skill is an already-constructed skill map with at least `:name`
- `:name` must be present and a non-blank string
- session registration adds a skill only when its `:name` is not already present
- duplicate names are ignored rather than replaced or merged
- duplicate registration does not reorder the existing entry
- registered skills preserve first-registration order
- the lower API should report whether the skill set changed so `agent-session` can keep prompt-refresh orchestration unchanged

Explicit non-goals after review:
- do not move `SKILL.md` parsing or filesystem discovery into the new component
- do not move `/skill:name` invocation expansion into the new component
- do not move prompt-facing enrichment/summary behavior into the new component unless a helper is clearly just a registry-shaped collection query
- do not require a broad canonicalization pass over stored skill maps in the first cut

Read-path note after review:
- the primary target is write-path/registration extraction
- trivial read-path delegation is acceptable where it sharpens ownership with low risk
- broad command/resolver cleanup is not required for task completion

Relationship to umbrella work:
- this should become a concrete child/refinement under `105-agent-session-component-extraction-map`
- it sharpens the prompt/skills boundary in the same way `tool-registry` sharpened the tool boundary, while intentionally remaining a pure collection-oriented component rather than a stateful runtime registry
