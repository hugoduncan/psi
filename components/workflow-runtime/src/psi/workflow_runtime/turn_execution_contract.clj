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

(defn- workflow-session-data
  [ctx session-id]
  (when-let [get-session-data (:get-session-data (execution-adapter/adapter ctx))]
    (get-session-data ctx session-id)))

(defn- workflow-session-stop-signal-for
  [ctx session-data]
  (when-let [state* (:state* ctx)]
    (let [run-id (:workflow-run-id session-data)
          run (when run-id (get-in @state* [:workflows :runs run-id]))]
      (when (and (:workflow-owned? session-data) run-id)
        (cond
          (nil? run) :removed
          (= :cancelled (:status run)) :cancelled)))))

(defn- latest-attempt-index
  [attempts]
  (when (seq attempts)
    (dec (count attempts))))

(defn- reserve-workflow-turn-start-in-state
  [state-map {:keys [workflow-run-id workflow-step-id workflow-attempt-id]}]
  (let [run (get-in state-map [:workflows :runs workflow-run-id])
        attempt-path [:workflows :runs workflow-run-id :step-runs workflow-step-id :attempts]
        attempts (get-in state-map attempt-path)
        latest-idx (latest-attempt-index attempts)
        latest-attempt (when latest-idx (nth attempts latest-idx))]
    (cond
      (nil? run)
      {:state state-map :reserved? false :reason :removed}

      (= :cancelled (:status run))
      {:state state-map :reserved? false :reason :cancelled}

      (not= workflow-attempt-id (:attempt-id latest-attempt))
      {:state state-map :reserved? false :reason :attempt-mismatch}

      :else
      {:state (update-in state-map (conj attempt-path latest-idx)
                         (fn [attempt]
                           (-> attempt
                               (assoc :turn-started-at (java.time.Instant/now))
                               (update :turn-start-count (fnil inc 0)))))
       :reserved? true})))

(defn- reserve-workflow-turn-start!
  [ctx session-data]
  (if-not (and (:workflow-owned? session-data)
               (:workflow-run-id session-data)
               (:workflow-step-id session-data)
               (:workflow-attempt-id session-data)
               (:state* ctx))
    {:reserved? true}
    (loop []
      (let [state* (:state* ctx)
            state-map @state*
            {:keys [state reserved? reason]} (reserve-workflow-turn-start-in-state state-map session-data)]
        (cond
          (not reserved?) {:reserved? false :reason reason}
          (compare-and-set! state* state-map state) {:reserved? true}
          :else (recur))))))

(defn- maybe-call-workflow-turn-start-hook!
  [ctx session-id session-data]
  (when (and (:workflow-owned? session-data)
             (:workflow-run-id session-data))
    (when-let [f (:before-workflow-turn-start-fn ctx)]
      (f ctx session-id {:workflow-run-id (:workflow-run-id session-data)
                         :workflow-step-id (:workflow-step-id session-data)
                         :workflow-attempt-id (:workflow-attempt-id session-data)}))))

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
  (let [session-data (workflow-session-data ctx session-id)]
    (if-let [reason (workflow-session-stop-signal-for ctx session-data)]
      (stopped-execution-result session-id reason)
      (do
        (maybe-call-workflow-turn-start-hook! ctx session-id session-data)
        (let [{:keys [reserved? reason]} (reserve-workflow-turn-start! ctx session-data)]
          (if-not reserved?
            (stopped-execution-result session-id reason)
            (execution-adapter/prompt-execution-result! ctx session-id text images opts)))))))

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
