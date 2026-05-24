(ns psi.workflow-step-materialization.structured-source-resolution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-step-materialization.source-resolution :as source-resolution]))

(def structured-step
  {:name "classify"
   :type :session
   :outputs {:classification {:source :session/structured-output
                              :mode :structured
                              :schema-id :psi.workflow/bug-reproduction-classification
                              :schema-version 1
                              :schema [:map
                                       [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]}}
   :yields {:type :data :data :classification}})

(defn workflow-run-with-output
  [classification-result]
  {:effective-definition {:canonical-ir {:steps [structured-step]}}
   :step-runs {"classify" {:accepted-result {:outcome :ok
                                             :outputs {:classification classification-result}}}}})

(deftest structured-source-ref-resolution-test
  ;; Tests downstream source refs read fields from validated structured values,
  ;; not raw output or parsed-but-invalid debug data.
  (testing "path references read validated structured output values"
    (let [run (workflow-run-with-output
               {:raw-output "{...}"
                :structured-output {:mode :structured
                                    :schema-id :psi.workflow/bug-reproduction-classification
                                    :schema-version 1
                                    :strategy :provider-native
                                    :native-mechanism :openai/chat-completions-json-schema-response-format
                                    :payload {"next-action" "handoff-to-fix"}
                                    :raw-payload "{raw}"
                                    :status :valid
                                    :value {:next-action :handoff-to-fix}}})]
      (is (= :handoff-to-fix
             (source-resolution/apply-source-spec
              run
              {:from {:step "classify" :output :classification}
               :path [:next-action]})))))

  (testing "invalid structured output fails before downstream path traversal"
    (let [run (workflow-run-with-output
               {:raw-output "{}"
                :structured-output {:mode :structured
                                    :schema-id :psi.workflow/bug-reproduction-classification
                                    :schema-version 1
                                    :strategy :prompted-json
                                    :status :invalid
                                    :errors [{:type :validation-error}]}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Workflow structured output is not valid"
           (source-resolution/apply-source-spec
            run
            {:from {:step "classify" :output :classification}
             :path [:next-action]})))))

  (testing "missing structured output path fails clearly"
    (let [run (workflow-run-with-output
               {:raw-output "{...}"
                :structured-output {:mode :structured
                                    :schema-id :psi.workflow/bug-reproduction-classification
                                    :schema-version 1
                                    :strategy :prompted-json
                                    :status :valid
                                    :value {:next-action :handoff-to-fix}}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Workflow structured output path is missing"
           (source-resolution/apply-source-spec
            run
            {:from {:step "classify" :output :classification}
             :path [:missing]})))))

  (testing "path references against non-structured source outputs fail clearly"
    (let [run {:effective-definition {:canonical-ir {:steps [(assoc structured-step
                                                                    :outputs {:final-llm-reply {:source :session/final-llm-reply}})]}}
               :step-runs {"classify" {:accepted-result {:outcome :ok
                                                         :outputs {:final-llm-reply "plain text"}}}}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Workflow source output is not structured"
           (source-resolution/apply-source-spec
            run
            {:from {:step "classify" :output :final-llm-reply}
             :path [:decision]}))))))
