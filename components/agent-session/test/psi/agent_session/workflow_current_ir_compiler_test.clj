(ns psi.agent-session.workflow-current-ir-compiler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-current-ir-compiler :as compiler]
   [psi.agent-session.workflow-ir :as workflow-ir]))

(def current-single-step-definition
  {:definition-id "planner"
   :name "planner"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}
                                      :original {:source :workflow-input :path [:original]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}
                     :capability-policy {:tools #{"read" "bash"}}}}})

(def current-judged-definition
  {:definition-id "plan-build-review"
   :name "plan-build-review"
   :step-order ["step-1-plan" "step-2-build" "step-3-review"]
   :steps {"step-1-plan" {:executor {:type :agent :profile "planner"}
                          :prompt-template "$INPUT"
                          :input-bindings {:input {:source :workflow-input :path [:input]}
                                           :original {:source :workflow-input :path [:original]}}
                          :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                          :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}
           "step-2-build" {:executor {:type :agent :profile "builder" :skill "clojure-coding-standards"}
                           :prompt-template "Execute: $INPUT\nOriginal: $ORIGINAL"
                           :input-bindings {:input {:source :step-output :path ["step-1-plan" :outputs :text]}
                                            :original {:source :workflow-input :path [:original]}}
                           :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                           :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}
                           :session-preload [{:kind :value
                                              :role "user"
                                              :binding {:source :workflow-input :path [:original]}}]
                           :session-overrides {:tools []
                                               :model "gpt-5"
                                               :thinking-level :high}}
           "step-3-review" {:executor {:type :agent :profile "reviewer"}
                            :prompt-template "Review: $INPUT"
                            :input-bindings {:input {:source :step-output
                                                     :path ["step-2-build" :diagnostics :summary]}}
                            :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                            :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}
                            :session-preload [{:kind :session-transcript
                                               :step-id "step-2-build"
                                               :projection {:type :tail :turns 2 :tool-output false}}
                                              {:kind :value
                                               :role "assistant"
                                               :binding {:source :step-output :path ["step-2-build" :blocked :reason]}}]
                            :judge {:prompt "APPROVED or REVISE?"
                                    :system-prompt "You are a routing judge."
                                    :projection {:type :tail :turns 1}}
                            :on {"APPROVED" {:goto :done}
                                 "REVISE" {:goto "step-2-build" :max-iterations 3}}}}})

(deftest compile-current-single-step-to-ir-test
  (testing "single current-authored step compiles to canonical IR session step"
    (let [ir (compiler/compile-workflow-definition current-single-step-definition)]
      (is (= {:version :workflow-ir/v1
              :steps [{:name "step-1"
                       :type :session
                       :session {:tools ["bash" "read"]
                                 :contributions [{:type :template
                                                  :text "{{input}}"
                                                  :vars {"input" {:from :workflow-input :path [:input]}
                                                         "original" {:from :workflow-original}}
                                                  :compat {:current-template-syntax :dollar-bindings
                                                           :current-prompt-template "$INPUT"}}]}
                       :outputs {:text {:source :session/final-llm-reply}
                                 :transcript {:source :session/transcript}
                                 :result {:source :session/result}}
                       :yields {:type :text :text :text}
                       :compat {:current-step-id "step-1"
                                :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                :executor {:type :agent :profile "planner"}
                                :capability-policy {:tools #{"read" "bash"}}}}]}
             ir))
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             (dissoc (workflow-ir/validate-workflow-ir ir) :ir :compile-error))))))

(deftest compile-current-multistep-compatibility-surfaces-test
  (testing "current authored preload, bindings, judge, routing, and compat envelope reads compile into normalized IR"
    (let [ir (compiler/compile-workflow-definition current-judged-definition)
          [_step-1 step-2 step-3] (:steps ir)]
      (is (= :workflow-ir/v1 (:version ir)))
      (is (= ["step-1-plan" "step-2-build" "step-3-review"]
             (mapv :name (:steps ir))))
      (is (= {:type :session
              :tools []
              :skills ["clojure-coding-standards"]
              :model "gpt-5"
              :thinking-level :high
              :contributions [{:type :source
                               :from :workflow-original
                               :compat {:current-preload {:kind :value
                                                          :role "user"}}}
                              {:type :template
                               :text "Execute: {{input}}\nOriginal: {{original}}"
                               :vars {"input" {:from {:step "step-1-plan" :output :text}}
                                      "original" {:from :workflow-original}}
                               :compat {:current-template-syntax :dollar-bindings
                                        :current-prompt-template "Execute: $INPUT\nOriginal: $ORIGINAL"}}]}
             (assoc (:session step-2) :type :session)))
      (is (= {:type :llm
              :session {:system-prompt "You are a routing judge."
                        :contributions [{:type :template
                                         :text "APPROVED or REVISE?"
                                         :vars {}}]}
              :projection {:type :tail :turns 1}}
             (:judge step-3)))
      (is (= {"APPROVED" {:goto :done}
              "REVISE" {:goto "step-2-build" :max-iterations 3}}
             (:on step-3)))
      (is (= [{:type :source
               :from {:step "step-2-build" :output :transcript}
               :projection {:type :tail :turns 2 :tool-output false}
               :compat {:current-preload {:kind :session-transcript
                                          :step-id "step-2-build"}}}
              {:type :source
               :from {:step "step-2-build" :output :result}
               :path [:blocked :reason]
               :compat {:current-preload {:kind :value
                                          :role "assistant"}
                        :current-binding-ref {:source :step-output
                                              :path ["step-2-build" :blocked :reason]
                                              :accepted-result-envelope true
                                              :surface :blocked}}}
              {:type :template
               :text "Review: {{input}}"
               :vars {"input" {:from {:step "step-2-build" :output :result}
                               :path [:diagnostics :summary]
                               :compat {:current-binding-ref {:source :step-output
                                                              :path ["step-2-build" :diagnostics :summary]
                                                              :accepted-result-envelope true
                                                              :surface :diagnostics}}}}
               :compat {:current-template-syntax :dollar-bindings
                        :current-prompt-template "Review: $INPUT"}}]
             (get-in step-3 [:session :contributions])))))

  (testing "compiled IR validates successfully"
    (let [{:keys [valid? ir structural-errors semantic-errors compile-error]}
          (compiler/compile-and-validate-workflow-definition current-judged-definition)]
      (is (true? valid?))
      (is (some? ir))
      (is (nil? structural-errors))
      (is (= [] semantic-errors))
      (is (nil? compile-error)))))

(deftest compile-current-workflow-runtime-binding-test
  (testing "current workflow-runtime bindings remain compile-time compatibility refs"
    (let [definition {:definition-id "runtime-proof"
                      :name "runtime-proof"
                      :step-order ["step-1"]
                      :steps {"step-1" {:executor {:type :agent :profile "planner"}
                                        :prompt-template "Status: $STATUS"
                                        :input-bindings {:status {:source :workflow-runtime
                                                                  :path [:status]}}
                                        :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                        :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}}
          ir (compiler/compile-workflow-definition definition)]
      (is (= {:from :workflow-runtime
              :path [:status]
              :compat {:current-binding-ref {:source :workflow-runtime
                                             :path [:status]}}}
             (get-in ir [:steps 0 :session :contributions 0 :vars "status"])))
      (let [{:keys [valid? structural-errors semantic-errors]}
            (workflow-ir/validate-workflow-ir ir)]
        (is (false? valid?))
        (is (some? structural-errors))
        (is (= [] semantic-errors))))))
