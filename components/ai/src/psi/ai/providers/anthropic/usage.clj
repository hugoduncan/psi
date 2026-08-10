(ns psi.ai.providers.anthropic.usage
  "Anthropic stream token-usage accumulation and cost calculation."
  (:require [psi.ai.models :as models]))

(defn update-usage!
  [usage-acc usage usage-map]
  (when usage
    (swap! usage-acc
           (fn [acc]
             (reduce-kv (fn [m k usage-key]
                          (assoc m k (or (get usage usage-key) 0)))
                        acc
                        usage-map)))))

(defn update-start-usage!
  [usage-acc usage]
  (update-usage! usage-acc
                 usage
                 {:input-tokens       :input_tokens
                  :cache-read-tokens  :cache_read_input_tokens
                  :cache-write-tokens :cache_creation_input_tokens}))

(defn update-output-usage!
  [usage-acc usage]
  (update-usage! usage-acc
                 usage
                 {:output-tokens :output_tokens}))

(defn usage-with-cost
  [model usage-acc]
  (let [usage @usage-acc
        usage (assoc usage :total-tokens (+ (:input-tokens usage)
                                            (:output-tokens usage)
                                            (:cache-read-tokens usage)
                                            (:cache-write-tokens usage)))]
    (assoc usage :cost (models/calculate-cost model usage))))
