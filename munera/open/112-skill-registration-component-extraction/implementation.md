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

Implementation notes — 2026-05-07
- created new lower component `components/skill-registry/` with authoritative namespace `psi.skill-registry.registry`
- registry API is intentionally pure over vectors of already-constructed skill maps: `valid-skill-name?`, `all-skills`, `find-skill`, `skill-names`, `skill-count`, and `register-skill`
- first-cut validation is minimal and local to registration: `:name` must be a non-blank string; discovery-time prompt-assets validation remains unchanged and lower-owned there
- `register-skill` preserves the design contract exactly: first registration per name wins, duplicates are ignored, ordering is preserved, and the return shape includes `:added?`, `:changed?`, and `:count`
- `agent-session` `:session/register-skill` now delegates registry semantics to `psi.skill-registry.registry/register-skill` and keeps prompt-refresh orchestration local by only emitting `:runtime/refresh-system-prompt` when `:changed?` is true
- `prompt-assets.skills` remains the owner of discovery/parsing/validation/invocation semantics, but trivial registered-skill read helpers `find-skill` and `skill-names` now delegate downward to the new registry owner
- follow-on ownership cleanup kept going one small step further: `agent-session` registry-shaped consumers in `resolvers.discovery` and `workflow-step-prep` now depend directly on `psi.skill-registry.registry` instead of reaching that collection behavior through the prompt-assets wrapper
- focused follow-on verification after that cleanup stayed green: `psi.prompt-assets.skills-test`, `psi.agent-session.config-compaction-test`, and `psi.agent-session.workflow-step-prep-test` (`32 tests, 204 assertions, 0 failures`)
- added focused `skill-registry` tests for validation, registration, duplicate-ignore behavior, ordering, lookup, and count
- extended `agent-session` config-compaction coverage to assert the new return contract for first registration and to prove duplicate registration leaves prompt/session skill state unchanged and does not trigger follow-on refresh events
- repository test/deps surfaces were updated to include the new component in root/test resolution
