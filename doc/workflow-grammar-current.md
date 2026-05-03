# Workflow Grammar — Current Implementation

This document describes the **currently implemented** workflow schema surface reflected by `components/agent-session/src/psi/agent_session/workflow_model.clj`.

It is intentionally separate from `doc/workflow-grammar.md`, which describes the **target converged authoring grammar** proposed in task `077-deterministic-workflow-steps`, and from `doc/workflow-grammar-concepts.md`, which explains that target design conceptually.

This document is a compact documentation grammar, not a complete executable parser specification.

Optionality notation such as `field?` in this document marks an optional field in the documentation grammar. It does **not** mean the literal EDN key includes a trailing `?`.

```clojure
workflow-definition ::= {:definition-id? workflow-definition-id
                         :name? string
                         :summary? string
                         :description? string
                         :step-order [step-id+]
                         :steps {step-id workflow-step-definition}+}

workflow-step-definition ::= {:label? string
                              :description? string
                              :executor workflow-executor
                              :prompt-template? string
                              :input-bindings? {keyword workflow-binding-ref}
                              :result-schema any
                              :retry-policy workflow-retry-policy
                              :capability-policy? workflow-capability-policy
                              :judge? judge-spec
                              :on? routing-table
                              :session-preload? [session-preload-item*]
                              :session-overrides? session-overrides}

workflow-executor ::= {:type :agent
                       :profile? string
                       :mode? (:sync | :async)
                       :skill? string}

workflow-binding-ref ::= {:source (:workflow-input | :step-output | :workflow-runtime)
                          :path [path-segment*]}

workflow-retry-policy ::= {:max-attempts pos-int
                           :retry-on #{retryable-failure+}}

retryable-failure ::= :execution-failed | :validation-failed

workflow-capability-policy ::= {:tools? #{string*}}

judge-spec ::= {:prompt string
                :system-prompt? string
                :projection? projection}

projection ::= :none
             | :full
             | {:type :tail
                :turns pos-int
                :tool-output? boolean}

routing-table ::= {judge-signal routing-directive}+

judge-signal ::= string

routing-directive ::= {:goto goto-target
                       :max-iterations? pos-int}

goto-target ::= :next | :previous | :done | step-id

session-preload-item ::= preload-value | preload-session-transcript

preload-value ::= {:kind :value
                   :role string
                   :binding workflow-binding-ref}

preload-session-transcript ::= {:kind :session-transcript
                                :step-id step-id
                                :projection? projection}

session-overrides ::= {:system-prompt? string
                       :tools? [string*]
                       :skills? [string*]
                       :model? (string | map)
                       :thinking-level? (:off | :minimal | :low | :medium | :high | :xhigh)
                       :prompt-component-selection? prompt-component-selection-schema}

workflow-run ::= {:run-id workflow-run-id
                  :status workflow-run-status
                  :effective-definition workflow-definition
                  :source-definition-id? workflow-definition-id
                  :workflow-input? map
                  :current-step-id? step-id
                  :step-runs {step-id workflow-step-run}*
                  :history [workflow-history-entry*]
                  :blocked? map
                  :terminal-outcome? map
                  :created-at instant
                  :updated-at instant
                  :finished-at? instant}

workflow-step-run ::= {:step-id step-id
                       :attempts [workflow-step-attempt*]
                       :accepted-result? workflow-result-envelope
                       :iteration-count? int}

workflow-step-attempt ::= {:attempt-id workflow-attempt-id
                           :status workflow-step-attempt-status
                           :execution-session-id? string
                           :result-envelope? workflow-result-envelope
                           :validation-outcome? workflow-validation-outcome
                           :execution-error? map
                           :blocked? map
                           :judge-session-id? string
                           :judge-output? string
                           :judge-event? string
                           :created-at instant
                           :updated-at instant
                           :finished-at? instant}

workflow-result-envelope ::= ok-envelope | blocked-envelope

ok-envelope ::= {:outcome :ok
                 :outputs map
                 :diagnostics? map}

blocked-envelope ::= {:outcome :blocked
                      :blocked map
                      :diagnostics? map}

workflow-validation-outcome ::= {:accepted? boolean
                                 :errors? [map*]}

workflow-history-entry ::= {:event keyword
                            :timestamp instant
                            :data? map}

workflow-run-status ::= :pending | :running | :blocked | :completed | :failed | :cancelled

workflow-step-attempt-status ::= :pending
                               | :running
                               | :validating
                               | :succeeded
                               | :blocked
                               | :validation-failed
                               | :execution-failed
                               | :cancelled

workflow-definition-id ::= string
workflow-run-id ::= string
workflow-attempt-id ::= string
step-id ::= string
path-segment ::= keyword | string | int
pos-int ::= positive-integer
instant ::= inst
```

## Notes

- The current implementation models workflow steps through a single `workflow-step-definition` shape with a required `:executor`.
- The only currently modelled executor type in `workflow_model.clj` is `{:type :agent ...}`.
- `:input-bindings` is an optional map from keyword binding names to `workflow-binding-ref` values.
- A current `workflow-binding-ref` always has both `:source` and `:path`; the path is a vector of keywords, strings, and/or ints.
- The current binding model uses `{:source ... :path ...}` references rather than the newer target-design `{:step ... :output ...}` / `{:step ... :yield ...}` references.
- The current judge model is prompt/projection-based and does not yet use the target-design `:judge {:type :llm ...}` / `:judge {:type :invoke ...}` split.
- Routing-table keys are currently strings.
- The current model supports optional transition-local `:max-iterations` on routing directives.
- `:workflow-input` on a run is currently optional and map-shaped in `workflow_model.clj`.
- The current model uses `:session-preload` and `:session-overrides` rather than the newer target-design `:contributions` and explicit `:delegate` boundary model.
- For the target converged authoring grammar, see `doc/workflow-grammar.md`. For the conceptual explanation of that target design, see `doc/workflow-grammar-concepts.md`.
