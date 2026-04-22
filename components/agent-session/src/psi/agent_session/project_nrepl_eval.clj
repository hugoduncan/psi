(ns psi.agent-session.project-nrepl-eval
  "Eval and interrupt operations for managed project nREPL instances."
  (:require
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime])
  (:import
   (java.util UUID)))

(defn- now []
  (java.time.Instant/now))

(defn- new-operation-id []
  (str (UUID/randomUUID)))

(defn- primary-value
  [v]
  (cond
    (vector? v) (if (= 1 (count v))
                  (first v)
                  v)
    :else v))

(defn- summarize-response
  [responses]
  (let [combine-responses (requiring-resolve 'nrepl.core/combine-responses)
        combined          (combine-responses responses)
        statuses          (set (:status combined))
        outcome           (cond
                            (contains? statuses "interrupted") :interrupted
                            (contains? statuses :interrupted) :interrupted
                            (contains? statuses "eval-error") :error
                            (contains? statuses :eval-error) :error
                            (contains? statuses "done") :success
                            (contains? statuses :done) :success
                            :else :unknown)]
    {:status outcome
     :value  (or (primary-value (:value combined)) (:out combined) (:err combined))
     :out    (:out combined)
     :err    (:err combined)
     :ns     (:ns combined)
     :raw    combined}))

(defn eval-instance-in!
  "Evaluate `code` on the managed single nREPL client session for `worktree-path`.
   Enforces first-slice single-flight semantics per worktree instance."
  [ctx worktree-path code]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        instance           (project-nrepl-runtime/instance-in ctx effective-worktree)
        client-session     (get-in instance [:runtime-handle :client-session])]
    (when-not instance
      (throw (ex-info "Managed project nREPL instance not found"
                      {:phase :eval-instance
                       :worktree-path effective-worktree})))
    (when-not client-session
      (throw (ex-info "Managed project nREPL instance is unavailable for eval"
                      {:phase :eval-instance
                       :worktree-path effective-worktree
                       :lifecycle-state (:lifecycle-state instance)})))
    (when (get-in instance [:runtime-handle :active-op])
      (throw (ex-info "Managed project nREPL eval is already in flight for this worktree"
                      {:phase :eval-instance
                       :worktree-path effective-worktree
                       :active-op (select-keys (get-in instance [:runtime-handle :active-op]) [:op-id :started-at])})))
    (let [op-id     (new-operation-id)
          started-at (now)]
      (project-nrepl-runtime/update-instance-in!
       ctx effective-worktree
       #(-> %
            (assoc :lifecycle-state :busy
                   :readiness true)
            (update :runtime-handle assoc :active-op {:op-id op-id
                                                      :code code
                                                      :started-at started-at})
            (assoc :last-eval {:op-id op-id
                               :code code
                               :started-at started-at
                               :status :running})))
      (try
        (let [responses (doall (client-session {:op "eval" :code code :id op-id}))
              summary   (summarize-response responses)
              finished-at (now)
              result    {:op-id op-id
                         :worktree-path effective-worktree
                         :session-id (or (:active-session-id instance)
                                         (get-in instance [:runtime-handle :session-id]))
                         :input code
                         :started-at started-at
                         :finished-at finished-at
                         :status (:status summary)
                         :value (:value summary)
                         :out (:out summary)
                         :err (:err summary)
                         :summary (:raw summary)}]
          (project-nrepl-runtime/update-instance-in!
           ctx effective-worktree
           #(-> %
                (assoc :lifecycle-state :ready
                       :readiness true
                       :last-eval result)
                (update :runtime-handle dissoc :active-op)))
          result)
        (catch Throwable t
          (let [finished-at (now)
                result      {:op-id op-id
                             :worktree-path effective-worktree
                             :session-id (or (:active-session-id instance)
                                             (get-in instance [:runtime-handle :session-id]))
                             :input code
                             :started-at started-at
                             :finished-at finished-at
                             :status :unavailable
                             :error {:message (.getMessage t)
                                     :data (ex-data t)}}]
            (project-nrepl-runtime/update-instance-in!
             ctx effective-worktree
             #(-> %
                  (assoc :lifecycle-state :ready
                         :readiness true
                         :last-eval result
                         :last-error (:error result))
                  (update :runtime-handle dissoc :active-op)))
            result))))))

(defn interrupt-instance-in!
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        instance           (project-nrepl-runtime/instance-in ctx effective-worktree)]
    (when-not instance
      (throw (ex-info "Managed project nREPL instance not found"
                      {:phase :interrupt-instance
                       :worktree-path effective-worktree})))
    (let [client-session (get-in instance [:runtime-handle :client-session])
          active-op      (get-in instance [:runtime-handle :active-op])]
      (if-not active-op
        {:status :unavailable
         :reason :no-active-eval
         :worktree-path effective-worktree}
        (let [responses (doall (client-session {:op "interrupt"
                                                :interrupt-id (:op-id active-op)
                                                :id (new-operation-id)}))
              summary   (summarize-response responses)
              result    {:status (if (= :interrupted (:status summary)) :interrupted :success)
                         :worktree-path effective-worktree
                         :interrupted-op-id (:op-id active-op)
                         :summary (:raw summary)}]
          (project-nrepl-runtime/update-instance-in!
           ctx effective-worktree
           #(assoc % :last-interrupt result))
          result)))))
