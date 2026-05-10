# 135 — Higher-order workflow references

## Goal

Add first-class workflow references and dynamic delegation to the workflow model so workflows can compose other workflows by value/reference, while preserving deterministic execution and keeping authored workflow definitions as the canonical executable units.

## Why

The current workflow model already has an explicit workflow boundary:

- `:type :delegate`
- a named workflow target
- deterministic execution through canonical workflow definitions

That makes workflows reusable, but only in a first-order way. A workflow can call another workflow only by naming it statically at author time.

That limitation blocks a useful class of composition patterns:

- selecting one of several reusable workflows from prior structured results
- passing a workflow choice through workflow inputs or intermediate outputs
- separating workflow selection from workflow execution
- expressing reusable orchestration patterns that treat workflows as values without turning runtime execution into ad hoc code generation

The project needs a small, coherent higher-order extension to the workflow model, not a broad redesign of workflow execution.

## Problem

Today, delegation is structurally higher-level but not first-class:

- delegate targets are authored as static names rather than workflow reference values
- there is no explicit value shape for “a workflow to run later”
- workflow selection and workflow execution are fused into the same authored step
- introducing higher-order composition informally would risk ambiguous runtime behavior, weak validation, or replay-hostile dynamic execution

Without an explicit model, higher-order usage would tend to drift toward one of two bad outcomes:

- ad hoc stringly typed dynamic target selection with weak validation
- runtime-authored executable workflow generation that undermines determinism and boundary clarity

## Intent

Extend the workflow model with first-class workflow references and dynamic delegation.

This task should:

- define a canonical workflow-reference value model
- allow delegate targets to be either static workflow names or resolved workflow references
- preserve delegation as the execution boundary
- preserve authored workflow definitions as canonical executable workflow units
- preserve deterministic and replayable workflow execution
- keep capability and validation rules explicit
- avoid broadening into runtime workflow generation or macro-like executable synthesis

## Decision

The first slice of “higher-order workflows” will mean:

1. workflows may refer to workflows by first-class validated reference values
2. delegate steps may target either:
   - a static workflow name, or
   - a resolved workflow reference value
3. higher-order behavior is limited to composition/selection of existing canonical workflows
4. runtime-authored executable workflow generation is explicitly out of scope

This is preferred over workflow factories, anonymous runtime workflows, or collection-level higher-order operators because it gives the smallest coherent increase in expressive power while preserving the current execution model.

## Canonical model change

### New concept: workflow reference

Introduce a first-class workflow reference value that represents a named canonical workflow definition that may be delegated to later.

The value model should:

- identify a workflow definition by canonical name/reference
- be machine-distinguishable from ordinary strings
- be validatable before execution
- preserve deterministic meaning under replay
- be safe to carry through workflow inputs, outputs, and intermediate data

Preferred semantic rule:

- a workflow reference is a data value that points to an already-loadable canonical workflow definition
- it is not executable code
- it is not a partially compiled workflow artifact
- it does not embed arbitrary runtime behavior

The implementation must choose and document the exact external and IR-facing shape for workflow references.

### Preferred candidate shape

Prefer an explicit tagged map rather than a plain string.

Candidate external/runtime value shape:

```clojure
{:type :workflow-ref
 :name "builder"}
```

Why this is the preferred candidate:

- it is machine-distinguishable from ordinary strings
- it keeps workflow-reference intent explicit in authored data and resolved values
- it avoids ambiguous "any string might be executable" drift
- it stays small, serializable, and replay-friendly
- it points to a canonical workflow by stable name without embedding executable code

Candidate semantic rule:

- `:type` must be exactly `:workflow-ref`
- `:name` must be a workflow name string that identifies a canonical discovered/registered workflow definition
- additional keys are invalid in v1 unless explicitly introduced by a later task

Preferred first-cut consequence:

- dynamic higher-order delegation consumes explicit workflow-reference values of this shape
- plain strings remain the static delegate-target authoring form
- plain strings do not count as dynamic workflow-reference values in the sourced dynamic path unless a later implementation decision explicitly records a compatibility coercion rule

## Delegate target extension

### Current behavior

A delegate step currently targets a named workflow directly.

### New behavior

A delegate step should support both:

- static target by workflow name
- dynamic target by workflow reference source

Preferred author-facing split:

- static form remains:

```clojure
{:type :delegate
 :target "builder"
 ...}
```

- dynamic higher-order form should source a workflow reference value:

```clojure
{:type :delegate
 :target {:from {:step "choose-workflow" :output :data}
          :path [:selected-workflow]}
 ...}
```

where the sourced value is expected to be:

```clojure
{:type :workflow-ref
 :name "builder"}
```

The design should preserve one clear execution rule:

- delegation always executes a canonical resolved workflow definition

The target resolution path must therefore:

1. resolve the authored/static or sourced target shape
2. validate that it is a canonical workflow reference or canonical workflow name
3. resolve it to a real registered workflow definition
4. fail explicitly when the target is missing, malformed, or unavailable

### Preferred grammar shape

Prefer extending the existing author-facing `:target` field as a small union rather than introducing a new sibling key.

Preferred grammar rule:

```clojure
delegate-step ::= {:name step-name
                   :type :delegate
                   :target (workflow-name | workflow-target-source-spec)
                   :prompt-string (string | template-contribution)
                   :context? [source-item*]
                   outputs?
                   yields?
                   control-flow*}

workflow-target-source-spec ::= {:from source-ref
                                 source-projection?}
```

Exact interpretation rule:

- `workflow-target-source-spec` is not a new mini-language
- it is exactly the existing workflow `source-spec` shape reused in the `:target` slot
- therefore dynamic `:target` supports either bare `:from` or `:from` plus optional `:path` or `:projection` shaping already used for other sourced values
- if `:projection` is used, the final resolved value must still be a valid workflow-reference value of shape `{:type :workflow-ref :name "..."}`

Meaning:

- `:target "builder"` remains the static authored form
- `:target {:from ...}` becomes the dynamic higher-order form
- the sourced value must resolve to an explicit workflow-reference value of shape `{:type :workflow-ref :name "..."}`
- `:target` does not accept template contributions, plain maps of other shapes, or arbitrary executable data

This keeps the author-facing extension small:

- static delegates remain visually identical
- dynamic delegates reuse the existing source-spec idiom already used elsewhere in the workflow model
- workflow references stay explicit at the value layer rather than being encoded as a second string form

### Preferred IR shape

Prefer preserving the existing `delegate-spec` owner while making its `:target` field explicit as a static-or-dynamic union.

Preferred IR rule:

```clojure
delegate-spec ::= {:target (workflow-name | workflow-target-source-spec)
                   :prompt-string (string | template-contribution)
                   :context? [source-contribution*]}

workflow-target-source-spec ::= {:from source-ref
                                 source-projection?}
```

Preferred normalized semantic rule:

- static IR target: a workflow-name string
- dynamic IR target: a source-spec map that resolves at runtime to a workflow-reference value
- the runtime-resolved value is validated as `{:type :workflow-ref :name workflow-name}` before canonical workflow lookup
- static strings are not wrapped into workflow-reference values inside IR unless implementation later records a deliberate normalization step and why it improves the model

Preferred non-goal for this slice:

- do not introduce a second executable delegate step kind
- do not compile workflow references into opaque executable artifacts in IR
- do not erase the distinction between a static authored target string and a sourced workflow-reference value

## In scope

- defining the canonical workflow-reference concept and value semantics
- extending workflow grammar/IR/model validation to represent workflow references cleanly
- extending delegate target semantics to accept static or resolved workflow targets
- defining how workflow references may flow through workflow inputs, outputs, and source resolution
- defining validation and failure behavior for invalid workflow references and unresolved targets
- ensuring capability gating and availability rules remain explicit when delegation is dynamic
- preserving deterministic execution and replay semantics for dynamic delegation
- documenting author-facing usage and examples for higher-order workflow references
- adding focused proof for grammar, compilation, runtime resolution, and error behavior

## Out of scope

- runtime-authored executable workflow generation
- anonymous workflows authored inline as executable values at runtime
- macro systems for generating workflow code during execution
- collection operators such as workflow-map, workflow-reduce, or parallel-over-workflow
- redesigning non-delegate workflow step kinds unless required for reference flow consistency
- broad workflow capability-model redesign beyond what dynamic delegation requires
- changing `.psi/workflows/` discovery semantics
- changing existing static delegation behavior except to generalize it compatibly

## Core semantic rules

### 1. Workflows remain canonical named definitions

Higher-order workflow support must not make arbitrary runtime data executable as a workflow.

The executable unit remains:

- a canonical workflow definition discoverable/registered through the existing workflow system

A workflow reference is only a way to select one of those definitions.

### 2. Delegation remains the only execution boundary in v1

First-cut higher-order workflow execution should happen only through `:type :delegate`.

This task should not add a separate generic “execute workflow value” step kind unless implementation proves that doing so is cleaner than extending delegate and records why.

Preferred first-cut rule:

- higher-order composition extends delegate target resolution, not the overall step taxonomy

### 3. Deterministic resolution

Dynamic delegation must remain deterministic relative to canonical runtime state and workflow inputs.

That means:

- workflow references must resolve through canonical workflow-definition lookup
- the same workflow reference in the same replayable state must resolve the same way
- failures must be explicit rather than falling back to heuristics
- no ambient code generation or dynamic interpretation step may sit between reference resolution and canonical workflow lookup

### 4. Explicit failure over guessing

The system must fail clearly when:

- a target value is not a valid workflow reference
- a source resolves to a value of the wrong shape
- a referenced workflow name does not exist
- a referenced workflow exists but is unavailable under the active capability/runtime rules

The runtime must not guess from plain strings when the chosen model requires an explicit workflow-reference value shape, unless the design intentionally allows both shapes and records the exact rule.

### 5. Capability and availability rules remain enforced

Dynamic target selection must not bypass workflow availability/capability rules.

If the runtime currently distinguishes among:

- known workflows
- available workflows
- session-allowed workflows

then higher-order delegation must use the same canonical enforcement path already used for static delegation or a documented equivalent that preserves the same constraints.

## Design questions this task must answer explicitly

Implementation must answer and record at least these questions:

1. What exact external and internal value shape represents a workflow reference?
2. Should author-facing source references resolve to explicit workflow-reference values only, or may plain workflow-name strings also participate in the dynamic path?

Preferred answer:

- sourced dynamic targets should require explicit workflow-reference values
- plain strings should remain valid for the existing static authored `:target "name"` form
- this preserves backward compatibility for static delegation while keeping higher-order delegation explicit and non-stringly
3. Where in the pipeline should workflow-reference validation happen:
   - grammar validation
   - target IR compilation
   - runtime source resolution
   - runtime delegate execution
   - or a layered combination?

Preferred answer:

- use a layered validation model
- grammar/target compilation should validate only the authored target shape union: string workflow name or source-spec-like map
- runtime source resolution should validate that dynamic sourced values are explicit workflow-reference values
- canonical delegate execution should continue to validate final workflow availability through the existing lookup/enforcement path

This keeps authored-shape errors local while preserving runtime validation for data-dependent target values.
4. What is the exact error behavior for malformed workflow references versus unresolved canonical workflow names?
5. How does dynamic delegation interact with workflow capability availability/gating?
6. What is the smallest coherent documentation and authoring surface that makes higher-order delegation understandable without introducing workflow-generation expectations?

## Preferred author model

The preferred author-facing model is:

- use static delegate targets when the target is fixed
- use workflow references when the target must be selected from data
- model workflow references with the explicit tagged shape `{:type :workflow-ref :name "..."}`
- continue to think of workflows as named reusable units
- do not treat workflows as runtime-generated code blobs

A likely first-cut author story is:

- one step selects or emits structured data containing a workflow reference
- that workflow reference should normally travel through a machine-readable data output surface, not through yielded text
- a later delegate step consumes that workflow reference as its target

Preferred first-cut authoring discipline:

- when a workflow is choosing a later workflow dynamically, expose that choice through a structured `:output` / `:data` path
- workflow references are first-cut ordinary structured data values carried through existing data surfaces, not a new global output type system
- if an LLM-backed step is involved in choosing a workflow, it should emit structured data suitable for validation/projection rather than prose naming a workflow
- do not rely on free-form text generation to produce workflow references

This task should add enough model/documentation support to make that pattern explicit and unsurprising.

## Validation and failure behavior

At minimum, implementation should define and prove behavior for:

- valid static delegate target still works unchanged
- valid dynamic workflow reference target resolves and delegates successfully
- invalid authored dynamic target shape fails explicitly during grammar/compiler validation
- invalid workflow-reference shape fails explicitly at runtime value validation
- dynamic source resolves to wrong-type data and fails explicitly
- workflow reference names a non-existent workflow and fails explicitly
- workflow reference names a workflow unavailable to the active runtime/session and fails explicitly

Preferred failure split:

- authored-shape failure: the authored `:target` is neither a workflow-name string nor a valid source-spec shape
- runtime-type failure: the dynamic source resolved, but not to `{:type :workflow-ref :name string}`
- lookup failure: the workflow reference shape was valid, but no canonical workflow definition exists for `:name`
- availability failure: the canonical workflow exists, but the current runtime/session may not delegate to it under capability/availability rules

The task does not have to freeze exact exception class names now, but it should preserve these semantic distinctions in code, proof, and recorded implementation notes.

The semantic distinction between lookup failure and availability failure should be preserved even if later user-facing message wording is normalized for policy or UX reasons.

If static strings are accepted in the dynamic path as a compatibility convenience, the implementation must record:

- exactly where that coercion happens
- why it does not reintroduce stringly ambiguity
- why explicit workflow references remain the canonical model

## Relationship to current workflow model

This task should build on the current workflow system rather than replace it.

It assumes existing delegate-yield and delegate-handoff downstream consumption behavior remains canonical and preserved; this task generalizes only how the delegate target is selected, not how downstream consumers read delegated results.

It should remain compatible with:

- current `.psi/workflows/` authored workflow discovery/loading
- current workflow grammar and IR structure except where extension is required
- current delegate semantics other than target generalization
- current workflow runtime boundaries and extracted workflow components

This task is not a reason to fold workflow behavior upward or redesign the workflow runtime architecture.

## Preferred ownership boundaries

This task should preserve the existing lower workflow component boundaries and place new ownership according to responsibility:

- grammar/model/IR changes stay with the canonical lower workflow owners
- runtime delegate-resolution changes stay with the canonical lower workflow runtime/source-resolution owners
- higher core session-facing/docs/wiring changes stay with the existing higher workflow/core owners

This task should not introduce ad hoc higher-level workflow target parsing if the lower workflow model already owns the canonical semantics.

Default expected ownership split:

- grammar/compiler owners validate authored `:target` shape
- source-resolution owners validate resolved workflow-reference value shape
- delegate runtime / canonical lookup owners validate workflow existence and availability

## Public surface preservation

Must preserve:

- existing static `:delegate` authoring remains valid unless a tightly justified migration is explicitly recorded
- existing workflow load/reload behavior
- existing workflow runtime semantics for non-higher-order workflows
- existing delegate result and handoff semantics once a target workflow has been resolved

May extend:

- workflow grammar documentation
- workflow IR documentation
- target validation rules
- runtime error messages

## Testing expectations

Implementation should add focused proof at the right layers.

### Grammar / compiler proof

Cover at least:

- static delegate target still compiles as before
- dynamic delegate target shape compiles into canonical IR
- malformed higher-order target shapes fail validation clearly
- workflow-reference value semantics are represented explicitly rather than incidentally

### Runtime resolution proof

Cover at least:

- valid workflow reference resolves to the intended canonical workflow definition
- wrong-type resolved target value fails as a runtime-type failure
- valid-shaped but unknown workflow reference fails as a lookup failure
- valid-shaped but unavailable workflow reference fails as an availability failure
- a workflow reference selected earlier but removed before delegation fails as a lookup failure at delegation time
- dynamic target resolution reuses canonical workflow lookup/enforcement rather than bypassing it
- replay-relevant behavior stays deterministic

### End-to-end proof

Cover at least one realistic higher-order workflow example:

- one step selects or yields a workflow reference
- a later delegate step consumes that reference
- the delegated workflow runs successfully
- downstream yield/handoff behavior remains unchanged after target resolution

Also cover at least one failure example where dynamic target resolution fails explicitly.

## Worked examples

### Success example — choose a workflow, then delegate to it

Illustrative author-facing shape:

```clojure
{:steps [{:name "choose-workflow"
          :type :invoke
          :operation "workflow/select-bug-path"
          :args {:issue-type {:from :workflow-input}}
          :outputs {:data {:source :invoke/result}}
          :yields {:type :data :data :data}}

         {:name "run-selected-workflow"
          :type :delegate
          :target {:from {:step "choose-workflow" :output :data}
                   :path [:selected-workflow]}
          :prompt-string "Handle the issue using the selected workflow."
          :context [{:type :source
                     :from :workflow-original}]}

         {:name "report-result"
          :type :session
          :contributions [{:type :template
                           :text "Delegated result:\n\n{{result}}"
                           :vars {"result" {:from {:step "run-selected-workflow" :yield :text}}}}]
          :yields {:type :text :text :final-llm-reply}}]}
```

Expected machine-readable selection output from `choose-workflow`:

```clojure
{:selected-workflow {:type :workflow-ref
                     :name "gh-bug-triage-modular"}}
```

Why this is the preferred pattern:

- workflow selection is explicit and machine-readable
- the workflow reference travels through structured data
- the delegate step remains the only execution boundary
- downstream yield/handoff consumption works the same after target resolution

### Invalid example — dynamic target resolves to plain string data

Illustrative resolved source value:

```clojure
{:selected-workflow "gh-bug-triage-modular"}
```

With dynamic target:

```clojure
{:type :delegate
 :target {:from {:step "choose-workflow" :output :data}
          :path [:selected-workflow]}
 ...}
```

This should fail as a runtime-type failure, not silently coerce the string into a workflow reference, because the canonical higher-order value shape is explicit:

```clojure
{:type :workflow-ref
 :name "gh-bug-triage-modular"}
```

### Unavailable-workflow example — valid reference shape, unavailable target

Illustrative resolved source value:

```clojure
{:selected-workflow {:type :workflow-ref
                     :name "admin-only-workflow"}}
```

If `admin-only-workflow` exists canonically but is unavailable to the current runtime/session under workflow capability rules, the delegate step should fail as an availability failure.

It should not:

- pretend the workflow is missing if it is actually known but unavailable
- bypass capability/availability checks because the target was selected dynamically
- degrade to some fallback workflow

## Documentation expectations

User-facing workflow docs should explain:

- what higher-order workflow references are
- what they are not
- when to use static delegate targets vs workflow references
- a minimal worked example of dynamic delegation
- the rule that workflows remain canonical named definitions rather than runtime-generated executable code

If the implementation introduces a specific author-facing value shape, docs must show that exact shape.

## Acceptance

- a task exists for adding higher-order workflow references through first-class workflow-reference values and dynamic delegation
- the design clearly limits first-cut higher-order support to composition/selection of existing canonical workflows
- delegate targets can be modeled as static or dynamically resolved without redefining the overall workflow execution model
- authored workflows remain the canonical executable units; runtime-generated executable workflow definitions remain out of scope
- the design requires explicit validation, deterministic resolution, and capability-safe enforcement for dynamic delegation
- implementation is guided to preserve current static delegation behavior while extending the model for higher-order composition
- the task defines focused proof and documentation expectations for grammar, runtime resolution, end-to-end success, and explicit failure behavior
