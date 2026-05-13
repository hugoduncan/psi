# 149 — reload fixup inventory and reload-safety coverage

## Goal

Make `reload-code` reliably preserve runtime correctness by inventorying every namespace that requires post-reload in-memory fixups and implementing the fixups needed for any namespace whose stale in-memory references would otherwise break ψ after reload.

## Why

`reload-code` already contains namespace-specific post-reload fixup logic because some runtime-owned in-memory objects retain stale vars, functions, handlers, or assembled values across namespace reload.

That means reload safety is currently only partially explicit:

- some namespaces are known to require fixups and already have them
- other namespaces may require the same treatment but have not yet been inventoried
- when a required fixup is missing, reload may appear to succeed while leaving ψ in a broken or partially stale runtime state

This is a correctness problem for psi self-development. A successful reload should not quietly leave the runtime pointing at pre-reload implementations when those stale references break canonical behavior.

## Problem

Namespace reload updates code definitions, but it does not automatically rebuild every long-lived in-memory object that may have captured references to those definitions earlier.

Potential breakage surfaces include:

- caches or registries that store functions or vars
- callback maps and assembled context values
- runtime-owned registries of tools, commands, prompts, lifecycle hooks, resolvers, mutations, or workflow collaborators
- long-lived session/runtime state that embeds namespace-owned functions in maps or records
- any other in-memory object whose validity depends on post-reload rebinding or rebuilding

Today, the system lacks an explicit inventory answering:

1. which namespaces have reload-sensitive in-memory ownership
2. which of those already have fixups
3. which missing fixups are merely stale-but-tolerable
4. which missing fixups can break ψ after reload and therefore must be implemented now

## Intent

Create a focused task that first inventories reload-sensitive namespaces and then closes the safety gap for namespaces that are required to keep ψ working after reload.

This task should:

- identify every currently known namespace whose reload requires namespace-specific in-memory repair or rebuild work
- classify the reason each namespace needs fixup and the owning in-memory surface affected
- distinguish mandatory reload-safety fixups from lower-priority freshness/completeness follow-ons
- implement the missing fixups for namespaces that would otherwise break canonical runtime behavior after reload
- leave behind an explicit, reviewable inventory and proof surface so future reload-sensitive additions are easier to detect

This task should not:

- broaden into a full hot-reload architecture redesign
- require reload to provide perfect freshness for every non-critical cached value in the system
- turn every namespace reload concern into a generic dynamic discovery framework unless that falls out cleanly from the minimal safe implementation
- broaden into unrelated runtime bootstrap cleanup except where a fixup cannot be made safe without a small ownership clarification

## Relationship to task 148

Task 148 addresses reload target selection and user/operator guidance for psi self-development.

This task is distinct and complementary:

- task 148: reload the intended source from the intended worktree and surface mismatch guidance
- task 149: once reload runs, ensure the live runtime repairs or rebuilds the in-memory surfaces that would otherwise remain stale or broken

If implementation discovers overlap in the concrete reload pipeline owner, that overlap should be noted, but the tasks remain distinct in intent.

## Required inventory before mechanism changes

Before deciding the final implementation shape, this task must inventory all reload-sensitive namespaces and classify them.

For each namespace or owner, record:

- namespace name
- owning component / layer
- in-memory surface that survives reload
- what stale value is retained across reload
- current fixup status: `already-fixed`, `missing`, or `not-needed`
- severity: `breaks-psi`, `degrades-behavior`, or `freshness-only`
- canonical symptom if left unfixed
- preferred fixup owner/path

The inventory must cover at least these kinds of surfaces:

- tool definitions and any live tool registry or projection
- command registries
- prompt contribution registries / prompt asset caches
- resolver and mutation registration refresh surfaces
- workflow registration / workflow bootstrap surfaces
- extension-owned runtime registries that survive reload
- session context assembly values that embed callback fns
- scheduler / lifecycle / event callback registries
- any other long-lived runtime-owned map, atom, cache, or assembled context containing namespace-resident functions or values

The inventory must be based on actual code-path inspection rather than guesses from namespace names.

## Decision boundary

The task should prefer the smallest implementation that makes reload safe.

Preferred order:

1. inventory and classify existing explicit fixup code
2. identify missing reload-breaking namespaces
3. add focused fixups for mandatory safety cases
4. only then consider whether small shared helpers or a narrow declarative table improve clarity

This task does not require replacing namespace-specific fixups with a generic framework unless review shows that the current mechanism cannot be extended safely without one.

## In scope

- inventory of all namespaces / owners that require post-reload in-memory fixup or rebuild work
- documentation of why each sensitive surface requires fixup
- implementation of missing fixups for namespaces whose stale state would otherwise break ψ after reload
- tightening or clarifying the canonical reload fixup path/owner if needed to make mandatory fixups reliable
- focused tests proving both the inventory-backed mandatory cases and the absence of the known reload breakages
- concise developer-facing documentation of how new reload-sensitive surfaces should be handled

## Out of scope

- broad rearchitecture of runtime state ownership unrelated to reload safety
- exhaustive elimination of all caches or all stale-but-benign post-reload values
- speculative fixups for namespaces with no surviving in-memory surface
- full restartless upgrade semantics across every possible runtime mutation
- non-psi worktree or deployment hot-reswap semantics beyond the existing psi self-development reload contract

## Success criteria

This task is successful only if all of the following are true:

- there is an explicit inventory of reload-sensitive namespaces / owners and their fixup status
- every inventory entry classified `breaks-psi` has a concrete implemented fixup or an explicit proof-backed reason it is already safe
- `reload-code` no longer reports success while leaving any currently known reload-breaking stale namespace references in memory
- the implementation path for reload fixups is clear enough that future additions can be reviewed against the inventory
- focused proof covers the known mandatory safety cases

## Design constraints

- preserve the explicit `reload-code` API contract
- prefer worktree-authoritative reload semantics from task 148 where relevant, but do not conflate source targeting with post-reload repair
- keep fixups explicit and reviewable; do not hide critical repair behavior behind opaque incidental side effects
- avoid introducing a broad plugin framework for fixups unless the current implementation cannot express the needed safety cases cleanly
- preserve existing successful fixups while expanding coverage
- if a fixup requires rebuilding a larger assembled value, rebuild through its canonical owner rather than patching random subfields in place

## Key design questions

1. What are all of the long-lived in-memory objects that survive namespace reload and can retain stale references?
2. Which namespaces already participate in explicit reload fixups, and what exactly do those fixups repair?
3. Which additional namespaces or owners currently break ψ after reload when they are changed?
4. For each missing mandatory case, what is the canonical owner that should rebuild or refresh the stale value?
5. Should the final shape remain imperative namespace-specific fixup code, or is a small data-driven inventory/dispatch table clearer once the mandatory cases are known?
6. What proof level best demonstrates reload safety without overfitting to implementation details?

## Acceptance

- a new Munera task exists for reload fixup inventory and reload-safety coverage
- the task clearly distinguishes inventory work from mandatory implementation work
- the task requires an explicit classification of reload-sensitive namespaces and their in-memory surfaces
- the task scopes implementation to namespaces that would otherwise break ψ after reload
- the task defines success in terms of reload safety, not generic freshness perfection
- the task records its relationship to task 148 without merging the two concerns
- the task requires focused proof and concise developer guidance so future reload-sensitive additions are easier to catch
