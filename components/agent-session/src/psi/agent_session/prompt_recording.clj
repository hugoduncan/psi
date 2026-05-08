(ns psi.agent-session.prompt-recording
  "Deterministic prompt response classification/recording scaffold."
  (:require
   [psi.agent-session.journal-append-effect :as journal-append-effect]
   [psi.session-state.state :as session]
   [psi.turn-runtime.recording :as turn-recording]))

(defn extract-tool-calls
  [assistant-msg]
  (turn-recording/extract-tool-calls assistant-msg))

(defn classify-assistant-message
  [assistant-msg]
  (turn-recording/classify-assistant-message assistant-msg))

(defn build-record-response
  "Build a pure-result for prompt response recording.
   Records bounded summary, appends the assistant journal entry, and returns the
   next prompt lifecycle event so orchestration can remain dispatch-visible."
  [session-id execution-result _progress-queue]
  (let [{:keys [turn-id turn-outcome tool-calls assistant-message next-event classified]}
        (turn-recording/build-recording-decision execution-result)]
    {:root-state-update
     (session/session-update
      session-id
      #(assoc %
              :last-execution-result-summary
              {:turn-id         turn-id
               :turn-outcome    turn-outcome
               :stop-reason     (:execution-result/stop-reason execution-result)
               :tool-call-count (count tool-calls)
               :recorded-at     (java.time.Instant/now)}))
     :effects [(journal-append-effect/append-message-effect session-id assistant-message)]
     :return {:recorded? true
              :turn-id turn-id
              :outcome turn-outcome
              :next-event next-event
              :classified classified
              :assistant-message assistant-message}}))
