# 170 root-registry semantic alignment for future adopters

## Intent

Align `root-registry` semantics and adopter-boundary semantics so the next shared-storage migration does not have to choose between:

- preserving incumbent registry behaviour through awkward adapter workarounds, or
- forcing incumbent registries to keep semantics that no longer fit the shared storage model

The immediate design target is the gap between `root-registry` and `deterministic-operation-registry`, with `workflow-registry` as the first already-migrated adopter that may need small follow-up adjustment if the shared contract becomes clearer.

This task should identify and implement a coherent set of combined semantic changes across:

- `root-registry`
- `workflow-registry`
- the future migration target shape for `deterministic-operation-registry`

so that future adopters can converge on shared semantics intentionally rather than by compatibility shims alone.

## Problem

The first `root-registry` adoption wave succeeded by preserving adopter-specific behaviour at adapter boundaries:

- `command-registry`
- `tool-registry`
- `workflow-registry`

That was the right first move, but it also left an important follow-on design question open:

- which incumbent semantics should remain adapter-owned forever
- which semantics should instead become part of the shared lower contract
- which incumbent registries should change their behaviour to better match the shared lower contract

`deterministic-operation-registry` is the most important unresolved case because it still differs materially from `root-registry`:

- duplicate registration currently throws rather than replacing
- it preserves registration order
- it supports bulk unregister by `ext-path`
- it is runtime-object-owned rather than pure root-state owned
- invoke lookup miss throws

At the same time, `root-registry` currently bakes in its own first-cut choices:

- `register` means insert-or-replace
- duplicate ids owned by the same extension replace silently
- remove miss returns failure info rather than throwing
- operation results are explicit result maps rather than exceptions

Those choices may be correct, but they have not yet been pressure-tested against the next harder adopter. Before migrating `deterministic-operation-registry`, we should align the shared semantics deliberately.

## Design goal

Produce and implement a small, coherent semantic alignment layer around `root-registry` so that:

- `root-registry` can express both replace-style and duplicate-rejecting registration intentionally
- adopters do not need to simulate one shared semantic with exception-catching or preflight lookups when a lower result contract would be clearer
- future migration of `deterministic-operation-registry` has an agreed target shape
- existing migrated adopters remain coherent and locally comprehensible

## Task artifact roles

This task uses the standard Munera artifact split:

- `design.md` — intent, scope, semantic decisions, and acceptance
- `plan.md` — chosen implementation approach and proof strategy
- `steps.md` — executable implementation checklist only, including the explicit task-local obligation to keep `design-steps.md` present as a maintained deliverable for this task
- `design-steps.md` — actionable design/ambiguity/inconsistency follow-up items only; for this task it is a maintained task-local artifact, not an assumed side file
- `implementation.md` — append-only review notes, decisions, discoveries, and blockers

Design-review follow-up items belong in `design-steps.md` and should be executed independently from `steps.md` work when a review-follow-up pass asks for them.

## Scope

This task includes:

- auditing the current semantic mismatch between `root-registry`, `workflow-registry`, and `deterministic-operation-registry`
- deciding which mismatches should be solved by changing shared lower semantics versus changing adopter semantics
- refining `root-registry` operation vocabulary and result contracts where a more expressive shared lower contract is justified
- making any small follow-up adjustments needed in `workflow-registry` so it uses the clarified shared result contract directly rather than preserving incidental glue behaviour
- identifying the exact post-alignment target semantics that a future `deterministic-operation-registry` migration should aim for
- adding focused tests that prove the aligned lower semantics and any changed adopter behaviour
- recording the migration guidance that follows from the alignment

This task may include code changes to `root-registry` and `workflow-registry` when those changes are part of the semantic alignment itself.

## Out of scope

This task does not include:

- fully migrating `deterministic-operation-registry` onto `root-registry`
- redesigning workflow runtime invoke semantics
- removing registration-order semantics from `deterministic-operation-registry` unless a separate follow-on task explicitly chooses that change
- changing tool-registry or command-registry user-visible behaviour unless a direct coherence bug is discovered
- broad registry-unification abstraction beyond the concrete semantic gaps needed for the next migration

## Key design questions

### 1. Registration conflict semantics

Should shared lower storage support only replace-style registration, or should it also support explicit duplicate-rejecting insertion?

Concrete pressure case:

- `deterministic-operation-registry` currently treats duplicate ids as an error
- `root-registry/register` currently means insert-or-replace

A likely alignment option is to add an explicit lower operation or mode such as:

- insert-only / fail-on-duplicate
- register-or-replace

with explicit result maps rather than exceptions.

### 2. Error signaling style

Should more adopter behaviour move toward explicit lower result handling rather than pre-check-plus-throw or catch-and-translate patterns?

Concrete pressure case:

- `root-registry` already returns explicit failure info for unregister miss
- `workflow-registry/remove-definition` currently preserves throw-on-miss semantics at its public boundary

This task should decide whether:

- throw-on-miss remains adapter-owned public behaviour only
- lower shared callers should prefer result inspection over exception translation
- any existing adapter logic should be simplified now that the lower contract is explicit

### 3. Ownership and bulk cleanup semantics

Can shared lower semantics grow to support owner-scoped cleanup in a way that helps future `deterministic-operation-registry` migration without distorting current adopters?

Concrete pressure case:

- `deterministic-operation-registry` requires bulk unregister by `ext-path`
- `root-registry` already has `clear-by-extension`, but its result shape and state model may still need evaluation against runtime-owned usage

### 4. Ordering semantics

Should registration-order support remain adapter-owned, remain entirely outside shared storage, or become an optional lower capability?

This task answers that question as follows:

- preserved registration order remains part of the future `deterministic-operation-registry` public/adapter contract
- preserved registration order does **not** become part of the shared `root-registry` lower contract in this task
- future deterministic-operation migration should therefore preserve ordered reads/listing at its adapter/runtime boundary rather than treating ordering as intentionally dropped
- if that adapter-owned preservation later proves too awkward or incoherent, create a named follow-on task to add principled lower ordering support rather than expanding `root-registry` implicitly during migration

The goal here is not to force ordering into `root-registry` now, but to make the migration target explicit: future adopters should not infer that order dependence has been erased, only that ordering remains outside the lower shared storage contract unless a later task changes that deliberately.

## Proposed combined change set

This task should not stop at enumerating options. The proposed direction is to align around a small explicit shared mutation vocabulary and then adapt adopter boundaries intentionally.

### Chosen direction

1. **Extend `root-registry` with explicit insert-vs-upsert registration semantics**
   - add an explicit lower **insert-only** operation that fails on any existing id and returns a structured failure result rather than throwing
   - keep explicit **upsert / replace-capable** registration for adopters like `workflow-registry`, `tool-registry`, and `command-registry` whose current boundary semantics intentionally allow replacement
   - stop relying on one overloaded lower `register` meaning to serve both duplicate-rejecting and replace-style adopters

2. **Make conflict outcomes first-class lower results**
   - duplicate insert should return an explicit lower failure such as `:failure-kind :duplicate-id`
   - conflicting ownership during replace-capable registration should remain an explicit lower failure such as `:failure-kind :ownership-conflict`
   - lower callers should not need exception control flow to distinguish duplicate, ownership, unknown-registry, or not-found outcomes

3. **Treat throw behaviour as adapter-owned unless intentionally shared**
   - `root-registry` should remain result-oriented
   - adopter boundaries may still throw where that is part of their public contract, but they should do so by inspecting lower results rather than by preflight lookup or exception-catching glue

4. **Use `workflow-registry` as the first semantic cleanup adopter**
   - preserve current public workflow-registry behaviour
   - simplify any lower interaction that currently performs preflight lookup only to preserve public throw-on-miss behaviour
   - specifically, `remove-definition` should consume the lower unregister result directly and translate `:not-found` into the current public throw, instead of first reading the definition and then removing it
   - registration should use the explicit replace-capable lower operation intentionally rather than depending on an ambiguous shared `register` name

5. **Set a clear future target for `deterministic-operation-registry`**
   - future shared-storage migration should target duplicate rejection through the new explicit lower insert-only result contract, not by reintroducing lower thrown duplicate errors
   - registration-order semantics remain preserved at the future deterministic-operation public/adapter boundary, but remain outside the shared `root-registry` lower contract for this task
   - future migration should therefore adapt ordering explicitly at the adapter/runtime layer unless a named follow-on later proves shared lower ordering support is warranted
   - bulk cleanup should target the existing owner-scoped shared semantics (`clear-by-extension`) unless a concrete missing capability remains after result-contract alignment
   - invoke-miss throw behaviour remains outside shared storage and stays adapter/runtime-owned

### Why this is the right combined set

This direction solves the main current pressure points with one coherent change family:

- `workflow-registry` keeps replace-style semantics without depending on an overly generic lower `register`
- future `deterministic-operation-registry` migration gets explicit duplicate-failing lower semantics without needing to preserve lower thrown duplicate exceptions
- `root-registry` becomes more expressive without absorbing invoke semantics or adopter-specific public APIs
- conflict and miss handling become easier to test through result maps instead of hidden control flow

### Concrete design target

The intended post-task shared semantic shape is:

- **insert-only mutation**
  - success on absent id
  - explicit `:duplicate-id` failure on present id
  - no throwing
- **replace-capable mutation**
  - explicit `:insert` or `:replace` success result
  - explicit `:ownership-conflict` failure when the existing id belongs to another owner
  - no throwing
- **targeted remove**
  - explicit success result on hit
  - explicit `:not-found` result on miss
  - no throwing
- **owner-scoped clear**
  - explicit success/no-op style lower results
  - no throwing

Adapter/public layers then choose whether to:

- return lower results directly
- project them into existing public result shapes
- or throw intentionally for public compatibility

### Naming direction

Prefer explicit lower operation names over overloaded verbs or boolean options. The design should converge on names that make semantic differences obvious, for example:

- `insert`
- `upsert` or `register-replaceable`
- `unregister`
- `clear-by-extension`

The exact final names can be refined during implementation, but the task should preserve the semantic split, not collapse it back into a single ambiguous mutation.

## Semantic delta to prove

This task should leave behind a proven semantic delta, not just code motion.

1. **For `root-registry`**
   - explicit duplicate-failing insert exists
   - replace-capable registration remains available explicitly
   - result contracts distinguish duplicate from ownership conflict

2. **For `workflow-registry`**
   - public behaviour stays the same
   - lower interactions become result-oriented and more direct
   - no preflight lookup remains where a lower mutation result can authoritatively drive the outcome

3. **For future `deterministic-operation-registry` migration**
   - duplicate rejection target is now a lower result contract, not a lower exception contract
   - registration-order preservation remains part of the future deterministic-operation public/adapter contract, while ordering storage/maintenance remains explicitly outside the shared lower `root-registry` contract unless a later task changes that deliberately
   - runtime-object ownership remains explicitly classified as adapter/runtime-owned unless later changed
   - owner-scoped cleanup is confirmed as compatible or the remaining gap is named precisely

## Constraints

- Prefer explicit lower operations over boolean flags when the semantics are materially different.
- Do not hide meaningful conflict outcomes behind exception control flow if an explicit result contract is clearer.
- Preserve local comprehensibility: shared semantics should become easier to name, test, and use.
- Avoid speculative generality; every shared semantic added here must be justified by at least one current adopter and one future adopter pressure case.
- Keep runtime invoke semantics out of `root-registry`.
- Do not accidentally change persisted compatibility paths already preserved by `workflow-registry`.

## Desired outcome

At the end of this task:

- `root-registry` has a clearer and more intentional lower semantic contract for registration conflicts and related failure handling
- `workflow-registry` remains coherent with the clarified shared contract
- the next deterministic-operation migration has an explicit semantic target instead of an open-ended compatibility debate
- the repo has focused tests proving the aligned lower semantics and any changed adapter behaviour
- task artifacts record which semantics are now shared, which remain adapter-owned, and why

## Acceptance

This task is complete when:

- the semantic gaps between `root-registry` and `deterministic-operation-registry` are explicitly inventoried and classified
- one chosen combined alignment change set is implemented or fully pinned for immediate follow-on implementation without ambiguity
- any adopted `root-registry` API/result-contract changes are covered by focused tests
- any `workflow-registry` follow-up needed by the chosen alignment is covered by focused tests
- the task leaves a clear target contract for future deterministic-operation shared-storage migration, including that duplicate rejection moves into lower result contracts while preserved registration-order behaviour remains adapter/public-boundary owned rather than part of the shared lower contract
- no full deterministic-operation migration is bundled into this task

## Non-goals

This task is not asking for:

- a complete rewrite of root-registry
- immediate storage migration of every remaining registry
- forcing all registries to have identical public APIs
- removal of useful adapter-owned compatibility behaviour where it still serves a clear boundary purpose
