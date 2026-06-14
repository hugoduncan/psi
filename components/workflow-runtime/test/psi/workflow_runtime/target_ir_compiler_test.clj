(ns psi.workflow-runtime.target-ir-compiler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.target-ir-compiler :as target-compiler]))

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
            :session-profile :review
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
            :session-profile :coding
            :thinking-level :high
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

(def target-dynamic-delegate-definition
  {:steps [{:name "choose-workflow"
            :type :invoke
            :operation "demo/select-workflow"
            :args {}}
           {:name "run-selected-workflow"
            :type :delegate
            :target {:from {:step "choose-workflow" :output :data}
                     :path [:selected-workflow]}
            :prompt-string "Handle the issue using the selected workflow."
            :context [{:type :source
                       :from :workflow-original}]}]})

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
                                 :session-profile :review
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
                                  :session {:session-profile :coding
                                            :thinking-level :high}
                                  :context [{:type :source
                                             :from :workflow-original}
                                            {:type :source
                                             :from {:step "discover" :output :data}
                                             :path [:issues]}]}
                       :outputs {:handoff {:source :delegate/handoff}}
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
      (is (= {:handoff {:issue_number "12"}}
             (workflow-ir/step-output-surfaces
              delegate-step
              {:outcome :ok
               :outputs {:handoff {:issue_number "12"}}}))))))

(deftest compile-temperature-preserved-through-session-step-test
  (testing ":temperature is preserved through session step IR compilation"
    (let [ir (target-compiler/compile-workflow-definition
              {:steps [{:name "run"
                        :type :session
                        :temperature 0.3
                        :contributions [{:type :source :from :workflow-original}]}]})]
      (is (= 0.3 (get-in ir [:steps 0 :session :temperature])))))

  (testing ":temperature is preserved through judge session IR compilation"
    (let [ir (target-compiler/compile-workflow-definition
              {:steps [{:name "build"
                        :type :session
                        :contributions [{:type :source :from :workflow-original}]
                        :judge {:type :llm
                                :temperature 1.2
                                :contributions [{:type :template
                                                 :text "APPROVED or REVISE?"
                                                 :vars {}}]}
                        :on {"APPROVED" {:goto :done}}}]})]
      (is (= 1.2 (get-in ir [:steps 0 :judge :session :temperature])))))

  (testing "absent :temperature is absent from compiled session spec"
    (let [ir (target-compiler/compile-workflow-definition
              {:steps [{:name "run"
                        :type :session
                        :contributions [{:type :source :from :workflow-original}]}]})]
      (is (not (contains? (get-in ir [:steps 0 :session]) :temperature))))))

(deftest target-authored-workflow-definition?-alias-test
  (testing "agent-session compiler alias matches lower shared authored-shape predicate"
    (is (true? (target-compiler/target-authored-workflow-definition?
                {:steps [{:name "plan" :type :session}]})))
    (is (false? (target-compiler/target-authored-workflow-definition? {:steps {}})))))

(deftest compile-target-dynamic-delegate-workflow-test
  (testing "target authored delegate workflows compile dynamic target source-specs into canonical IR"
    (let [ir (target-compiler/compile-workflow-definition target-dynamic-delegate-definition)]
      (is (= {:version :workflow-ir/v1
              :steps [{:name "choose-workflow"
                       :type :invoke
                       :invoke {:operation "demo/select-workflow"
                                :args {}}
                       :outputs {:data {:source :invoke/data}
                                 :summary {:source :invoke/summary}
                                 :result {:source :invoke/result}}
                       :yields {:type :data :data :data}}
                      {:name "run-selected-workflow"
                       :type :delegate
                       :delegate {:target {:from {:step "choose-workflow" :output :data}
                                           :path [:selected-workflow]}
                                  :prompt-string "Handle the issue using the selected workflow."
                                  :context [{:type :source
                                             :from :workflow-original}]}
                       :outputs {:handoff {:source :delegate/handoff}}
                       :yields {:type :delegated}}]}
             ir)))))

(deftest compile-target-dynamic-delegate-invalid-target-shape-test
  (testing "delegate target authored shape fails clearly when not a workflow name string or source-spec"
    (let [{:keys [valid? compile-error]}
          (target-compiler/compile-and-validate-workflow-definition
           {:steps [{:name "run-selected-workflow"
                     :type :delegate
                     :target {:path [:selected-workflow]}
                     :prompt-string "Handle the issue using the selected workflow."}]})]
      (is (false? valid?))
      (is (= "Delegate target must be a workflow name string or workflow source-spec"
             (get-in compile-error [:message]))))))

(deftest compile-target-judge-routing-and-loop-bounds-test
  (testing "target authored judges, routing, and self-loop control edges compile into canonical IR"
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
      (is (= {:valid? true
              :structural-errors nil
              :semantic-errors []}
             (select-keys (workflow-ir/validate-workflow-ir ir)
                          [:valid? :structural-errors :semantic-errors])))
      (is (= {:valid? false
              :structural-errors nil
              :semantic-errors [{:type :non-prior-step-ref
                                 :step "build"
                                 :ref {:step "build" :output :final-llm-reply}}]}
             (select-keys (workflow-ir/validate-workflow-ir bad-ir)
                          [:valid? :structural-errors :semantic-errors])))))

  (testing "target-authored source specs reject non-canonical workflow-runtime refs at validation time"
    (let [{:keys [valid? structural-errors semantic-errors compile-error]}
          (target-compiler/compile-and-validate-workflow-definition
           {:steps [{:name "status"
                     :type :session
                     :contributions [{:type :template
                                      :text "Status: {{status}}"
                                      :vars {"status" {:from :workflow-runtime
                                                       :path [:status]}}}]}]})]
      (is (false? valid?))
      (is (some? structural-errors))
      (is (= [] semantic-errors))
      (is (nil? compile-error))))

  (testing "delegate target authored shape fails clearly when not a workflow name string or source-spec"
    (let [{:keys [valid? compile-error]}
          (target-compiler/compile-and-validate-workflow-definition
           {:steps [{:name "run-selected-workflow"
                     :type :delegate
                     :target {:path [:selected-workflow]}
                     :prompt-string "Handle the issue using the selected workflow."}]})]
      (is (false? valid?))
      (is (= "Delegate target must be a workflow name string or workflow source-spec"
             (get-in compile-error [:message]))))))

(deftest compile-judge-outputs-passthrough-test
  (testing "judge :outputs is passed through to compiled IR when present"
    (let [outputs {:routing-result
                   {:source :judge/structured-output
                    :mode :structured
                    :schema-id :psi.workflow/judge-routing-result
                    :schema-version 1
                    :schema [:enum "REPEAT" "DONE"]
                    :json-schema {:type "string" :enum ["REPEAT" "DONE"]}}}
          ir (target-compiler/compile-workflow-definition
              {:steps [{:name "review"
                        :type :session
                        :contributions [{:type :template
                                         :text "Review: {{input}}"
                                         :vars {"input" {:from :workflow-input :path [:input]}}}]
                        :judge {:type :llm
                                :contributions [{:type :template
                                                 :text "REPEAT or DONE?"
                                                 :vars {}}]
                                :outputs outputs}
                        :on {"REPEAT" {:goto "review" :max-iterations 6}
                             "DONE" {:goto :done}}}]})]
      (is (= outputs (get-in ir [:steps 0 :judge :outputs])))))

  (testing "judge without :outputs compiles without :outputs key"
    (let [ir (target-compiler/compile-workflow-definition
              {:steps [{:name "review"
                        :type :session
                        :contributions [{:type :template
                                         :text "Review: {{input}}"
                                         :vars {"input" {:from :workflow-input :path [:input]}}}]
                        :judge {:type :llm
                                :contributions [{:type :template
                                                 :text "REPEAT or DONE?"
                                                 :vars {}}]}
                        :on {"REPEAT" {:goto "review" :max-iterations 6}
                             "DONE" {:goto :done}}}]})]
      (is (not (contains? (get-in ir [:steps 0 :judge]) :outputs))))))

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
          [state definition-id _] (workflow-registry/register-definition state
                                                                         (assoc target-invoke-session-delegate-definition
                                                                                :definition-id "target-authored"))
          [_ _ run] (workflow-runtime/create-run state {:definition-id definition-id
                                                        :run-id "registered-target-run"
                                                        :workflow-input {:repo "org/repo"
                                                                         :labels ["bug"]}})]
      (is (= "target-authored" definition-id))
      (is (= "target-authored" (:source-definition-id run)))
      (is (= "target-authored" (get-in run [:effective-definition :definition-id]))))))

;; ── Task 226 Slice 2 — `:prompts` named-group compilation ───────────────────

(def target-multi-prompt-session-definition
  {:steps [{:name "design-review"
            :type :session
            :model "gpt-5.4"
            :tools ["read"]
            :prompts [{:name "architecture"
                       :contributions [{:type :template
                                        :text "Architecture review: {{input}}"
                                        :vars {"input" {:from :workflow-input :path [:input]}}}]}
                      {:name "ambiguity"
                       :contributions [{:type :template
                                        :text "Ambiguity review"
                                        :vars {}}]}]}]})

(deftest compile-target-multi-prompt-session-workflow-test
  (testing "authored :prompts named groups compile into a canonical :session :prompts queue"
    (let [ir (target-compiler/compile-workflow-definition
              target-multi-prompt-session-definition)
          session-step (first (:steps ir))]
      (is (= [{:name "architecture"
               :contributions [{:type :template
                                :text "Architecture review: {{input}}"
                                :vars {"input" {:from :workflow-input :path [:input]}}}]}
              {:name "ambiguity"
               :contributions [{:type :template
                                :text "Ambiguity review"
                                :vars {}}]}]
             (get-in session-step [:session :prompts])))
      (is (not (contains? (:session session-step) :contributions))
          "a :prompts step carries no step-level :contributions")
      (is (= "gpt-5.4" (get-in session-step [:session :model])))
      (is (= ["read"] (get-in session-step [:session :tools])))
      (is (= ["architecture" "ambiguity"]
             (mapv :name (workflow-ir/session-step-prompt-queue session-step))))))

  (testing "the compiled multi-prompt IR validates"
    (let [ir (target-compiler/compile-workflow-definition
              target-multi-prompt-session-definition)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             (dissoc (workflow-ir/validate-workflow-ir ir) :ir :compile-error))))))
