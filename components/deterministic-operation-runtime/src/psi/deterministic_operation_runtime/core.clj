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

(defn- latest-attempt-index
  [attempts]
  (when (seq attempts)
    (dec (count attempts))))

(defn- workflow-operation-attempt-path
  [{:keys [workflow-run-id step-id]}]
  [:workflows :runs workflow-run-id :step-runs step-id :attempts])

(defn- reserve-workflow-operation-start-in-state
  [state-map {:keys [workflow-run-id workflow-attempt-id] :as invocation}]
  (let [run (get-in state-map [:workflows :runs workflow-run-id])
        attempt-path (workflow-operation-attempt-path invocation)
        attempts (get-in state-map attempt-path)
        latest-idx (latest-attempt-index attempts)
        latest-attempt (when latest-idx (nth attempts latest-idx))]
    (cond
      (nil? run)
      {:state state-map :reserved? false :reason :removed}

      (= :cancelled (:status run))
      {:state state-map :reserved? false :reason :cancelled}

      (nil? latest-idx)
      {:state state-map :reserved? false :reason :attempt-missing}

      (and workflow-attempt-id
           (not= workflow-attempt-id (:attempt-id latest-attempt)))
      {:state state-map :reserved? false :reason :attempt-mismatch}

      :else
      {:state (update-in state-map (conj attempt-path latest-idx)
                         (fn [attempt]
                           (assoc attempt
                                  :operation-start-state :reserved
                                  :operation-start-reserved-at (java.time.Instant/now))))
       :reserved? true})))

(defn- commit-workflow-operation-start-in-state
  [state-map {:keys [workflow-run-id workflow-attempt-id] :as invocation}]
  (let [run (get-in state-map [:workflows :runs workflow-run-id])
        attempt-path (workflow-operation-attempt-path invocation)
        attempts (get-in state-map attempt-path)
        latest-idx (latest-attempt-index attempts)
        latest-attempt (when latest-idx (nth attempts latest-idx))]
    (cond
      (nil? run)
      {:state state-map :committed? false :reason :removed}

      (= :cancelled (:status run))
      {:state state-map :committed? false :reason :cancelled}

      (nil? latest-idx)
      {:state state-map :committed? false :reason :attempt-missing}

      (and workflow-attempt-id
           (not= workflow-attempt-id (:attempt-id latest-attempt)))
      {:state state-map :committed? false :reason :attempt-mismatch}

      :else
      {:state (update-in state-map (conj attempt-path latest-idx)
                         (fn [attempt]
                           (-> attempt
                               (assoc :operation-start-state :started
                                      :operation-started-at (java.time.Instant/now))
                               (update :operation-start-count (fnil inc 0)))))
       :committed? true})))

(defn- begin-workflow-operation-call-in-state
  [state-map {:keys [workflow-run-id workflow-attempt-id] :as invocation}]
  (let [run (get-in state-map [:workflows :runs workflow-run-id])
        attempt-path (workflow-operation-attempt-path invocation)
        attempts (get-in state-map attempt-path)
        latest-idx (latest-attempt-index attempts)
        latest-attempt (when latest-idx (nth attempts latest-idx))]
    (cond
      (nil? run)
      {:state state-map :begun? false :reason :removed}

      (= :cancelled (:status run))
      {:state state-map :begun? false :reason :cancelled}

      (nil? latest-idx)
      {:state state-map :begun? false :reason :attempt-missing}

      (and workflow-attempt-id
           (not= workflow-attempt-id (:attempt-id latest-attempt)))
      {:state state-map :begun? false :reason :attempt-mismatch}

      :else
      {:state (update-in state-map (conj attempt-path latest-idx)
                         (fn [attempt]
                           (assoc attempt
                                  :operation-call-state :started
                                  :operation-call-started-at (java.time.Instant/now))))
       :begun? true})))

(defn- workflow-operation-start-required?
  [invocation]
  (and (get-in invocation [:ctx :state*])
       (:workflow-run-id invocation)
       (:step-id invocation)))

(defn- reserve-workflow-operation-start!
  [invocation]
  (if-not (workflow-operation-start-required? invocation)
    {:reserved? true}
    (loop []
      (let [state* (get-in invocation [:ctx :state*])
            state-map @state*
            {:keys [state reserved? reason]} (reserve-workflow-operation-start-in-state state-map invocation)]
        (cond
          (not reserved?) {:reserved? false :reason reason}
          (compare-and-set! state* state-map state) {:reserved? true}
          :else (recur))))))

(defn- commit-workflow-operation-start!
  [invocation]
  (if-not (workflow-operation-start-required? invocation)
    {:committed? true}
    (loop []
      (let [state* (get-in invocation [:ctx :state*])
            state-map @state*
            {:keys [state committed? reason]} (commit-workflow-operation-start-in-state state-map invocation)]
        (cond
          (not committed?) {:committed? false :reason reason}
          (compare-and-set! state* state-map state) {:committed? true}
          :else (recur))))))

(defn- begin-workflow-operation-call!
  [invocation]
  (if-not (workflow-operation-start-required? invocation)
    {:begun? true}
    (loop []
      (let [state* (get-in invocation [:ctx :state*])
            state-map @state*
            {:keys [state begun? reason]} (begin-workflow-operation-call-in-state state-map invocation)]
        (cond
          (not begun?) {:begun? false :reason reason}
          (compare-and-set! state* state-map state) {:begun? true}
          :else (recur))))))

(defn- call-workflow-operation-start-hook!
  [operation invocation phase]
  (when (:workflow-run-id invocation)
    (when-let [f (get-in invocation [:ctx :before-workflow-operation-start-fn])]
      (f (:ctx invocation)
         {:operation-id (:id operation)
          :workflow-run-id (:workflow-run-id invocation)
          :step-id (:step-id invocation)
          :phase phase}))))

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
                   (call-workflow-operation-start-hook! operation invocation :before-reserve)
                   (let [{:keys [reserved? reason]} (reserve-workflow-operation-start! invocation)]
                     (if-not reserved?
                       (workflow-stopped-result operation invocation reason)
                       (do
                         (call-workflow-operation-start-hook! operation invocation :after-reserve)
                         (let [{:keys [committed? reason]} (commit-workflow-operation-start! invocation)]
                           (if-not committed?
                             (workflow-stopped-result operation invocation reason)
                             (do
                               (call-workflow-operation-start-hook! operation invocation :after-commit)
                               (let [{:keys [begun? reason]} (begin-workflow-operation-call! invocation)]
                                 (if-not begun?
                                   (workflow-stopped-result operation invocation reason)
                                   (do
                                     (call-workflow-operation-start-hook! operation invocation :after-call-begin)
                                     (if-let [reason (workflow-stop-signal invocation)]
                                       (workflow-stopped-result operation invocation reason)
                                       ((:handler operation) (assoc invocation :operation-id (:id operation)))))))))))))
                   (catch Throwable t
                     {:status :error
                      :reason :operation-threw
                      :message (or (ex-message t) (str t))
                      :details {:operation-id (:id operation)}})))]
    (when-not (defs/valid-operation-result? result)
      (throw (malformed-operation-result-ex operation invocation result)))
    result))
