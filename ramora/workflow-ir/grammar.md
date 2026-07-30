# Workflow IR — Documentation Grammar

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
