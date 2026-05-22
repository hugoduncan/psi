# 168 tool registry root registry migration

## Intent

Migrate `tool-registry` to use the shared `root-registry` component as its lower storage owner, while preserving the current `tool-registry` public behavior.

This task should be the next direct adoption of the shared registry substrate after `167-command-registry-root-registry-migration`.

## Context

Task `164-registry-semantics-unification-audit` identified `tool-registry` and `command-registry` as the strongest shared-implementation candidates.

Task `166-root-registry-component-build-task` implemented the standalone `root-registry` substrate with:

- explicit registry declaration
- root-state storage
- globally unique ids per registry
- required extension ownership
- nil lookup miss
- targeted unregister, clear-by-extension, and clear-registry
- explicit result maps at the lower boundary
- built-in support via artificial `:built-in` ownership
- no ordering semantics in shared storage

Task `167-command-registry-root-registry-migration` then proved the intended adoption pattern:

- `root-registry` owns storage and lower mutation/query mechanics
- the adopter registry keeps validation, canonicalization, and compatibility projection
- current public semantics stay preserved at the adopter boundary rather than leaking raw `root-registry` behavior

`tool-registry` is the clearest next migration target because its current shape is very close to `command-registry`:

- built-ins and extension-owned tools are projected through one merged visible read surface
- built-ins shadow same-name extension entries on merged read paths
- lookup miss returns `nil`
- extension-owned registration requires an already-registered extension path
- public queries (`tool-names-in`, `all-tools-in`, `get-tool-in`) are more important than the exact internal storage shape

However, `tool-registry` also has tool-specific behavior that must remain local to the adapter layer during migration:

- tool names must be canonical kebab-case ASCII
- registered tool defs must include a `:format-request` function after normalization
- canonical tool normalization via `psi.tool-registry.defs/normalize-tool-def` must remain authoritative
- merged read paths currently expose rich normalized tool maps used by prompt, provider, workflow, and runtime callers

## Scope

This task includes:

- refactoring `tool-registry` internals to use `root-registry` as the lower storage owner
- preserving current tool-registry public query and registration behavior
- keeping tool-specific validation and normalization in `tool-registry` / `tool-registry.defs`, not in `root-registry`
- updating focused `tool-registry` tests to prove preserved boundary semantics
- adding or adjusting lower integration tests where needed to prove the new internal storage path behaves correctly
- recording any compatibility behavior that must remain adapter-owned after migration

This task does not include:

- intentionally redesigning `tool-registry` public behavior beyond what is necessary for migration safety
- broadening `root-registry` semantics to absorb tool-specific validation or normalization
- migrating `workflow-registry`, `prompt-registry`, `skill-registry`, or `deterministic-operation-registry`
- changing adopter-facing call sites unless needed for internal migration
- unrelated tool-definition redesign outside the storage-owner migration

## Desired outcome

At the end of this task:

- `tool-registry` uses `root-registry` internally
- current caller-facing `tool-registry` behavior remains intact
- tool-specific validation, normalization, and merged-surface compatibility remain explicit in `tool-registry`
- tests make clear which behavior belongs to shared storage versus the `tool-registry` adapter boundary
- the migration pattern from `167` is reused without collapsing tool-specific semantics into the shared layer

## Migration direction

The intended shape is:

- `root-registry` owns canonical storage and lower mutation/query mechanics
- `tool-registry` owns tool-name validation, canonical tool normalization, and compatibility projection
- built-ins are represented through artificial built-in ownership in the lower layer, but remain exposed through current built-in tool APIs and merged read surfaces
- any required merged ordering or precedence behavior is implemented in the `tool-registry` adapter layer if it does not fall out directly from unordered shared storage

A likely first-cut storage shape is one root-registry entry per owner, mirroring the successful command-registry migration:

- extension-owned entry keyed by extension path with nested `:tools` map
- built-in-owned entry keyed by built-in provenance id with nested `:tools` map

But this task should preserve behavior, not force that exact shape if a clearer adapter form emerges.

## Key design constraints

### Preserve boundary behavior

This migration should preserve current `tool-registry` public behavior, including at minimum:

- canonical tool-name validation using current kebab-case ASCII rules
- rejection of unregistered extension paths for extension-owned tool registration
- rejection of tool definitions missing required `:format-request`
- canonical normalization through `psi.tool-registry.defs/normalize-tool-def`
- nil lookup miss from `get-tool-in`
- visible merged built-in + extension tool surface
- merged read-path precedence where built-ins shadow same-name extension tools
- merged listing behavior for `all-tools-in`: built-ins first, then extension tools in first-encounter order by extension registration order
- existing rich normalized tool maps on public read paths, including the built-in/extension provenance fields callers already consume

The task should distinguish true public contract from incidental storage details and preserve only the former.

### Keep tool-specific validation out of shared storage

`root-registry` should not become responsible for:

- tool-name regex rules
- `:format-request` requirements
- normalization of tool defs
- tool-specific prompt/runtime projection fields

Those belong at the `tool-registry` boundary.

### Move storage responsibility down

The migration should remove `tool-registry`-specific storage ownership where `root-registry` now owns it.

In particular, it should avoid reintroducing a parallel `:built-in-tools` plus `:extensions ... :tools` storage scheme once the lower shared registry is available.

### Keep compatibility at the adapter layer

Any current behavior that differs from normalized `root-registry` semantics should remain explicit in `tool-registry` code rather than being pushed back down into the shared component.

## Known design questions to resolve during refinement

The audit in `164` already narrowed a few tool-specific unknowns that this task should settle explicitly:

- whether same-owner duplicate tool registration should be treated as preserved replacement behavior or only as an internal policy so long as public merged reads remain unchanged
- whether multi-provenance built-in ordering in `all-tools-in` needs explicit proof, mirroring the follow-up that closed `167`
- whether any current public callers depend on exact built-in tool map shape beyond the already-tested `:source` / provenance fields

The task should answer these with tests and caller evidence rather than guesswork.

## Resolved compatibility points from ambiguity review

The current code and focused tests already expose enough boundary behavior to pin the following migration expectations.

### Built-in ordering rule in `all-tools-in`

Built-in ordering is part of the preserved public behavior for this migration.

The required rule is:

- `all-tools-in` lists all visible built-in tools before any extension-owned tools
- within the built-in segment, first encounter order follows built-in provenance registration order as it exists in registry storage
- within each provenance entry, tool order follows the owner-local map encounter order used by current storage
- after built-ins, extension-owned tools are projected in extension registration order
- duplicate names remain first-visible-wins across the merged read surface, so an earlier built-in shadows later built-ins and all same-name extension tools

This does not claim a stronger shared-storage ordering guarantee from `root-registry`; it pins the adapter-level merged projection that migration tests should preserve.

### Required provenance and read-shape fields

The preserved public read shape for tool-registry lookups/listings is:

- all public tool maps continue to include normalized `:name`, `:label`, `:description`, `:parameters`, and required runtime `:format-request`
- built-in public reads carry `:source :built-in` and `:ext-path <provenance-id>`
- extension-owned stored/public reads carry `:source :extension` and `:ext-path <extension-path>`
- extension-owned merged listing entries from `all-tools-in` and successful extension lookup projections continue to carry `:extension-path <extension-path>`

The migration does not need to preserve the old raw storage key layout (`:built-in-tools` or `:extensions ... :tools`), but it must preserve these caller-visible provenance fields because current tests and callers consume them.

### Same-owner duplicate replacement semantics

Same-owner duplicate replacement semantics are part of the preserved public contract for both owner kinds.

The required rule is:

- re-registering a tool with the same name for the same extension owner replaces that owner's previously stored tool definition
- re-registering a built-in tool with the same name under the same built-in provenance id replaces that provenance owner's previously stored tool definition
- cross-owner duplicate names remain allowed at storage level, with merged reads continuing to expose the first visible entry according to built-in-first then extension registration order

This keeps same-owner replacement explicit at the tool-registry boundary while still allowing `root-registry` ownership rules to remain lower-level and generic.

## Acceptance

This task is complete when:

- `tool-registry` is backed by `root-registry`
- focused tests prove preserved `tool-registry` public behavior after migration
- tool-specific validation and normalization remain clearly separate from shared root-registry concerns
- built-in and extension merged read semantics remain intact at the `tool-registry` boundary
- no unrelated registry migration is bundled into this task

## Non-goals

This task is not asking for:

- normalization of `tool-registry` public behavior to the raw `root-registry` contract
- speculative abstraction beyond the shared substrate already built
- a general tool-runtime redesign
- prompt assembly or provider-facing tool rendering changes except where current `tool-registry` behavior must be preserved during migration
