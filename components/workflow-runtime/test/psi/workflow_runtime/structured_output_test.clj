(ns psi.workflow-runtime.structured-output-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.ai.providers.anthropic.structured-output :as anthropic-structured-output]
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

(deftest structured-output-envelope-plain-text-validation-error-test
  ;; Tests that plain-text model output (not valid JSON) is accepted by
  ;; parse-json-value via the plain-text fallback (trimmed raw string, :ok? true),
  ;; then rejected by malli schema validation.  This exercises the validation-error
  ;; path, not a parse-error path — parse-json-value never returns :ok? false.
  (testing "plain-text output fails malli validation and records errors"
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

(def judge-routing-spec
  {:source :judge/structured-output
   :mode :structured
   :schema-id :psi.workflow/judge-routing-result
   :schema-version 1
   :schema [:enum "REPEAT" "DONE"]
   :json-schema {:type "string" :enum ["REPEAT" "DONE"]}})

(deftest structured-output-envelope-string-enum-json-test
  ;; Regression test: parse-json-value previously rejected non-object JSON
  ;; (including plain strings) with a hard parse-error, making [:enum "REPEAT" "DONE"]
  ;; judge schemas permanently invalid regardless of AI output.
  ;; parse-json-value now accepts:
  ;;   - valid JSON values (including JSON-quoted strings like "\"DONE\"")
  ;;   - plain-text fallback: if JSON parsing fails, the trimmed raw text is
  ;;     treated as a plain string. This handles judge models that return DONE
  ;;     (unquoted) rather than "DONE" (JSON string).
  (testing "string enum JSON value validates correctly against [:enum ...] schema"
    (doseq [[raw expected-value] [["\"DONE\"" "DONE"] ["\"REPEAT\"" "REPEAT"]
                                  ["DONE" "DONE"] ["REPEAT" "REPEAT"]]]
      (let [result (structured-output/output-result judge-routing-spec raw)]
        (is (= :valid (get-in result [:structured-output :status])) raw)
        (is (= expected-value (get-in result [:structured-output :value])) raw)))))

(deftest structured-output-envelope-anthropic-native-string-payload-test
  ;; Integrated regression: Anthropic native JSON Schema output returns provider
  ;; metadata containing a bare string payload, and the workflow envelope validates
  ;; that metadata directly against the judge routing schema instead of reparsing
  ;; the full assistant text.
  (let [ai-metadata (anthropic-structured-output/structured-output-result
                     {:strategy :provider-native
                      :native-mechanism :anthropic/json-schema-output}
                     :anthropic/json-schema-output
                     "\"DONE\"")
        result (structured-output/output-result judge-routing-spec nil ai-metadata)
        envelope (:structured-output result)]
    (is (= :valid (:status envelope)))
    (is (= "DONE" (:value envelope)))
    (is (= :provider-native (:strategy envelope)))
    (is (= :anthropic/json-schema-output (:source envelope)))
    (is (= :anthropic/json-schema-output (:native-mechanism envelope)))
    (is (= "DONE" (:payload envelope)))
    (is (= "\"DONE\"" (:raw-payload envelope)))))

(deftest reusable-pass-status-result-schema-test
  ;; Tests the psi.workflow/pass-status-result schema exported by the runtime
  ;; and referenced by schema id/version. Validates representative valid and
  ;; invalid JSON inputs.
  (let [pass-status-spec {:source :session/structured-output
                          :mode :structured
                          :schema-id schemas/pass-status-result-schema-id
                          :schema-version schemas/pass-status-result-schema-version
                          :schema schemas/pass-status-result-schema
                          :json-schema schemas/pass-status-result-json-schema}]
    (testing "valid pass-status-result JSON validates and exposes coerced value"
      (let [result (structured-output/output-result
                    pass-status-spec
                    "{\"status\":\"PASS\",\"reason\":\"all checks green\"}")]
        (is (= :valid (get-in result [:structured-output :status])))
        (is (= "PASS" (get-in result [:structured-output :value :status])))
        (is (= "all checks green" (get-in result [:structured-output :value :reason])))))
    (testing "invalid pass-status-result JSON (missing :reason) is invalid"
      (let [result (structured-output/output-result
                    pass-status-spec
                    "{\"status\":\"PASS\"}")]
        (is (= :invalid (get-in result [:structured-output :status])))
        (is (seq (get-in result [:structured-output :errors])))))))
