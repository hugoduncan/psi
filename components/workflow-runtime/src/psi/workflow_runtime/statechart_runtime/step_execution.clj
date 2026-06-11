(ns psi.workflow-runtime.statechart-runtime.step-execution
  (:require
   [psi.deterministic-operation-registry.defs :as deterministic-op-defs]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.attempts :as attempts]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.structured-output :as structured-output]
   [psi.workflow-runtime.statechart-runtime.queue :as queue]
   [psi.workflow-runtime.statechart-runtime.state :as state]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

(def assistant-message-text
  turn-execution/assistant-message-text)

(defn operation-result->invoke-step-result
  [operation-result]
  (when-not (deterministic-op-defs/valid-operation-result? operation-result)
    (throw (ex-info "Cannot wrap malformed deterministic operation result"
                    {:type :malformed-operation-result
                     :result operation-result
                     :explanation (deterministic-op-defs/explain-operation-result operation-result)})))
  (case (:status operation-result)
    :ok
    {:kind :accepted-result
     :accepted-result {:outcome :ok
                       :outputs (cond-> {:data (:data operation-result)
                                         :result operation-result}
                                  (contains? operation-result :summary)
                                  (assoc :summary (:summary operation-result)))}}

    :error
    {:kind :execution-error
     :execution-error (cond-> {:reason (:reason operation-result)
                               :message (:message operation-result)
                               :operation-result operation-result}
                        (:details operation-result)
                        (assoc :operation-details (:details operation-result)))}

    (throw (ex-info "Unknown deterministic operation result status"
                    {:result operation-result}))))

(defn invoke-step-runtime-result
  [ctx parent-session-id run-id step-id step-def workflow-run]
  (let [invoke-spec (or (:invoke step-def)
                        (get-in step-def [:judge :invoke]))
        args (workflow-source-resolution/resolve-invoke-args workflow-run step-id (:args invoke-spec))
        operation-result (deterministic-op-registry/invoke-operation-in
                          (:deterministic-operation-registry ctx)
                          (:operation invoke-spec)
                          {:ctx ctx
                           :parent-session-id parent-session-id
                           :workflow-run-id run-id
                           :step-id step-id
                           :args args}
                          deterministic-op-runtime/invoke-operation)]
    {:effective-args args
     :operation-result operation-result}))

(defn apply-invoke-step-result
  [{:keys [effective-args operation-result]}]
  (let [{:keys [kind accepted-result execution-error]} (operation-result->invoke-step-result operation-result)]
    (case kind
      :accepted-result {:attempt-data {:effective-args effective-args}
                        :pending-kind :success
                        :payload accepted-result}
      :execution-error {:attempt-data {:effective-args effective-args}
                        :pending-kind :failure
                        :payload execution-error})))

(defn- fallback-candidates
  [execution-session]
  (get-in execution-session [:model-fallback :candidates]))

(defn- fallback-enabled?
  [execution-session]
  (and (= :ranked-model-candidates (get-in execution-session [:model-fallback :type]))
       (contains? execution-session :model-fallback)))

(defn- candidate-failure
  [model failure]
  {:model model
   :failure failure})

(defn- exhaustion-failure
  [candidate-failures]
  {:reason :ranked-candidate-exhausted
   :message "Workflow model-query candidates exhausted"
   :candidate-failures candidate-failures})

(defn- execute-with-ranked-fallback!
  [ctx execution-session prompt opts]
  (let [initial-candidates (vec (fallback-candidates execution-session))]
    (if-not (seq initial-candidates)
      {:status :error
       :session-id (:session-id execution-session)
       :assistant-message nil
       :assistant-text ""
       :execution-result nil
       :failure (exhaustion-failure [])}
      (loop [remaining initial-candidates
             candidate-failures []
             current-session execution-session
             first-candidate? true]
        (let [model (first remaining)
              current-session (if first-candidate?
                                (assoc current-session :model model)
                                (attempts/set-execution-session-model! ctx current-session model))
              result (if opts
                       (turn-execution/execute-actor-turn! ctx (:session-id current-session) prompt opts)
                       (turn-execution/execute-actor-turn! ctx (:session-id current-session) prompt))]
          (cond
            (= :ok (:status result))
            result

            (and (next remaining)
                 (get-in result [:failure :fallback-worthy?]))
            (recur (next remaining)
                   (conj candidate-failures (candidate-failure model (:failure result)))
                   current-session
                   false)

            :else
            (let [all-failures (conj candidate-failures (candidate-failure model (:failure result)))]
              {:status :error
               :session-id (:session-id current-session)
               :assistant-message (:assistant-message result)
               :assistant-text (:assistant-text result)
               :execution-result (:execution-result result)
               :structured-output (:structured-output result)
               :failure (if (get-in result [:failure :fallback-worthy?])
                          (exhaustion-failure all-failures)
                          (:failure result))})))))))

(defn- structured-output-blocked-payload
  [reason message details outputs]
  {:outcome :blocked
   :blocked {:reason reason
             :message message
             :details details}
   :outputs outputs})

(defn- record-actor-pending!
  [working-memory* event-queue* step-id attempt-id kind payload event]
  (swap! working-memory* assoc :pending-actor-result {:kind kind
                                                      :payload payload
                                                      :step-id step-id
                                                      :attempt-id attempt-id
                                                      :updated-at (state/now)})
  (queue/enqueue-event! event-queue* working-memory* event {}))

(defn execute-session-step!
  ([ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt]
   (execute-session-step! ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt nil))
  ([ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt stopped?]
   (let [stopped? (or stopped? (constantly false))
         structured-entry (structured-output/single-structured-output-entry (:outputs step-def))
         request-result (when-let [[output-key output-spec] structured-entry]
                          (structured-output/structured-output-request output-key output-spec))]
     (cond
       (stopped?)
       (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

       (false? (:ok? request-result))
       (record-actor-pending!
        working-memory* event-queue* step-id attempt-id :blocked
        (structured-output-blocked-payload (:reason request-result)
                                           (:message request-result)
                                           (:details request-result)
                                           {})
        :actor/blocked)

       :else
       (let [turn-opts (:opts request-result)
             {:keys [status assistant-text failure execution-result assistant-message structured-output]}
             (if (fallback-enabled? execution-session)
               (execute-with-ranked-fallback! ctx execution-session prompt turn-opts)
               (if turn-opts
                 (turn-execution/execute-actor-turn! ctx (:session-id execution-session) prompt turn-opts)
                 (turn-execution/execute-actor-turn! ctx (:session-id execution-session) prompt)))]
         (cond
           (stopped?)
           (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})

           (= :error status)
           (if (= :unsupported-structured-output (or (get-in structured-output [:reason])
                                                     (:reason failure)))
             (record-actor-pending!
              working-memory* event-queue* step-id attempt-id :blocked
              (structured-output-blocked-payload :unsupported-structured-output
                                                 (or (:message failure)
                                                     "Workflow structured output is not supported by the resolved model")
                                                 {:output-key (first structured-entry)
                                                  :structured-output structured-output
                                                  :failure failure}
                                                 {})
              :actor/blocked)
             (record-actor-pending!
              working-memory* event-queue* step-id attempt-id :failure failure :actor/failed))

           :else
           (let [logprobs (:execution-result/logprobs execution-result)
                 raw-outputs {:final-llm-reply assistant-text
                              :text assistant-text
                              :transcript (when assistant-message [assistant-message])
                              :logprobs logprobs
                              :session-id (:session-id execution-session)}
                 raw-outputs (if-let [[output-key output-spec] structured-entry]
                               (assoc raw-outputs output-key
                                      (if (some? structured-output)
                                        (structured-output/output-result output-spec assistant-text structured-output)
                                        (structured-output/missing-ai-structured-output-result output-spec assistant-text)))
                               raw-outputs)
                 structured-result (some-> structured-entry first raw-outputs)
                 invalid-structured-output? (and structured-entry
                                                 (not (structured-output/valid-output-result? structured-result)))
                 normalized-outputs (when-not invalid-structured-output?
                                      (workflow-ir/step-output-surfaces
                                       step-def
                                       {:outcome :ok
                                        :outputs raw-outputs}))
                 envelope (if invalid-structured-output?
                            {:outcome :blocked
                             :blocked {:reason :invalid-structured-output
                                       :message "Workflow structured output failed validation"
                                       :details {:output-key (first structured-entry)
                                                 :structured-output (:structured-output structured-result)}}
                             :outputs raw-outputs}
                            {:outcome :ok
                             :outputs (merge normalized-outputs raw-outputs)})]
             (if (stopped?)
               (queue/enqueue-event! event-queue* working-memory* :workflow/cancel {})
               (record-actor-pending!
                working-memory* event-queue* step-id attempt-id
                (if (= :blocked (:outcome envelope)) :blocked :success)
                envelope
                (if (= :blocked (:outcome envelope)) :actor/blocked :actor/done))))))))))
(defn execute-actor-step!
  [ctx parent-session-id run-id step-id step-def workflow-run execution-session attempt-id working-memory* event-queue* prompt]
  (try
    (cond
      (= :invoke (:type step-def))
      (let [invoke-result (invoke-step-runtime-result ctx parent-session-id run-id step-id step-def workflow-run)
            {:keys [attempt-data pending-kind payload]} (apply-invoke-step-result invoke-result)]
        {:attempt-data attempt-data
         :pending-kind pending-kind
         :payload payload})

      :else
      (do
        (execute-session-step! ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt)
        nil))
    (catch Exception e
      {:pending-kind :failure
       :payload {:message (ex-message e)}})))
