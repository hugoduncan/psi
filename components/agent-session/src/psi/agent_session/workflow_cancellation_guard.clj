(ns psi.agent-session.workflow-cancellation-guard
  "Helpers for making workflow-owned stale pure results cancellation-safe."
  (:require
   [psi.session-state.state :as session]
   [psi.workflow-coordination.stop-signal :as stop-signal]))

(defn live-run-in-state?
  "True when run-id still names a non-cancelled canonical workflow run."
  [state-map run-id]
  (stop-signal/workflow-live-in-state? state-map run-id))

(defn guard-root-state-update
  "Wrap root-state-update so stale workflow-owned pure results no-op after cancel/remove."
  [root-state-update run-id]
  (if-not (and root-state-update run-id)
    root-state-update
    (fn [state-map]
      (if (live-run-in-state? state-map run-id)
        (root-state-update state-map)
        state-map))))

(defn workflow-owned-session-run-id
  [ctx session-id]
  (let [session-data (session/get-session-data-in ctx session-id)]
    (when (:workflow-owned? session-data)
      (:workflow-run-id session-data))))

(defn event-or-session-run-id
  [ctx {:keys [session-id workflow-run-id]}]
  (or workflow-run-id
      (workflow-owned-session-run-id ctx session-id)))

(defn with-workflow-run-id
  [m run-id]
  (cond-> m
    run-id (assoc :workflow-run-id run-id)))

(defn guarded-effect
  [effect run-id]
  (with-workflow-run-id effect run-id))
