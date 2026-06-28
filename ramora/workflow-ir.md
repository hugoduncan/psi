# Workflow IR

This document defines the **normalized workflow IR** for deterministic workflow
steps.

The workflow IR is the canonical runtime execution model for authored workflow
files compiled from `doc/workflow-grammar.md`.

It is intentionally not a user-facing authoring grammar. It is the normalized
execution boundary used by runtime execution.

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

- **Canonical** — one execution model for all authored workflow files
- **Explicit** — execution form, references, outputs, and yielded value are visible in data
- **Typed by tag** — execution form and yielded-value semantics use explicit `:type` discrimination
- **Observable** — runtime can record inspectable effective boundary inputs for each step form
- **Small** — only execution-relevant concepts belong here

## IR overview

A normalized workflow IR is a map with ordered steps.

Illustrative top-level shape:

```clojure
{:version :workflow-ir/v1
 :steps [ir-step+]}
```

The normalized IR boundary requires at least one step. Empty workflows are invalid IR and should be rejected before execution.

This IR is the **compiled execution model**, not an authored workflow surface.
It is intentionally close to the workflow grammar while remaining free to carry
runtime-oriented normalization detail.

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
 :outputs {:final-llm-reply {:source :session/final-llm-reply}
           :transcript {:source :session/transcript}
           :result {:source :session/result}}
 :yields {:type :text
          :text :final-llm-reply}}
```

### Session semantics

- `:session` contains the effective child-session construction data
- `:contributions` are ordered and preserved as authored
- the assembled result of contributions is the child-session conversation
- canonical first-cut session output is `:final-llm-reply`
- transcript output is optional but normalized when present
- structured machine-facing output is declared under step-local `:outputs` with `:source :session/structured-output`

#### Prompt source: `:contributions` vs `:prompts`

A session step's prompt source is normalized at IR time into one internal
prompt-queue representation, regardless of authoring form:

- a `:contributions` (or `:prompt-workflow`) session step normalizes to a
  **single unnamed** prompt-group — the degenerate one-turn queue shown above;
- a `:prompts` session step normalizes to an **ordered queue of named**
  prompt-groups, each run as its own model turn in the same shared child
  session, drained in author order by `drive-session-prompt-queue!`.

The two authoring forms are mutually exclusive on a single session step. See
[`doc/workflow-grammar.md`](workflow-grammar.md) *Multi-prompt session steps
(`:prompts`)* for the author-facing `:prompts` form, precedence/validation
rules, per-prompt output surfaces, and drain/resume/abort semantics.

### Session structured outputs

A session step may declare at most one structured output entry under its
step-local `:outputs` map. The output key is the logical machine-facing value
that downstream references address. Authors who need multiple machine-facing
fields should model them as fields inside that one structured value.

Illustrative normalized shape:

```clojure
{:name "classify-reproduction"
 :type :session
 :session {:contributions [...]}
 :outputs {:classification
           {:source :session/structured-output
            :mode :structured
            :schema-id :psi.workflow/bug-reproduction-classification
            :schema-version 1
            :schema [:map
                     [:status [:enum :reproducible :not-reproducible :unclear]]
                     [:summary :string]
                     [:evidence [:vector :string]]
                     [:commands-run [:vector :string]]
                     [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]}}
 :yields {:type :data
          :data :classification}}
```

Session structured outputs are optional. If omitted, the session step remains a
text-producing step and should expose `:final-llm-reply` when downstream text
consumption is needed.

## Delegate step

A delegate step invokes another workflow through an explicit workflow boundary.

Illustrative shape:

```clojure
{:name "report-call"
 :type :delegate
 :delegate {:target "builder"
            :session {:session-profile :coding
                      :model "gpt-5.5"
                      :thinking-level :medium}
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
- first cut does not re-export the callee workflow's step-local output surfaces through downstream `{:step ... :output ...}` refs against the delegate step itself
- optional `[:delegate :session]` config contains only `:session-profile`, `:model`, and `:thinking-level` in the current IR
- delegate session config shapes the concrete `:inherited-defaults` snapshot passed to the delegated run; it does not construct a delegate actor session for the delegate step itself
- profile-derived `:speed-mode` and `:effort-override` may flow into the delegated run through that inherited-defaults snapshot, but direct authored delegate session config does not accept `:speed-mode` or `:effort-override`

## Outputs

The IR separates execution from output surfaces.

Each step may expose step-local outputs by logical output key.

Illustrative common output keys:

- invoke step: `:data`, `:summary`, `:result`
- session step: commonly `:final-llm-reply`, `:transcript`, `:result`, plus any declared structured output keys
- delegate step: no first-cut step-local outputs except explicitly declared boundary outputs such as `:handoff`
- LLM judge: judge-local structured output keys under the judge's own `:outputs` map for transition evaluation only

The `:outputs` map should describe what logical output keys exist for a step or
judge and, at minimum, give runtime a canonical local meaning for each key.

The exact internal value of each `:outputs` entry may evolve, but the key-space should be stable for runtime reference and validation.

If a local `:yields` form names an output key (for example `{:type :text :text :final-llm-reply}` or `{:type :data :data :data}`), that output key must be declared in the same step's `:outputs` map at the normalized IR boundary.

### Structured output specs

A structured output spec is an output entry with a structured source and schema
contract. The normalized IR accepts these sources for this slice:

- `:session/structured-output` on session-step `:outputs`
- `:judge/structured-output` on LLM-judge `:outputs`

Normalized structured output spec shape:

```clojure
{:source structured-output-source
 :mode :structured
 :schema-id schema-id
 :schema-version schema-version
 :schema malli-schema
 :json-schema json-schema-map
 :strategy-preference :provider-native
 :fallback :prompted-json
 :require-provider-native? false
 :on-invalid? invalid-policy}
```

Invalid structured-output handling depends on the structured output source. For
session-step structured outputs, the first implementation's minimum invalid
policy is fail-fast: if `:on-invalid` is omitted, runtime treats it as
`{:action :fail-fast}`. For LLM judge structured outputs, invalid generated
structured output is retried by default up to the built-in judge retry limit using
judge retry feedback; each retry preserves the original structured-output
options/schema. Unsupported structured output still fails immediately with
`:unsupported-structured-output` rather than retrying. Explicit `:on-invalid`
retry/repair policy beyond this built-in judge retry behavior may be added only
when proven by tests.

Cardinality is constrained in the normalized IR: a session step must not contain
more than one `:source :session/structured-output` entry, and an LLM judge must
not contain more than one `:source :judge/structured-output` entry. Compiler or
IR validation should reject multiple structured entries clearly. One raw
model/judge response maps to one structured envelope for the one declared key.
Structured workflow outputs use two schema contracts. `:schema` is the Malli
contract used by workflow runtime for local coercion and validation. `:json-schema`
is the explicit provider/request contract passed under provider-neutral
`:structured-output` options to turn execution; workflow runtime does not infer
JSON Schema from Malli. Omitted `:strategy-preference` defaults to
`:provider-native`, omitted `:fallback` defaults to `:prompted-json`, and
`:require-provider-native? true` forbids fallback. `:fallback :none` also forbids
fallback. If a structured spec opts into provider-native request shaping but has
no `:json-schema`, execution fails with `:missing-json-schema` before generation.
If fallback is forbidden and the resolved model/transport cannot provide native
structured output, execution fails with `:unsupported-structured-output` rather
than silently degrading to prose.

For `:prompted-json`, the AI adapter injects schema-guided JSON-only
instructions into the outbound provider request, and the workflow runtime parses
the returned text as a single JSON value matching the declared JSON Schema. For
`:provider-native`, the provider is asked for a single schema-constrained JSON
value. Scalar, array, object, boolean, number, string, and `null` values are all
valid when the schema allows them. Sibling JSON fields are not promoted into
additional output keys; use a map/object schema plus downstream `:path`
references instead.

### Structured output runtime envelope

Execution records the resolved value behind a structured output key as a
canonical envelope. Downstream references must use only the validated
`:structured-output :value` when status is `:valid`.

Valid example:

```clojure
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :strategy :provider-native
  :native-mechanism :openai/chat-completions-json-schema-response-format
  :source :openai/message-json
  :payload {"decision" "needs-work"
            "issues" []
            "confidence" 0.84}
  :raw-payload "... raw provider diagnostic payload when available ..."
  :status :valid
  :value {:decision :needs-work
          :issues []
          :confidence 0.84}}}
```

Invalid example:

```clojure
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :strategy :prompted-json
  :status :invalid
  :errors [{:message "missing required key"
            :path [:decision]}]
  :parsed-value {"issues" []}}}
```

The strategy field is observable runtime metadata. The first slice should record
at least one of:

- `:provider-native` — the provider/API accepted a structured-output schema or mode directly
- `:prompted-json` — the AI adapter injected a JSON-only/schema instruction, while workflow runtime parsed the result, coerced it to Malli-domain data, and validated it
- `:repair-parse` — the runtime performed an explicit repair parse attempt
- `:unsupported` — the runtime could not reasonably request the structured mode

For `:prompted-json`, the adapter-owned request shaping is limited to prompting
for a single JSON value that matches the declared schema; it does not make the
provider response trusted workflow data. Raw model text remains in
`:raw-output`; workflow runtime parses JSON and schema-guides it into
Malli-domain values before validation. Object keys may become keywords when the
schema expects map keys, and enum strings may become keyword enum values when
the declared enum contains the corresponding keyword. Non-object JSON values are
preserved and validated directly when the schema allows them. Coercion, parse,
or validation failure records `:status :invalid`, `:errors`, and
`:parsed-value` when a parsed value exists; it must not expose `:value`.

Reusable schemas are owned by workflow-runtime code. The first standard reusable
schema should live in `psi.workflow-runtime.structured-output-schemas` with id
`:psi.workflow/judge-review-result` and version `1`. Runtime/docs/tests should
refer to the id/version pair, and known reusable schema declarations should match
the exported Malli schema for that id/version.

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
- inline session steps yield their canonical terminal text output key `:final-llm-reply`
- delegated steps yield the callee's yielded value unchanged

## Control flow

Control flow is orthogonal to execution form.

The IR uses:

- `:judge`
- `:on`
- `:max-iterations`

Transition directives inside `:on` additionally use:

- `:max-iterations` (transition-local loop bound)
- `:on-max-iterations` (optional exhaustion target; only valid alongside
  transition-local `:max-iterations`)

Illustrative shape:

```clojure
{:judge {...}
 :on {"APPROVED" {:goto :done}
      "REVISE" {:goto "build" :max-iterations 3 :on-max-iterations "summary"}}
 :max-iterations 5}
```

### Routing rules

- a judge produces one logical outcome value
- `:on` maps that outcome to a transition directive
- normalized IR requires `:on` when `:judge` is present, and requires `:judge` when `:on` is present
- if a selected transition goes to `:done`, the parent step's yielded value becomes the workflow result
- a judge routes; it does not replace the parent step's yielded value
- a transition directive may carry `:on-max-iterations` (a goto-target) only
  when it also carries transition-local `:max-iterations`; a directive with
  `:on-max-iterations` and no `:max-iterations` is rejected
- when a judged loop exhausts its transition-local `:max-iterations`: if the
  directive carries `:on-max-iterations`, the run routes to that target and
  continues (`:status :running`); if absent, the run hard-fails with
  `:reason :iteration-exhausted`

## Judge forms

The IR should normalize judge execution mode explicitly.

Current executed runtime support:

- `:type :llm`
- `:type :invoke`

### Runtime support note

`components/agent-session/src/psi/agent_session/workflow_judge.clj` executes both
prompt/session-based LLM judges and deterministic invoke judges. LLM judges create
a judge child session and route from the judge response. Invoke judges resolve
`:invoke :args` with the shared workflow source-resolution path, invoke the named
deterministic operation through the deterministic operation registry, and route
from the operation's returned data.

Therefore:

- `:judge {:type :llm ...}` is part of the current executed IR contract
- `:judge {:type :invoke :invoke {:operation ... :args ...}}` is part of the
  current executed IR contract and is used by built-in review workflows for
  deterministic `PASS_STATUS` and constant follow-up routing
- invoke-judge operation failures surface as judge/routing failures rather than
  falling back to LLM text matching

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

An LLM judge may declare judge-local structured outputs. The `:outputs` map is
local to the judge result, not to the parent step's ordinary text outputs.

Illustrative normalized structured judge shape:

```clojure
{:type :llm
 :session {:model "gpt-5.4"
           :contributions [...]}
 :outputs {:review
           {:source :judge/structured-output
            :mode :structured
            :schema-id :psi.workflow/judge-review-result
            :schema-version 1
            :schema [:map
                     [:decision [:enum :clear :needs-work :unclear]]
                     [:issues
                      [:vector
                       [:map
                        [:severity [:enum :blocking :minor]]
                        [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
                        [:description :string]
                        [:evidence :string]
                        [:suggested-change :string]]]]
                     [:confidence [:double {:min 0.0 :max 1.0}]]]}}
 :projection {:type :tail
              :turns 4
              :tool-output false}}
```

Judge structured outputs are intended for judge routing, retry decisions, and
review-loop control data. They do not replace the parent step's yielded value; a
judge routes while the parent step still yields according to its own `:yields`
form. Judge-local output keys are not implicitly promoted into the parent step's
step-local `:outputs` map.

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
- delegated prompt-string template vars
- judge args where applicable

Current runtime ownership note:

- canonical runtime materialization of these refs/specs now lives in `components/agent-session/src/psi/agent_session/workflow_source_resolution.clj`
- compiler/authoring seams may translate authored syntax into IR-compatible refs/specs, but they should not re-encode divergent runtime resolution semantics

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

These align with the workflow grammar's shared data-reference model and form
the runtime boundary.

### Meaning of refs

- `:workflow-input` -> current workflow invocation input value
- `:workflow-original` -> current workflow invocation original request surface
- `{:step s :output k}` -> step-local output surface `k` from prior step `s`
- `{:step s :yield f}` -> yielded-value field `f` from prior step `s`

For session structured outputs, `{:step s :output k}` addresses the logical
parent step output key that declared `:source :session/structured-output`. A
source-spec `:path` is resolved against the validated structured `:value`, not
against `:raw-output`, `:parsed-value`, or prose. Resolution must fail clearly
when the source output is missing, non-structured, invalid, or lacks the requested
path.

Judge-local structured outputs declared with `:source :judge/structured-output`
are available to the judge result and transition evaluation only in this slice.
They are not valid downstream `{:step s :output k}` refs unless a future explicit
promotion/export contract adds a named parent step output. The current source-ref
grammar has no judge identifier, so implicit judge-output promotion would make
the parent step output namespace ambiguous.

Current implementation note:

- canonical normalized IR currently admits `:workflow-input`, `:workflow-original`, prior-step `:output`, and prior-step `:yield`
- `:workflow-runtime` is not a canonical IR `:from` source-ref and target-authored definitions using it are rejected at validation time

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

session-spec ::= {:session-profile? keyword
                  :model? model-selection-spec
                  :thinking-level? (:off | :minimal | :low | :medium | :high | :xhigh)
                  :tools? [tool-id*]
                  :skills? [skill-id*]
                  :temperature? double           ;; optional, range [0.0, 2.0]; absent = provider default
                  :contributions [contribution+]
                  session-extension*}

delegate-spec ::= {:target workflow-name
                   :session? delegate-session-spec
                   :prompt-string (string | template-contribution)
                   :context? [source-contribution*]}

delegate-session-spec ::= {:session-profile? keyword
                           :model? model-selection-spec
                           :thinking-level? (:off | :minimal | :low | :medium | :high | :xhigh)}

control-flow ::= :judge judge-spec
               | :on outcome-map
               | :max-iterations pos-int

judge-spec ::= llm-judge | invoke-judge   ;; both forms are current executed runtime IR

llm-judge ::= {:type :llm
               :session judge-session-spec
               outputs?
               :projection? projection}

judge-session-spec ::= {:model? model-selection-spec
                        :tools? [tool-id*]
                        :skills? [skill-id*]
                        :temperature? double     ;; optional, range [0.0, 2.0]; absent = provider default
                        :contributions [contribution+]}

invoke-judge ::= {:type :invoke
                  :invoke invoke-spec}

outcome-map ::= {outcome transition-map}+

transition-map ::= {:goto goto-target
                    :max-iterations? pos-int
                    :on-max-iterations? goto-target}
;; :on-max-iterations is only valid alongside :max-iterations; it names the
;; exhaustion target instead of the default :reason :iteration-exhausted fail.

goto-target ::= :next | :previous | :done | step-name

outputs ::= {:outputs {output-key output-spec}+}

output-spec ::= text-output-spec | structured-output-spec | delegate-output-spec | invoke-output-spec

text-output-spec ::= {:source :session/final-llm-reply}

structured-output-spec ::= {:source structured-output-source
                            :mode :structured
                            :schema-id schema-id
                            :schema-version schema-version
                            :schema malli-schema
                            :on-invalid? invalid-policy}

structured-output-source ::= :session/structured-output | :judge/structured-output

delegate-output-spec ::= {:source :delegate/handoff}

invoke-output-spec ::= {:source keyword
                        output-metadata*}

invalid-policy ::= {:action :fail-fast}
                 | {:action :retry :max-attempts pos-int}

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

;; `:workflow-runtime` is intentionally not a canonical normalized IR source-ref.

output-key ::= keyword
yield-field ::= keyword
schema-id ::= keyword
schema-version ::= pos-int
malli-schema ::= vector | map | keyword
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

## Recommended use

Use this document together with `doc/workflow-grammar.md` and
`doc/workflow-grammar-concepts.md` when changing workflow compilation,
validation, or runtime execution.
