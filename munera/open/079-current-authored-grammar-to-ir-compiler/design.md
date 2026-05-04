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

## Acceptance

- a current-grammar -> IR compiler exists
- representative current authored workflows compile into expected normalized IR
- the compiler follows the preservation rules documented in `doc/workflow-grammar-migration.md`
- current `:executor`-based steps compile to IR session steps, not delegates
- current judges compile to typed IR judge forms
- current preload/binding/override semantics compile in a way that preserves execution intent closely enough for runtime adoption
