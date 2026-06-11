(ns psi.workflow-runtime.execution-adapter
  "Named workflow-runtime ↔ session-owned execution seam.

   Lower workflow runtime code depends on this explicit adapter surface for the
   small set of higher/session-bound operations it must cross into during
   workflow execution.")

(def adapter-key :workflow-execution-adapter)

(defn create
  [{:keys [create-child-session!
           prompt-execution-result!
           get-session-data
           list-context-sessions
           find-skill
           set-session-model!
           execute-judge!
           abort-session!]}]
  {:create-child-session! create-child-session!
   :prompt-execution-result! prompt-execution-result!
   :get-session-data get-session-data
   :list-context-sessions list-context-sessions
   :find-skill find-skill
   :set-session-model! set-session-model!
   :execute-judge! execute-judge!
   :abort-session! abort-session!})

(defn adapter
  [ctx]
  (or (get ctx adapter-key)
      (throw (ex-info "Workflow execution adapter is required"
                      {:adapter-key adapter-key}))))

(defn- required-op
  [ctx op-key]
  (or (get (adapter ctx) op-key)
      (throw (ex-info "Workflow execution adapter operation is required"
                      {:adapter-key adapter-key
                       :operation op-key}))))

(defn create-child-session!
  [ctx parent-session-id opts]
  ((required-op ctx :create-child-session!) ctx parent-session-id opts))

(defn prompt-execution-result!
  ([ctx session-id text]
   (prompt-execution-result! ctx session-id text nil nil))
  ([ctx session-id text images]
   (prompt-execution-result! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (let [f (required-op ctx :prompt-execution-result!)]
     (cond
       (some? opts) (f ctx session-id text images opts)
       (some? images) (f ctx session-id text images)
       :else (f ctx session-id text)))))

(defn get-session-data
  [ctx session-id]
  ((required-op ctx :get-session-data) ctx session-id))

(defn list-context-sessions
  [ctx]
  ((required-op ctx :list-context-sessions) ctx))

(defn find-skill
  [ctx skills skill-name]
  ((required-op ctx :find-skill) ctx skills skill-name))

(defn set-session-model!
  ([ctx session-id model]
   (set-session-model! ctx session-id model nil))
  ([ctx session-id model scope]
   ((required-op ctx :set-session-model!) ctx session-id model scope)))

(defn execute-judge!
  [ctx parent-session-id actor-session-id judge-spec routing-table routing-context]
  ((required-op ctx :execute-judge!)
   ctx parent-session-id actor-session-id judge-spec routing-table routing-context))

(defn abort-session!
  [ctx session-id]
  ((required-op ctx :abort-session!) ctx session-id))
