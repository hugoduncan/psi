# Plan

1. Define the target shared registry component in terms of state ownership, identity, validation boundaries, and normalized semantics.
2. Specify the shared root-state storage shape, operation set, and uniform result contracts.
3. Assess the current registries against the new target to identify true direct adopters versus registries that require adapter layers because of ordered/public compatibility semantics.
4. Keep unknown-registry semantics consistent across the storage model and operation contracts by requiring consuming-layer registry initialization rather than first-write creation.
5. Refine the design until it is implementation-guiding for a later standalone component build task.
