# Workflow Grammar Migration

This document defines the migration path from the **current implemented workflow grammar** to the **target converged workflow grammar** for task `077-deterministic-workflow-steps`.

It exists because the project needs more than a target authoring grammar. It also needs a safe way to move from the current step/session-centered authoring model to the target `:type :invoke | :session | :delegate` model without a flag-day rewrite.

The central recommendation is:

- the **current grammar** is an authored surface
- the **target grammar** is an authored surface
- the **normalized workflow IR** is the canonical execution surface

The runtime should converge on the normalized workflow IR rather than treating either authored grammar as the execution model.

## Purpose

The migration must solve four problems at once:

1. preserve execution of currently authored workflows
2. make room for deterministic invoke-style steps
3. allow the target grammar to become real rather than purely aspirational documentation
4. decouple runtime execution from authored syntax details

Without an intermediate normalized representation, the project risks one of two failures:

- the runtime remains permanently shaped around the current grammar, making the target grammar only a compatibility veneer
- the runtime is rewritten directly to the target grammar, forcing a risky flag-day conversion of existing workflows and execution paths

The migration therefore introduces a normalized workflow IR as the canonical runtime boundary.

## Canonical layers

The converged architecture has three layers.

### Layer 1 — current authored grammar

Documented in:

- `doc/workflow-grammar-current.md`

This is the currently implemented authoring shape, centered on:

- `:executor`
- `:prompt-template`
- `:input-bindings`
- `:session-preload`
- `:session-overrides`
- prompt/projection-based judge configuration

This layer remains supported during migration.

### Layer 2 — target authored grammar

Documented in:

- `doc/workflow-grammar.md`
- `doc/workflow-grammar-concepts.md`

This is the target authoring shape, centered on:

- `:type :invoke | :session | :delegate`
- `:operation` + `:args`
- `:contributions`
- `:target` + `:prompt-string` + `:context`
- shared `:from` / `:path` / `:projection` references
- explicit yielded-value semantics

This layer should become author-facing once the migration substrate exists.

### Layer 3 — normalized workflow IR

Documented in:

- `doc/workflow-ir.md`

This is the canonical runtime execution model.

The runtime should compile both authored grammars into the same normalized IR and execute only that IR.

## Architectural rule

The key architectural rule is:

> The target grammar is not the runtime model. The normalized workflow IR is the runtime model.

Consequences:

- authored syntax may evolve without forcing execution-engine churn
- compatibility concerns stay in compilation layers, not deep in runtime execution
- deterministic, session, and delegated execution become explicit runtime concepts
- tests can prove semantic equivalence between current-authored and target-authored forms by comparing compiled IR

## Migration strategy

The migration should proceed by compilation, not by in-place mutation of runtime semantics.

### Compilation paths

Two compilation paths are required:

1. `current-authored-workflow -> normalized-ir`
2. `target-authored-workflow -> normalized-ir`

Execution, progression, routing, attempts, result recording, and observability should consume only normalized IR.

## Normalization goals

The normalized IR should make the following runtime concerns explicit and uniform:

- step identity
- execution form
- deterministic invocation boundary
- inline session-construction boundary
- delegated workflow boundary
- control flow and loop bounds
- judge execution mode
- source references and projection
- step-local output surfaces
- yielded value
- terminal workflow result

The IR should preserve enough source detail for introspection and debugging while remaining canonical for execution.

## Semantic preservation rules

This section defines the intended meaning-preserving mappings from current grammar to normalized IR.

### Current `:executor` maps to IR session execution

Current step definitions require `:executor`, and the only current modeled executor type is agent-oriented.

That should compile to normalized:

- `:type :session`
- `:session {...normalized child-session construction...}`

It should **not** compile to `:type :delegate`.

Rationale:

- the current model is fundamentally about constructing and running a child session
- delegation is workflow-to-workflow invocation and should remain a distinct boundary

### Current `:prompt-template` maps to a template contribution

A current prompt-template compiles to a normalized template contribution.

Illustrative mapping:

Current:

```clojure
{:prompt-template "Review these issues:\n\n{{issues}}"
 :input-bindings {...}}
```

Normalized IR fragment:

```clojure
{:type :template
 :text "Review these issues:\n\n{{issues}}"
 :vars {"issues" ...}}
```

Compatibility ordering rule:

- compiled preload/source contributions appear before the compiled prompt-template contribution
- the compiled prompt-template contribution appears last unless a later explicitly defined compatibility rule says otherwise

This preserves the current broad shape that preload establishes context and prompt-template supplies the authored ask.

### Current `:input-bindings` map to template vars and/or source refs

Current input bindings should compile to normalized source specs.

The exact mapping from `workflow-binding-ref` should preserve source semantics while translating into the target-style reference language.

Illustrative direction:

Current:

```clojure
{:input-bindings
 {:issues {:source :step-output
           :path ["discover" :data :issues]}}}
```

Compiles to template vars like:

```clojure
{"issues" {:from {:step "discover" :output :data}
           :path [:issues]}}
```

This translation may require a dedicated compatibility compiler for old `:source` values such as:

- `:workflow-input`
- `:step-output`
- `:workflow-runtime`

The compatibility translation belongs in the current-grammar compiler, not in runtime execution.

Current implementation note:

- the first current-grammar compatibility compiler now maps canonical `:outputs` reads directly to IR source refs
- non-canonical accepted-result-envelope reads from current `:step-output` refs, including whole-envelope, `:diagnostics`, and `:blocked`, currently compile through the canonical IR `:result` output plus narrow source-spec `:compat` breadcrumbs
- current `:workflow-runtime` refs remain an explicit unresolved seam: they can still appear in current-authored input, but canonical `workflow_ir.clj` validation does not yet admit `:workflow-runtime` as an IR source-ref

### Current `:session-preload` maps to normalized source contributions

Current session preload items should compile to normalized contributions wherever possible.

Likely mapping:

- `{:kind :value ...}` -> source contribution or compatibility-shaped sourced conversation item in IR
- `{:kind :session-transcript ...}` -> source contribution with projection

Current implementation note:

- the first compatibility compiler normalizes both preload forms as ordered IR source contributions, preserving preload order before the terminal template contribution
- current preload role semantics are preserved only in `:compat` metadata on the compiled source contribution

If some current preload semantics carry author-invisible transport details such as role shaping that are not directly expressible in the target grammar, those details may exist temporarily as IR-level compatibility fields.

Important rule:

- compatibility-only preload semantics may exist in IR during migration
- they must **not** become part of the target authored grammar unless independently justified

### Current `:session-overrides` map to session config in IR

Current session overrides should compile into normalized session configuration under the IR step's `:session` payload.

Likely carried fields include:

- `:system-prompt`
- `:tools`
- `:skills`
- `:model`
- `:thinking-level`
- `:prompt-component-selection`

Current implementation note:

- the current compatibility compiler also folds executor-local `:skill` into IR session `:skills` when present, while preserving the original executor under step `:compat`

This means the normalized IR session form should allow canonical session-configuration fields plus possible compatibility metadata when required for semantic preservation.

### Current judge schema maps to typed IR judge forms

Current judge shape:

```clojure
{:prompt string
 :system-prompt? string
 :projection? projection}
```

Should compile to a typed normalized judge form, conceptually equivalent to:

```clojure
{:type :llm
 :session {:contributions [...]
           ...optional normalized session config...}
 :projection ...}
```

The migration should normalize judge execution mode explicitly even before the final target authoring surface for judge details is settled.

This gives runtime one execution concept for LLM-backed judges and a separate one for invoke-style deterministic judges.

Current implementation note:

- the first current-grammar compatibility compiler normalizes the current judge shape to IR `{:type :llm ...}` with a one-item template contribution containing the current judge prompt

### Current routing table outcomes normalize without coercion

The target model allows string or keyword outcomes.

Current routing keys are strings.

Migration rule:

- current authored routing keys compile as strings
- runtime does not auto-coerce strings to keywords or vice versa
- compatibility lives in authored-layer compilation, not execution-time matching

### Current workflow input assumptions must loosen

The current implementation treats workflow input as optional and map-shaped in the run model.

The target model requires broader workflow-input semantics because delegated steps pass a fully rendered prompt string as the delegated workflow's local `:workflow-input`.

Migration rule:

- normalized IR and runtime execution must treat workflow input as a general value, not map-only
- current-authored compiler may still assume current workflows expect map-shaped input
- target-authored compiler may compile delegated workflows whose local input is string-shaped
- any run/state schemas that currently force map-shaped workflow input must be widened before or alongside IR execution adoption

This is an enabling change for delegation semantics and should be handled explicitly.

## Runtime adoption sequence

The migration should happen in phases.

### Phase 1 — define normalized IR

Deliverables:

- `doc/workflow-ir.md`
- explicit normalized execution, control-flow, source-ref, output, and yielded-value shapes
- no required authored-syntax or runtime behavior change yet

Exit condition:

- the project has one documented canonical runtime model independent of authored grammar

### Phase 2 — compile current grammar to IR

Deliverables:

- compiler from current authored shape to normalized IR
- golden tests proving representative current workflows compile to expected IR
- semantic-preservation documentation for current compatibility mappings

Exit condition:

- current workflows can be compiled losslessly enough for existing runtime behavior

### Phase 3 — execute IR in runtime

Deliverables:

- runtime execution paths consume normalized IR rather than current authored step maps
- progression, routing, judge execution, attempts, and observability align to IR
- current workflow test suite remains green

Exit condition:

- execution engine no longer depends on current authored schema details

### Phase 4 — compile target grammar to IR

Deliverables:

- compiler from target authored grammar to normalized IR
- golden tests proving representative target workflows compile to expected IR
- equivalence tests where old and new authored forms normalize to the same semantic IR where appropriate

Exit condition:

- target grammar is executable, not just documented

### Phase 5 — land first-class invoke and delegate execution on IR

Deliverables:

- deterministic invoke execution using normalized operation registry and structured result semantics
- delegated execution using explicit target, prompt-string, and context semantics
- observability surfaces for invoke/session/delegate forms

Exit condition:

- all three target execution forms are real runtime concepts

### Phase 6 — migrate examples and built-in workflows

Deliverables:

- selected built-in workflows rewritten to target grammar
- examples cover invoke, session, and delegate steps
- docs explain current-vs-target status clearly

Exit condition:

- target grammar is the preferred authoring path for new workflows

### Phase 7 — deprecate and retire current authored grammar

Deliverables:

- explicit deprecation notice for current grammar
- removal plan once compatibility usage is low enough and built-in surfaces are migrated

Exit condition:

- current grammar can be removed without destabilizing runtime or losing required workflows

## Testing strategy

The migration needs tests at three levels.

### 1. Compiler golden tests

For both current and target authored workflows:

- authored workflow input
- expected normalized IR
- exact structural comparison

Purpose:

- prove normalization rules
- keep compatibility compilers honest
- make semantic deltas visible in review

### 2. Equivalence tests

For cases where the current and target grammars can express the same workflow meaning:

- current-authored workflow compiles to IR A
- target-authored workflow compiles to IR B
- assert semantic equivalence between A and B

Purpose:

- prove the migration is convergent rather than merely additive

### 3. Runtime execution tests

Execute normalized IR workflows and prove:

- progression
- judge routing
- attempts/history
- yielded values
- terminal workflow result
- invoke/session/delegate observability

Purpose:

- ensure runtime is actually decoupled from authored syntax

## Recommended implementation slices after design convergence

Suggested child slices for task `077`:

1. normalized workflow IR design
2. current grammar -> IR compiler
3. runtime execution on IR
4. target grammar -> IR compiler
5. invoke-step runtime execution and operation registry
6. delegate-step runtime execution and boundary semantics
7. workflow/example migration to target grammar
8. compatibility retirement and cleanup

## Non-goals of this migration document

This document does not itself define:

- the full final deterministic operation registry API
- all future typed schemas for operation args/results
- arbitrary embedded scripting in workflow files
- every possible session-contribution kind that might ever exist

Its purpose is specifically to define the convergence path from the current grammar to the target grammar.

## Success criteria

The migration is successful when all of the following are true:

- current workflows still execute correctly
- target-authored workflows execute correctly
- both authored grammars compile to the same normalized runtime model
- runtime execution no longer depends on authored current-schema details
- invoke, session, and delegate are first-class execution forms in runtime and observability
- the current authored grammar can be deprecated without blocking active workflow usage
