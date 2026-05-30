(ns psi.agent-session.test-support
  "Helpers for canonical-root-backed agent-session test contexts."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.dispatch-schema :as dispatch-schema]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.prompt-recording]
   [psi.agent-session.prompt-request]
   [psi.agent-session.services :as services]
   [psi.agent-session.scheduler-time :as scheduler-time]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-judge]
   [psi.agent-session.context :as session-context]
   [psi.session-state.model :as session-data]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-step-materialization.core]
   [psi.workflow-step-session-config.core]
   [psi.session-state.state :as ss]
   [psi.agent-session.statechart :as session-sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]
   [psi.ui.state :as ui-state])
  (:import
   (java.util.concurrent Executors)))

(def ^:private session-scoped-keys
  "Keys that are stored per-session and require a session id."
  #{:session-data :provider-error-replies
    :journal :flush-state :turn-ctx
    :tool-output-stats :tool-call-attempts :tool-lifecycle-events
    :provider-requests :provider-replies :provider-events})

(defn fixed-scheduler-time-source
  "Return a scheduler test time source fixed at `instant`."
  [instant]
  (fn [] instant))

(defn atom-scheduler-time-source
  "Return an advanceable scheduler test time source and backing atom."
  [instant]
  (let [instant* (atom instant)]
    {:time-source (fn [] @instant*)
     :instant* instant*}))

(defn set-scheduler-instant!
  [instant* instant]
  (reset! instant* instant))

(defn advance-scheduler-instant!
  [instant* millis]
  (swap! instant* #(.plusMillis ^java.time.Instant % millis)))

(defn temp-cwd []
  (let [p (str (java.nio.file.Files/createTempDirectory
                "psi-agent-session-test-"
                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (java.io.File. p))
    p))

(defn temp-session-root []
  (let [p (str (java.nio.file.Files/createTempDirectory
                "psi-agent-session-store-"
                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (java.io.File. p))
    p))

(defn delete-recursively!
  [path]
  (let [f (java.io.File. (str path))]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

(defn with-temp-session-root
  "Run f with a test-owned temporary session root. Cleans it up in finally.
   Passes the root path string to f."
  [f]
  (let [root (temp-session-root)]
    (try
      (f root)
      (finally
        (delete-recursively! root)))))

(defn safe-context-opts
  "Merge test-safe defaults into session/create-context opts and fail fast if
   the resolved cwd targets the repo root/user.dir. Tests may override :cwd,
   but never to the process cwd.

   This is the authoritative persisted-test guardrail seam shared by test
   context helpers.

   When persistence is enabled, tests must also provide an explicit isolated
   :session-root rather than falling through to the real default user-home
   session store."
  [opts]
  (let [opts*         (merge {:cwd (temp-cwd)
                              :persist? false}
                             opts)
        cwd-path      (.getCanonicalPath (java.io.File. (str (:cwd opts*))))
        user-dir-path (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))]
    (when (= cwd-path user-dir-path)
      (throw (ex-info "Unsafe test context cwd resolves to process user.dir"
                      {:cwd cwd-path
                       :user-dir user-dir-path
                       :opts opts*})))
    (when (and (not= false (:persist? opts*))
               (nil? (:session-root opts*)))
      (throw (ex-info "Unsafe persisted test context missing isolated :session-root"
                      {:cwd cwd-path
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

(defn with-workflow-execution-adapter-overrides
  "Replace the named workflow execution adapter as one seam value while
   allowing targeted operation overrides for adapter-consumer tests."
  [ctx overrides]
  (assoc ctx
         workflow-execution-adapter/adapter-key
         (merge (get ctx workflow-execution-adapter/adapter-key) overrides)))

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
        initial-sd    (cond-> initial-sd
                        (contains? initial-sd :skills)
                        (-> (assoc :skill-ids (mapv :name (or (:skills initial-sd) [])))
                            (dissoc :skills)))
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
        state-with-skills    (if (contains? initial-sd :skill-ids)
                               (let [seed-skills (or (:skills session-data)
                                                     (mapv (fn [skill-id]
                                                             {:name skill-id
                                                              :description (str skill-id " description")
                                                              :source :project
                                                              :disable-model-invocation false})
                                                           (:skill-ids initial-sd)))]
                                 (-> (merge base-state (or state {}))
                                     (skill-storage/set-skills-in-root-state sid seed-skills)
                                     :root-state))
                               (merge base-state (or state {})))
        state*               (atom state-with-skills)
        ext-reg       (ext/create-registry)
        wf-reg        (extension-workflow-runtime/create-registry)
        sc-env        (session-sc/create-sc-env)
        dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
        tool-batch-executor (Executors/newFixedThreadPool 4)
        ctx0          {:state*                       state*
                       :sc-env                       sc-env
                       :config                       {}
                       :session-defaults             initial-sd
                       :extension-registry           ext-reg
                       :workflow-registry            wf-reg
                       :service-registry             (services/create-registry)
                       :project-nrepl-registry       (project-nrepl-runtime/create-registry)
                       :post-tool-registry           (post-tool/create-registry)
                       :tool-batch-executor          tool-batch-executor
                       :extension-run-fn-atom        (atom nil)
                       :scheduler-timers*            (atom {})
                       :scheduler-time-source        (scheduler-time/system-time-source)
                       :apply-root-state-update-fn   ss/apply-root-state-update-in!
                       :read-session-state-fn        ss/get-state-value-in
                       :execute-dispatch-effect-fn   (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
                       :execute-effect-fn            (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
                       :dispatch-statechart-event-fn dispatch-statechart-event-fn
                       :runtime-tool-executor-fn     tool-plan/default-execute-runtime-tool-in!
                       :execute-tool-runtime-fn      #'tool-plan/execute-tool-runtime-in!
                       :build-prepared-request-fn    #'psi.agent-session.prompt-request/build-prepared-request
                       :build-record-response-fn     #'psi.agent-session.prompt-recording/build-record-response
                       :continue-prompt-chain-fn     (fn [_ctx _session-id _execution-result _progress-queue]
                                                       {:continued? true})
                       :refresh-system-prompt-fn     (fn
                                                       ([_ctx] (throw (ex-info "refresh-system-prompt-fn requires explicit session-id" {:callback :refresh-system-prompt-fn})))
                                                       ([ctx session-id] (session-core/dispatch-in! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})))
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
                       :workflow-prompt-execution-result-fn (fn [ctx sid text images opts]
                                                              (cond
                                                                (some? opts) (psi.agent-session.turn/prompt-execution-result-in! ctx sid text images opts)
                                                                (some? images) (psi.agent-session.turn/prompt-execution-result-in! ctx sid text images)
                                                                :else (psi.agent-session.turn/prompt-execution-result-in! ctx sid text)))
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
                                                         (session-core/dispatch-in! ctx
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
                                                         (session-core/dispatch-in! ctx
                                                                                    :session/notify-extension
                                                                                    {:session-id session-id :message msg}
                                                                                    {:origin :core})
                                                         msg)))
                       :get-session-data-fn          ss/get-session-data-in
                       :list-context-sessions-fn     ss/list-context-sessions-in
                       :find-skill-fn                psi.skill-registry.root-storage/find-skill-in
                       :resolve-workflow-step-session-config-fn psi.workflow-step-session-config.core/resolve-step-session-config
                       :materialize-workflow-step-session-conversation-fn psi.workflow-step-materialization.core/materialize-step-session-conversation
                       :split-workflow-step-session-conversation-fn psi.workflow-step-materialization.core/split-step-session-conversation
                       :execute-workflow-judge-fn    psi.agent-session.workflow-judge/execute-judge!
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
                       :validate-dispatch-result-fn  dispatch-schema/validate-dispatch-schemas
                       :validate-result-fn           dispatch-schema/validate-dispatch-schemas}
        ctx           (assoc ctx0 workflow-execution-adapter/adapter-key
                             (session-context/workflow-execution-adapter ctx0))
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

   Persisted test usage is guardrailed here via `safe-context-opts`, so tests
   cannot silently fall through to the real default user-home session store."
  ([] (create-test-session {:persist? false}))
  ([opts]
   (let [ctx (session-core/create-context (safe-context-opts opts))
         sd  (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))
