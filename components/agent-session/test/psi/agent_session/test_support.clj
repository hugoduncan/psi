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
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]
   [psi.session-state.model :as session-data]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-step-materialization.core]
   [psi.workflow-step-session-config.core]
   [psi.session-state.state :as ss]
   [psi.agent-session.statechart :as session-sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]
   [psi.deterministic-operation-registry.registry :as op-registry]
   [psi.ui.state :as ui-state])
  (:import
   (java.util.concurrent Executors)))

(def ^:private session-scoped-keys
  "Keys that are stored per-session and require a session id."
  #{:session-data :provider-error-replies
    :journal :flush-state :turn-ctx
    :tool-output-stats :tool-call-attempts :tool-lifecycle-events
    :provider-requests :provider-replies :provider-events})

(defn instant
  "Parse an ISO-8601 string into a `java.time.Instant`."
  [s]
  (java.time.Instant/parse s))

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

(defn capturing-delay-fn
  "Return `[override-fn cb*]` for the scheduler timer seam.

  `override-fn` is suitable as a `:scheduler-run-after-delay-fn` override: it
  captures the requested delay and fire callback into `cb*` (an atom holding
  `{:delay-ms delay-ms :f f}`) instead of scheduling on a real timer, and
  returns a sentinel `{:handle :captured}`. Tests then invoke the captured
  callback directly (no wall-clock sleep), e.g. `((:f @cb*))`."
  []
  (let [cb* (atom nil)]
    [(fn [_ctx delay-ms f]
       (reset! cb* {:delay-ms delay-ms :f f})
       {:handle :captured})
     cb*]))

(def ^:private default-stub-execution-instant
  "Fixed instant for the canonical execution-result stub assistant-message
  timestamp. Deterministic (no wall-clock) — no test asserts this timestamp
  today, so the exact value is irrelevant; it only keeps the stub time-controlled
  (`control(time(tests))`)."
  (java.time.Instant/parse "2026-01-01T00:00:00Z"))

(defn stub-execution-result
  "Return the canonical execution-result stub shape used by the
  `:execute-prepared-request-fn` ctx seam.

  Opts: `:sid` (session-id), `:prepared` (the prepared-request map, supplies the
  turn-id), `:timestamp` (assistant-message timestamp; defaults to a fixed
  deterministic instant — never wall-clock), `:text` (assistant text;
  default \"ok\")."
  [{:keys [sid prepared timestamp text]
    :or   {timestamp default-stub-execution-instant text "ok"}}]
  {:execution-result/turn-id          (:prepared-request/id prepared)
   :execution-result/session-id       sid
   :execution-result/assistant-message {:role        "assistant"
                                        :content     [{:type :text :text text}]
                                        :stop-reason :stop
                                        :timestamp   timestamp}
   :execution-result/turn-outcome     :turn.outcome/stop
   :execution-result/tool-calls       []
   :execution-result/stop-reason      :stop})

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
                                                       (stub-execution-result {:sid sid :prepared prepared}))
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
                       :workflow-inflight-runs-handle workflow-runtime-state/inflight-runs
                       :workflow-cancellation-entry-locks-handle (atom {})
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

;; --- Deterministic-operation surface fixtures (task 205) ---
;; Shared canonical fixtures for the four deterministic-operation test suites
;; (unit action, psi-tool report, psi-tool integration, command). One home,
;; no incidental per-suite variation.

(defn make-op-ctx
  "Minimal canonical-root-backed ctx for unit deterministic-operation tests.
   Carries the registry and a `:state*` atom whose
   `{:agent-session {:sessions sessions}}` shape matches production
   `get-session-data-in`. `sessions` defaults to `{}`."
  ([reg] (make-op-ctx reg {}))
  ([reg sessions]
   {:deterministic-operation-registry reg
    :state* (atom {:agent-session {:sessions sessions}})}))

(defn ok-op
  "A deterministic operation that succeeds, echoing the whole invocation map
   under `[:data :echo]`. Callers reach `:args` via `[:data :echo :args]`."
  [id]
  {:id id
   :description (str "desc for " id)
   :handler (fn [invocation] {:status :ok :data {:echo invocation}})})

(defn create-op-session-context
  "Create a real session context for end-to-end deterministic-operation tests.
   Returns [ctx session-id]."
  []
  (let [ctx (session-core/create-context (safe-context-opts {:persist? false}))
        sd  (session-core/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn register-op!
  "Register a deterministic operation on `ctx`'s registry."
  [ctx op]
  (op-registry/register-operation-in! (:deterministic-operation-registry ctx) op))
(defn scheduled-message-by-id
  "Return the scheduled user message delivered into `session-id`'s journal for
  `schedule-id`, or nil.

  Scans the session journal for a `\"user\"`-role message carrying scheduled
  provenance (`:source :scheduled` + matching `:schedule-id`). One shared
  journal-scan abstraction for the scheduler verification tests."
  [ctx session-id schedule-id]
  (->> (ss/get-state-value-in ctx (ss/state-path :journal session-id))
       (keep #(get-in % [:data :message]))
       (some (fn [message]
               (when (and (= "user" (:role message))
                          (= :scheduled (:source message))
                          (= schedule-id (:schedule-id message)))
                 message)))))

(defn schedule-by-id
  "Return the full schedule map for `schedule-id` in `session-id`'s scheduler
  state, or nil. One shared schedule-read abstraction for the scheduler
  verification tests."
  [ctx session-id schedule-id]
  (get-in (ss/get-session-data-in ctx session-id)
          [:scheduler :schedules schedule-id]))

(defn schedule-status
  "Return the `:status` of `schedule-id` in `session-id`'s scheduler state."
  [ctx session-id schedule-id]
  (:status (schedule-by-id ctx session-id schedule-id)))

(defn schedule-queue
  "Return the scheduler `:queue` vector for `session-id`."
  [ctx session-id]
  (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue]))

(defn set-session-streaming!
  "Set `session-id`'s `:is-streaming` flag to `streaming?` in `ctx`'s state.

  Symmetric write-helper to the `schedule-status`/`schedule-queue` read helpers
  for driving the origin-session busy/idle state in the scheduler verification
  tests (busy = `:is-streaming true`)."
  [ctx session-id streaming?]
  (swap! (:state* ctx)
         (ss/session-update session-id
                            (fn [session] (assoc session :is-streaming streaming?)))))
