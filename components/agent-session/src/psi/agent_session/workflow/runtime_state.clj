(ns psi.agent-session.workflow.runtime-state
  "Narrow owner for higher-core built-in workflow runtime state and session-bound
   helper accessors.

   Built-in lifecycle callbacks are stored in `built-in-lifecycle-callbacks`,
   a plain atom mapping event-name → vector of handler fns.  These are invoked
   by `session_lifecycle.clj` directly rather than through extension event
   dispatch.")

(defonce state (atom nil))
(defonce inflight-runs (atom {}))
(defonce built-in-lifecycle-callbacks (atom {}))
(def ^:dynamic *active-workflow-session-id* nil)

(def built-in-workflow-path "built-in:workflow")
(def prompt-contribution-id "workflow-loader-workflows")

(defn query-fn [] (:query-fn @state))

(defn mutate!
  [sym params]
  ((:mutate-fn @state) sym params))

(defn log!
  [msg]
  ((:log-fn @state) msg))

(defn notify!
  [msg level]
  (let [notify-fn (:notify-fn @state)]
    (try
      (notify-fn msg {:role "assistant"
                      :custom-type "workflow-loader"
                      :level level})
      (catch clojure.lang.ArityException _
        (notify-fn msg level)))))

(defn ui-notify!
  [msg level]
  (if-let [notify-fn (some-> @state :ui :notify)]
    (notify-fn msg level)
    (notify! msg level)))

(defn worktree-path []
  (when-let [qf (query-fn)]
    (:psi.agent-session/worktree-path
     (qf [:psi.agent-session/worktree-path]))))

(defn current-session-id []
  (or *active-workflow-session-id*
      (:current-session-id @state)
      (when-let [qf (query-fn)]
        (:psi.agent-session/session-id
         (qf [:psi.agent-session/session-id])))))

(defn query-session-fn [] (:query-session-fn @state))
(defn mutate-session-fn [] (:mutate-session-fn @state))

(defn ui [] (:ui @state))
(defn ctx [] (:ctx @state))
(defn loaded-definitions [] (:loaded-definitions @state))
(defn widget-ids [] (:widget-ids @state))
(defn register-prompt-contribution-fn [] (:register-prompt-contribution @state))

(defn swap-state! [f & args]
  (apply swap! state f args))

(defn assoc-state! [& kvs]
  (apply swap! state assoc kvs))

;;; Built-in lifecycle registration and invocation

(defn register-built-in-lifecycle-callback!
  "Register `handler-fn` for `event-name` in the built-in lifecycle store.
   `event-name` is a string (e.g. `\"session_switch\"`).
   Replaces any existing handler for the same event to avoid duplication on
   repeated bootstrap calls."
  [event-name handler-fn]
  (swap! built-in-lifecycle-callbacks assoc event-name handler-fn))

(defn invoke-built-in-lifecycle!
  "Invoke the registered built-in lifecycle handler for `event-name`, if any.
   `event` is the payload map passed to the handler.
   Returns the handler return value, or nil when no handler is registered."
  [event-name event]
  (when-let [handler (get @built-in-lifecycle-callbacks event-name)]
    (try
      (handler event)
      (catch Exception e
        {:error (.getMessage e)}))))
