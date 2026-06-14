(ns psi.workflow-runtime.ir-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [psi.workflow-runtime.ir :as workflow-ir]))

(def valid-invoke-step
  {:name "discover"
   :type :invoke
   :invoke {:operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}
                   :state "open"}}
   :outputs {:data {:source :invoke/data}
             :summary {:source :invoke/summary}
             :result {:source :invoke/result}}
   :yields {:type :data :data :data}})

(def valid-session-step
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
             :transcript {:source :session/transcript}}
   :yields {:type :text :text :final-llm-reply}})

(def valid-delegate-step
  {:name "report-call"
   :type :delegate
   :delegate {:target "builder"
              :prompt-string {:type :template
                              :text "Review these issues:\n\n{{issues}}"
                              :vars {"issues" {:from {:step "discover" :output :data}
                                               :path [:issues]}}}
              :session {:session-profile :coding
                        :model "gpt-5.5"
                        :thinking-level :high}
              :context [{:type :source
                         :from :workflow-original}
                        {:type :source
                         :from {:step "discover" :output :data}
                         :path [:issues]}]}
   :yields {:type :delegated}})

(def valid-workflow-ir
  {:version :workflow-ir/v1
   :steps [valid-invoke-step
           (assoc valid-session-step
                  :judge {:type :llm
                          :session {:model "gpt-5.4"
                                    :contributions [{:type :template
                                                     :text "APPROVED or REVISE?"
                                                     :vars {}}]}
                          :projection {:type :tail :turns 4 :tool-output false}}
                  :on {"APPROVED" {:goto :done}
                       "REVISE" {:goto "discover" :max-iterations 3}}
                  :compat {:source-file "legacy.md"})
           valid-delegate-step]})

(deftest workflow-ir-structural-schema-test
  (testing "top-level normalized workflow IR schema accepts representative IR"
    (is (m/validate workflow-ir/workflow-ir-schema valid-workflow-ir))
    (is (workflow-ir/valid-workflow-ir? valid-workflow-ir)))

  (testing "tagged step schemas accept invoke/session/delegate forms"
    (is (m/validate workflow-ir/invoke-step-schema valid-invoke-step))
    (is (m/validate workflow-ir/session-step-schema valid-session-step))
    (is (m/validate workflow-ir/delegate-step-schema valid-delegate-step)))

  (testing "shared source-ref and source-spec schemas accept canonical forms"
    (is (m/validate workflow-ir/source-ref-schema :workflow-input))
    (is (m/validate workflow-ir/source-ref-schema :workflow-original))
    (is (m/validate workflow-ir/source-ref-schema {:step "discover" :output :data}))
    (is (m/validate workflow-ir/source-ref-schema {:step "discover" :yield :data}))
    (is (m/validate workflow-ir/source-ref-schema {:step "discover" :yield :custom-field}))
    (is (m/validate workflow-ir/source-spec-schema {:from :workflow-input :path [:repo]}))
    (is (m/validate workflow-ir/source-spec-schema {:from {:step "discover" :output :data}
                                                    :projection {:type :tail :turns 1}}))
    (is (m/validate workflow-ir/delegate-target-schema "builder"))
    (is (m/validate workflow-ir/delegate-target-schema {:from {:step "discover" :output :data}
                                                        :path [:selected-workflow]})))

  (testing "delegate-prompt-string-schema accepts :map type with :fields"
    (is (m/validate workflow-ir/delegate-prompt-string-schema
                    {:type :map
                     :fields {:issue_number {:from {:step "discover" :output :data}
                                             :path [:issue-number]}
                              :report       {:from {:step "reproduce" :yield :text}}}}))
    (is (not (m/validate workflow-ir/delegate-prompt-string-schema
                         {:type :map})))
    (is (not (m/validate workflow-ir/delegate-prompt-string-schema
                         {:type :map
                          :fields "not-a-map"}))))

  (testing "delegate session schema accepts only inherited-default shaping keys"
    (is (m/validate workflow-ir/delegate-session-spec-schema
                    {:session-profile :coding
                     :model "gpt-5.5"
                     :thinking-level :high}))
    (is (not (m/validate workflow-ir/delegate-session-spec-schema
                         {:session-profile "coding"})))
    (is (not (m/validate workflow-ir/delegate-session-spec-schema
                         {:session-profile :coding
                          :speed-mode :fast}))))

  (testing "contribution schemas accept source and template variants"
    (is (m/validate workflow-ir/source-contribution-schema
                    {:type :source :from :workflow-original}))
    (is (m/validate workflow-ir/template-contribution-schema
                    {:type :template
                     :text "Hello {{name}}"
                     :vars {"name" {:from :workflow-input :path [:name]}}})))

  (testing "yield schemas accept data/text/error/delegated variants"
    (is (m/validate workflow-ir/yields-schema {:type :data :data :data}))
    (is (m/validate workflow-ir/yields-schema {:type :text :text :final-llm-reply}))
    (is (m/validate workflow-ir/yields-schema {:type :error :reason :invalid :message "nope"}))
    (is (m/validate workflow-ir/yields-schema {:type :delegated})))

  (testing "judge schemas accept llm and invoke variants"
    (is (m/validate workflow-ir/judge-schema
                    {:type :llm
                     :session {:contributions [{:type :template :text "APPROVED" :vars {}}]}
                     :projection :full}))
    (is (m/validate workflow-ir/judge-schema
                    {:type :invoke
                     :invoke {:operation "workflow/classify-result"
                              :args {:result {:from {:step "discover" :output :data}}}}})))

  (testing "routing and compat schemas accept canonical forms"
    (is (m/validate workflow-ir/routing-directive-schema {:goto :done}))
    (is (m/validate workflow-ir/routing-directive-schema {:goto "build" :max-iterations 2}))
    (is (m/validate workflow-ir/routing-table-schema {"APPROVED" {:goto :done}}))
    (is (m/validate workflow-ir/compat-schema {:legacy-source :session-preload}))))

(deftest workflow-ir-invalid-structural-shape-test
  (testing "source-spec rejects both path and projection"
    (is (not (m/validate workflow-ir/source-spec-schema
                         {:from :workflow-input
                          :path [:repo]
                          :projection {:type :tail :turns 1}}))))

  (testing "step rejects mismatched execution payload"
    (is (not (m/validate workflow-ir/ir-step-schema
                         {:name "bad"
                          :type :invoke
                          :session {:contributions []}}))))

  (testing "delegate target rejects authored map shapes that are not source-specs"
    (is (not (m/validate workflow-ir/delegate-target-schema
                         {:path [:selected-workflow]}))))

  (testing "judge rejects unsupported tag"
    (is (not (m/validate workflow-ir/judge-schema
                         {:type :other
                          :session {:contributions []}}))))

  (testing "routing directive rejects missing goto"
    (is (not (m/validate workflow-ir/routing-directive-schema {:max-iterations 3}))))

  (testing "yield rejects malformed shape"
    (is (not (m/validate workflow-ir/yields-schema {:type :text :data :data}))))

  (testing "workflow IR rejects missing version"
    (is (not (m/validate workflow-ir/workflow-ir-schema
                         {:steps [valid-invoke-step]}))))

  (testing "workflow IR rejects empty steps vector"
    (is (not (m/validate workflow-ir/workflow-ir-schema
                         {:version :workflow-ir/v1
                          :steps []})))))

(deftest workflow-ir-semantic-validation-boundary-test
  (testing "semantic validation allows representative valid workflow IR"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (workflow-ir/validate-workflow-ir valid-workflow-ir))))

  (testing "normalized IR requires compiler-materialized yields defaults"
    (let [ir {:version :workflow-ir/v1
              :steps [(dissoc valid-invoke-step :yields)]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-yields
               :step "discover"}]
             (:semantic-errors result)))))

  (testing "normalized IR requires local yields output keys to be declared in local outputs"
    (let [local-session-step {:name "report"
                              :type :session
                              :session {:contributions [{:type :source
                                                         :from :workflow-original}]}
                              :outputs {:transcript {:source :session/transcript}}
                              :yields {:type :text :text :final-llm-reply}}
          ir {:version :workflow-ir/v1
              :steps [local-session-step]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-local-yield-output-key
               :step "report"
               :output-key :final-llm-reply
               :available-outputs [:transcript]}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects on without judge"
    (let [ir (assoc valid-workflow-ir
                    :steps [(assoc valid-invoke-step
                                   :on {"OK" {:goto :done}})])
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :routing-without-judge
               :step "discover"}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects judge without routing"
    (let [ir {:version :workflow-ir/v1
              :steps [{:name "report"
                       :type :session
                       :session {:contributions [{:type :source
                                                  :from :workflow-original}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                       :yields {:type :text :text :final-llm-reply}
                       :judge {:type :llm
                               :session {:contributions [{:type :template
                                                          :text "APPROVED"
                                                          :vars {}}]}}}]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :judge-without-routing
               :step "report"}]
             (:semantic-errors result)))))

  (testing "semantic validation allows self-loop control edges while keeping data refs prior-only"
    (let [self-loop-step {:name "build"
                          :type :session
                          :session {:contributions [{:type :template
                                                     :text "Build {{input}}"
                                                     :vars {"input" {:from :workflow-input :path [:input]}}}]}
                          :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                          :yields {:type :text :text :final-llm-reply}
                          :judge {:type :llm
                                  :session {:contributions [{:type :template
                                                             :text "REPEAT or DONE"
                                                             :vars {}}]}}
                          :on {"REPEAT" {:goto "build" :max-iterations 3}
                               "DONE" {:goto :done}}}
          ir {:version :workflow-ir/v1
              :steps [self-loop-step]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result))))

  (testing "semantic validation rejects non-prior-step refs"
    (let [future-session-step (assoc valid-session-step
                                     :session {:contributions [{:type :template
                                                                :text "{{later}}"
                                                                :vars {"later" {:from {:step "report-call" :output :data}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step future-session-step valid-delegate-step]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= :non-prior-step-ref (-> result :semantic-errors first :type)))))

  (testing "semantic validation rejects self/future refs across delegate target and prompt-map surfaces"
    (let [self-target-step {:name "self-target"
                            :type :delegate
                            :delegate {:target {:from {:step "self-target" :output :data}
                                                :path [:workflow]}
                                       :prompt-string {:type :map
                                                       :fields {:report {:from {:step "discover" :output :data}}}}
                                       :context [{:type :source
                                                  :from :workflow-original}]}
                            :outputs {:data {:source :delegate/data}}
                            :yields {:type :delegated}}
          future-prompt-map-step {:name "map-future"
                                  :type :delegate
                                  :delegate {:target "builder"
                                             :prompt-string {:type :map
                                                             :fields {:report {:from {:step "later" :output :data}}}}
                                             :context [{:type :source
                                                        :from :workflow-original}]}
                                  :yields {:type :delegated}}
          later-step (assoc valid-session-step
                            :name "later"
                            :session {:contributions [{:type :source
                                                       :from :workflow-original}]})
          self-result (workflow-ir/validate-workflow-ir
                       {:version :workflow-ir/v1
                        :steps [valid-invoke-step self-target-step]})
          future-result (workflow-ir/validate-workflow-ir
                         {:version :workflow-ir/v1
                          :steps [valid-invoke-step future-prompt-map-step later-step]})]
      (is (false? (:valid? self-result)))
      (is (= :non-prior-step-ref (-> self-result :semantic-errors first :type)))
      (is (false? (:valid? future-result)))
      (is (= :non-prior-step-ref (-> future-result :semantic-errors first :type)))))

  (testing "semantic validation rejects self/future refs across delegate context and judge-owned source surfaces"
    (let [self-context-step {:name "self-context"
                             :type :delegate
                             :delegate {:target "builder"
                                        :prompt-string {:type :template
                                                        :text "Prompt"
                                                        :vars {}}
                                        :context [{:type :source
                                                   :from {:step "self-context" :yield :text}}]}
                             :yields {:type :delegated}}
          same-step-judge-output-step {:name "judge-invoke-same-step-output"
                                       :type :session
                                       :session {:contributions [{:type :source
                                                                  :from :workflow-original}]}
                                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                       :yields {:type :text :text :final-llm-reply}
                                       :judge {:type :invoke
                                               :invoke {:operation "workflow/classify-result"
                                                        :args {:result {:from {:step "judge-invoke-same-step-output"
                                                                               :output :final-llm-reply}}}}}
                                       :on {"DONE" {:goto :done}}}
          self-judge-yield-step {:name "judge-invoke-self-yield"
                                 :type :session
                                 :session {:contributions [{:type :source
                                                            :from :workflow-original}]}
                                 :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                 :yields {:type :text :text :final-llm-reply}
                                 :judge {:type :invoke
                                         :invoke {:operation "workflow/classify-result"
                                                  :args {:result {:from {:step "judge-invoke-self-yield"
                                                                         :yield :text}}}}}
                                 :on {"DONE" {:goto :done}}}
          future-judge-llm-step {:name "judge-llm-future"
                                 :type :session
                                 :session {:contributions [{:type :source
                                                            :from :workflow-original}]}
                                 :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                 :yields {:type :text :text :final-llm-reply}
                                 :judge {:type :llm
                                         :session {:contributions [{:type :template
                                                                    :text "{{later}}"
                                                                    :vars {"later" {:from {:step "later" :output :data}}}}]}}
                                 :on {"DONE" {:goto :done}}}
          future-judge-invoke-step {:name "judge-invoke-future"
                                    :type :session
                                    :session {:contributions [{:type :source
                                                               :from :workflow-original}]}
                                    :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                    :yields {:type :text :text :final-llm-reply}
                                    :judge {:type :invoke
                                            :invoke {:operation "workflow/classify-result"
                                                     :args {:result {:from {:step "later" :output :data}}}}}
                                    :on {"DONE" {:goto :done}}}
          later-step (assoc valid-invoke-step :name "later")
          self-result (workflow-ir/validate-workflow-ir
                       {:version :workflow-ir/v1
                        :steps [self-context-step]})
          same-step-result (workflow-ir/validate-workflow-ir
                            {:version :workflow-ir/v1
                             :steps [same-step-judge-output-step]})
          self-yield-result (workflow-ir/validate-workflow-ir
                             {:version :workflow-ir/v1
                              :steps [self-judge-yield-step]})
          llm-result (workflow-ir/validate-workflow-ir
                      {:version :workflow-ir/v1
                       :steps [future-judge-llm-step later-step]})
          invoke-result (workflow-ir/validate-workflow-ir
                         {:version :workflow-ir/v1
                          :steps [future-judge-invoke-step later-step]})]
      (is (false? (:valid? self-result)))
      (is (= :non-prior-step-ref (-> self-result :semantic-errors first :type)))
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             same-step-result))
      (is (false? (:valid? self-yield-result)))
      (is (= :non-prior-step-ref (-> self-yield-result :semantic-errors first :type)))
      (is (false? (:valid? llm-result)))
      (is (= :non-prior-step-ref (-> llm-result :semantic-errors first :type)))
      (is (false? (:valid? invoke-result)))
      (is (= :non-prior-step-ref (-> invoke-result :semantic-errors first :type)))))

  (testing "semantic validation rejects refs to undeclared output keys"
    (let [bad-session-step (assoc valid-session-step
                                  :session {:contributions [{:type :template
                                                             :text "{{missing}}"
                                                             :vars {"missing" {:from {:step "discover" :output :missing}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step bad-session-step]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= :missing-output-key (-> result :semantic-errors first :type)))))

  (testing "semantic validation rejects delegate output refs because delegates expose yielded text rather than local outputs"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{missing}}"
                                                   :vars {"missing" {:from {:step "report-call" :output :data}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step valid-delegate-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-output-key
               :step "report"
               :ref {:step "report-call" :output :data}
               :available-outputs []}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects delegated handoff refs when the delegate step does not declare handoff output"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{handoff}}"
                                                   :vars {"handoff" {:from {:step "report-call" :output :handoff}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step valid-delegate-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-output-key
               :step "report"
               :ref {:step "report-call" :output :handoff}
               :available-outputs []}]
             (:semantic-errors result)))))

  (testing "semantic validation accepts delegate yielded text refs as the minimal canonical downstream surface"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{report}}"
                                                   :vars {"report" {:from {:step "report-call" :yield :text}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step valid-delegate-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result))))

  (testing "yield refs to undeclared delegated fields remain invalid"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{missing}}"
                                                   :vars {"missing" {:from {:step "report-call" :yield :data}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step valid-delegate-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-yield-field
               :step "report"
               :ref {:step "report-call" :yield :data}
               :available-yield-fields [:text]}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects refs to undeclared yield fields"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{missing}}"
                                                   :vars {"missing" {:from {:step "discover" :yield :text}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= :missing-yield-field (-> result :semantic-errors first :type)))))

  (testing "yield refs allow keyword-shaped fields structurally but validate against the referenced yield form semantically"
    (let [step-2 (assoc valid-session-step
                        :session {:contributions [{:type :template
                                                   :text "{{missing}}"
                                                   :vars {"missing" {:from {:step "discover" :yield :custom-field}}}}]})
          ir {:version :workflow-ir/v1
              :steps [valid-invoke-step step-2]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :missing-yield-field
               :step "report"
               :ref {:step "discover" :yield :custom-field}
               :available-yield-fields [:data]}]
             (:semantic-errors result)))))

  (testing "compat source-ref-shaped breadcrumbs do not participate in semantic validation"
    (let [ir {:version :workflow-ir/v1
              :steps [(assoc valid-invoke-step
                             :compat {:breadcrumb {:step "missing" :output :data}})
                      valid-session-step]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result))))

  (testing "semantic validation rejects multiple structured outputs on one session step"
    (let [ir {:version :workflow-ir/v1
              :steps [{:name "classify"
                       :type :session
                       :session {:contributions [{:type :template
                                                  :text "classify"
                                                  :vars {}}]}
                       :outputs {:classification {:source :session/structured-output
                                                  :mode :structured
                                                  :schema-id :psi.workflow/test-classification
                                                  :schema-version 1
                                                  :schema [:map [:decision [:enum :pass :fail]]]}
                                 :diagnostics {:source :session/structured-output
                                               :mode :structured
                                               :schema-id :psi.workflow/test-diagnostics
                                               :schema-version 1
                                               :schema [:map [:summary :string]]}}
                       :yields {:type :data :data :classification}}]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :multiple-structured-outputs
               :step "classify"
               :scope :step
               :output-keys [:classification :diagnostics]}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects multiple structured outputs on one LLM judge"
    (let [prior-step valid-invoke-step
          judge-output {:source :judge/structured-output
                        :mode :structured
                        :schema-id :psi.workflow/judge-review-result
                        :schema-version 1
                        :schema [:map
                                 [:decision [:enum :clear :needs-work :unclear]]
                                 [:issues [:vector [:map
                                                    [:severity [:enum :blocking :minor]]
                                                    [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
                                                    [:description :string]
                                                    [:evidence :string]
                                                    [:suggested-change :string]]]]
                                 [:confidence [:double {:min 0.0 :max 1.0}]]]}
          ir {:version :workflow-ir/v1
              :steps [prior-step
                      (assoc valid-session-step
                             :judge {:type :llm
                                     :session {:contributions [{:type :template
                                                                :text "judge"
                                                                :vars {}}]}
                                     :outputs {:review judge-output
                                               :review-extra judge-output}}
                             :on {:clear {:goto :done}})]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :multiple-structured-outputs
               :step "report"
               :scope :judge
               :output-keys [:review :review-extra]}]
             (:semantic-errors result)))))

  (testing "semantic validation rejects mismatched reusable structured-output schema declarations"
    (let [ir {:version :workflow-ir/v1
              :steps [valid-invoke-step
                      (assoc valid-session-step
                             :judge {:type :llm
                                     :session {:contributions [{:type :template
                                                                :text "judge"
                                                                :vars {}}]}
                                     :outputs {:review {:source :judge/structured-output
                                                        :mode :structured
                                                        :schema-id :psi.workflow/judge-review-result
                                                        :schema-version 1
                                                        :schema [:map [:decision [:enum :clear :needs-work]]]}}}
                             :on {:clear {:goto :done}})]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :reusable-structured-output-schema-mismatch
               :step "report"
               :scope :judge
               :output-key :review
               :schema-id :psi.workflow/judge-review-result
               :schema-version 1}]
             (:semantic-errors result)))))

  (testing "structural failures stop before semantic validation"
    (let [result (workflow-ir/validate-workflow-ir {:steps []})]
      (is (false? (:valid? result)))
      (is (some? (:structural-errors result)))
      (is (= [] (:semantic-errors result)))))

  (testing "semantic validation rejects session steps with skills but no read tool"
    (let [ir {:version :workflow-ir/v1
              :steps [{:name "run"
                       :type :session
                       :session {:tools []
                                 :skills ["lambda-compiler"]
                                 :contributions [{:type :template
                                                  :text "hello"
                                                  :vars {}}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                       :yields {:type :text :text :final-llm-reply}}]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (false? (:valid? result)))
      (is (= [{:type :skills-without-read-tool
               :step "run"
               :skills ["lambda-compiler"]}]
             (:semantic-errors result)))))

  (testing "semantic validation allows session steps with skills when read tool is present"
    (let [ir {:version :workflow-ir/v1
              :steps [{:name "run"
                       :type :session
                       :session {:tools ["read"]
                                 :skills ["lambda-compiler"]
                                 :contributions [{:type :template
                                                  :text "hello"
                                                  :vars {}}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                       :yields {:type :text :text :final-llm-reply}}]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result))))

  (testing "semantic validation allows session steps with no skills and no read tool"
    (let [ir {:version :workflow-ir/v1
              :steps [{:name "run"
                       :type :session
                       :session {:tools []
                                 :contributions [{:type :template
                                                  :text "hello"
                                                  :vars {}}]}
                       :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                       :yields {:type :text :text :final-llm-reply}}]}
          result (workflow-ir/validate-workflow-ir ir)]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result)))))

;; ── Temperature schema validation ──────────────────────────────────────────

(def ^:private base-session-spec
  {:contributions [{:type :template :text "hello" :vars {}}]})

(deftest session-spec-schema-temperature-validation-test
  (testing "session-spec-schema accepts temperature within [0.0, 2.0]"
    (is (m/validate workflow-ir/session-spec-schema (assoc base-session-spec :temperature 0.0)))
    (is (m/validate workflow-ir/session-spec-schema (assoc base-session-spec :temperature 1.0)))
    (is (m/validate workflow-ir/session-spec-schema (assoc base-session-spec :temperature 2.0))))

  (testing "session-spec-schema accepts nil temperature (opt-in; absent = provider default)"
    (is (m/validate workflow-ir/session-spec-schema (assoc base-session-spec :temperature nil))))

  (testing "session-spec-schema accepts spec with no :temperature key"
    (is (m/validate workflow-ir/session-spec-schema base-session-spec)))

  (testing "session-spec-schema rejects temperature below 0.0"
    (is (not (m/validate workflow-ir/session-spec-schema
                         (assoc base-session-spec :temperature -0.1)))))

  (testing "session-spec-schema rejects temperature above 2.0"
    (is (not (m/validate workflow-ir/session-spec-schema
                         (assoc base-session-spec :temperature 2.1))))))

(deftest session-step-prompt-queue-derivation-test
  ;; Task 226 Slice 1 — the normalized internal prompt-queue derivation.
  ;; Single-prompt `:contributions` session steps are the N=1 degenerate: one
  ;; UNNAMED group carrying the step's contributions and no `:name`.
  (testing "single-prompt :contributions session step derives one unnamed group"
    (let [queue (workflow-ir/session-step-prompt-queue valid-session-step)]
      (is (= 1 (count queue)))
      (is (not (contains? (first queue) :name))
          "the N=1 degenerate group is unnamed (step-level surfaces only)")
      (is (= (get-in valid-session-step [:session :contributions])
             (:contributions (first queue))))
      (is (workflow-ir/valid-prompt-queue? queue))))

  (testing "a session step with no contributions derives one empty unnamed group"
    (let [queue (workflow-ir/session-step-prompt-queue
                 {:name "empty" :type :session :session {:contributions []}})]
      (is (= [{:contributions []}] queue))
      (is (workflow-ir/valid-prompt-queue? queue))))

  (testing "authored named :prompts groups derive verbatim, preserving author order"
    (let [groups [{:name "architecture"
                   :contributions [{:type :template :text "arch" :vars {}}]}
                  {:name "ambiguity"
                   :contributions [{:type :template :text "ambig" :vars {}}]}]
          queue (workflow-ir/session-step-prompt-queue
                 {:name "review" :type :session :session {:prompts groups}})]
      (is (= groups queue))
      (is (= ["architecture" "ambiguity"] (mapv :name queue)))
      (is (workflow-ir/valid-prompt-queue? queue)))))

(deftest prompt-queue-schema-test
  (testing "prompt-queue-schema requires at least one group"
    (is (not (workflow-ir/valid-prompt-queue? [])))
    (is (workflow-ir/valid-prompt-queue?
         [{:contributions [{:type :template :text "x" :vars {}}]}])))

  (testing "prompt-group :name is optional but must be a string when present"
    (is (workflow-ir/valid-prompt-queue?
         [{:name "g1" :contributions []}]))
    (is (not (workflow-ir/valid-prompt-queue?
              [{:name 7 :contributions []}])))))

;; ── Task 226 Slice 2 — `:prompts` grammar + IR normalization ────────────────

(def ^:private named-prompt-group-a
  {:name "architecture"
   :contributions [{:type :template :text "arch review" :vars {}}]})

(def ^:private named-prompt-group-b
  {:name "ambiguity"
   :contributions [{:type :template :text "ambiguity review" :vars {}}]})

(defn- multi-prompt-session-step
  [prompts]
  {:name "design-review"
   :type :session
   :session {:tools ["read"]
             :prompts prompts}
   :outputs {:final-llm-reply {:source :session/final-llm-reply}
             :transcript {:source :session/transcript}}
   :yields {:type :text :text :final-llm-reply}})

(defn- session-step-semantic-result
  [step]
  (workflow-ir/validate-workflow-ir {:version :workflow-ir/v1 :steps [step]}))

(deftest session-prompts-grammar-validation-test
  (testing "a multi-prompt session step with named groups is valid"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (session-step-semantic-result
            (multi-prompt-session-step [named-prompt-group-a named-prompt-group-b])))))

  (testing "a one-element :prompts queue is valid (AC-1 N>=1)"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (session-step-semantic-result
            (multi-prompt-session-step [named-prompt-group-a])))))

  (testing "an empty :prompts queue is rejected structurally"
    (let [result (session-step-semantic-result (multi-prompt-session-step []))]
      (is (false? (:valid? result)))
      (is (some? (:structural-errors result)))))

  (testing "step-level :contributions and :prompts together is a semantic error"
    (let [step (assoc-in (multi-prompt-session-step [named-prompt-group-a])
                         [:session :contributions] [])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :session-contributions-and-prompts
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "a session step with neither :contributions nor :prompts is a semantic error"
    (let [step {:name "design-review"
                :type :session
                :session {:tools ["read"]}
                :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                :yields {:type :text :text :final-llm-reply}}
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :session-without-prompt-source
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "duplicate prompt-group names within a step are a semantic error"
    (let [step (multi-prompt-session-step
                [named-prompt-group-a
                 (assoc named-prompt-group-b :name "architecture")])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :duplicate-prompt-group-name
               :step "design-review"
               :duplicate-names ["architecture"]}]
             (:semantic-errors result)))))

  (testing "an unnamed group inside :prompts is a semantic error"
    (let [step (multi-prompt-session-step
                [named-prompt-group-a (dissoc named-prompt-group-b :name)])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :unnamed-prompt-group
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "names may repeat across distinct steps"
    (let [step-1 (assoc (multi-prompt-session-step [named-prompt-group-a])
                        :name "review-1")
          step-2 (assoc (multi-prompt-session-step [named-prompt-group-a])
                        :name "review-2")
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step-1 step-2]})]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result)))))

;; ── Task 226 Slice 4 — `:prompt` source-ref discriminator validation ────────

(def ^:private prior-multi-prompt-step
  (assoc (multi-prompt-session-step [named-prompt-group-a named-prompt-group-b])
         :name "review"))

(defn- consumer-step
  "A later single-prompt session step whose contribution carries `source-ref`."
  [source-ref]
  {:name "consume"
   :type :session
   :session {:contributions [{:type :source :from source-ref}]}
   :outputs {:final-llm-reply {:source :session/final-llm-reply}}
   :yields {:type :text :text :final-llm-reply}})

(defn- two-step-result
  [source-ref]
  (workflow-ir/validate-workflow-ir
   {:version :workflow-ir/v1
    :steps [prior-multi-prompt-step (consumer-step source-ref)]}))

(deftest prompt-source-ref-validation-test
  (testing "a valid `:prompt` ref to a prior multi-prompt group is accepted"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :prompt "architecture"
                             :output :final-llm-reply})))
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :prompt "ambiguity"
                             :output :transcript}))))

  (testing "a no-`:prompt` ref to the step-level surface stays valid (back-compat)"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :output :final-llm-reply}))))

  (testing "a `:prompt` ref to an unknown group is rejected"
    (let [result (two-step-result {:step "review" :prompt "nope"
                                   :output :final-llm-reply})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-unknown-group
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a non-text surface is rejected"
    (let [result (two-step-result {:step "review" :prompt "architecture"
                                   :output :result})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-non-text-surface
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a single-prompt step is rejected"
    (let [single {:name "single"
                  :type :session
                  :session {:contributions [{:type :template :text "x" :vars {}}]}
                  :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                  :yields {:type :text :text :final-llm-reply}}
          consumer (consumer-step {:step "single" :prompt "x"
                                   :output :final-llm-reply})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [single consumer]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-single-prompt-step
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a non-session step is rejected"
    (let [consumer (consumer-step {:step "discover" :prompt "x"
                                   :output :final-llm-reply})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [valid-invoke-step consumer]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-non-session-step
             (-> result :semantic-errors first :type)))))

  (testing "a same-step sibling-group `:prompt` ref in a contribution is rejected"
    (let [step (assoc-in prior-multi-prompt-step
                         [:session :prompts]
                         [named-prompt-group-a
                          (assoc named-prompt-group-b
                                 :contributions
                                 [{:type :source
                                   :from {:step "review" :prompt "architecture"
                                          :output :final-llm-reply}}])])
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-same-step
             (-> result :semantic-errors first :type)))))

  (testing "the step's own post-drain judge may reference its prompt-groups (carve-out)"
    (let [step (assoc prior-multi-prompt-step
                      :judge {:type :llm
                              :session {:contributions
                                        [{:type :source
                                          :from {:step "review" :prompt "architecture"
                                                 :output :final-llm-reply}}]}}
                      :on {:done {:goto :done}})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step]})]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result)))))
