(ns psi.agent-session.prompt-chain
  "Prompt lifecycle continuation helpers.

   Transitional runtime boundary: runtime-only tool execution for prompt
   continuation. Higher-level next-turn orchestration is expressed through
   dispatch-visible continuation events."
  (:require
   [psi.workflow-coordination.stop-signal :as stop-signal]
   [psi.agent-session.dispatch :as dispatch]
   [psi.session-state.state :as session]
   [psi.tool-runtime.args :as tool-args]))

(defn- workflow-run-stop-signal
  [ctx run-id]
  (stop-signal/workflow-stop-signal ctx run-id))

(defn- workflow-session-run-id
  [ctx session-id]
  (let [session-data (session/get-session-data-in ctx session-id)]
    (when (:workflow-owned? session-data)
      (:workflow-run-id session-data))))

(defn- call-before-tool-dispatch-hook!
  [ctx data]
  (when-let [f (:before-prompt-continue-tool-dispatch-fn ctx)]
    (f ctx data)))

(defn- stopped-result
  [dispatched-count reason]
  {:continued? (pos? dispatched-count)
   :tool-call-count dispatched-count
   :workflow-stopped? true
   :reason reason})

(defn- dispatch-tool-run-if-live!
  [ctx session-id progress-queue run-id tool-call]
  (call-before-tool-dispatch-hook! ctx {:session-id session-id
                                        :workflow-run-id run-id
                                        :tool-call tool-call})
  (when-not (workflow-run-stop-signal ctx run-id)
    (dispatch/dispatch! ctx :session/tool-run
                        (cond-> {:session-id     session-id
                                 :tool-call      tool-call
                                 :parsed-args    (tool-args/parse-args (:arguments tool-call))
                                 :progress-queue progress-queue}
                          run-id (assoc :workflow-run-id run-id))
                        {:origin :core})
    true))

(defn run-prompt-tools!
  ([ctx session-id execution-result progress-queue]
   (run-prompt-tools! ctx session-id execution-result progress-queue nil))
  ([ctx session-id execution-result progress-queue opts]
   (let [tool-calls (vec (:execution-result/tool-calls execution-result))
         run-id (or (:workflow-run-id opts)
                    (workflow-session-run-id ctx session-id))]
     (loop [remaining tool-calls
            dispatched-count 0]
       (if-let [reason (workflow-run-stop-signal ctx run-id)]
         (stopped-result dispatched-count reason)
         (if-let [tool-call (first remaining)]
           (if (dispatch-tool-run-if-live! ctx session-id progress-queue run-id tool-call)
             (recur (subvec remaining 1) (inc dispatched-count))
             (stopped-result dispatched-count (workflow-run-stop-signal ctx run-id)))
           {:continued? (pos? dispatched-count)
            :tool-call-count dispatched-count}))))))
