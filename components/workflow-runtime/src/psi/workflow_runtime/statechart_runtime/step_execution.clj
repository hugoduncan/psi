(ns psi.workflow-runtime.statechart-runtime.step-execution
  (:require
   [clojure.string :as str]
   [psi.deterministic-operation-registry.defs :as deterministic-op-defs]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.attempts :as attempts]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.statechart-runtime.queue :as queue]
   [psi.workflow-runtime.statechart-runtime.state :as state]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

(def assistant-message-text
  turn-execution/assistant-message-text)

(def ^:private logprob-uncertain-threshold 0.90)

(defn- format-token-str
  [s]
  (cond
    (= s " ")  "\" \""
    (= s "\n") "\"\\n\""
    (= s "\t") "\"\\t\""
    :else       (str "\"" s "\"")))

(defn- format-prob [logprob]
  (when (some? logprob)
    (format "%.2f" (Math/exp logprob))))

(defn- format-logprob-line
  [{:keys [token logprob top]}]
  (let [prob-str (or (format-prob logprob) "?")
        top-alts (remove #(= (:token %) token) top)
        alts-str (when (seq top-alts)
                   (str "  |  "
                        (str/join " " (map (fn [t]
                                             (str (format-token-str (:token t)) " "
                                                  (or (format-prob (:logprob t)) "?")))
                                           top-alts))))]
    (str "  " (format-token-str token) " " prob-str alts-str)))

(defn- format-logprob-message
  [tokens]
  (let [uncertain (filter (fn [{:keys [logprob]}]
                            (and (some? logprob) (< (Math/exp logprob) logprob-uncertain-threshold)))
                          tokens)
        lines (mapv format-logprob-line uncertain)
        header "[logprob context — previous response]"]
    (if (seq lines)
      (str header "\nUncertain tokens (p < 0.90):\n"
           (str/join "\n" lines)
           "\nAll other tokens: p ≥ 0.90")
      (str header "\nAll tokens p ≥ 0.90"))))

(defn- transcript-with-logprobs
  [assistant-message logprobs]
  (cond-> (when assistant-message [assistant-message])
    (seq logprobs)
    (conj {:role "user"
           :content (format-logprob-message logprobs)})))

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
  [ctx execution-session prompt]
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
              result (turn-execution/execute-actor-turn! ctx (:session-id current-session) prompt)]
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
               :failure (if (get-in result [:failure :fallback-worthy?])
                          (exhaustion-failure all-failures)
                          (:failure result))})))))))

(defn execute-session-step!
  [ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt]
  (let [{:keys [status assistant-text failure execution-result assistant-message]}
        (if (fallback-enabled? execution-session)
          (execute-with-ranked-fallback! ctx execution-session prompt)
          (turn-execution/execute-actor-turn! ctx (:session-id execution-session) prompt))]
    (if (= :error status)
      (do
        (swap! working-memory* assoc :pending-actor-result {:kind :failure
                                                            :payload failure
                                                            :step-id step-id
                                                            :attempt-id attempt-id
                                                            :updated-at (state/now)})
        (queue/enqueue-event! event-queue* working-memory* :actor/failed {}))
      (let [logprobs (:execution-result/logprobs execution-result)
            raw-outputs {:final-llm-reply assistant-text
                         :text assistant-text
                         :transcript (transcript-with-logprobs assistant-message logprobs)
                         :logprobs logprobs}
            normalized-outputs (workflow-ir/step-output-surfaces
                                step-def
                                {:outcome :ok
                                 :outputs raw-outputs})
            envelope {:outcome :ok
                      :outputs (merge raw-outputs normalized-outputs)}]
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
