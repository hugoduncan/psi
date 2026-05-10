(ns psi.workflow-runtime.statechart-runtime.step-execution
  (:require
   [psi.deterministic-operation-registry.defs :as deterministic-op-defs]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.ir :as workflow-ir]
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
        args (workflow-source-resolution/resolve-invoke-args workflow-run (:args invoke-spec))
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

(defn execute-session-step!
  [ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt]
  (let [{:keys [status assistant-text failure]}
        (turn-execution/execute-actor-turn! ctx (:session-id execution-session) prompt)]
    (if (= :error status)
      (do
        (swap! working-memory* assoc :pending-actor-result {:kind :failure
                                                            :payload failure
                                                            :step-id step-id
                                                            :attempt-id attempt-id
                                                            :updated-at (state/now)})
        (queue/enqueue-event! event-queue* working-memory* :actor/failed {}))
      (let [normalized-outputs (workflow-ir/step-output-surfaces
                                step-def
                                {:outcome :ok
                                 :outputs {:final-llm-reply assistant-text
                                           :text assistant-text}})
            envelope {:outcome :ok
                      :outputs (merge {:text assistant-text}
                                      normalized-outputs)}]
        (swap! working-memory* assoc :pending-actor-result {:kind (if (= :blocked (:outcome envelope)) :blocked :success)
                                                            :payload envelope
                                                            :step-id step-id
                                                            :attempt-id attempt-id
                                                            :updated-at (state/now)})
        (queue/enqueue-event! event-queue* working-memory*
                              (if (= :blocked (:outcome envelope)) :actor/blocked :actor/done)
                              {})))))

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
