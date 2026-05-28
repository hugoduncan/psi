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
            [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]
   :strategy-preference :provider-native
   :json-schema {:type "object"
                 :required ["status" "summary" "evidence" "next-action"]
                 :properties {"status" {:type "string"}
                              "summary" {:type "string"}
                              "evidence" {:type "array" :items {:type "string"}}
                              "next-action" {:type "string"}}}})

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

(deftest structured-output-envelope-non-object-json-test
  ;; Tests the prompted JSON boundary: valid JSON that does not match the schema
  ;; (e.g. arrays or scalars against a map schema) is rejected as invalid.
  ;; parse-json-value accepts any valid JSON; schema mismatch is caught by malli.
  (testing "valid non-object JSON is rejected as an invalid structured envelope"
    (doseq [[raw parsed] [["[1,2,3]" [1 2 3]]
                          ["42" 42]
                          ["\"text\"" "text"]]]
      (let [result (structured-output/output-result classification-output-spec raw)]
        (is (= :invalid (get-in result [:structured-output :status])) raw)
        (is (= :prompted-json (get-in result [:structured-output :strategy])) raw)
        (is (= parsed (get-in result [:structured-output :parsed-value])) raw)
        (is (seq (get-in result [:structured-output :errors])) raw)
        (is (not (contains? (:structured-output result) :value)) raw)))))

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

(deftest structured-output-request-policy-test
  ;; Tests workflow policy keys are translated to provider-neutral AI request options.
  (testing "defaults prefer provider-native and allow prompted JSON fallback"
    (is (= {:ok? true
            :opts {:structured-output {:schema-id :psi.workflow/bug-reproduction-classification
                                       :schema-version 1
                                       :json-schema (:json-schema classification-output-spec)
                                       :strategy-preference :provider-native
                                       :fallback-allowed? true
                                       :strict? true}}}
           (structured-output/structured-output-request :classification classification-output-spec))))
  (testing "required-native and fallback none encode fallback-forbidden without capability checks"
    (is (false? (get-in (structured-output/structured-output-request
                         :classification
                         (assoc classification-output-spec :require-provider-native? true))
                        [:opts :structured-output :fallback-allowed?])))
    (is (false? (get-in (structured-output/structured-output-request
                         :classification
                         (assoc classification-output-spec :fallback :none))
                        [:opts :structured-output :fallback-allowed?]))))
  (testing "missing JSON Schema fails before generation"
    (let [result (structured-output/structured-output-request :classification
                                                              (dissoc classification-output-spec :json-schema))]
      (is (false? (:ok? result)))
      (is (= :missing-json-schema (:reason result)))
      (is (= :classification (get-in result [:details :output-key]))))))

(deftest structured-output-envelope-provider-native-payload-test
  ;; Tests provider-native metadata/payload is copied into the workflow envelope
  ;; and the parsed/native payload is the local validation input.
  (testing "valid provider-native payload is locally validated and exposes only coerced value downstream"
    (let [ai-metadata {:strategy :provider-native
                       :native-mechanism :openai/chat-completions-json-schema-response-format
                       :source :openai/message-json
                       :payload {"status" "reproducible"
                                 "summary" "fails"
                                 "evidence" ["bb test"]
                                 "next-action" "handoff-to-fix"}
                       :raw-payload "{raw}"}
          result (structured-output/output-result classification-output-spec nil ai-metadata)]
      (is (= :valid (get-in result [:structured-output :status])))
      (is (= :provider-native (get-in result [:structured-output :strategy])))
      (is (= :openai/chat-completions-json-schema-response-format
             (get-in result [:structured-output :native-mechanism])))
      (is (= (:payload ai-metadata) (get-in result [:structured-output :payload])))
      (is (= "{raw}" (get-in result [:structured-output :raw-payload])))
      (is (= {:status :reproducible
              :summary "fails"
              :evidence ["bb test"]
              :next-action :handoff-to-fix}
             (get-in result [:structured-output :value]))))))

(deftest structured-output-envelope-string-enum-json-test
  ;; Regression test: parse-json-value previously rejected non-object JSON
  ;; (including plain strings) with a hard parse-error, making [:enum "REPEAT" "DONE"]
  ;; judge schemas permanently invalid regardless of AI output.
  ;; parse-json-value now accepts any valid JSON; malli validates the schema.
  (testing "string enum JSON value validates correctly against [:enum ...] schema"
    (let [judge-routing-spec {:source :judge/structured-output
                              :mode :structured
                              :schema-id :psi.workflow/judge-routing-result
                              :schema-version 1
                              :schema [:enum "REPEAT" "DONE"]
                              :json-schema {:type "string" :enum ["REPEAT" "DONE"]}}]
      (doseq [[raw expected-value] [["\"DONE\"" "DONE"] ["\"REPEAT\"" "REPEAT"]]]
        (let [result (structured-output/output-result judge-routing-spec raw)]
          (is (= :valid (get-in result [:structured-output :status])) raw)
          (is (= expected-value (get-in result [:structured-output :value])) raw))))))
