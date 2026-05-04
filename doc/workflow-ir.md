# Workflow IR

This document defines the **normalized workflow IR** for deterministic workflow steps.

The workflow IR is the canonical runtime execution model used to bridge:

- the **current implemented workflow grammar** documented in `doc/workflow-grammar-current.md`
- the **target converged workflow grammar** documented in `doc/workflow-grammar.md`

It is intentionally not a user-facing authoring grammar. It is the normalized execution boundary that both authored grammars should compile into.

For the migration architecture that introduces this IR, see `doc/workflow-grammar-migration.md`.

## Purpose

The workflow IR exists to make the runtime independent of authored syntax.

It gives execution one canonical model for:

- step identity
- execution form
- control flow
- judge execution
- data references
- session construction
- delegated boundaries
- step-local outputs
- yielded values
- workflow result composition

The runtime should execute IR, not raw authored workflow documents.

## Design properties

The IR should have these properties:

- **Canonical** — one execution model regardless of authored grammar source
- **Explicit** — execution form, references, outputs, and yielded value are visible in data
- **Typed by tag** — execution form and yielded-value semantics use explicit `:type` discrimination
- **Observable** — runtime can record inspectable effective boundary inputs for each step form
- **Compatibility-capable** — current-grammar semantics can be preserved during migration without leaking compatibility fields into the target authoring surface
- **Small** — only execution-relevant concepts belong here

## IR overview

A normalized workflow IR is a map with ordered steps.

Illustrative top-level shape:

```clojure
{:version :workflow-ir/v1
 :steps [ir-step+]}
```

The normalized IR boundary requires at least one step. Empty workflows are invalid IR and should be rejected before execution.

This IR is the **compiled execution model**, not an authored workflow surface. It is intentionally close to the target grammar, but it is allowed to contain additional normalization and temporary compatibility detail needed for runtime execution and migration.

The ordered `:steps` vector is the canonical authored/program order.

The runtime may derive a name index or graph index as needed, but those are execution-time conveniences rather than authored meaning.

## Step forms

The IR has three step execution forms:

- invoke
- session
- delegate

Each step has exactly one execution `:type`.

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 ...}
```

### Common step fields

All step forms share these conceptual fields:

- `:name`
- `:type`
- optional `:outputs`
- optional `:yields`
- optional `:judge`
- optional `:on`
- optional `:max-iterations`
- optional compatibility metadata during migration

The IR should validate execution-specific fields according to step type.

Important alignment rule:

- the authored target grammar hoists execution-specific fields directly onto the step
- the IR groups those fields under one execution-specific key such as `:invoke`, `:session`, or `:delegate`

This keeps authored syntax compact while giving runtime one explicit normalized place for execution payload.

## Invoke step

An invoke step executes a deterministic operation.

Illustrative shape:

```clojure
{:name "discover"
 :type :invoke
 :invoke {:operation "github/search-issues-by-label"
          :args {:repo {:from :workflow-input :path [:repo]}
                 :labels {:from :workflow-input :path [:labels]}
                 :state "open"}}
 :outputs {:data {:source :invoke/data}
           :summary {:source :invoke/summary}
           :result {:source :invoke/result}}
 :yields {:type :data
          :data :data}}
```

### Invoke semantics

- `:operation` is a canonical runtime operation id
- `:args` is a fully normalized named-argument map
- the operation runs without child-session construction
- canonical machine-readable output is `:data`
- optional human-readable output is `:summary`
- optional debug/result envelope output is `:result`

## Session step

A session step constructs and runs a child session inline.

Illustrative shape:

```clojure
{:name "report"
 :type :session
 :session {:model "gpt-5.4"
           :tools ["read" "bash"]
           :skills ["issue-feature-triage"]
           :contributions [{:type :source
                            :from :workflow-original}
                           {:type :template
                            :text "Review these issues:\n\n{{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}}}]}
 :outputs {:text {:source :session/final-llm-reply}
           :transcript {:source :session/transcript}
           :result {:source :session/result}}
 :yields {:type :text
          :text :text}}
```

### Session semantics

- `:session` contains the effective child-session construction data
- `:contributions` are ordered and preserved as authored
- the assembled result of contributions is the child-session conversation
- canonical first-cut session output is `:final-llm-reply`
- transcript output is optional but normalized when present

## Delegate step

A delegate step invokes another workflow through an explicit workflow boundary.

Illustrative shape:

```clojure
{:name "report-call"
 :type :delegate
 :delegate {:target "builder"
            :prompt-string {:type :template
                            :text "Review these issues:\n\n{{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "discover" :output :data}
                       :path [:issues]}]}
 :yields {:type :delegated}}
```

### Delegate semantics

- `:target` resolves to a named workflow definition
- `:prompt-string` must render to a final string before invocation
- `:context` is ordered forwarded material and is optional
- the delegated workflow's local `:workflow-input` becomes the rendered prompt string
- the delegated workflow's local `:workflow-original` is rebound for the delegated invocation
- by default, the step yields the delegated workflow's yielded value unchanged
- first cut does not use delegated session overrides; delegation is a workflow boundary, not a child-session customization surface

## Outputs

The IR separates execution from output surfaces.

Each step may expose step-local outputs by logical output key.

Illustrative common output keys:

- invoke step: `:data`, `:summary`, `:result`
- session step: commonly `:text`, `:transcript`, `:result`
- delegate step: optional debug/result outputs only if justified later

The `:outputs` map should describe what logical output keys exist for a step and, at minimum, give runtime a canonical local meaning for each key.

The exact internal value of each `:outputs` entry may evolve, but the key-space should be stable for runtime reference and validation.

If a local `:yields` form names an output key (for example `{:type :text :text :final-llm-reply}` or `{:type :data :data :data}`), that output key must be declared in the same step's `:outputs` map at the normalized IR boundary.

## Yielded value

The IR distinguishes:

- step-local outputs addressable through `:output`
- the step's resulting value as a whole, modeled through `:yields`

### Yield forms

The first-cut yielded-value union is:

```clojure
{:type :data
 :data output-key}
```

```clojure
{:type :text
 :text output-key}
```

```clojure
{:type :error
 :reason keyword
 :message string
 :details? map}
```

For delegate steps, the normal behavior is compositional delegation:

```clojure
{:type :delegated}
```

Meaning:

- yield the called workflow's yielded value unchanged

This avoids redundantly restating the delegated workflow's yield form at every delegating callsite.

### Default yield rules

When omitted in authored input, compiler-side normalization must supply defaults before the runtime-owned IR validation boundary:

- invoke step -> `{:type :data :data :data}`
- session step -> `{:type :text :text :final-llm-reply}`
- delegate step -> `{:type :delegated}`

The IR validator treats missing `:yields` as invalid normalized IR rather than filling defaults locally.

This aligns with the target grammar's preferred defaults:

- deterministic/invoke steps yield their canonical machine-readable `:data`
- inline session steps yield their canonical terminal text output key for the chosen compilation path; the current-grammar compatibility compiler currently normalizes this as `:text`
- delegated steps yield the callee's yielded value unchanged

## Control flow

Control flow is orthogonal to execution form.

The IR uses:

- `:judge`
- `:on`
- `:max-iterations`

Illustrative shape:

```clojure
{:judge {...}
 :on {"APPROVED" {:goto :done}
      "REVISE" {:goto "build" :max-iterations 3}}
 :max-iterations 5}
```

### Routing rules

- a judge produces one logical outcome value
- `:on` maps that outcome to a transition directive
- normalized IR requires `:on` when `:judge` is present, and requires `:judge` when `:on` is present
- if a selected transition goes to `:done`, the parent step's yielded value becomes the workflow result
- a judge routes; it does not replace the parent step's yielded value

## Judge forms

The IR should normalize judge execution mode explicitly.

First-cut judge forms:

- `:type :llm`
- `:type :invoke`

### LLM judge

Illustrative shape:

```clojure
{:type :llm
 :session {:model "gpt-5.4"
           :contributions [...]} 
 :projection {:type :tail
              :turns 4
              :tool-output false}}
```

### Invoke judge

Illustrative shape:

```clojure
{:type :invoke
 :invoke {:operation "workflow/classify-result"
          :args {:result {:from {:step "build" :output :data}}}}}
```

### Judge outcome contract

All judge forms normalize to one logical outcome value.

That outcome:

- may be a string or keyword
- is matched exactly against the keys in `:on`
- is case-sensitive for strings
- does not auto-coerce between strings and keywords

## Source references

The IR uses one shared source-spec language for:

- invoke args
- source contributions
- template vars
- delegated context
- judge args where applicable

### Source spec

Illustrative shape:

```clojure
{:from source-ref
 :path [:issues]}
```

or:

```clojure
{:from source-ref
 :projection {:type :tail :turns 4 :tool-output false}}
```

A source-spec must not contain both `:path` and `:projection` in the first cut.

### Source refs

Illustrative source refs:

```clojure
:workflow-input
:workflow-original
{:step "discover" :output :data}
{:step "discover" :yield :data}
```

These align with the target grammar's shared data-reference model and replace current-grammar compatibility references such as `{:source ... :path ...}` at the runtime boundary.

### Meaning of refs

- `:workflow-input` -> current workflow invocation input value
- `:workflow-original` -> current workflow invocation original request surface
- `{:step s :output k}` -> step-local output surface `k` from prior step `s`
- `{:step s :yield f}` -> yielded-value field `f` from prior step `s`

Current implementation note:

- canonical normalized IR currently admits `:workflow-input`, `:workflow-original`, prior-step `:output`, and prior-step `:yield`
- the current-authored compatibility compiler also encounters current `:workflow-runtime` refs from the legacy grammar, but `workflow_ir.clj` does not yet admit `:workflow-runtime` as a canonical IR `:from` source-ref
- therefore `:workflow-runtime` is currently a known migration seam rather than a settled canonical IR feature

## Contributions

Session construction and delegated context reuse normalized contribution items.

### Source contribution

```clojure
{:type :source
 :from source-ref
 source-projection?}
```

### Template contribution

```clojure
{:type :template
 :text string
 :vars {string source-spec}*}
```

Rules:

- author order is preserved
- template vars use the same source-spec language as invoke args
- unresolved vars are first-cut errors
- template rendering must produce deterministic text from resolved values

## Delegated prompt-string

Delegate `:prompt-string` may be represented in IR either as:

- a literal final string, or
- a template contribution-like renderer that will deterministically render to a final string before invocation

Recommended first-cut shape keeps authored intent visible until boundary rendering:

```clojure
:string
```

or

```clojure
{:type :template
 :text string
 :vars {string source-spec}*}
```

Before actual delegated execution, runtime should materialize this to a final string.

## Compatibility metadata

During migration, the IR may temporarily carry compatibility metadata required to preserve semantics from the current grammar.

Illustrative shape:

```clojure
:compat {...}
```

Rules:

- compatibility metadata is allowed only when needed to preserve current behavior during migration
- execution semantics should still be driven by canonical IR fields
- compatibility metadata must not be treated as part of the target authored grammar
- long-term goal is to shrink and remove compatibility metadata as current grammar is retired

Examples of things that may temporarily need compatibility metadata include:

- role-shaping details compiled from current `:session-preload`
- distinctions preserved from current `workflow-binding-ref` sources during translation
- accepted-result-envelope breadcrumbs preserved from current `:step-output` reads that target whole-envelope, `:diagnostics`, or `:blocked` surfaces outside canonical declared outputs
- current required `:result-schema`, preserved as compile-time breadcrumbs rather than a canonical execution field
- authored-source breadcrumbs useful for debugging compiler output while both grammars coexist

## Workflow result composition

The workflow result is the yielded value of the step whose chosen transition reaches `:done`.

Rules:

- direct step -> `:done` means that step's yielded value becomes the workflow result
- judge-selected transition -> `:done` still returns the parent step's yielded value
- delegated steps normally return the delegated workflow's yielded value unchanged

## Suggested documentation grammar

This section gives a compact documentation grammar for the normalized IR.

```clojure
workflow-ir ::= {:version :workflow-ir/v1
                 :steps [ir-step+]}

ir-step ::= invoke-ir-step | session-ir-step | delegate-ir-step

invoke-ir-step ::= {:name step-name
                    :type :invoke
                    :invoke invoke-spec
                    outputs?
                    yields?
                    control-flow*
                    compat?}

session-ir-step ::= {:name step-name
                     :type :session
                     :session session-spec
                     outputs?
                     yields?
                     control-flow*
                     compat?}

delegate-ir-step ::= {:name step-name
                      :type :delegate
                      :delegate delegate-spec
                      outputs?
                      yields?
                      control-flow*
                      compat?}

invoke-spec ::= {:operation operation-id
                 :args {keyword (literal | source-spec)}*}

session-spec ::= {:model? model-selection-spec
                  :tools? [tool-id*]
                  :skills? [skill-id*]
                  :contributions [contribution+]
                  session-extension*}

delegate-spec ::= {:target workflow-name
                   :prompt-string (string | template-contribution)
                   :context? [source-contribution*]}

control-flow ::= :judge judge-spec
               | :on outcome-map
               | :max-iterations pos-int

judge-spec ::= llm-judge | invoke-judge

llm-judge ::= {:type :llm
               :session judge-session-spec
               :projection? projection}

judge-session-spec ::= {:model? model-selection-spec
                        :tools? [tool-id*]
                        :skills? [skill-id*]
                        :contributions [contribution+]}

invoke-judge ::= {:type :invoke
                  :invoke invoke-spec}

outcome-map ::= {outcome transition-map}+

transition-map ::= {:goto goto-target
                    :max-iterations? pos-int}

goto-target ::= :next | :previous | :done | step-name

outputs ::= {:outputs {output-key output-spec}+}

output-spec ::= {:source keyword
                 output-metadata*}

yields ::= {:type :data :data output-key}
         | {:type :text :text output-key}
         | {:type :error :reason keyword :message string :details? map}
         | {:type :delegated}

contribution ::= source-contribution | template-contribution

source-contribution ::= {:type :source
                         :from source-ref
                         source-projection?}

template-contribution ::= {:type :template
                           :text string
                           :vars {var-name source-spec}*}

source-spec ::= {:from source-ref
                 source-projection?}

source-projection ::= :path path
                    | :projection projection

source-ref ::= :workflow-input
             | :workflow-original
             | {:step step-name :output output-key}
             | {:step step-name :yield yield-field}

;; current-grammar migration note:
;; legacy `:workflow-runtime` refs exist in the current grammar but are not yet
;; admitted as canonical normalized IR source-refs.

output-key ::= keyword
yield-field ::= keyword
projection ::= map
compat ::= :compat map
step-name ::= string
workflow-name ::= string
operation-id ::= string
tool-id ::= string
skill-id ::= string
var-name ::= string
outcome ::= string | keyword
path ::= vector
literal ::= string | keyword | number | boolean | nil | vector | map
pos-int ::= integer
map ::= clojure-map
vector ::= clojure-vector
string ::= clojure-string
keyword ::= clojure-keyword
number ::= clojure-number
boolean ::= true | false
nil ::= nil
```

## Recommended next use

This IR should be used to drive implementation slicing for task `077`:

1. define IR validation/schema
2. compile current grammar to IR
3. execute IR in runtime
4. compile target grammar to IR
5. land invoke/session/delegate execution on that substrate
