# Workflow Grammar

```clojure
workflow ::= workflow-map

workflow-map ::= {:steps [step+]}

step ::= invoke-step | session-step | delegate-step

invoke-step ::= {:name step-name
                 :type :invoke
                 :operation operation-id
                 :args arg-map
                 control-flow*}

session-step ::= {:name step-name
                  :type :session
                  session-config*
                  :contributions [contribution+]
                  yields?
                  control-flow*}

delegate-step ::= {:name step-name
                   :type :delegate
                   :target workflow-name
                   :prompt-string (string | template-contribution)
                   :context [source-item*]
                   yields?
                   control-flow*}

control-flow ::= :judge judge-spec
               | :on outcome-map
               | :max-iterations pos-int

judge-spec ::= llm-judge | invoke-judge

llm-judge ::= {:type :llm
               judge-session-config*
               :contributions [contribution+]}

invoke-judge ::= {:type :invoke
                  :operation operation-id
                  :args arg-map}

outcome-map ::= {outcome transition-map}+

transition-map ::= {:goto goto-target
                    :max-iterations? pos-int}

goto-target ::= :next | :previous | :done | step-name

contribution ::= source-contribution | template-contribution

source-contribution ::= {:type :source
                         :from source-ref
                         :path? path
                         :projection? projection}

template-contribution ::= {:type :template
                           :text string
                           :vars {var-name source-spec}*}

source-item ::= {:type :source
                 :from source-ref
                 :path? path
                 :projection? projection}

source-spec ::= {:from source-ref
                 :path? path
                 :projection? projection}

source-ref ::= :workflow-input
             | :workflow-original
             | {:step step-name :output output-key}

output-key ::= :data | :summary | :result | :final-llm-reply | :transcript

arg-map ::= {keyword (literal | source-spec)}*

session-config ::= :model model-selection-spec
                 | :tools [tool-id*]
                 | :skills [skill-id*]
                 | session-config-extension

judge-session-config ::= :model model-selection-spec
                       | :tools [tool-id*]
                       | :skills [skill-id*]
                       | judge-session-config-extension

model-selection-spec ::= external-nonterminal-defined-in-doc-model-selection-grammar

yields ::= {:type :data :data yield-source}
         | {:type :text :text yield-source}
         | {:type :error :reason keyword :message string :details? map}

yield-source ::= keyword

step-name ::= string
workflow-name ::= string
operation-id ::= string
tool-id ::= string
skill-id ::= string
var-name ::= keyword | symbol | string
outcome ::= string | keyword
path ::= vector
projection ::= map
literal ::= string | keyword | number | boolean | nil | vector | map
pos-int ::= integer
map ::= clojure-map
vector ::= clojure-vector
string ::= clojure-string
keyword ::= clojure-keyword
symbol ::= clojure-symbol
number ::= clojure-number
boolean ::= true | false
nil ::= nil
```
