(ns psi.workflow-runtime.turn-execution-contract
  "Lower bounded turn-execution contract for workflow callers.

   Owns execution of one already-shaped session-backed prompt turn and returns a
   canonical bounded result directly, without requiring workflow callers to
   depend on higher `psi.agent-session.turn` orchestration helpers or recover
   semantic results from journals/transcripts."
  (:require
   [clojure.string :as str]
   [psi.turn-runtime.recording :as turn-recording]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]))

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

(defn- fallback-worthy-execution-message?
  [message]
  (let [text (some-> message str/lower-case)]
    (boolean
     (and (seq text)
          (or (str/includes? text "connection refused")
              (str/includes? text "connection reset")
              (str/includes? text "connection timed out")
              (str/includes? text "timed out")
              (str/includes? text "unreachable")
              (str/includes? text "refused")
              (str/includes? text "transport")
              (str/includes? text "provider execution failure")
              (str/includes? text "premature end of chunk")
              (str/includes? text "failed to connect"))))))

(defn fallback-worthy-execution-failure?
  [assistant-message]
  (let [{:keys [turn/outcome]} (turn-recording/classify-assistant-message assistant-message)]
    (and (= :turn.outcome/error outcome)
         (fallback-worthy-execution-message? (assistant-error-message assistant-message)))))

(defn execution-failure-payload
  [execution-session-id assistant-message]
  (let [{:keys [turn/outcome]} (turn-recording/classify-assistant-message assistant-message)]
    (cond-> {:message (assistant-error-message assistant-message)}
      (:stop-reason assistant-message)
      (assoc :stop-reason (:stop-reason assistant-message))

      (= :turn.outcome/error outcome)
      (assoc :turn-outcome outcome)

      (fallback-worthy-execution-failure? assistant-message)
      (assoc :reason :provider-unavailable
             :fallback-worthy? true)

      execution-session-id
      (assoc :session-id execution-session-id))))

(defn- workflow-session-stop-signal
  [ctx session-id]
  (when-let [state* (:state* ctx)]
    (let [session-data (when-let [get-session-data (:get-session-data (execution-adapter/adapter ctx))]
                         (get-session-data ctx session-id))
          run-id (:workflow-run-id session-data)
          run (when run-id (get-in @state* [:workflows :runs run-id]))]
      (when (and (:workflow-owned? session-data) run-id)
        (cond
          (nil? run) :removed
          (= :cancelled (:status run)) :cancelled)))))

(defn- stopped-execution-result
  [session-id reason]
  {:execution-result/session-id session-id
   :execution-result/assistant-message {:role "assistant"
                                        :content [{:type :error
                                                   :text "Workflow execution stopped before turn start"}]
                                        :stop-reason :error
                                        :error-message "Workflow execution stopped before turn start"
                                        :workflow-stop-reason reason}
   :execution-result/turn-outcome :turn.outcome/error
   :execution-result/tool-calls []
   :execution-result/error-message "Workflow execution stopped before turn start"
   :execution-result/stop-reason :error})

(defn- prompt-execution-result
  [ctx session-id text images opts]
  (if-let [reason (workflow-session-stop-signal ctx session-id)]
    (stopped-execution-result session-id reason)
    (execution-adapter/prompt-execution-result! ctx session-id text images opts)))

(defn execute-session-turn!
  "Execute one bounded prompt turn for `session-id` using already-shaped prompt
   inputs.

   Returns a canonical bounded result map:
   - success: {:status :ok ... :assistant-message :assistant-text :execution-result :structured-output}
   - failure: {:status :error ... :assistant-message :assistant-text :execution-result :structured-output :failure}"
  ([ctx session-id text]
   (execute-session-turn! ctx session-id text nil nil))
  ([ctx session-id text images]
   (execute-session-turn! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (let [execution-result (prompt-execution-result ctx session-id text images opts)
         assistant-message (:execution-result/assistant-message execution-result)
         assistant-text (assistant-message-text assistant-message)
         structured-output (:execution-result/structured-output execution-result)
         {:keys [turn/outcome]} (turn-recording/classify-assistant-message assistant-message)]
     (cond-> {:status :ok
              :session-id session-id
              :turn-outcome outcome
              :assistant-message assistant-message
              :assistant-text assistant-text
              :execution-result execution-result
              :structured-output structured-output}
       (= :turn.outcome/error outcome)
       (assoc :status :error
              :failure (execution-failure-payload session-id assistant-message))))))

(defn execute-actor-turn!
  "Intent-named semantic alias for workflow actor-step callers.
   Accepts optional provider-neutral turn options as a fourth argument."
  ([ctx session-id prompt]
   (execute-session-turn! ctx session-id prompt))
  ([ctx session-id prompt opts]
   (execute-session-turn! ctx session-id prompt nil opts)))

(defn execute-judge-turn!
  "Intent-named semantic alias for workflow judge callers.
   Accepts optional provider-neutral turn options as a fourth argument."
  ([ctx session-id prompt]
   (execute-session-turn! ctx session-id prompt))
  ([ctx session-id prompt opts]
   (execute-session-turn! ctx session-id prompt nil opts)))
