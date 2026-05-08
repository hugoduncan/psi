(ns psi.agent-session.deterministic-operations
  "Canonical deterministic-operation boundary for workflow IR `:type :invoke`.

   Owns runtime invoke execution and workflow-facing invoke-step result wrapping.
   Formal deterministic-operation contracts live in
   `psi.deterministic-operation-registry.defs`."
  (:require
   [psi.deterministic-operation-registry.defs :as defs]))

(def operation-success-result-schema defs/operation-success-result-schema)
(def operation-error-result-schema defs/operation-error-result-schema)
(def operation-result-schema defs/operation-result-schema)
(def valid-operation-id? defs/valid-operation-id?)
(def operation-id-pattern defs/operation-id-pattern)
(def operation-definition-schema defs/operation-definition-schema)
(def valid-operation-definition? defs/valid-operation-definition?)
(def normalize-operation-def defs/normalize-operation-def)
(def valid-operation-result? defs/valid-operation-result?)
(def explain-operation-result defs/explain-operation-result)

(defn malformed-operation-result-ex
  [operation invocation result]
  (ex-info "Deterministic operation returned malformed result"
           {:type :malformed-operation-result
            :operation-id (:id operation)
            :invocation (dissoc invocation :ctx)
            :result result
            :explanation (explain-operation-result result)}))

(defn invoke-operation
  "Invoke a normalized deterministic operation.

   Implementations receive one invocation map. Current first-cut keys may include:
   - :operation-id
   - :args
   - :ctx
   - :session-id
   - :workflow-run-id
   - :step-id
   - :parent-session-id

   Implementations must return one tagged operation result:
   - success => {:status :ok :data ... :summary? string :details? map}
   - failure => {:status :error :reason keyword :message string :details? map}

   Thrown exceptions are canonicalized into tagged `:error` results.
   Malformed returned values are rejected with ex-info."
  [operation invocation]
  (let [result (try
                 ((:handler operation) (assoc invocation :operation-id (:id operation)))
                 (catch Throwable t
                   {:status :error
                    :reason :operation-threw
                    :message (or (ex-message t) (str t))
                    :details {:operation-id (:id operation)}}))]
    (when-not (valid-operation-result? result)
      (throw (malformed-operation-result-ex operation invocation result)))
    result))

(defn operation-result->invoke-step-result
  "Wrap a canonical operation result into runtime-owned invoke-step semantics.

   Success becomes an accepted-result envelope with canonical invoke outputs:
   - :data    <- operation :data
   - :summary <- operation :summary when present
   - :result  <- full tagged operation result

   Failure remains attempt-local execution failure input. It does not produce a
   synthetic accepted-result or a separately recorded yielded-value surface."
  [operation-result]
  (when-not (valid-operation-result? operation-result)
    (throw (ex-info "Cannot wrap malformed deterministic operation result"
                    {:type :malformed-operation-result
                     :result operation-result
                     :explanation (explain-operation-result operation-result)})))
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
