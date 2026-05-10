(ns psi.turn-statechart.core
  "Public facade for the turn-statechart component.

   Owns the stable component API while delegating implementation concerns to
   narrower internal namespaces:
   - `psi.turn-statechart.data`    — turn-data shape and accumulation actions
   - `psi.turn-statechart.chart`   — statechart definition
   - `psi.turn-statechart.runtime` — runtime/session helpers and query surface"
  (:require
   [psi.turn-statechart.chart :as chart]
   [psi.turn-statechart.data :as data]
   [psi.turn-statechart.runtime :as runtime]))

(defn create-turn-data
  "Return a fresh canonical turn data map."
  []
  (data/create-turn-data))

(defn make-accumulation-actions
  "Create a pure accumulation actions-fn for one turn.

   `done-p` is an optional promise delivered on terminal :on-done/:on-error."
  [done-p]
  (data/make-accumulation-actions done-p))

(def turn-chart
  "Canonical per-turn stream-assembly statechart definition."
  chart/turn-chart)

(defn create-turn-context
  "Create an isolated turn streaming context."
  [actions-fn]
  (runtime/create-turn-context actions-fn))

(defn send-event!
  "Send an event to the turn statechart and return a narrowed component-level
   snapshot:
   `{:turn-phase kw :turn-data map}`."
  ([turn-ctx event-kw]
   (runtime/send-event! turn-ctx event-kw))
  ([turn-ctx event-kw extra-data]
   (runtime/send-event! turn-ctx event-kw extra-data)))

(defn turn-snapshot
  "Return the narrowed component-level snapshot for `turn-ctx`."
  [turn-ctx]
  (runtime/turn-snapshot turn-ctx))

(defn turn-configuration
  "Return the active statechart configuration set (e.g. #{:idle})."
  [turn-ctx]
  (runtime/turn-configuration turn-ctx))

(defn turn-phase
  "Return the active turn phase keyword."
  [turn-ctx]
  (runtime/turn-phase turn-ctx))

(defn get-turn-data
  "Return the current turn data map."
  [turn-ctx]
  (runtime/get-turn-data turn-ctx))
