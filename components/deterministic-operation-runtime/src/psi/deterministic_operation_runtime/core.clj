(ns psi.deterministic-operation-runtime.core
  "Canonical deterministic-operation runtime boundary.

   Owns invoke execution plus returned-result validation/error shaping.
   Formal deterministic-operation contracts live in
   `psi.deterministic-operation-registry.defs`."
  (:require
   [psi.deterministic-operation-registry.defs :as defs]))

(defn malformed-operation-result-ex
  [operation invocation result]
  (ex-info "Deterministic operation returned malformed result"
           {:type :malformed-operation-result
            :operation-id (:id operation)
            :invocation (dissoc invocation :ctx)
            :result result
            :explanation (defs/explain-operation-result result)}))

(defn- workflow-stop-signal
  [invocation]
  (let [state* (get-in invocation [:ctx :state*])
        run-id (:workflow-run-id invocation)]
    (when (and state* run-id)
      (let [run (get-in @state* [:workflows :runs run-id])]
        (cond
          (nil? run) :removed
          (= :cancelled (:status run)) :cancelled)))))

(defn- workflow-stopped-result
  [operation invocation reason]
  {:status :error
   :reason :workflow-stopped
   :message "Workflow execution stopped before deterministic operation start"
   :details {:operation-id (:id operation)
             :workflow-run-id (:workflow-run-id invocation)
             :step-id (:step-id invocation)
             :stop-reason reason}})

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
  (let [result (if-let [reason (workflow-stop-signal invocation)]
                 (workflow-stopped-result operation invocation reason)
                 (try
                   ((:handler operation) (assoc invocation :operation-id (:id operation)))
                   (catch Throwable t
                     {:status :error
                      :reason :operation-threw
                      :message (or (ex-message t) (str t))
                      :details {:operation-id (:id operation)}})))]
    (when-not (defs/valid-operation-result? result)
      (throw (malformed-operation-result-ex operation invocation result)))
    result))
