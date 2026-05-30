(ns psi.ai.providers.anthropic.structured-output
  "Anthropic provider-native structured-output helpers."
  (:require [psi.ai.structured-output :as structured-output]))

(def json-schema-output-beta "structured-outputs-2025-11-13")

(defn json-schema-output-mechanism?
  [strategy]
  (and (= :provider-native (:strategy strategy))
       (= :anthropic/json-schema-output (:native-mechanism strategy))))

(defn forced-tool-mechanism?
  [strategy]
  (and (= :provider-native (:strategy strategy))
       (= :anthropic/forced-tool-use (:native-mechanism strategy))))

(defn output-format
  [request]
  {:type "json_schema"
   :schema (:json-schema request)})

(defn structured-tool-name
  [request tools]
  (let [base     (str "psi_structured_output__"
                      (structured-output/structured-output-name request))
        occupied (set (keep :name tools))]
    (loop [candidate base
           n         2]
      (if (contains? occupied candidate)
        (recur (str base "_" n) (inc n))
        candidate))))

(defn structured-tool
  [request tools]
  {:name (structured-tool-name request tools)
   :description "Return the requested structured output."
   :input_schema (:json-schema request)})

(defn structured-tool-name-from-request
  [strategy request-body]
  (when (forced-tool-mechanism? strategy)
    (get-in request-body [:tool_choice :name])))

(defn structured-tool-block?
  [structured-tool-name block-info]
  (and (= "tool_use" (:type block-info))
       (= structured-tool-name (:name block-info))))

(defn structured-output-result
  [strategy source raw-payload]
  (let [parse-result (structured-output/parse-json-value raw-payload)]
    (cond-> (assoc strategy
                   :source source
                   :raw-payload raw-payload)
      (:parsed? parse-result) (assoc :payload (:payload parse-result))
      (not parse-result) (assoc :parse-error? true))))

(defn emit-structured-result!
  [consume-fn strategy source raw-payload]
  (consume-fn {:type :structured-output-result
               :structured-output (structured-output-result strategy source raw-payload)}))

(defn maybe-emit-structured-result!
  [consume-fn strategy raw-payload]
  (emit-structured-result! consume-fn strategy :anthropic/tool-use raw-payload))

(defn maybe-emit-json-schema-output-result!
  [consume-fn emitted? strategy raw-text]
  (when (and (json-schema-output-mechanism? strategy)
             (compare-and-set! emitted? false true))
    (emit-structured-result! consume-fn strategy :anthropic/json-schema-output raw-text)))

(defn maybe-emit-prompted-json-result!
  [consume-fn emitted? strategy raw-text]
  (when (and (= :prompted-json (:strategy strategy))
             (compare-and-set! emitted? false true))
    (emit-structured-result! consume-fn strategy :prompted-json/text raw-text)))
