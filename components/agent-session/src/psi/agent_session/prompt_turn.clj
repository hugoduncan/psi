(ns psi.agent-session.prompt-turn
  "Prompt turn orchestration.

   Owns recursive tool-use progression while delegating canonical request
   preparation, live turn execution, and assistant-message journaling to the
   prompt runtime path."
  (:require
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]
   [psi.agent-session.turn :as turn]
   [psi.session-state.state :as session]
   [psi.turn-runtime.augmentation :as turn-augmentation]
   [psi.turn-runtime.recording :as turn-recording]))

(defn- seed-no-op-augmentation-record!
  "Seed a well-formed no-op terminal augmentation record for a direct-loop
   turn-id that never passed through the dispatch-level pre-turn augmentation
   barrier. Request preparation fails closed on a missing record, so this keeps
   the direct agent-loop path (and its self-generated turn-ids) preparable while
   leaving the dispatch/barrier path untouched."
  [ctx session-id turn-id]
  (session/assoc-state-value-in!
   ctx
   (conj (session/session-data-path session-id) :turn-augmentations turn-id)
   (turn-augmentation/terminal-record session-id turn-id nil [])))

(defn stream-turn!
  "Stream one LLM response into agent-core via the per-turn statechart.
   Blocks until the statechart reaches :done or :error.
   Stores turn context in canonical state for nREPL introspection."
  [ai-ctx ctx session-id ai-model extra-ai-options progress-queue]
  (let [turn-id (str (java.util.UUID/randomUUID))]
    (seed-no-op-augmentation-record! ctx session-id turn-id)
    (:execution-result/assistant-message
     (turn/execute-prepared-request-and-journal!
      ai-ctx ctx session-id
      (prompt-request/build-prepared-request
       ctx session-id
       {:turn-id       turn-id
        :user-message  nil
        :runtime-opts  extra-ai-options
        :runtime-model ai-model})
      progress-queue))))

(defn run-turn-loop!
  [ai-ctx ctx session-id ai-model extra-ai-options progress-queue]
  (let [assistant-message (stream-turn! ai-ctx ctx session-id ai-model
                                        extra-ai-options progress-queue)
        outcome           (turn-recording/classify-assistant-message assistant-message)]
    (case (:turn/outcome outcome)
      :turn.outcome/tool-use
      (do (tool-runtime-adapter/run-tool-calls! ctx session-id (:tool-calls outcome) progress-queue)
          (run-turn-loop! ai-ctx ctx session-id ai-model
                          extra-ai-options progress-queue))

      assistant-message)))

