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
           execute-judge!]}]
  {:create-child-session! create-child-session!
   :prompt-execution-result! prompt-execution-result!
   :get-session-data get-session-data
   :list-context-sessions list-context-sessions
   :find-skill find-skill
   :execute-judge! execute-judge!})

(defn adapter
  [ctx]
  (or (get ctx adapter-key)
      (throw (ex-info "Workflow execution adapter is required"
                      {:adapter-key adapter-key}))))

(defn create-child-session!
  [ctx parent-session-id opts]
  ((:create-child-session! (adapter ctx)) ctx parent-session-id opts))

(defn prompt-execution-result!
  ([ctx session-id text]
   (prompt-execution-result! ctx session-id text nil nil))
  ([ctx session-id text images]
   (prompt-execution-result! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (let [f (:prompt-execution-result! (adapter ctx))]
     (cond
       (some? opts) (f ctx session-id text images opts)
       (some? images) (f ctx session-id text images)
       :else (f ctx session-id text)))))

(defn get-session-data
  [ctx session-id]
  ((:get-session-data (adapter ctx)) ctx session-id))

(defn list-context-sessions
  [ctx]
  ((:list-context-sessions (adapter ctx)) ctx))

(defn find-skill
  [ctx skills skill-name]
  ((:find-skill (adapter ctx)) skills skill-name))

(defn execute-judge!
  [ctx parent-session-id actor-session-id judge-spec routing-table routing-context]
  ((:execute-judge! (adapter ctx))
   ctx parent-session-id actor-session-id judge-spec routing-table routing-context))
