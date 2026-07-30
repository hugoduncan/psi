# Workflow Grammar — Grammar Reference

This document describes the supported workflow authoring grammar for the
deterministic-workflow-step design.

Higher-order workflow references are supported in a narrow, explicit form:
- workflows remain canonical named definitions
- dynamic delegation happens only through `:type :delegate`
- dynamic `:target` reuses canonical `source-spec` shape
- resolved dynamic targets must be explicit workflow references of the form
  `{:type :workflow-ref :name "builder"}`
- plain strings remain the static authored delegate-target form only

It documents the author-facing `:type :invoke | :session | :delegate` model.
For the conceptual explanation of this design, see
`doc/workflow-grammar-concepts.md`.

```clojure
workflow ::= workflow-map

workflow-map ::= {:steps [step+]
                  terminal-contract?}

terminal-contract ::= :terminal-contract {:handoff {:type :markdown-handoff-data}}

step ::= invoke-step | session-step | delegate-step

invoke-step ::= {:name step-name
                 :type :invoke
                 :operation operation-id
                 :args arg-map
                 yields?
                 control-flow*}

session-step ::= {:name step-name
                  :type :session
                  session-config-entry*
                  (:contributions [contribution+]
                   | :prompt-workflow relative-md-path
                   | :prompts [prompt-group+])
                  outputs?
                  yields?
                  control-flow*}

prompt-group ::= {:name prompt-name
                  (:prompt-workflow relative-md-path | :contributions [contribution+])}

delegate-step ::= {:name step-name
                   :type :delegate
                   :target (workflow-name | source-spec)
                   delegate-session-config-entry*
                   :prompt-string (string | template-contribution)
                   :context? [source-item*]
                   outputs?
                   yields?
                   control-flow*}

control-flow ::= :judge judge-spec
               | :on outcome-map
               | :max-iterations pos-int

judge-spec ::= llm-judge | invoke-judge

llm-judge ::= {:type :llm
               judge-session-config-entry*
               :contributions [contribution+]
               outputs?}

invoke-judge ::= {:type :invoke
                  :operation operation-id
                  :args arg-map}

outcome-map ::= {outcome transition-map}+

transition-map ::= {:goto goto-target
                    max-iterations-clause?
                    on-max-iterations-clause?}

max-iterations-clause ::= :max-iterations pos-int

on-max-iterations-clause ::= :on-max-iterations goto-target
;; optional; only valid alongside :max-iterations. Names the target a judged
;; loop routes to when it exhausts :max-iterations, instead of hard failing.

goto-target ::= :next | :previous | :done | step-name

contribution ::= source-contribution | template-contribution

source-contribution ::= {:type :source
                         :from source-ref
                         source-projection?}

template-contribution ::= {:type :template
                           :text string
                           :vars {var-name source-spec}*}

source-item ::= {:type :source
                 :from source-ref
                 source-projection?}

source-spec ::= {:from source-ref
                 source-projection?}

For delegate targets:
- `:target "builder"` is the static form
- `:target {:from ... :path [...]}` is the dynamic higher-order form
- dynamic target resolution must produce a `workflow-ref`
- free-form text and plain strings are not valid dynamic workflow-reference values

source-projection ::= :path path
                    | :projection projection

source-ref ::= :workflow-input
             | :workflow-original
             | {:step step-name :output output-key}
             | {:step step-name :prompt prompt-name :output output-key}
             | {:step step-name :yield yield-field}

outputs ::= {output-key output-spec}+

output-spec ::= delegate-handoff-output
              | session-text-output
              | session-structured-output
              | judge-structured-output

delegate-handoff-output ::= {:source :delegate/handoff}

session-text-output ::= {:source :session/final-llm-reply}

session-structured-output ::= {:source :session/structured-output
                               :mode :structured
                               schema-contract
                               invalid-policy?}

judge-structured-output ::= {:source :judge/structured-output
                             :mode :structured
                             schema-contract
                             invalid-policy?}

schema-contract ::= :schema-id keyword
                  :schema-version pos-int
                  :schema malli-schema
                  :json-schema json-schema-map?
                  :strategy-preference (:provider-native | :prompted-json)?
                  :fallback (:prompted-json | :none)?
                  :require-provider-native? boolean?

invalid-policy ::= :on-invalid {:action :fail-fast}
                 | :on-invalid {:action :retry
                                :max-attempts pos-int}

output-key ::= keyword

yield-field ::= keyword

arg-map ::= {keyword (literal | source-spec)}*

session-config-entry ::= :session-profile keyword
                       | :model model-selection-spec
                       | :tools [tool-id*]
                       | :skills [skill-id*]
                       | :thinking-level (:off | :minimal | :low | :medium | :high | :xhigh)
                       | :temperature double   ;; optional, range [0.0, 2.0]; absent = provider default
                       | session-config-extension

delegate-session-config-entry ::= :session-profile keyword
                                | :model model-selection-spec
                                | :thinking-level (:off | :minimal | :low | :medium | :high | :xhigh)

;; Delegate session config shapes the delegated run's concrete inherited
;; defaults; it does not construct a delegate actor session. Direct authored
;; :speed-mode/:effort-override keys are intentionally not part of this grammar;
;; they can flow from resolved session profiles.

judge-session-config-entry ::= :model model-selection-spec
                             | :tools [tool-id*]
                             | :skills [skill-id*]
                             | :temperature double   ;; optional, range [0.0, 2.0]; absent = provider default
                             | judge-session-config-extension

model-selection-spec ::= external-nonterminal-defined-in-doc-model-selection-grammar

yields ::= {:type :data :data output-keyword}
         | {:type :text :text output-keyword}
         | {:type :error :reason keyword :message string :details? map}
         | {:type :delegated}

output-keyword ::= keyword

step-name ::= string
prompt-name ::= string
relative-md-path ::= string
workflow-ref ::= {:type :workflow-ref
                 :name workflow-name}

workflow-name ::= string
operation-id ::= string
tool-id ::= string
skill-id ::= string
var-name ::= string
outcome ::= string | keyword
path ::= vector
projection ::= map
literal ::= string | keyword | number | boolean | nil | vector | map
malli-schema ::= vector | map | keyword
pos-int ::= integer
map ::= clojure-map
vector ::= clojure-vector
string ::= clojure-string
keyword ::= clojure-keyword
number ::= clojure-number
boolean ::= true | false
nil ::= nil
```

## See Also

- [Multi-Prompt Sessions](multi-prompt-sessions.md) — `:prompts` queue, drain, resume, abort
- [Structured Outputs](structured-outputs.md) — session and judge structured output declarations
