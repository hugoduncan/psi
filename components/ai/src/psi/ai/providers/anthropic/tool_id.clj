(ns psi.ai.providers.anthropic.tool-id
  "Anthropic tool id normalization helpers."
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

(def ^:private anthropic-tool-id-pattern
  "Anthropic requires tool_use.id to match ^[a-zA-Z0-9_-]+$."
  #"^[a-zA-Z0-9_-]+$")

(defn- coerce-str
  "Coerce any value to a string; nil and false become \"\"."
  [x]
  (str (or x "")))

(defn- valid-anthropic-tool-id?
  [id]
  (and (string? id)
       (boolean (re-matches anthropic-tool-id-pattern id))))

(defn- fallback-anthropic-tool-id
  []
  (str "tool_" (UUID/randomUUID)))

(defn ensure-anthropic-tool-id
  "Return an Anthropic-safe tool id (alnum, underscore, hyphen only).
   Generates a fallback when id is nil/blank/invalid."
  [id]
  (let [s (coerce-str id)
        sanitized (-> s
                      (str/replace #"[^a-zA-Z0-9_-]" "_")
                      (str/replace #"_+" "_")
                      (str/replace #"-+" "-")
                      (str/replace #"^[_-]+|[_-]+$" ""))]
    (or (when (valid-anthropic-tool-id? s)
          s)
        (when (valid-anthropic-tool-id? sanitized)
          sanitized)
        (fallback-anthropic-tool-id))))

(defn canonical-tool-id-fn
  []
  (let [tool-id-map (atom {})]
    (fn [raw-id]
      (let [key (coerce-str raw-id)]
        (or (get @tool-id-map key)
            (let [canonical-id (ensure-anthropic-tool-id raw-id)]
              (swap! tool-id-map assoc key canonical-id)
              canonical-id))))))
