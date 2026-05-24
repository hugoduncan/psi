(ns psi.ai.providers.anthropic.structured-output
  "Anthropic provider-native structured-output helpers."
  (:require [psi.ai.structured-output :as structured-output]))

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
  (when (= :provider-native (:strategy strategy))
    (get-in request-body [:tool_choice :name])))

(defn structured-tool-block?
  [structured-tool-name block-info]
  (and (= "tool_use" (:type block-info))
       (= structured-tool-name (:name block-info))))

(defn maybe-emit-structured-result!
  [consume-fn strategy raw-payload]
  (let [payload (structured-output/parse-json-object raw-payload)]
    (consume-fn {:type :structured-output-result
                 :structured-output (cond-> (assoc strategy
                                                   :source :anthropic/tool-use
                                                   :raw-payload raw-payload)
                                      payload (assoc :payload payload))})))
