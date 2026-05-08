(ns psi.agent-session.prompt-recording
  "Deterministic prompt response classification/recording scaffold."
  (:require
   [psi.agent-session.journal-append-effect :as journal-append-effect]
   [psi.session-state.state :as session]))

(defn extract-tool-calls
  [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn classify-assistant-message
  [assistant-msg]
  (let [tool-calls (vec (extract-tool-calls assistant-msg))]
    (cond
      (= :error (:stop-reason assistant-msg))
      {:turn/outcome :turn.outcome/error
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      (seq tool-calls)
      {:turn/outcome :turn.outcome/tool-use
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      :else
      {:turn/outcome :turn.outcome/stop
       :assistant-message assistant-msg
       :tool-calls tool-calls})))

(defn build-record-response
  "Build a pure-result for prompt response recording.
   Records bounded summary, appends the assistant journal entry, and returns the
   next prompt lifecycle event so orchestration can remain dispatch-visible."
  [session-id execution-result _progress-queue]
  (let [{:keys [turn/outcome assistant-message tool-calls] :as classified}
        (classify-assistant-message (:execution-result/assistant-message execution-result))
        turn-id (:execution-result/turn-id execution-result)
        next-event (if (= :turn.outcome/tool-use outcome)
                     :session/prompt-continue
                     :session/prompt-finish)]
    {:root-state-update
     (session/session-update
      session-id
      #(assoc %
              :last-execution-result-summary
              {:turn-id         turn-id
               :turn-outcome    outcome
               :stop-reason     (:execution-result/stop-reason execution-result)
               :tool-call-count (count tool-calls)
               :recorded-at     (java.time.Instant/now)}))
     :effects [(journal-append-effect/append-message-effect session-id assistant-message)]
     :return {:recorded? true
              :turn-id   turn-id
              :outcome   outcome
              :next-event next-event
              :classified classified
              :assistant-message assistant-message}}))
