(ns psi.agent-session.dispatch-handlers.prompt-lifecycle
  "Registration/adaptation layer for the canonical turn lifecycle.

   Turn lifecycle orchestration lives under `psi.agent-session.turn` and
   `psi.agent-session.turn.handlers`; this namespace only registers dispatch handlers."
  (:require
   [psi.agent-session.journal-append-effect :as journal-append-effect]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.turn.handlers :as turn.handlers]))

(defn- register-core-handler! [event handler]
  (kernel/register-handler! event handler))

(defn register!
  "Register prompt lifecycle handlers. Called once during context creation."
  [_ctx]
  ;; Intentional narrow synchronous boundary — callers require a direct return
  ;; value and the surrounding statechart transitions already own the state
  ;; transition. execute-compaction-fn is injected by core.clj to avoid a
  ;; circular dependency. Keep the boundary explicit via :return; do not
  ;; generalise this pattern.
  (register-core-handler!
   :session/manual-compaction-execute
   (fn [ctx {:keys [session-id custom-instructions]}]
     {:return ((:execute-compaction-fn ctx) ctx session-id custom-instructions)}))

  (register-core-handler!
   :session/prompt-submit
   (fn [ctx {:keys [session-id user-msg workflow-run-id]}]
     (let [run-id   (or workflow-run-id
                        (:workflow-run-id (ss/get-session-data-in ctx session-id)))
           journal  (persist/all-entries-in ctx session-id)
           messages (into []
                          (keep (fn [entry]
                                  (when (= :message (:kind entry))
                                    (get-in entry [:data :message]))))
                          journal)
           repairs  (prompt-request/tail-dangling-tool-result-repairs messages)
           effects  (into []
                          (concat
                           (map #(journal-append-effect/append-message-effect session-id % run-id) repairs)
                           [(journal-append-effect/append-message-effect session-id user-msg run-id)]))]
       {:effects effects
        :return {:submitted? true
                 :turn-id (str (java.util.UUID/randomUUID))
                 :user-msg user-msg
                 :repaired-tool-result-count (count repairs)}})))

  (register-core-handler!
   :session/submit-synthetic-user-prompt
   (fn [ctx {:keys [session-id user-msg workflow-run-id]}]
     (let [run-id (or workflow-run-id
                      (:workflow-run-id (ss/get-session-data-in ctx session-id)))]
       {:effects (turn.handlers/synthetic-user-prompt-effects session-id user-msg run-id)
        :return {:submitted? true
                 :user-msg user-msg}})))

  (register-core-handler!
   :session/append-journal-entry
   (fn [ctx {:keys [session-id entry]}]
     (let [next-entries (conj (persist/all-entries-in ctx session-id) entry)
           flush-state  (ss/get-state-value-in ctx (ss/state-path :flush-state session-id))
           session-data (ss/get-session-data-in ctx session-id)
           io-request   (persist/persistence-io-request {:entries next-entries
                                                         :flush-state flush-state
                                                         :session-id session-id
                                                         :worktree-path (:worktree-path session-data)
                                                         :parent-session-id (:parent-session-id session-data)
                                                         :parent-session-path (:parent-session-path session-data)})]
       (cond-> {:root-state-update (persist/append-journal-entry-root-update session-id entry)
                :return entry}
         io-request
         (assoc :effects [{:effect/type :persist/session-journal-io
                           :session-id session-id
                           :request io-request}])))))

  (register-core-handler! :session/prompt-prepare-request turn.handlers/prompt-prepare-request-handler)
  (register-core-handler! :session/prompt-record-response turn.handlers/prompt-record-response-handler)
  (register-core-handler! :session/prompt-continue turn.handlers/prompt-continue-handler)
  (register-core-handler! :session/prompt-finish turn.handlers/prompt-finish-handler)
  (register-core-handler! :session/prompt-execute turn.handlers/prompt-execute-handler))
