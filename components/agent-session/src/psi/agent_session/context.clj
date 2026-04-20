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
  "Register all agent-session resolvers into an isolated `qctx` query context.
   Includes history, memory, and recursion resolvers so session-root attrs
   like :git.worktree/current are resolvable.
   Rebuilds the env unless `rebuild?` is false (useful when the caller will
   rebuild after adding further operations).
   Use in tests to avoid touching global state."
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r (resolvers/session-resolver-surface)]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-mutations-in!
  "Register agent-session mutations into an isolated `qctx` query context.
   `mutations` is the list of mutation vars to register (e.g. mutations/all-mutations).
   Includes history mutations so extension/runtime mutation calls can execute
   git worktree and branch operations through the isolated query env."
  ([qctx mutations] (register-mutations-in! qctx mutations true))
  ([qctx mutations rebuild?]
   (doseq [m (concat mutations history-resolvers/all-mutations)]
     (query/register-mutation-in! qctx m))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-resolvers!
  "Register all agent-session resolvers into the global query graph and
   rebuild the environment."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))

(defn register-mutations!
  "Register agent-session mutations into the global query graph and
   rebuild the environment.
   `mutations` is the list of mutation vars to register (e.g. mutations/all-mutations)."
  [mutations]
  (doseq [m mutations]
    (query/register-mutation! m))
  (query/rebuild-env!))

(defn- resolve-session-defaults
  "Merge caller-supplied session-defaults with resolved cwd and ui-type."
  [session-defaults resolved-cwd ui-type]
  (cond-> (or session-defaults {})
    (not (contains? (or session-defaults {}) :worktree-path))
    (assoc :worktree-path resolved-cwd)
    (some? ui-type) (assoc :ui-type ui-type)))

(defn- initial-root-state
  "Build the initial value for the canonical root state atom."
  [nrepl-runtime-atom recursion-ctx]
  {:agent-session {:sessions {}}
   :workflows (workflow-model/initial-workflow-state)
   :runtime {:nrepl (or (some-> nrepl-runtime-atom deref) nil)
             :rpc-trace {:enabled? false
                         :file nil}}
   :background-jobs {:store (bg-jobs/empty-state)}
   :ui {:extension-ui @(ui-state/create-ui-state)}
   :recursion (or (some-> recursion-ctx :state-atom deref) nil)
   :oauth {:authenticated-providers []
           :last-login-provider nil
           :last-login-at nil}})

(defn- register-projection-listener!
  [listeners* listener-fn]
  (let [listener-id (str (java.util.UUID/randomUUID))]
    (swap! listeners* assoc listener-id listener-fn)
    listener-id))

(defn- unregister-projection-listener!
  [listeners* listener-id]
  (swap! listeners* dissoc listener-id)
  nil)

(defn- publish-projection-change!
  [listeners* change]
  (doseq [[_ listener-fn] @listeners*]
    (try
      (listener-fn change)
      (catch Throwable _
        nil)))
  nil)

(defn- callback-fns
  "Build the indirection table of callback/strategy fns for ctx.
   These allow dispatch handlers and extensions to call back into the
   session layer without circular requires."
  [mutations projection-listeners*]
  {:apply-root-state-update-fn                     ss/apply-root-state-update-in!
   :read-session-state-fn                          ss/get-state-value-in
   :execute-dispatch-effect-fn                     (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
   :dispatch-statechart-event-fn                   dispatch-handlers/dispatch-statechart-event-in!
   :runtime-tool-executor-fn                       tool-plan/default-execute-runtime-tool-in!
   :execute-tool-runtime-fn                        #'tool-plan/execute-tool-runtime-in!
   :build-prepared-request-fn                      #'prompt-request/build-prepared-request
   :execute-prepared-request-fn                    #'prompt-runtime/execute-prepared-request!
   :build-record-response-fn                       #'prompt-recording/build-record-response
   :continue-prompt-chain-fn                       #'prompt-chain/run-prompt-tools!
   :refresh-system-prompt-fn                       (fn
                                                     ([_ctx]
                                                      (throw (ex-info "refresh-system-prompt-fn requires explicit session-id"
                                                                      {:callback :refresh-system-prompt-fn})))
                                                     ([ctx session-id]
                                                      (dispatch/dispatch! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core})))
   :execute-compaction-fn                          (fn [ctx session-id custom-instructions]
                                                     (compaction-runtime/execute-compaction-in! ctx session-id custom-instructions))
   :notify-extension-fn                            #'ext-rt/notify-extension-in!
   :register-resolvers-fn                          (fn [qctx rebuild?] (register-resolvers-in! qctx rebuild?))
   :register-mutations-fn                          (fn [qctx mutations rebuild?] (register-mutations-in! qctx mutations rebuild?))
   :mark-workflow-jobs-terminal-fn                 bg-rt/maybe-mark-workflow-jobs-terminal!
   :emit-background-job-terminal-messages-fn       bg-rt/maybe-emit-background-job-terminal-messages!
   :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
   :journal-append-fn                              ss/journal-append-in!
   :effective-cwd-fn                               (fn
                                                     ([_ctx]
                                                      (throw (ex-info "effective-cwd-fn requires explicit session-id"
                                                                      {:callback :effective-cwd-fn})))
                                                     ([ctx session-id]
                                                      (ss/session-worktree-path-in ctx session-id)))
   :daemon-thread-fn                               dispatch-handlers/daemon-thread
   :drop-trailing-overflow-error-fn                dispatch-effects/drop-trailing-overflow-error!
   :validate-dispatch-result-fn                    dispatch/validate-dispatch-schemas
   :register-projection-listener-fn                (fn [_ctx listener-fn]
                                                     (register-projection-listener! projection-listeners* listener-fn))
   :unregister-projection-listener-fn              (fn [_ctx listener-id]
                                                     (unregister-projection-listener! projection-listeners* listener-id))
   :publish-projection-change-fn                   (fn [_ctx change]
                                                     (publish-projection-change! projection-listeners* change))
   :all-mutations                                  mutations})

(defn- create-context*
  "Internal: create a session context without creating a session.
   Returns ctx (not a vector). Used by create-context and tests."
  [{:keys [session-defaults compaction-fn branch-summary-fn agent-initial config cwd persist? event-queue oauth-ctx recursion-ctx nrepl-runtime-atom ui-type mutations]
    :or   {persist? true mutations []}}]
  (let [resolved-cwd        (or cwd (System/getProperty "user.dir"))
        resolved-defaults   (resolve-session-defaults session-defaults resolved-cwd ui-type)
        state*              (atom (initial-root-state nrepl-runtime-atom recursion-ctx))
        projection-listeners* (atom {})
        tool-batch-executor (create-tool-batch-executor (merge session/default-config (or config {})))
        ctx0                (merge
                             {:sc-env                     (sc/create-sc-env)
                              :started-at                 (java.time.Instant/now)
                              :state*                     state*
                              :session-defaults           resolved-defaults
                              :agent-initial              agent-initial
                              :nrepl-runtime-atom         nrepl-runtime-atom
                              :extension-registry         (ext/create-registry)
                              :workflow-registry          (wf/create-registry)
                              :service-registry           (services/create-registry)
                              :project-nrepl-registry     (project-nrepl-runtime/create-registry)
                              :post-tool-registry         (post-tool/create-registry)
                              :event-queue                event-queue
                              :cwd                        resolved-cwd
                              :persist?                   persist?
                              :oauth-ctx                  oauth-ctx
                              :recursion-ctx              recursion-ctx
                              :compaction-fn              (or compaction-fn compaction/stub-compaction-fn)
                              :branch-summary-fn          (or branch-summary-fn compaction/stub-branch-summary-fn)
                              :config                     (merge session/default-config (or config {}))
                              :tool-batch-executor        tool-batch-executor
                              :extension-run-fn-atom      (atom nil)
                              :background-job-ui-refresh-fn (atom nil)
                              :projection-listeners*      projection-listeners*}
                             (callback-fns mutations projection-listeners*))
        _                   (dispatch-handlers/register-all! ctx0)
        actions-fn          (dispatch-handlers/make-actions-fn ctx0)
        ctx                 (assoc ctx0 :session-actions-fn actions-fn)]
    ctx))

(defn create-context
  "Create an isolated session context.

  Does not create a session. Call `new-session-in!` explicitly when a live
  session is required.

  Options (all optional):
    :session-defaults  — overrides merged into session defaults (model,
                          thinking-level, worktree-path, ui-type, etc.)
                          Stored as :session-defaults on ctx.
    :compaction-fn     — (fn [session-data preparation instructions]) → CompactionResult
                          default: stub (no LLM call)
    :branch-summary-fn — (fn [session-data entries instructions]) → BranchSummaryResult
                          default: stub
    :agent-initial     — initial data overrides for the agent-core context
    :config            — config overrides merged over session/default-config
    :cwd               — working directory for session file layout (default: process cwd)
    :persist?          — if false, disable all disk I/O (default: true)
    :oauth-ctx         — optional OAuth context for extension auth helpers
    :nrepl-runtime-atom — optional runtime metadata atom used only to seed canonical nREPL state
    :ui-type           — runtime UI type hint (:console | :tui | :emacs)
    :mutations         — mutation var list for extension query contexts (default: [])"
  ([] (create-context {}))
  ([opts]
   (create-context* opts)))

(defn shutdown-context!
  "Release runtime resources owned by ctx. Safe to call multiple times."
  [ctx]
  (shutdown-tool-batch-executor! (:tool-batch-executor ctx))
  nil)
