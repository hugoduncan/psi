Goal: compile the current implemented workflow grammar into normalized workflow IR so existing workflows can run on the new canonical execution model without a flag-day rewrite.

## Intent

Task `077` chose IR-first convergence. This task builds the first compatibility compiler from the currently implemented authored workflow grammar documented in `doc/workflow-grammar-current.md` into the normalized IR documented in `doc/workflow-ir.md`.

## Problem statement

The current runtime and built-in workflows still depend on the current grammar surface:

- `:executor`
- `:prompt-template`
- `:input-bindings`
- `:session-preload`
- `:session-overrides`
- current prompt/projection judge shapes

Those workflows cannot move to IR execution until there is a deterministic, documented mapping from those current authored shapes into the canonical IR.

## Scope

In scope:

- implement compilation from current authored workflow definitions to normalized IR
- encode the documented semantic preservation rules from `doc/workflow-grammar-migration.md`
- compile current `:executor` steps to IR `:type :session`
- compile current prompt/binding/preload/override/judge/routing shapes into IR
- preserve enough authored-source breadcrumbs or compatibility metadata for debugging if needed
- add golden tests for representative current-workflow -> IR compilation

Out of scope:

- executing IR in runtime
- compiling target grammar to IR
- removing current authored grammar support
- implementing new invoke or delegate authored syntax

## Desired outcome

Existing authored workflows can be normalized into one canonical IR shape without changing their authored files.

## Accepted-result envelope compatibility rule

The current-grammar compiler must preserve current `:step-output` binding and session-authoring behavior for reads against the full accepted-result envelope, not just declared `:outputs`.

This is required because the current implemented surface and tests already use accepted-result paths such as:

- `{:source :step-output :path ["step-1-discover" :diagnostics :summary]}`
- whole-envelope reads such as `{:source :step-output :path ["plan"]}` used by preload materialization

Those are part of the current authored/runtime contract even though `doc/workflow-ir.md` describes canonical prior-step refs primarily in terms of declared IR `:outputs` and `:yields`.

For task `079`, settle the migration rule as:

- canonical execution-relevant data remains represented through explicit IR `:outputs` and `:yields`
- when current-grammar sources read accepted-result-envelope fields outside canonical `:outputs`, the compiler preserves that behavior through narrowly-scoped `:compat` metadata rather than pretending those fields are canonical target-grammar outputs
- supported compatibility envelope surfaces are the whole accepted-result envelope, `:diagnostics`, and `:blocked`, because those are documented/currently-exercised current-grammar read surfaces
- the compiler should reject unsupported non-canonical envelope reads only if they fall outside the accepted-result envelope shape the current grammar actually exposes

This keeps the IR canonical for new execution while preserving current authored semantics during migration.

## Acceptance

- a current-grammar -> IR compiler exists
- representative current authored workflows compile into expected normalized IR
- the compiler follows the preservation rules documented in `doc/workflow-grammar-migration.md`
- current `:executor`-based steps compile to IR session steps, not delegates
- current judges compile to typed IR judge forms
- current preload/binding/override semantics compile in a way that preserves execution intent closely enough for runtime adoption
- the compilation rule for current required `:result-schema` is explicit and proven by tests

## Current `:result-schema` migration rule

The current authored grammar requires `:result-schema` on every step, but normalized IR does not currently expose a canonical `:result-schema` field or a runtime validation hook for it.

For task `079`, settle the compatibility rule as:

- normalized IR does **not** treat current `:result-schema` as a canonical execution field
- current `:result-schema` is preserved only as narrowly-scoped `:compat` metadata so migration can retain authored/debug breadcrumbs without pretending IR execution depends on that schema today
- canonical IR `:outputs` and `:yields` are derived from execution-form defaults and current compatibility mapping rules, not mechanically from arbitrary current `:result-schema` values
- task `079` does not reject current authored workflows solely because their current `:result-schema` is not representable as first-cut canonical IR validation
- later runtime-adoption work may choose to interpret, validate, or retire this compatibility breadcrumb explicitly, but this slice should keep the rule compile-time-only

This mirrors the accepted-result-envelope compatibility decision: preserve current authored semantics where needed, but keep canonical IR small and execution-relevant.
