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
