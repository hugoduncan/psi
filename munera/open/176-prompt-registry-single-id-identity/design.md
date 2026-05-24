# 176 prompt-registry single-id identity

## Intent

Simplify `prompt-registry` so its identity model matches `root-registry` more closely by removing composite prompt-contribution identity and disallowing cross-owner same-id coexistence.

Today prompt contributions are identified by `ext-path + id`, which makes ownership part of identity and allows different extensions to register the same prompt contribution `id` without conflict. This task should instead make contribution `id` the sole registry identity and treat extension ownership as metadata/ownership, not as part of identity.

## Scope

In scope:

- the `prompt-registry` identity model and normalization rules
- duplicate/conflict behavior for prompt contribution registration
- lookup/update/unregister APIs and callers that currently address contributions by `ext-path + id`
- tests and higher-level projections that depend on the current composite identity contract
- any prompt-lifecycle or session-state seams that rely on `[priority ext-path id]` ordering or on owner-qualified identity targeting
- migration/design consequences for eventual `root-registry` adoption

Out of scope:

- performing the actual `root-registry` migration
- broader prompt-lifecycle redesign beyond what is required by the identity simplification
- unrelated prompt content/section rendering changes

## Desired outcome

After this task:

- prompt contributions are identified by canonical prompt contribution `id` alone
- registering a contribution with an existing `id` cannot coexist across owners
- ownership conflicts are explicit and deterministic rather than silently coexisting behind composite identity
- public prompt-registry APIs and higher callers have one clear targeting model for lookup/update/unregister
- the resulting contract is simpler and materially closer to `root-registry`’s single-id, owner-aware model
- the design clearly records which current behaviors are intentionally removed versus preserved for compatibility

## Required behavioral change

This task specifically changes two current prompt-registry semantics:

1. **Remove composite identity**
   - `ext-path` is no longer part of prompt contribution identity
   - canonical identity is prompt contribution `id`

2. **Disallow cross-owner same-id coexistence**
   - two different owners/extensions cannot both register the same contribution `id`
   - the design must choose and specify the conflict behavior for this case

## Design questions to resolve

The refined design must make the following points unambiguous:

- What is the canonical prompt contribution `id` type and normalization rule?
- Are nil/blank ids still accepted, rejected, or normalized differently?
- On duplicate `id` registration from the same owner, does registration replace the existing contribution or become a no-op?
- On duplicate `id` registration from a different owner, does registration throw, return a structured failure result, or use some other explicit conflict contract?
- How are lookup, update, and unregister targeted after removing `ext-path` from identity?
- Is owner information still stored on contributions for provenance/introspection/cleanup even though it is no longer identity?
- Does canonical ordering remain `[priority ext-path id]`, become `[priority id]`, or use another deterministic order now that owner is no longer identity?
- What compatibility, if any, is preserved at adapter boundaries for callers that currently pass `ext-path + id`?

## Task artifact scaffolding

This task is not intentionally exempt from standard Munera execution artifacts.
`plan.md`, `steps.md`, and `implementation.md` are required before implementation or further design-review passes continue, and their absence before this follow-up was task scaffolding drift rather than a design choice.

## Constraints

- Prefer one obvious identity model over compatibility-shaped ambiguity.
- Do not preserve cross-owner same-id coexistence behind hidden translation layers.
- Keep ownership/provenance explicit even if it is no longer identity.
- Any compatibility path that remains must be narrow, temporary, and explicitly justified.
- The design should move `prompt-registry` toward easier `root-registry` adoption, not introduce new prompt-specific identity complexity.

## Acceptance criteria

- The design specifies a single canonical prompt contribution identity based on `id` alone.
- The design specifies explicit same-owner and cross-owner duplicate-registration behavior.
- The design specifies post-change lookup/update/unregister targeting behavior.
- The design identifies all caller-visible surfaces affected by removing `ext-path` from identity.
- The design specifies the deterministic ordering rule after the identity change.
- The design leaves a clear follow-on path for implementation and later `root-registry` migration.
