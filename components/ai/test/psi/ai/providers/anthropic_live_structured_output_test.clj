(ns ^:integration psi.ai.providers.anthropic-live-structured-output-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]))

(def ^:private smoke-json-schema
  {:type "object"
   :additionalProperties false
   :required ["ok" "label"]
   :properties {:ok {:type "boolean"}
                :label {:type "string"}}})

(def ^:private smoke-request
  {:schema-id :psi.smoke/anthropic-structured-output
   :schema-version 1
   :name "psi_anthropic_structured_output_smoke"
   :json-schema smoke-json-schema
   :strict? true
   :fallback-allowed? false})

(defn- enabled?
  []
  (= "1" (System/getenv "PSI_LIVE_ANTHROPIC_STRUCTURED_OUTPUT")))

(defn- api-key-present?
  []
  (not (str/blank? (System/getenv "ANTHROPIC_API_KEY"))))

(deftest ^:integration live-anthropic-json-schema-structured-output-test
  ;; Opt-in live smoke for Anthropic JSON Schema native output. Secrets enter
  ;; only through the provider :api-key/ANTHROPIC_API_KEY seam and are not logged.
  (cond
    (not (enabled?))
    (testing "skipped: missing PSI_LIVE_ANTHROPIC_STRUCTURED_OUTPUT=1"
      (is true))

    (not (api-key-present?))
    (testing "skipped: missing Anthropic credential at :api-key/ANTHROPIC_API_KEY provider seam"
      (is true))

    :else
    (let [model  (models/get-model :sonnet-4.5)
          convo  (-> (conv/create "Return exactly the requested JSON object.")
                     (conv/add-user-message "Return ok=true and label=live-smoke."))
          result ((:execute anthropic/provider)
                  convo model {:structured-output smoke-request
                               :max-tokens 128
                               :temperature 0})]
      (is (not= :error (:type result)) (:error-message result))
      (is (= :provider-native (get-in result [:structured-output :strategy])))
      (is (= :anthropic/json-schema-output
             (get-in result [:structured-output :native-mechanism])))
      (is (= :anthropic/json-schema-output
             (get-in result [:structured-output :source])))
      (is (map? (get-in result [:structured-output :payload]))))))
