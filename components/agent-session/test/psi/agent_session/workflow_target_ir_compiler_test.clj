(ns psi.agent-session.workflow-target-ir-compiler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [psi.agent-session.workflow-current-ir-compiler :as current-compiler]
   [psi.agent-session.workflow-ir :as workflow-ir]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-target-ir-compiler :as target-compiler]))

(def target-invoke-session-delegate-definition
  {:steps [{:name "discover"
            :type :invoke
            :operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}
                   :state "open"}}
           {:name "report"
            :type :session
            :model "gpt-5.4"
            :tools ["read" "bash"]
            :skills ["issue-feature-triage"]
            :contributions [{:type :source
                             :from :workflow-original}
                            {:type :template
                             :text "Review these issues:\n\n{{issues}}"
                             :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}}}]}
           {:name "report-call"
            :type :delegate
            :target "builder"
            :prompt-string {:type :template
                            :text "Review these issues:\n\n{{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                             :path [:issues]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "discover" :output :data}
                       :path [:issues]}]}]})

(def target-judged-definition
  {:steps [{:name "build"
            :type :session
            :model "gpt-5.4"
            :contributions [{:type :template
                             :text "Build it: {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]
            :judge {:type :llm
                    :model "gpt-5.4"
                    :contributions [{:type :template
                                     :text "APPROVED or REVISE?"
                                     :vars {}}]
                    :projection {:type :tail :turns 1}}
            :on {"APPROVED" {:goto :done}
                 "REVISE" {:goto "build" :max-iterations 3}}
            :max-iterations 5}]})

(def current-single-step-definition
  {:definition-id "planner"
   :name "planner"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}
                     :capability-policy {:tools #{"read" "bash"}}}}})

(def target-single-step-equivalent-definition
  {:steps [{:name "step-1"
            :type :session
            :tools ["bash" "read"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]})

(def current-plan-build-definition
  {:definition-id "plan-build"
   :name "plan-build"
   :step-order ["plan" "build"]
   :steps {"plan" {:executor {:type :agent :profile "planner"}
                   :prompt-template "$INPUT"
                   :input-bindings {:input {:source :workflow-input :path [:input]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                   :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "build" {:executor {:type :agent :profile "builder"}
                    :prompt-template "Build: $INPUT"
                    :input-bindings {:input {:source :step-output :path ["plan" :outputs :final-llm-reply]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                    :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(def target-plan-build-equivalent-definition
  {:steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build: {{input}}"
                             :vars {"input" {:from {:step "plan" :output :result}
                                             :path [:outputs :final-llm-reply]}}}]}]})

(defn- strip-compat
  [x]
  (walk/postwalk (fn [form]
                   (if (map? form)
                     (dissoc form :compat)
                     form))
                 x))

(deftest compile-target-invoke-session-delegate-workflow-test
  (testing "target authored invoke/session/delegate workflows compile into canonical IR"
    (let [ir (target-compiler/compile-workflow-definition target-invoke-session-delegate-definition)]
      (is (= {:version :workflow-ir/v1
              :steps [{:name "discover"
                       :type :invoke
                       :invoke {:operation "github/search-issues-by-label"
                                :args {:repo {:from :workflow-input :path [:repo]}
                                       :labels {:from :workflow-input :path [:labels]}
                                       :state "open"}}
                       :outputs {:data {:source :invoke/data}
                                 :summary {:source :invoke/summary}
                                 :result {:source :invoke/result}}
                       :yields {:type :data :data :data}}
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
                       :yields {:type :text :text :final-llm-reply}}
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
                       :yields {:type :delegated}}]}
             ir))
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             (dissoc (workflow-ir/validate-workflow-ir ir) :ir :compile-error)))))

  (testing "normalized output surfaces resolve declared keys rather than storage aliases"
    (let [compiled-steps (:steps (target-compiler/compile-workflow-definition
                                  target-invoke-session-delegate-definition))
          invoke-step (nth compiled-steps 0)
          session-step (nth compiled-steps 1)
          delegate-step (nth compiled-steps 2)]
      (is (= {:data {:issues [1 2]}
              :summary "found two"
              :result {:outcome :ok
                       :outputs {:data {:issues [1 2]}
                                 :summary "found two"}}}
             (workflow-ir/step-output-surfaces
              invoke-step
              {:outcome :ok
               :outputs {:data {:issues [1 2]}
                         :summary "found two"}})))
      (is (= {:final-llm-reply "done"
              :transcript [{:role "assistant" :content "done"}]
              :result {:outcome :ok
                       :outputs {:text "done"
                                 :transcript [{:role "assistant" :content "done"}]}}}
             (workflow-ir/step-output-surfaces
              session-step
              {:outcome :ok
               :outputs {:text "done"
                         :transcript [{:role "assistant" :content "done"}]}})))
      (is (= {}
             (workflow-ir/step-output-surfaces
              delegate-step
              {:outcome :ok
               :outputs {:data {:ignored true}}}))))))

(deftest compile-target-judge-routing-and-loop-bounds-test
  (testing "target authored judges, routing, and loop bounds compile into canonical IR"
    (let [ir (target-compiler/compile-workflow-definition target-judged-definition)
          bad-ir {:version :workflow-ir/v1
                  :steps [{:name "build"
                           :type :session
                           :session {:contributions [{:type :template
                                                      :text "Self: {{x}}"
                                                      :vars {"x" {:from {:step "build" :output :final-llm-reply}}}}]}
                           :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                           :yields {:type :text :text :final-llm-reply}}]}]
      (is (= {:version :workflow-ir/v1
              :steps [{:name "build"
                       :type :session
                       :session {:model "gpt-5.4"
                                 :contributions [{:type :template
                                                  :text "Build it: {{input}}"
                                                  :vars {"input" {:from :workflow-input :path [:input]}}}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}
                                 :transcript {:source :session/transcript}
                                 :result {:source :session/result}}
                       :yields {:type :text :text :final-llm-reply}
                       :judge {:type :llm
                               :session {:model "gpt-5.4"
                                         :contributions [{:type :template
                                                          :text "APPROVED or REVISE?"
                                                          :vars {}}]}
                               :projection {:type :tail :turns 1}}
                       :on {"APPROVED" {:goto :done}
                            "REVISE" {:goto "build" :max-iterations 3}}
                       :max-iterations 5}]}
             ir))
      (is (= {:valid? false
              :structural-errors nil
              :semantic-errors [{:type :non-prior-step-ref
                                 :step "build"
                                 :ref {:step "build" :output :final-llm-reply}}]}
             (select-keys (workflow-ir/validate-workflow-ir bad-ir)
                          [:valid? :structural-errors :semantic-errors]))))))

(deftest cross-grammar-semantic-equivalence-test
  (testing "current-authored and target-authored overlapping forms normalize to equivalent canonical IR after compat stripping"
    (let [current-single-ir (current-compiler/compile-workflow-definition current-single-step-definition)
          target-single-ir (target-compiler/compile-workflow-definition target-single-step-equivalent-definition)
          current-plan-build-ir (current-compiler/compile-workflow-definition current-plan-build-definition)
          target-plan-build-ir (target-compiler/compile-workflow-definition target-plan-build-equivalent-definition)]
      (is (= (strip-compat current-single-ir)
             target-single-ir))
      (is (= (strip-compat current-plan-build-ir)
             target-plan-build-ir)))))

(deftest create-run-compiles-target-authored-definition-at-effective-definition-seam-test
  (testing "create-run compiles target-authored definitions at the effective-definition seam"
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}
          [_ run-id run] (workflow-runtime/create-run state {:definition target-invoke-session-delegate-definition
                                                             :run-id "target-run"
                                                             :workflow-input {:repo "org/repo"
                                                                              :labels ["bug"]}})]
      (is (= "target-run" run-id))
      (is (= :workflow-ir/v1 (get-in run [:effective-definition :canonical-ir :version])))
      (is (= ["discover" "report" "report-call"]
             (mapv :name (get-in run [:effective-definition :canonical-ir :steps]))))
      (is (nil? (get-in run [:effective-definition :definition-id]))))))

(deftest create-run-preserves-registered-target-definition-provenance-without-inline-definition-id-test
  (testing "registered target-authored runs keep source provenance while inline snapshots remain source-id free"
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}
          [state definition-id _] (workflow-runtime/register-definition state
                                                                        (assoc target-invoke-session-delegate-definition
                                                                               :definition-id "target-authored"))
          [_ _ run] (workflow-runtime/create-run state {:definition-id definition-id
                                                        :run-id "registered-target-run"
                                                        :workflow-input {:repo "org/repo"
                                                                         :labels ["bug"]}})]
      (is (= "target-authored" definition-id))
      (is (= "target-authored" (:source-definition-id run)))
      (is (= "target-authored" (get-in run [:effective-definition :definition-id]))))))
