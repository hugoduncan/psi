(ns psi.workflow-runtime.structured-output-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.structured-output :as structured-output]
   [psi.workflow-runtime.structured-output-schemas :as schemas]))

(def classification-output-spec
  {:source :session/structured-output
   :mode :structured
   :schema-id :psi.workflow/bug-reproduction-classification
   :schema-version 1
   :schema [:map
            [:status [:enum :reproducible :not-reproducible :unclear]]
            [:summary :string]
            [:evidence [:vector :string]]
            [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]})

(deftest structured-output-envelope-valid-json-test
  ;; Tests prompted JSON fallback parsing, schema-guided keyword coercion,
  ;; validation, and canonical envelope recording for valid model output.
  (testing "valid prompted JSON becomes a validated structured value"
    (let [result (structured-output/output-result
                  classification-output-spec
                  "{\"status\":\"reproducible\",\"summary\":\"fails\",\"evidence\":[\"bb test\"],\"next-action\":\"handoff-to-fix\"}")]
      (is (= "{\"status\":\"reproducible\",\"summary\":\"fails\",\"evidence\":[\"bb test\"],\"next-action\":\"handoff-to-fix\"}"
             (:raw-output result)))
      (is (= {:mode :structured
              :schema-id :psi.workflow/bug-reproduction-classification
              :schema-version 1
              :strategy :prompted-json
              :status :valid
              :value {:status :reproducible
                      :summary "fails"
                      :evidence ["bb test"]
                      :next-action :handoff-to-fix}}
             (:structured-output result))))))

(deftest structured-output-envelope-invalid-json-test
  ;; Tests that malformed model output records invalid status and parse errors
  ;; without exposing a structured value.
  (testing "malformed JSON is invalid and records errors"
    (let [result (structured-output/output-result classification-output-spec "not json")]
      (is (= :invalid (get-in result [:structured-output :status])))
      (is (= :prompted-json (get-in result [:structured-output :strategy])))
      (is (seq (get-in result [:structured-output :errors])))
      (is (not (contains? (:structured-output result) :value))))))

(deftest structured-output-envelope-invalid-schema-test
  ;; Tests that parsed JSON with uncoercible enum values remains invalid and
  ;; keeps the parsed value for debugging rather than downstream consumption.
  (testing "schema-invalid JSON records parsed value and validation errors"
    (let [result (structured-output/output-result
                  classification-output-spec
                  "{\"status\":\"maybe\",\"summary\":\"fails\",\"evidence\":[],\"next-action\":\"handoff-to-fix\"}")]
      (is (= :invalid (get-in result [:structured-output :status])))
      (is (= {"status" "maybe"
              "summary" "fails"
              "evidence" []
              "next-action" "handoff-to-fix"}
             (get-in result [:structured-output :parsed-value])))
      (is (seq (get-in result [:structured-output :errors])))
      (is (not (contains? (:structured-output result) :value))))))

(deftest reusable-judge-review-result-schema-test
  ;; Tests the first reusable workflow structured-output schema exported by the
  ;; runtime and referenced by schema id/version.
  (testing "judge review result schema id/version validates representative output"
    (let [result (structured-output/output-result
                  {:source :judge/structured-output
                   :mode :structured
                   :schema-id schemas/judge-review-result-schema-id
                   :schema-version schemas/judge-review-result-schema-version
                   :schema schemas/judge-review-result-schema}
                  "{\"decision\":\"needs-work\",\"issues\":[{\"severity\":\"blocking\",\"kind\":\"ambiguity\",\"description\":\"unclear\",\"evidence\":\"design\",\"suggested-change\":\"clarify\"}],\"confidence\":0.75}")]
      (is (= :valid (get-in result [:structured-output :status])))
      (is (= :needs-work (get-in result [:structured-output :value :decision])))
      (is (= :ambiguity (get-in result [:structured-output :value :issues 0 :kind]))))))
