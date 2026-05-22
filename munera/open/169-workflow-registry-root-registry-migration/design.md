# 169 workflow registry root registry migration

## Intent

Migrate `workflow-registry` to use the shared `root-registry` component as its lower storage owner, while preserving the current `workflow-registry` public behavior.

This task should be the next root-registry-style adoption after `167-command-registry-root-registry-migration` and `168-tool-registry-root-registry-migration`.

## Context

Task `164-registry-semantics-unification-audit` now records two important conclusions relevant here:

- `workflow-registry` is the next best root-registry-style migration target
- future registry migrations must guard against stale higher read/introspection seams, not just storage rewrites

Task `166-root-registry-component-build-task` implemented the shared lower substrate with:

- explicit registry declaration
- root-state storage
- globally unique ids per registry
- targeted lookup/list/register/unregister helpers
- explicit lower result contracts
- built-in support where needed via artificial ownership

Tasks `167` and `168` then established the current migration pattern:

- `root-registry` owns storage and lower mutation/query mechanics
- the adopter registry keeps its validation, normalization, and compatibility projection
- public behavior is preserved at the adopter boundary rather than leaking raw `root-registry` semantics

Task `115-workflow-registration-component-extraction` is also relevant here because it intentionally preserved the first-cut `workflow-registry` contract:

- pure root-state API
- normalized `:definition-id`
- blank or missing ids generating UUIDs
- replacement semantics on repeated registration at the same normalized id
- public listing sorted by `:definition-id`
- nil lookup miss
- lower remove helper throwing on missing definition
- tuple-shaped lower return contract

That makes `workflow-registry` a strong substrate-adoption candidate, but not an identical migration to `167`/`168`.

Unlike command/tool registries:

- `workflow-registry` is already a pure root-state registry rather than a mutable extension-owned registry object
- it has no built-in-versus-extension merged surface to preserve
- its main compatibility surface is normalized identity, tuple-shaped pure operations, sorted reads, and current miss/remove behavior

## Scope

This task includes:

- refactoring `workflow-registry` internals to use `root-registry` as the lower storage owner
- preserving current `workflow-registry` public behavior at its adapter boundary
- keeping workflow-definition validation and id normalization explicit at the `workflow-registry` layer where they differ from generic shared substrate behavior
- updating focused `workflow-registry` tests to prove preserved boundary semantics
- updating any higher consumer seams that should read through `workflow-registry` rather than legacy direct root-state shape where needed
- adding migration-guard coverage for higher read/projection seams if any still implicitly depend on legacy workflow-definition storage shape

This task does not include:

- redesigning workflow-definition authoring or compilation
- changing workflow-run execution/progression semantics
- migrating `deterministic-operation-registry`
- broad workflow tool / resolver redesign unrelated to storage-owner migration
- changing user-visible workflow behavior except where necessary to preserve existing semantics on the new substrate

## Desired outcome

At the end of this task:

- `workflow-registry` uses `root-registry` internally
- current caller-facing `workflow-registry` behavior remains intact
- workflow-definition validation, id normalization, and compatibility semantics remain explicit at the `workflow-registry` boundary
- tests clearly distinguish shared storage behavior from workflow-registry adapter behavior
- higher read surfaces no longer depend on legacy direct workflow-definition storage shape

## Migration direction

The intended shape is:

- `root-registry` owns canonical storage and lower keyed mutation/query mechanics
- `workflow-registry` owns workflow-definition-specific validation, id normalization, sorted projection, and compatibility behavior
- any tuple-shaped pure return contract that callers currently depend on remains preserved at the `workflow-registry` boundary even if `root-registry` uses different lower result shapes internally

A likely first-cut storage shape is one declared root registry for workflow definitions, for example under a workflow-specific registry id, with one entry per normalized `:definition-id`.

This task should preserve behavior, not force a particular wrapper shape beyond what is needed for clarity and compatibility.

## Key design constraints

### Preserve workflow-registry boundary behavior

This migration should preserve current `workflow-registry` behavior, including at minimum:

- pure root-state ownership style
- id normalization to a canonical string form
- blank or missing ids continuing to generate UUIDs
- validation of registered definitions via the current target-authored workflow-definition predicate
- repeated registration at the same normalized id replacing the prior stored definition
- public lookup normalizing caller-provided ids and returning `nil` on miss
- `list-definitions` and `definition-ids` remaining sorted by `:definition-id`
- lower remove behavior remaining explicit, including current throw-on-miss semantics where preserved by the current public API
- current tuple-shaped lower return contract if still caller-visible

The task should preserve the current public contract, while treating raw storage shape as internal.

### Keep workflow-specific semantics out of shared storage

`root-registry` should not absorb:

- workflow-definition validation rules
- workflow id normalization policy
- blank-id UUID generation policy
- workflow-specific sorted read projection
- workflow-specific tuple-shaped adapter return contract

Those belong at the `workflow-registry` boundary unless later intentionally redesigned.

### Apply the 164 migration checklist

This migration must follow the explicit guidance added to task `164` after tasks `167` and `168`:

- name the new authoritative owner
- enumerate all write seams
- enumerate all read/projection/introspection seams
- classify compatibility requirements per seam
- add seam-level migration-guard tests
- prove higher seams no longer read legacy local storage shape
- run focused and full-suite verification before close

This is especially important because `workflow-registry` already lives in root-state, which makes stale direct-state reads harder to notice than in tool/command migrations.

## Known design questions to resolve during refinement

This task should answer these explicitly with caller and test evidence:

- whether the current tuple-shaped lower API must remain exactly preserved or only adapter-equivalent
- whether throw-on-missing remove semantics are truly required at the current lower boundary or only at public adapters
- which higher workflow resolver / mutation / psi-tool seams currently read raw workflow-definition state and must be redirected or regression-tested
- whether any legacy direct root-state reads should be removed entirely in favor of the workflow-registry query helpers
- whether `root-registry` entry result shapes should remain completely hidden from workflow-facing callers

## Acceptance

This task is complete when:

- `workflow-registry` is backed by `root-registry`
- focused tests prove preserved `workflow-registry` public behavior after migration
- workflow-definition-specific validation and normalization remain clearly separate from shared root-registry concerns
- higher workflow read/projection seams no longer depend on legacy direct workflow-definition storage shape
- the migration follows the `164` seam-inventory guidance and records any preserved adapter-owned compatibility behavior
- no unrelated registry migration is bundled into this task

## Non-goals

This task is not asking for:

- changing workflow authoring rules
- changing workflow runtime semantics
- redefining workflow ids beyond preserving current behavior
- migrating deterministic operations into root-registry
- speculative architecture beyond the shared registry substrate already built and the workflow-registry compatibility layer needed for a safe migration
