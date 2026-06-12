(ns psi.workflow-runtime.turn-execution-contract
  "Lower bounded turn-execution contract for workflow callers.

   Owns execution of one already-shaped session-backed prompt turn and returns a
   canonical bounded result directly, without requiring workflow callers to
   depend on higher `psi.agent-session.turn` orchestration helpers or recover
   semantic results from journals/transcripts."
  (:require
   [clojure.string :as str]
   [psi.turn-runtime.recording :as turn-recording]
   [psi.workflow-coordination.ordinary-entry :as ordinary-entry]
   [psi.workflow-coordination.stop-signal :as stop-signal]
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

(defn- workflow-session-data
  [ctx session-id]
  (when-let [get-session-data (:get-session-data (execution-adapter/adapter ctx))]
    (get-session-data ctx session-id)))

(defn- workflow-session-stop-signal-for
  [ctx session-data]
  (when (:workflow-owned? session-data)
    (stop-signal/workflow-stop-signal ctx (:workflow-run-id session-data))))

(defn- workflow-turn-start-required?
  [ctx session-data]
  (and (:workflow-owned? session-data)
       (:workflow-run-id session-data)
       (:workflow-step-id session-data)
       (:workflow-attempt-id session-data)
       (:state* ctx)))

(defn- transition-workflow-turn-phase!
  [ctx session-data success-key phase-opts]
  (if-not (workflow-turn-start-required? ctx session-data)
    {success-key true}
    (-> (ordinary-entry/transition-latest-attempt!
         (:state* ctx)
         (merge {:workflow-run-id (:workflow-run-id session-data)
                 :workflow-step-id (:workflow-step-id session-data)
                 :workflow-attempt-id (:workflow-attempt-id session-data)
                 :missing-attempt-reason :attempt-mismatch}
                phase-opts))
        (ordinary-entry/keyed-result success-key))))

(defn- reserve-workflow-turn-start!
  [ctx session-data]
  (transition-workflow-turn-phase!
   ctx session-data :reserved?
   {:phase-key :turn-start-state
    :phase-value :reserved
    :timestamp-key :turn-start-reserved-at}))

(defn- commit-workflow-turn-start!
  [ctx session-data]
  (transition-workflow-turn-phase!
   ctx session-data :committed?
   {:phase-key :turn-start-state
    :phase-value :started
    :timestamp-key :turn-started-at
    :count-key :turn-start-count}))

(defn- begin-workflow-turn-call!
  [ctx session-data]
  (transition-workflow-turn-phase!
   ctx session-data :begun?
   {:phase-key :turn-call-state
    :phase-value :begun
    :timestamp-key :turn-call-begun-at}))

(defn- commit-workflow-turn-call!
  [ctx session-data]
  (transition-workflow-turn-phase!
   ctx session-data :committed?
   {:required-phases [{:key :turn-call-state
                       :value :begun
                       :reason :call-state-mismatch}]
    :phase-key :turn-call-state
    :phase-value :committed
    :timestamp-key :turn-call-committed-at}))

(defn- call-workflow-turn-start-hook!
  [ctx session-id session-data phase]
  (when (and (:workflow-owned? session-data)
             (:workflow-run-id session-data))
    (when-let [f (:before-workflow-turn-start-fn ctx)]
      (f ctx session-id {:workflow-run-id (:workflow-run-id session-data)
                         :workflow-step-id (:workflow-step-id session-data)
                         :workflow-attempt-id (:workflow-attempt-id session-data)
                         :phase phase}))))

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

(defn- prompt-execution-result*
  [ctx session-id text images opts session-data]
  (if-let [reason (workflow-session-stop-signal-for ctx session-data)]
    (stopped-execution-result session-id reason)
    (do
      (call-workflow-turn-start-hook! ctx session-id session-data :before-reserve)
      (let [{:keys [reserved? reason]} (reserve-workflow-turn-start! ctx session-data)]
        (if-not reserved?
          (stopped-execution-result session-id reason)
          (do
            (call-workflow-turn-start-hook! ctx session-id session-data :after-reserve)
            (let [{:keys [committed? reason]} (commit-workflow-turn-start! ctx session-data)]
              (if-not committed?
                (stopped-execution-result session-id reason)
                (do
                  (call-workflow-turn-start-hook! ctx session-id session-data :after-commit)
                  (let [{:keys [begun? reason]} (begin-workflow-turn-call! ctx session-data)]
                    (if-not begun?
                      (stopped-execution-result session-id reason)
                      (do
                        (call-workflow-turn-start-hook! ctx session-id session-data :after-call-begin)
                        (let [{call-committed? :committed? reason :reason}
                              (commit-workflow-turn-call! ctx session-data)]
                          (if-not call-committed?
                            (stopped-execution-result session-id reason)
                            (do
                              (call-workflow-turn-start-hook! ctx session-id session-data :after-call-commit)
                              (if-let [reason (workflow-session-stop-signal-for ctx session-data)]
                                (stopped-execution-result session-id reason)
                                (execution-adapter/prompt-execution-result! ctx session-id text images opts)))))))))))))))))

(defn- prompt-execution-result
  [ctx session-id text images opts]
  (let [session-data (workflow-session-data ctx session-id)]
    (prompt-execution-result* ctx session-id text images opts session-data)))

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
   Accepts optional provider-neutral turn options as a fourth argument.

   Tests may supply `:workflow-execute-actor-turn-fn` on ctx as a nullable
   boundary seam for controlled actor turn execution."
  ([ctx session-id prompt]
   (if-let [f (:workflow-execute-actor-turn-fn ctx)]
     (f ctx session-id prompt)
     (execute-session-turn! ctx session-id prompt)))
  ([ctx session-id prompt opts]
   (if-let [f (:workflow-execute-actor-turn-fn ctx)]
     (f ctx session-id prompt opts)
     (execute-session-turn! ctx session-id prompt nil opts))))

(defn execute-judge-turn!
  "Intent-named semantic alias for workflow judge callers.
   Accepts optional provider-neutral turn options as a fourth argument.

   Tests may supply `:workflow-execute-judge-turn-fn` on ctx as a nullable
   boundary seam for controlled judge turn execution."
  ([ctx session-id prompt]
   (if-let [f (:workflow-execute-judge-turn-fn ctx)]
     (f ctx session-id prompt)
     (execute-session-turn! ctx session-id prompt)))
  ([ctx session-id prompt opts]
   (if-let [f (:workflow-execute-judge-turn-fn ctx)]
     (f ctx session-id prompt opts)
     (execute-session-turn! ctx session-id prompt nil opts))))
