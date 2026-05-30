(ns psi.ai.providers.openai.codex-structured-output
  "OpenAI ChatGPT/Codex structured-output helpers. This endpoint accepts
   Responses-style text.format JSON Schema only for streaming requests."
  (:require [psi.ai.structured-output :as structured-output]))

(defn native-mechanism?
  [strategy]
  (and (= :provider-native (:strategy strategy))
       (= :openai/responses-text-format-json-schema
          (:native-mechanism strategy))))

(defn text-format
  [request]
  {:type "json_schema"
   :name (structured-output/structured-output-name request)
   :schema (:json-schema request)
   :strict (not (false? (:strict? request)))})

(defn structured-output-result
  [strategy source raw-payload]
  (let [parse-result (structured-output/parse-json-value raw-payload)]
    (if (:parsed? parse-result)
      (assoc strategy
             :source source
             :raw-payload raw-payload
             :payload (:payload parse-result))
      (assoc strategy
             :source source
             :raw-payload raw-payload
             :parse-error? true))))

(defn emit-structured-result!
  [consume-fn strategy source raw-payload]
  (consume-fn {:type :structured-output-result
               :structured-output (structured-output-result strategy source raw-payload)}))

(defn maybe-emit-native-result!
  [consume-fn emitted? strategy raw-text]
  (when (and (native-mechanism? strategy)
             (compare-and-set! emitted? false true))
    (emit-structured-result! consume-fn strategy :openai/codex-text-format raw-text)))

(defn maybe-emit-prompted-json-result!
  [consume-fn emitted? strategy raw-text]
  (when (and (= :prompted-json (:strategy strategy))
             (compare-and-set! emitted? false true))
    (emit-structured-result! consume-fn strategy :prompted-json/text raw-text)))
