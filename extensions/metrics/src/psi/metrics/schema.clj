(ns psi.metrics.schema
  "Malli schemas for the metrics extension data shape."
  (:require
   [malli.core :as m]))

;;; Schemas

(def counter-schema
  "Invocation counter for workflows, commands, and operations."
  [:map
   [:invocations :int]])

(def tool-counter-schema
  "Invocation + error counter for tool calls."
  [:map
   [:invocations :int]
   [:errors :int]
   [:error-reasons [:map-of :string :int]]])

(def token-totals-schema
  "Cumulative token usage totals for a single model."
  [:map
   [:input :int]
   [:output :int]
   [:cache-read :int]
   [:cache-write :int]])

(def metrics-schema
  "Top-level metrics map persisted to disk and returned by metrics/summary."
  [:map
   [:tools [:map-of :string tool-counter-schema]]
   [:workflows [:map-of :string counter-schema]]
   [:commands [:map-of :string counter-schema]]
   [:operations [:map-of :string counter-schema]]
   [:tokens [:map-of :string token-totals-schema]]
   [:updated-at [:maybe :string]]])

(defn valid?
  "Return true when metrics-map conforms to metrics-schema."
  [metrics-map]
  (m/validate metrics-schema metrics-map))

(defn explain
  "Return malli explain output for a metrics-map that fails validation."
  [metrics-map]
  (m/explain metrics-schema metrics-map))
