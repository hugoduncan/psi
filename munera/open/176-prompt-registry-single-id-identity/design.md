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

## Refined contract

The refined design makes the targeting/compatibility split explicit.

### Canonical identity and normalization

- Canonical prompt contribution identity is `id` alone.
- Canonical stored `id` remains the current string-coerced form: `(str id)`.
- Nil and blank-like ids remain accepted for this task's first semantic simplification pass, preserving the current `str`-coercion behavior rather than adding a second behavioral change at the same time.
- `ext-path` remains stored on each contribution as provenance/ownership metadata, but it is not part of identity.

### Duplicate and conflict behavior

- Same-owner duplicate registration by `id` replaces the existing contribution for that `id`.
- Cross-owner duplicate registration by `id` is an explicit ownership conflict and must not silently coexist.
- The required external contract at prompt-registry, lower dispatch, and Pathom mutation seams is deterministic registration-time failure via the existing thrown ownership-conflict shape rather than a structured non-throwing failure result.
- Callers at those seams must therefore treat cross-owner duplicate registration as an exceptional path; the conflicting registration does not take effect.

### Canonical targeting after the change

- Canonical lookup, update, and unregister targeting is by `id` alone.
- Public prompt-registry helpers and extension-facing API surfaces should expose only single-id targeting.
- Ownership checks still apply where a caller is acting through an owner-scoped API: an extension may update or unregister only the contribution currently owned by that extension for the requested `id`.
- Provenance/introspection surfaces may still display `ext-path`, but they must not require `ext-path` as part of the targeting contract.

### Deterministic ordering

- Canonical ordering should no longer treat owner as part of identity.
- Primary deterministic order remains `priority`.
- The design target is `[priority id]` for canonical ordering, with owner/provenance available for display but not used to preserve former composite-identity behavior.
- If an implementation seam still carries `ext-path` temporarily, that temporary shape must not imply owner-qualified identity or coexistence semantics.

### Caller-surface targeting and temporary compatibility

The affected surfaces split into two groups.

1. **Already single-id extension-facing surfaces that should stay single-id-only**
   - Extension API helpers documented in `doc/extensions.md` and created in `components/agent-session/src/psi/agent_session/extensions/api.clj`:
     - `:register-prompt-contribution`
     - `:update-prompt-contribution`
     - `:unregister-prompt-contribution`
     - `:list-prompt-contributions`
   - Built-in workflow prompt-contribution registration helper in `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`, which supplies owner provenance internally but should continue to present a contribution-level single-id contract.
   - Extension-facing documentation/examples that already talk in terms of stable contribution ids.

   These surfaces are intentionally preserved as single-id-only. They should not grow owner-qualified targeting to accommodate lower-level compatibility.

2. **Lower-level surfaces that currently still accept `ext-path` and therefore need explicit migration/temporary compatibility treatment**
   - Session dispatch handlers/events in `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`:
     - `:session/register-prompt-contribution`
     - `:session/update-prompt-contribution`
     - `:session/unregister-prompt-contribution`
   - Pathom mutations in `components/agent-session/src/psi/agent_session/mutations/prompts.clj`:
     - `psi.extension/register-prompt-contribution`
     - `psi.extension/update-prompt-contribution`
     - `psi.extension/unregister-prompt-contribution`
   - Test/helper surfaces that currently model contribution identity as `ext-path + id`, such as the nullable extension API helper state and mutation tests.

   For these lower-level seams, temporary compatibility may continue to accept `ext-path` as supplied owner/provenance metadata while the implementation is being migrated, but not as a second identity coordinate. In other words:
   - register: `ext-path` may still be passed to record/check ownership, but duplicate/conflict resolution is keyed by canonical `id`
   - update/unregister: `id` is the target; any passed `ext-path` is only an ownership assertion/check, not part of lookup identity
   - docs for these seams should be updated so post-change callers do not infer that `ext-path + id` remains canonical targeting

### Query and projection surfaces

- Session/query/introspection projections may continue to expose `:psi.extension.prompt-contribution/ext-path` for provenance.
- Resolver/query output ordering should align with the canonical post-change ordering rule rather than preserving composite-identity ordering semantics.
- Session prompt-component selection `:extension-prompt-contributions` remains an owner allowlist by `ext-path`; it filters which owners contribute prompt text, but it does not change prompt contribution identity.

## Design questions to resolve

This task now treats the following points as resolved and implementation-guiding:

- canonical prompt contribution `id` is string-coerced `id` alone
- nil/blank ids remain accepted in this pass
- same-owner duplicate registration replaces
- cross-owner duplicate registration conflicts explicitly and does not take effect
- lookup/update/unregister target by `id`, with owner checks where relevant
- owner information remains stored for provenance/introspection/cleanup
- canonical ordering target is `[priority id]`
- only lower-level legacy seams may temporarily continue accepting `ext-path`, and only as provenance/ownership input, not identity

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
