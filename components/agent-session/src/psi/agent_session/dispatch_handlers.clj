(ns psi.agent-session.dispatch-handlers
  "Entry point for dispatch handler registration.
   Delegates to sub-namespaces; exposes the public API consumed by core.clj:
     register-all!, make-actions-fn, dispatch-statechart-event-in!,
     daemon-thread"
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.prompt-handlers :as prompt-handlers]
   [psi.agent-session.dispatch-handlers.prompt-lifecycle :as prompt-lifecycle]
   [psi.agent-session.dispatch-handlers.scheduler :as scheduler]
   [psi.agent-session.dispatch-handlers.session-lifecycle :as lifecycle]
   [psi.agent-session.dispatch-handlers.session-mutations :as mutations]
   [psi.agent-session.dispatch-handlers.statechart-actions :as sc-actions]
   [psi.agent-session.dispatch-handlers.ui-handlers :as ui-handlers]
   [psi.agent-session.dispatch-handlers.workflows :as workflows]
   [psi.session-state.state :as session]
   [psi.agent-session.statechart :as sc]))

;;; Re-exports expected by core.clj

(defn daemon-thread
  "Start a daemon thread running f. Returns the Thread."
  [f]
  (sc-actions/daemon-thread f))

;;; Wiring

(defn make-actions-fn
  "Return the side-effect dispatcher wired into the statechart working memory.
   The statechart calls (actions-fn action-key data) where data is the working
   memory map containing :session-id."
  [ctx]
  (fn [action-key data]
    (dispatch/dispatch! ctx action-key data {:origin :statechart})))

(defn dispatch-statechart-event-in!
  "Adapter boundary for routing session statechart events through dispatch.

   Returns {:claimed? true} when the event was sent to the session statechart.
   This makes statechart participation explicit in the dispatch pipeline while
   preserving the existing statechart runtime and transition ownership."
  [ctx event-type event-data _ictx]
  (when (contains? #{:session/prompt :session/abort :session/compact-start :session/compact-done} event-type)
    (sc/send-event! (:sc-env ctx) (session/sc-session-id-in ctx (:session-id event-data)) event-type event-data)
    {:claimed? true}))

(defn register-all!
  "Register all dispatch handlers for the agent-session pipeline.
   Safe to call again after reload to replace handler fns with the current vars."
  [ctx]
  (sc-actions/register! ctx)
  (ui-handlers/register! ctx)
  (prompt-handlers/register! ctx)
  (prompt-lifecycle/register! ctx)
  (scheduler/register! ctx)
  (lifecycle/register! ctx)
  (workflows/register! ctx)
  (mutations/register! ctx))
