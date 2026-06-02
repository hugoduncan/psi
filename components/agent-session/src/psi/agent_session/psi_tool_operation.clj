(ns psi.agent-session.psi-tool-operation
  "Operation action handler for psi-tool: list and invoke deterministic operations.

   Thin adapter over the shared `deterministic-operation-action` mechanism; owns
   only the structured `:psi-tool/...` rendering."
  (:require
   [psi.agent-session.deterministic-operation-action :as op-action]))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   {:message (or (ex-message e) (str e))
    :class   (.getName (class e))
    :phase   (or (:phase (ex-data e)) default-phase :execute)
    :data    (ex-data e)}))

(defn- operation-error-report
  [op kind e]
  {:psi-tool/action :operation
   :psi-tool/operation-op (some-> op keyword)
   :psi-tool/overall-status :error
   :psi-tool/error (cond-> (assoc (psi-tool-error-summary :operation e) :kind kind)
                     (:operation-id (ex-data e))
                     (assoc :operation-id (:operation-id (ex-data e))))})

(defn- propagated-operation-error
  "Render the two ex-info types that propagate from the runtime boundary, plus
   this handler's own validate-phase guards, into a distinct `:psi-tool/error`
   summary (D3). Re-throws any other throwable (already canonicalized to an
   `:error` tagged result by the runtime)."
  [op e]
  (case (:type (ex-data e))
    :missing-deterministic-operation (operation-error-report op :missing-operation e)
    :malformed-operation-result      (operation-error-report op :malformed-result e)
    (if (= :validate (:phase (ex-data e)))
      (operation-error-report op :validate e)
      (throw e))))

(defn execute-psi-tool-operation-report
  [{:keys [ctx session-id]} {:keys [op operation-id args]}]
  (let [started-at (System/nanoTime)
        result
        (try
          (when-not ctx
            (throw (ex-info "psi-tool operation action requires live runtime ctx"
                            {:phase :validate :action "operation" :op op})))
          (case op
            "list"
            {:psi-tool/action :operation
             :psi-tool/operation-op :list
             :psi-tool/overall-status :ok
             :psi-tool/operations (op-action/list-operations ctx)}

            "invoke"
            (let [tagged (op-action/invoke-operation ctx session-id operation-id args)]
              {:psi-tool/action :operation
               :psi-tool/operation-op :invoke
               :psi-tool/overall-status (:status tagged)
               :psi-tool/result (op-action/project-result tagged)}))
          (catch clojure.lang.ExceptionInfo e
            (propagated-operation-error op e)))]
    (assoc result
           :psi-tool/duration-ms
           (long (/ (- (System/nanoTime) started-at) 1000000)))))
