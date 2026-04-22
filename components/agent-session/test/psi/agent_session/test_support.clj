(ns psi.agent-session.test-support
  "Helpers for canonical-root-backed agent-session test contexts."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.services :as services]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.persistence :as persistence]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as session-sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state])
  (:import
   (java.util.concurrent Executors)))

(def ^:private session-scoped-keys
  "Keys that are stored per-session and require a session id."
  #{:session-data :provider-error-replies
    :journal :flush-state :turn-ctx
    :tool-output-stats :tool-call-attempts :tool-lifecycle-events
    :provider-requests :provider-replies})

(defn temp-cwd []
  (let [p (str (java.nio.file.Files/createTempDirectory
                "psi-agent-session-test-"
                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (java.io.File. p))
    p))

(defn safe-context-opts
  "Merge test-safe defaults into session/create-context opts and fail fast if
   the resolved cwd targets the repo root/user.dir. Tests may override :cwd,
   but never to the process cwd."
  [opts]
  (let [opts*         (merge {:cwd (temp-cwd)} opts)
        cwd-path      (.getCanonicalPath (java.io.File. (str (:cwd opts*))))
        user-dir-path (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))]
    (when (= cwd-path user-dir-path)
      (throw (ex-info "Unsafe test context cwd resolves to process user.dir"
                      {:cwd cwd-path
                       :user-dir user-dir-path
                       :opts opts*})))
    opts*))

(defn- resolve-state-path
  "Resolve the state path for key k, using the first context session id for
   session-scoped keys."
  [ctx k]
  (if (session-scoped-keys k)
    (let [session-id (some-> (ss/list-context-sessions-in ctx) first :session-id)]
      (ss/state-path k session-id))
    (ss/state-path k)))

(defn set-state!
  "Test-only canonical root-state setter.
   Keeps low-level state mutation localized to test support rather than test bodies."
  [ctx k value]
  (ss/assoc-state-value-in! ctx (resolve-state-path ctx k) value)
  ctx)

(defn update-state!
  "Test-only canonical root-state updater.
   Keeps low-level state mutation localized to test support rather than test bodies."
  [ctx k f & args]
  (apply ss/update-state-value-in! ctx (resolve-state-path ctx k) f args)
  ctx)

(defn make-session-ctx
  "Create a minimal canonical-root-backed session-like context for tests.
   Returns [ctx session-id] where session-id is the initial session id.
   Accepts overrides:
   - :state map merged into canonical root
   - :session-data map merged into [:agent-session :data]
   - :agent-ctx custom agent ctx"
  [{:keys [state session-data agent-ctx]}]
  (let [agent-ctx*    (or agent-ctx (agent/create-context))
        sc-session-id (java.util.UUID/randomUUID)
        initial-sd    (merge (assoc (session-data/initial-session {})
                                    :provider-error-replies [])
                             (or session-data {}))
        sid           (:session-id initial-sd)
        base-state    {:agent-session {:sessions {sid {:data          initial-sd
                                                       :agent-ctx     agent-ctx*
                                                       :sc-session-id sc-session-id
                                                       :telemetry {:tool-output-stats {:calls []
                                                                                       :aggregates {:total-context-bytes 0
                                                                                                    :by-tool {}
                                                                                                    :limit-hits-by-tool {}}}
                                                                   :tool-call-attempts []
                                                                   :tool-lifecycle-events []
                                                                   :provider-requests []
                                                                   :provider-replies []}
                                                       :persistence {:journal []
                                                                     :flush-state {:flushed? false :session-file nil}}
                                                       :turn {:ctx nil}}}}
                       :background-jobs {:store (bg-jobs/empty-state)}
                       :ui {:extension-ui @(ui-state/create-ui-state)}}
        state*               (atom (merge base-state (or state {})))
        ext-reg       (ext/create-registry)
        wf-reg        (wf/create-registry)
        sc-env        (session-sc/create-sc-env)
        dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
        tool-batch-executor (Executors/newFixedThreadPool 4)
        ctx           {:state*                       state*
                       :sc-env                       sc-env
                       :config                       {}
                       :session-defaults             (or session-data {})
                       :extension-registry           ext-reg
                       :workflow-registry            wf-reg
                       :service-registry             (services/create-registry)
                       :project-nrepl-registry       (project-nrepl-runtime/create-registry)
                       :post-tool-registry           (post-tool/create-registry)
                       :tool-batch-executor          tool-batch-executor
                       :extension-run-fn-atom        (atom nil)
                       :scheduler-timers*            (atom {})
                       :apply-root-state-update-fn   ss/apply-root-state-update-in!
                       :read-session-state-fn        ss/get-state-value-in
                       :execute-dispatch-effect-fn   (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
                       :dispatch-statechart-event-fn dispatch-statechart-event-fn
                       :runtime-tool-executor-fn     tool-plan/default-execute-runtime-tool-in!
                       :execute-tool-runtime-fn      #'tool-plan/execute-tool-runtime-in!
                       :build-prepared-request-fn    #'prompt-request/build-prepared-request
                       :build-record-response-fn     #'prompt-recording/build-record-response
                       :continue-prompt-chain-fn     (fn [_ctx _session-id _execution-result _progress-queue]
                                                       {:continued? true})
                       :refresh-system-prompt-fn     (fn
                                                       ([_ctx] (throw (ex-info "refresh-system-prompt-fn requires explicit session-id" {:callback :refresh-system-prompt-fn})))
                                                       ([ctx session-id] (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})))
                       :execute-prepared-request-fn  (fn [_ai-ctx _ctx sid prepared _progress-queue]
                                                       {:execution-result/turn-id (:prepared-request/id prepared)
                                                        :execution-result/session-id sid
                                                        :execution-result/assistant-message {:role "assistant"
                                                                                             :content [{:type :text :text "ok"}]
                                                                                             :stop-reason :stop
                                                                                             :timestamp (java.time.Instant/now)}
                                                        :execution-result/turn-outcome :turn.outcome/stop
                                                        :execution-result/tool-calls []
                                                        :execution-result/stop-reason :stop})
                       :persist?                     false
                       :notify-extension-fn         (fn
                                                      ([ctx role content custom-type]
                                                       (let [msg {:role      role
                                                                  :content   [{:type :text :text (str content)}]
                                                                  :timestamp (java.time.Instant/now)}
                                                             msg (cond-> msg
                                                                   custom-type (assoc :custom-type custom-type)
                                                                   (not custom-type) (assoc :custom-type "extension-notification"))
                                                             session-id (some-> (ss/list-context-sessions-in ctx) first :session-id)]
                                                         (dispatch/dispatch! ctx
                                                                             :session/notify-extension
                                                                             {:session-id session-id :message msg}
                                                                             {:origin :core})
                                                         msg))
                                                      ([ctx session-id role content custom-type]
                                                       (let [msg {:role      role
                                                                  :content   [{:type :text :text (str content)}]
                                                                  :timestamp (java.time.Instant/now)}
                                                             msg (cond-> msg
                                                                   custom-type (assoc :custom-type custom-type)
                                                                   (not custom-type) (assoc :custom-type "extension-notification"))]
                                                         (dispatch/dispatch! ctx
                                                                             :session/notify-extension
                                                                             {:session-id session-id :message msg}
                                                                             {:origin :core})
                                                         msg)))
                       :mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!
                       :emit-background-job-terminal-messages-fn bg-rt/maybe-emit-background-job-terminal-messages!
                       :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
                       :now-fn                       java.time.Instant/now
                       :scheduler-run-after-delay-fn (fn [ctx delay-ms f]
                                                       ((:daemon-thread-fn ctx)
                                                        (fn []
                                                          (Thread/sleep ^long delay-ms)
                                                          (f))))
                       :scheduler-cancel-delay-fn    (fn [_ctx handle]
                                                       (when (instance? Thread handle)
                                                         (.interrupt ^Thread handle)))
                       :daemon-thread-fn             (fn [f] (doto (Thread. ^Runnable f) (.setDaemon true) (.start)))
                       :effective-cwd-fn             (fn [ctx session-id] (ss/session-worktree-path-in ctx session-id))
                       :journal-append-fn            persistence/append-entry-in!}
        _             (dispatch-handlers/register-all! ctx)
        actions-fn     (dispatch-handlers/make-actions-fn ctx)
        ctx            (assoc ctx :session-actions-fn actions-fn)]
    (session-sc/start-session! sc-env sc-session-id
                               {:ctx        ctx
                                :session-id sid
                                :actions-fn actions-fn
                                :config     {}})
    [ctx sid]))

(defn create-test-session
  "Create a full session context with a real first session.
   Returns [ctx session-id].

   Accepts the same options as `session/create-context`. The :session-defaults
   overrides flow through into the first real session.

   Guard: when persistence is enabled, tests must not target process user.dir."
  ([] (create-test-session {:persist? false}))
  ([opts]
   (let [persist? (not= false (:persist? opts))
         _        (when persist?
                    (let [cwd-path      (.getCanonicalPath (java.io.File. (str (or (:cwd opts)
                                                                                   (System/getProperty "user.dir")))))
                          user-dir-path (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))]
                      (when (= cwd-path user-dir-path)
                        (throw (ex-info "Unsafe persisted test context cwd resolves to process user.dir"
                                        {:cwd cwd-path
                                         :user-dir user-dir-path
                                         :opts opts})))))
         ctx      (session-core/create-context opts)
         sd       (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))
