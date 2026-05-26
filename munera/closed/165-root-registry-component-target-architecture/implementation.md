# Implementation

Created the task and seeded it with the target architecture intent for a standalone shared registry component.

Initial direction captured in the design:

- single long-lived root-state storage supporting multiple registries
- extension-tracking identity with globally unique ids inside each registry
- strict shared semantics with registry-specific entry validation
- no duplicate coexistence; re-register replaces
- nil lookup miss
- targeted unregister, clear by extension, and global clear
- operation results return explicit status and value
- built-ins represented through artificial `:built-in` ownership
- no invoke behaviour in the shared component

Next step:

- refine the design into a precise state model, operation contract, and adopter/migration assessment for the current registries

2026-05-21 ambiguity review:

- Actionable ambiguities: re-register owner-change rule/result contract unspecified; unknown-registry query vs mutation semantics unspecified; adopter assessment requested by design is still not concretely recorded per current registry.

2026-05-21 ambiguity follow-up:

- Updated `design.md` with explicit owner-conflict semantics for re-register: same-id replacement is allowed only when ownership stays the same; owner changes fail with `:failure-kind :ownership-conflict`.
- Defined unknown-registry semantics precisely: lookup treats unknown registry as nil miss; mutating operations and list fail with `:failure-kind :unknown-registry`.
- Added concrete adoption assessment for tool, command, skill, prompt, workflow, and deterministic-operation registries, distinguishing direct adopters from adapter-backed adopters.
- The design is now implementation-guiding enough to mark the main task steps complete; no ambiguity follow-up remains from this pass.

2026-05-21 inconsistency review:

- Actionable inconsistencies: adopter assessment conflicts with current prompt/skill registry shapes (`prompt-registry` is currently ordered contributions, while `skill-registry` is currently registration-order-first-wins rather than id-keyed replace-by-id); storage model also conflicts on unknown-registry mutation because first-successful-registration initialization is described as optional while mutation semantics require unknown-registry failure.

2026-05-21 inconsistency follow-up:

- Reclassified `skill-registry` from direct adopter to adapter-backed adopter, based on task `164`'s audit evidence that its current public contract is an ordered collection with duplicate-ignore and behaviorally meaningful `:changed?` / `:added?` outcomes.
- Reframed `prompt-registry` more explicitly as adapter-backed rather than near-direct adoption, because its current contract centers ordered contributions, composite identity, and canonical priority sorting that must stay above any shared id-keyed storage layer.
- Sharpened the direct-adopter set to registries whose core contract is already one-active-entry-per-id keyed storage (`tool-registry`, `command-registry`, and likely `workflow-registry`), while leaving `deterministic-operation-registry` adapter-backed because of its stronger object/invoke/order compatibility surface.
- Removed the inconsistent "first successful registration may initialize the registry" allowance. `design.md` now requires consuming-layer registry declaration/initialization, while shared-component operations uniformly treat unknown registries as failures for mutation/list and as empty only for lookup-by-id.
- Both inconsistency-review follow-up items are now resolved in task artifacts; no blocking design-step remains from this pass.
