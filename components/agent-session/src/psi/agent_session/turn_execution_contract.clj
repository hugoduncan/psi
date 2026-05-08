(ns psi.agent-session.turn-execution-contract
  "Lower bounded turn-execution contract for workflow callers.

   Owns execution of one already-shaped session-backed prompt turn and returns a
   canonical bounded result directly, without requiring workflow callers to
   depend on higher `psi.agent-session.turn` orchestration helpers or recover
   semantic results from journals/transcripts."
  (:require
   [clojure.string :as str]
   [psi.agent-session.turn :as turn]
   [psi.turn-runtime.recording :as turn-recording]))

(defn assistant-message-text
  [assistant-message]
  (or (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      (when (string? (:content assistant-message))
        (:content assistant-message))
      ""))

(defn assistant-error-message
  [assistant-message]
  (or (:error-message assistant-message)
      (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :error (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      "Assistant turn ended in error"))

(defn execution-failure-payload
  [execution-session-id assistant-message]
  (let [{:keys [turn/outcome]} (turn-recording/classify-assistant-message assistant-message)]
    (cond-> {:message (assistant-error-message assistant-message)}
      (:stop-reason assistant-message)
      (assoc :stop-reason (:stop-reason assistant-message))

      (= :turn.outcome/error outcome)
      (assoc :turn-outcome outcome)

      execution-session-id
      (assoc :session-id execution-session-id))))

(defn- prompt-execution-result
  [ctx session-id text images opts]
  (cond
    (some? opts) (turn/prompt-execution-result-in! ctx session-id text images opts)
    (some? images) (turn/prompt-execution-result-in! ctx session-id text images)
    :else (turn/prompt-execution-result-in! ctx session-id text)))

(defn execute-session-turn!
  "Execute one bounded prompt turn for `session-id` using already-shaped prompt
   inputs.

   Returns a canonical bounded result map:
   - success: {:status :ok ... :assistant-message :assistant-text :execution-result}
   - failure: {:status :error ... :assistant-message :assistant-text :execution-result :failure}"
  ([ctx session-id text]
   (execute-session-turn! ctx session-id text nil nil))
  ([ctx session-id text images]
   (execute-session-turn! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (let [execution-result (prompt-execution-result ctx session-id text images opts)
         assistant-message (:execution-result/assistant-message execution-result)
         assistant-text (assistant-message-text assistant-message)
         {:keys [turn/outcome]} (turn-recording/classify-assistant-message assistant-message)]
     (cond-> {:status :ok
              :session-id session-id
              :turn-outcome outcome
              :assistant-message assistant-message
              :assistant-text assistant-text
              :execution-result execution-result}
       (= :turn.outcome/error outcome)
       (assoc :status :error
              :failure (execution-failure-payload session-id assistant-message))))))

(defn execute-actor-turn!
  "Intent-named semantic alias for workflow actor-step callers.
   Kept distinct from `execute-judge-turn!` so actor/judge callers share one
   bounded contract today while retaining a stable place for caller-specific
   divergence later if needed."
  [ctx session-id prompt]
  (execute-session-turn! ctx session-id prompt))

(defn execute-judge-turn!
  "Intent-named semantic alias for workflow judge callers.
   Kept distinct from `execute-actor-turn!` so actor/judge callers share one
   bounded contract today while retaining a stable place for caller-specific
   divergence later if needed."
  [ctx session-id prompt]
  (execute-session-turn! ctx session-id prompt))
