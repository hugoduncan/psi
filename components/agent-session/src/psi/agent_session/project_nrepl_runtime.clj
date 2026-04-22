(ns psi.agent-session.project-nrepl-runtime
  "Runtime-owned managed project nREPL registry keyed by canonical worktree path."
  (:require
   [psi.agent-session.project-nrepl-config :as project-nrepl-config])
  (:import
   (java.util UUID)))

(def ^:private lifecycle-states
  #{:absent :starting :ready :busy :stopping :failed})

(defn create-registry
  []
  {:state (atom {:instances {}})})

(defn- now []
  (java.time.Instant/now))

(defn- active-instance?
  [instance]
  (contains? #{:starting :ready :busy :stopping} (:lifecycle-state instance)))

(defn- assert-valid-lifecycle-state!
  [lifecycle-state]
  (when-not (contains? lifecycle-states lifecycle-state)
    (throw (ex-info "Invalid project nREPL lifecycle state"
                    {:lifecycle-state lifecycle-state
                     :allowed lifecycle-states})))
  lifecycle-state)

(defn- build-instance
  [{:keys [worktree-path acquisition-mode lifecycle-state endpoint command-vector runtime-handle] :or {lifecycle-state :starting}}]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        lifecycle-state    (assert-valid-lifecycle-state! lifecycle-state)]
    {:id                (str (UUID/randomUUID))
     :worktree-path     effective-worktree
     :acquisition-mode  acquisition-mode
     :transport-kind    :nrepl
     :lifecycle-state   lifecycle-state
     :readiness         (contains? #{:ready :busy} lifecycle-state)
     :endpoint          endpoint
     :command-vector    command-vector
     :runtime-handle    runtime-handle
     :session-mode      :single
     :active-session-id nil
     :last-error        nil
     :started-at        (now)
     :updated-at        (now)}))

(defn instance-in
  [ctx worktree-path]
  (get-in @(:state (:project-nrepl-registry ctx)) [:instances worktree-path]))

(defn instance-count-in
  [ctx]
  (count (get-in @(:state (:project-nrepl-registry ctx)) [:instances])))

(defn instance-worktree-paths-in
  [ctx]
  (keys (get-in @(:state (:project-nrepl-registry ctx)) [:instances])))

(defn instances-in
  [ctx]
  (vals (get-in @(:state (:project-nrepl-registry ctx)) [:instances])))

(defn ensure-instance-in!
  "Ensure a managed project nREPL instance slot exists for a worktree.

   Returns the existing active instance when the acquisition request matches.
   Throws on conflicting acquisition attempts unless the caller uses
   `replace-instance-in!` explicitly."
  [ctx {:keys [worktree-path acquisition-mode endpoint command-vector] :as opts}]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        state              (:state (:project-nrepl-registry ctx))
        existing           (get-in @state [:instances effective-worktree])]
    (cond
      (and existing
           (active-instance? existing)
           (= acquisition-mode (:acquisition-mode existing))
           (= endpoint (:endpoint existing))
           (= command-vector (:command-vector existing)))
      existing

      (and existing (active-instance? existing))
      (throw (ex-info "Conflicting project nREPL acquisition requires explicit replace"
                      {:phase :ensure-instance
                       :worktree-path effective-worktree
                       :existing {:acquisition-mode (:acquisition-mode existing)
                                  :endpoint (:endpoint existing)
                                  :command-vector (:command-vector existing)
                                  :lifecycle-state (:lifecycle-state existing)}
                       :requested (select-keys opts [:acquisition-mode :endpoint :command-vector])}))

      :else
      (let [instance (build-instance (assoc opts :worktree-path effective-worktree))]
        (swap! state assoc-in [:instances effective-worktree] instance)
        (instance-in ctx effective-worktree)))))

(defn replace-instance-in!
  "Intentionally replace the managed project nREPL instance slot for a worktree."
  [ctx {:keys [worktree-path] :as opts}]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        state              (:state (:project-nrepl-registry ctx))
        prior              (get-in @state [:instances effective-worktree])
        instance           (-> (build-instance (assoc opts :worktree-path effective-worktree))
                               (assoc :replaced-instance-id (:id prior)))]
    (swap! state assoc-in [:instances effective-worktree] instance)
    (instance-in ctx effective-worktree)))

(defn update-instance-in!
  [ctx worktree-path f & args]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        state              (:state (:project-nrepl-registry ctx))]
    (swap! state update-in [:instances effective-worktree]
           (fn [instance]
             (when-not instance
               (throw (ex-info "Managed project nREPL instance not found"
                               {:phase :update-instance
                                :worktree-path effective-worktree})))
             (-> (apply f instance args)
                 (assoc :updated-at (now)
                        :worktree-path effective-worktree))))
    (instance-in ctx effective-worktree)))

(defn remove-instance-in!
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        state              (:state (:project-nrepl-registry ctx))
        prior              (get-in @state [:instances effective-worktree])]
    (swap! state update :instances dissoc effective-worktree)
    prior))
