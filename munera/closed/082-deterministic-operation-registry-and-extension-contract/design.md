Goal: define and implement the deterministic operation registry and extension/runtime contract that backs workflow IR `:type :invoke` execution.

## Intent

Task `077` established that deterministic workflow steps should invoke named operations through a runtime-owned boundary using stable author-facing operation ids such as `"github/search-issues-by-label"`.

After IR schema (`078`), current-grammar compatibility compilation (`079`), runtime IR execution adoption (`080`), and target-grammar compilation (`081`), the next enabling slice is the operation substrate itself: a canonical registry and execution contract for deterministic invoke steps.

## Problem statement

The target workflow model now has first-class `:type :invoke` steps, but those steps are only executable once the runtime can:

- resolve an operation id
- validate and/or normalize arguments at the boundary
- invoke a registered implementation through a runtime-owned contract
- receive a canonical structured result
- record failures and diagnostics consistently

Without this slice:

- `:invoke` remains a grammar concept rather than an executable runtime feature
- extensions have no explicit supported contract for exposing deterministic operations
- operation resolution risks becoming ad hoc or implementation-leaky
- result recording and downstream data references cannot stabilize around one canonical invoke surface

## Scope

In scope:

- define the runtime-owned deterministic operation registry model
- define the extension/runtime registration contract for operations
- define the invocation boundary contract for operation implementations
- define the canonical tagged success/failure operation-result contract returned by operations
- define the runtime-owned invoke-step output wrapping from operation results into canonical invoke outputs
- define failure/diagnostic expectations for operation execution
- define how operation metadata should support introspection and author ergonomics at first cut
- add focused tests for registry behavior, operation resolution, and result-shape enforcement

Out of scope:

- broad implementation of many concrete operations
- fully general typed arg/result schemas unless a minimal boundary contract clearly requires them
- redesigning workflow IR or authored grammar beyond what this contract forces explicitly
- migrating all deterministic use cases at once

## Desired outcome

The runtime exposes one canonical way to register and execute deterministic operations for workflow invoke steps.

A workflow author can rely on stable operation ids. An extension author can rely on one explicit registration contract. Runtime execution can rely on one canonical operation-result contract and one runtime-owned invoke-step output shape.

## Authoritative boundary decision

Registered deterministic operations do not return the full invoke-step accepted-result envelope.

Instead, a registered operation returns a lower-level tagged operation result through the runtime boundary:

- success -> `{:status :ok :data ... :summary? string :details? map}`
- failure -> `{:status :error :reason keyword :message string :details? map}`

Runtime-owned invoke-step execution is responsible for wrapping that operation result into the canonical invoke-step output surfaces documented in `doc/workflow-ir.md`:

- `:data` comes from successful operation `:data`
- `:summary` comes from successful operation `:summary` when present
- `:result` is the full canonical structured operation result retained for diagnostics/debugging
- unsuccessful operation results become canonical error yields / accepted-result failure recording at the invoke-step execution layer rather than requiring operations to fabricate step-local workflow envelopes

This keeps the extension contract small and explicit while preserving one runtime-owned normalization point for workflow step semantics.

## Acceptance

- a deterministic operation registry model exists in code
- operations are registered by stable author-facing ids
- invoke execution can resolve an operation id through the registry rather than direct var/function references in authored workflows
- the operation invocation contract is explicit about inputs, outputs, and failure signaling
- canonical tagged success/failure operation-result shapes are defined and enforced at the boundary
- runtime-owned invoke-step output wrapping is explicit so task `083` does not guess where `:data` / `:summary` / `:result` shaping lives
- focused tests prove registry lookup, duplicate/conflict handling, representative invocation, and malformed-result rejection behavior at the operation boundary
- the implemented contract is consistent with task `077` design and `doc/workflow-ir.md`
