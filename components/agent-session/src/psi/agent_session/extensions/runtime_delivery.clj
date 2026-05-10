(ns psi.agent-session.extensions.runtime-delivery
  (:require
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.dispatch :as dispatch]
   [psi.session-state.state :as ss]))

(defn deliver-extension-prompt!
  "Deliver extension prompt text via the best available channel.
   Returns the delivery mode keyword."
  [ctx session-id text source]
  (let [run-fn @(:extension-run-fn-atom ctx)
        idle?  (ss/idle-in? ctx session-id)]
    (cond
      (and run-fn idle?)
      (do (future (run-fn (str text) source))
          :prompt)

      run-fn
      (do (future (run-fn (str text) source))
          :deferred)

      :else
      (do (dispatch/dispatch! ctx :session/enqueue-follow-up-message
                              {:session-id session-id :text (str text)} {:origin :core})
          :follow-up))))

(defn maybe-reconcile-after-run-fn-registration!
  [ctx session-id]
  (when (ss/idle-in? ctx session-id)
    (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx session-id)))

(defn maybe-reconcile-after-prompt-record!
  [ctx session-id]
  (when (ss/idle-in? ctx session-id)
    (bg-rt/reconcile-and-emit-background-job-terminals-in! ctx session-id)))
