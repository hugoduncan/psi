(ns psi.agent-session.prompt-loop
  "Agent loop lifecycle helpers.

   Canonical home for shared-session prompt loop lifecycle orchestration and
   terminal session-statechart completion."
  (:require
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-turn :as prompt-turn]
   [psi.session-state.state :as session]
   [psi.agent-session.statechart :as sc]))

(defn finish-agent-loop!
  "Send :agent-end to the session statechart (skipped for child sessions)."
  [ctx session-id result]
  (when (not= :agent (:spawn-mode (session/get-session-data-in ctx session-id)))
    (let [sc-env (:sc-env ctx)
          sc-sid (session/sc-session-id-in ctx session-id)]
      (when (and sc-env sc-sid)
        (sc/send-event! sc-env sc-sid
                        :session/agent-event
                        {:pending-agent-event {:type     :agent-end
                                               :messages (prompt-request/session->provider-messages ctx session-id)
                                               :provider-error/headers (:provider-error/headers result)}}))))
  result)

(defn run-agent-loop!
  "Run a complete agent loop from current session state.

   Callers are responsible for journaling user messages before calling this
   function. Drives turns until terminal, then finalizes the session statechart.

   Options (optional map):
     :api-key        — OAuth API key passed through to the provider
     :progress-queue — LinkedBlockingQueue for TUI progress events

   Returns the final assistant message."
  ([ai-ctx ctx session-id ai-model]
   (run-agent-loop! ai-ctx ctx session-id ai-model nil))
  ([ai-ctx ctx session-id ai-model opts]
   (let [result (try
                  (prompt-turn/run-turn-loop! ai-ctx ctx session-id ai-model
                                              (prompt-request/session->request-options
                                               ctx
                                               (session/get-session-data-in ctx session-id)
                                               opts)
                                              (:progress-queue opts))
                  (catch Throwable e
                    (cond-> {:role          "assistant"
                             :content       []
                             :stop-reason   :error
                             :error-message (or (ex-message e) (.getMessage e) (str e))
                             :timestamp     (java.time.Instant/now)}
                      (:status (ex-data e)) (assoc :http-status (:status (ex-data e)))
                      (:headers (ex-data e)) (assoc :provider-error/headers (:headers (ex-data e))))))]
     (finish-agent-loop! ctx session-id result))))
