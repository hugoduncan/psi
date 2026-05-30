(ns psi.turn-runtime.state
  "Lower-owned turn-runtime state readers and writers.

   Owns live turn context plus turn-execution telemetry that is authored during
   provider streaming. Journal append and broader prompt lifecycle orchestration
   remain above this boundary."
  (:require
   [psi.session-state.init :as init]
   [psi.session-state.state :as session]))

(defn turn-context-in
  "Return the current live turn context for `session-id`."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :turn-ctx session-id)))

(defn tool-call-attempts-in
  "Return canonical tool-call attempt telemetry for `session-id`."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :tool-call-attempts session-id)))

(defn provider-requests-in
  "Return canonical provider request captures for `session-id`."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :provider-requests session-id)))

(defn provider-replies-in
  "Return canonical provider reply captures for `session-id`."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :provider-replies session-id)))

(defn provider-events-in
  "Return canonical provider lifecycle events for `session-id`."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :provider-events session-id)))

(defn set-turn-context-root-update
  [session-id turn-ctx]
  (fn [state]
    (assoc-in state (session/session-turn-ctx-path session-id) turn-ctx)))

(defn append-tool-call-attempt-root-update
  [session-id attempt]
  (let [entry (assoc attempt :timestamp (java.time.Instant/now))]
    (fn [state]
      (update-in state (session/session-telemetry-path session-id :tool-call-attempts)
                 (fnil conj [])
                 entry))))

(defn append-provider-request-capture-root-update
  [session-id capture]
  (let [entry (assoc capture :timestamp (java.time.Instant/now))]
    (fn [state]
      (update-in state (session/session-telemetry-path session-id :provider-requests)
                 #(init/bounded-append 100 % entry)))))

(defn append-provider-reply-capture-root-update
  [session-id capture]
  (let [entry (assoc capture :timestamp (java.time.Instant/now))]
    (fn [state]
      (update-in state (session/session-telemetry-path session-id :provider-replies)
                 #(init/bounded-append 1000 % entry)))))

(defn append-provider-event-root-update
  [session-id event]
  (let [entry (assoc event :timestamp (java.time.Instant/now))]
    (fn [state]
      (update-in state (session/session-telemetry-path session-id :provider-events)
                 #(init/bounded-append 1000 % entry)))))

(defn set-turn-context-in!
  "Persist the current live turn context into canonical runtime state."
  [ctx session-id turn-ctx]
  (session/apply-root-state-update-in! ctx (set-turn-context-root-update session-id turn-ctx))
  (turn-context-in ctx session-id))

(defn append-tool-call-attempt-in!
  "Append one tool-call attempt telemetry entry into canonical state."
  [ctx session-id attempt]
  (session/apply-root-state-update-in! ctx (append-tool-call-attempt-root-update session-id attempt))
  (tool-call-attempts-in ctx session-id))

(defn append-provider-request-capture-in!
  "Append one provider request capture into canonical state with bounded retention."
  [ctx session-id capture]
  (session/apply-root-state-update-in! ctx (append-provider-request-capture-root-update session-id capture))
  (provider-requests-in ctx session-id))

(defn append-provider-reply-capture-in!
  "Append one provider reply capture into canonical state with bounded retention."
  [ctx session-id capture]
  (session/apply-root-state-update-in! ctx (append-provider-reply-capture-root-update session-id capture))
  (provider-replies-in ctx session-id))

(defn append-provider-event-in!
  "Append one provider lifecycle event into canonical state with bounded retention."
  [ctx session-id event]
  (session/apply-root-state-update-in! ctx (append-provider-event-root-update session-id event))
  (provider-events-in ctx session-id))
