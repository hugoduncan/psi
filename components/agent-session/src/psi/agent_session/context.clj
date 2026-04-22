(ns psi.agent-session.context
  "Context creation, callback wiring, and query graph registration for agent-session."
  (:require
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.compaction-runtime :as compaction-runtime]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.prompt-chain :as prompt-chain]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.services :as services]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.session-runtime :as session-runtime]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflows :as wf]
   [psi.history.resolvers :as history-resolvers]
   [psi.query.core :as query]
   [psi.ui.state :as ui-state])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn- create-tool-batch-executor
  ^ExecutorService
  [config]
  (let [n (long (max 1 (or (:tool-batch-max-parallelism config) 4)))]
    (Executors/newFixedThreadPool n)))

(defn- shutdown-tool-batch-executor!
  [^ExecutorService executor]
  (when executor
    (.shutdown executor)
    (.awaitTermination executor 5 TimeUnit/SECONDS)))

(defn register-resolvers-in!
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r (resolvers/session-resolver-surface)]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-mutations-in!
  ([qctx mutations] (register-mutations-in! qctx mutations true))
  ([qctx mutations rebuild?]
   (doseq [m (concat mutations history-resolvers/all-mutations)]
     (query/register-mutation-in! qctx m))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-resolvers! []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))

(defn register-mutations! [mutations]
  (doseq [m mutations]
    (query/register-mutation! m))
  (query/rebuild-env!))

(defn- resolve-session-defaults [session-defaults resolved-cwd ui-type]
  (cond-> (or session-defaults {})
    (not (contains? (or session-defaults {}) :worktree-path))
    (assoc :worktree-path resolved-cwd)
    (some? ui-type) (assoc :ui-type ui-type)))

(defn- initial-root-state [nrepl-runtime-atom recursion-ctx]
  {:agent-session {:sessions {}}
   :workflows (workflow-model/initial-workflow-state)
   :runtime {:nrepl (or (some-> nrepl-runtime-atom deref) nil)
             :rpc-trace {:enabled? false :file nil}
             :extension-installs {}}
   :background-jobs {:store (bg-jobs/empty-state)}
   :ui {:extension-ui @(ui-state/create-ui-state)}
   :recursion (or (some-> recursion-ctx :state-atom deref) nil)
   :oauth {:authenticated-providers [] :last-login-provider nil :last-login-at nil}})

(defn- register-projection-listener! [listeners* listener-fn]
  (let [listener-id (str (java.util.UUID/randomUUID))]
    (swap! listeners* assoc listener-id listener-fn)
    listener-id))

(defn- unregister-projection-listener! [listeners* listener-id]
  (swap! listeners* dissoc listener-id)
  nil)

(defn- publish-projection-change! [listeners* change]
  (doseq [[_ listener-fn] @listeners*]
    (try (listener-fn change) (catch Throwable _ nil)))
  nil)

(defn- create-workflow-child-session!
  [ctx parent-session-id {:keys [child-session-id session-name system-prompt tool-defs thinking-level model skills
                                 developer-prompt developer-prompt-source preloaded-messages
                                 cache-breakpoints prompt-component-selection
                                 workflow-run-id workflow-step-id workflow-attempt-id workflow-owned?]}]
  (dispatch/dispatch! ctx
                      :session/create-child
                      (cond-> {:session-id parent-session-id
                               :child-session-id child-session-id
                               :session-name session-name
                               :system-prompt system-prompt
                               :tool-defs tool-defs
                               :thinking-level thinking-level}
                        (some? model) (assoc :model model)
                        (some? skills) (assoc :skills skills)
                        (some? preloaded-messages) (assoc :preloaded-messages preloaded-messages)
                        (some? cache-breakpoints) (assoc :cache-breakpoints cache-breakpoints)
                        (some? prompt-component-selection) (assoc :prompt-component-selection prompt-component-selection)
                        (some? developer-prompt) (assoc :developer-prompt developer-prompt)
                        (some? developer-prompt-source) (assoc :developer-prompt-source developer-prompt-source)
                        (some? workflow-run-id) (assoc :workflow-run-id workflow-run-id)
                        (some? workflow-step-id) (assoc :workflow-step-id workflow-step-id)
                        (some? workflow-attempt-id) (assoc :workflow-attempt-id workflow-attempt-id)
                        (contains? {:workflow-owned? workflow-owned?} :workflow-owned?) (assoc :workflow-owned? workflow-owned?))
                      {:origin :mutations})
  (let [sd (ss/get-session-data-in ctx child-session-id)
        messages (vec (or preloaded-messages []))
        fresh (session-runtime/create-runtime! ctx child-session-id {:session-data sd :messages messages :agent-initial (:agent-initial ctx)})]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (assoc-in [:agent-session :sessions child-session-id :agent-ctx] (:agent-ctx fresh))
                 (assoc-in [:agent-session :sessions child-session-id :sc-session-id] (:sc-session-id fresh)))))
    (when (seq messages)
      (agent-core/replace-messages-in! (:agent-ctx fresh) messages)))
  {:psi.agent-session/session-id child-session-id})

(defn- callback-fns [mutations projection-listeners*]
  {:apply-root-state-update-fn ss/apply-root-state-update-in!
   :read-session-state-fn ss/get-state-value-in
   :execute-dispatch-effect-fn (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
   :dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
   :runtime-tool-executor-fn tool-plan/default-execute-runtime-tool-in!
   :execute-tool-runtime-fn #'tool-plan/execute-tool-runtime-in!
   :build-prepared-request-fn #'prompt-request/build-prepared-request
   :execute-prepared-request-fn #'prompt-runtime/execute-prepared-request!
   :build-record-response-fn #'prompt-recording/build-record-response
   :continue-prompt-chain-fn #'prompt-chain/run-prompt-tools!
   :refresh-system-prompt-fn (fn
                               ([_ctx] (throw (ex-info "refresh-system-prompt-fn requires explicit session-id" {:callback :refresh-system-prompt-fn})))
                               ([ctx session-id] (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})))
   :execute-compaction-fn (fn [ctx session-id custom-instructions]
                            (compaction-runtime/execute-compaction-in! ctx session-id custom-instructions))
   :notify-extension-fn #'ext-rt/notify-extension-in!
   :register-resolvers-fn (fn [qctx rebuild?] (register-resolvers-in! qctx rebuild?))
   :register-mutations-fn (fn [qctx mutations rebuild?] (register-mutations-in! qctx mutations rebuild?))
   :create-workflow-child-session-fn create-workflow-child-session!
   :execute-workflow-run-fn workflow-execution/execute-run!
   :resume-and-execute-workflow-run-fn workflow-execution/resume-and-execute-run!
   :mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!
   :emit-background-job-terminal-messages-fn bg-rt/maybe-emit-background-job-terminal-messages!
   :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
   :journal-append-fn ss/journal-append-in!
   :effective-cwd-fn (fn
                       ([_ctx] (throw (ex-info "effective-cwd-fn requires explicit session-id" {:callback :effective-cwd-fn})))
                       ([ctx session-id] (ss/session-worktree-path-in ctx session-id)))
   :now-fn java.time.Instant/now
   :scheduler-run-after-delay-fn (fn [ctx delay-ms f]
                                   ((:daemon-thread-fn ctx)
                                    (fn []
                                      (Thread/sleep ^long delay-ms)
                                      (f))))
   :scheduler-cancel-delay-fn (fn [_ctx handle]
                                (when (instance? Thread handle)
                                  (.interrupt ^Thread handle)))
   :daemon-thread-fn dispatch-handlers/daemon-thread
   :drop-trailing-overflow-error-fn dispatch-effects/drop-trailing-overflow-error!
   :validate-dispatch-result-fn dispatch/validate-dispatch-schemas
   :register-projection-listener-fn (fn [_ctx listener-fn] (register-projection-listener! projection-listeners* listener-fn))
   :unregister-projection-listener-fn (fn [_ctx listener-id] (unregister-projection-listener! projection-listeners* listener-id))
   :publish-projection-change-fn (fn [_ctx change] (publish-projection-change! projection-listeners* change))
   :all-mutations mutations})

(defn- create-context* [{:keys [session-defaults compaction-fn branch-summary-fn agent-initial config cwd persist? event-queue oauth-ctx recursion-ctx nrepl-runtime-atom ui-type mutations]
                         :or {persist? true mutations []}}]
  (let [resolved-cwd (or cwd (System/getProperty "user.dir"))
        resolved-defaults (resolve-session-defaults session-defaults resolved-cwd ui-type)
        state* (atom (initial-root-state nrepl-runtime-atom recursion-ctx))
        projection-listeners* (atom {})
        tool-batch-executor (create-tool-batch-executor (merge session/default-config (or config {})))
        ctx0 (merge {:sc-env (sc/create-sc-env)
                     :started-at (java.time.Instant/now)
                     :state* state*
                     :session-defaults resolved-defaults
                     :agent-initial agent-initial
                     :nrepl-runtime-atom nrepl-runtime-atom
                     :extension-registry (ext/create-registry)
                     :workflow-registry (wf/create-registry)
                     :service-registry (services/create-registry)
                     :project-nrepl-registry (project-nrepl-runtime/create-registry)
                     :post-tool-registry (post-tool/create-registry)
                     :event-queue event-queue
                     :cwd resolved-cwd
                     :persist? persist?
                     :oauth-ctx oauth-ctx
                     :recursion-ctx recursion-ctx
                     :compaction-fn (or compaction-fn compaction/stub-compaction-fn)
                     :branch-summary-fn (or branch-summary-fn compaction/stub-branch-summary-fn)
                     :config (merge session/default-config (or config {}))
                     :tool-batch-executor tool-batch-executor
                     :extension-run-fn-atom (atom nil)
                     :background-job-ui-refresh-fn (atom nil)
                     :scheduler-timers* (atom {})
                     :projection-listeners* projection-listeners*}
                    (callback-fns mutations projection-listeners*))
        _ (dispatch-handlers/register-all! ctx0)
        actions-fn (dispatch-handlers/make-actions-fn ctx0)
        ctx (assoc ctx0 :session-actions-fn actions-fn)]
    ctx))

(defn create-context
  ([] (create-context {}))
  ([opts] (create-context* opts)))

(defn shutdown-context! [ctx]
  (doseq [{:keys [session-id]} (ss/list-context-sessions-in ctx)]
    (dispatch/dispatch! ctx :scheduler/cancel-all {:session-id session-id} {:origin :core}))
  (dispatch-effects/cancel-all-scheduler-timers!)
  (when-let [timers* (:scheduler-timers* ctx)]
    (reset! timers* {}))
  (shutdown-tool-batch-executor! (:tool-batch-executor ctx))
  nil)
