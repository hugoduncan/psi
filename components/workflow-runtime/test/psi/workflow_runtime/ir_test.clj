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
                                                    :projection {:type :tail :turns 1}})))

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

  (testing "structural failures stop before semantic validation"
    (let [result (workflow-ir/validate-workflow-ir {:steps []})]
      (is (false? (:valid? result)))
      (is (some? (:structural-errors result)))
      (is (= [] (:semantic-errors result))))))
