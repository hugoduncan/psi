(ns psi.agent-session.context
  "Context creation, callback wiring, and query graph registration for agent-session."
  (:require
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.compaction-runtime :as compaction-runtime]
   [psi.deterministic-operation-registry.registry :as deterministic-op-registry]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-schema :as schema]
   [psi.agent-session.dispatch-handlers :as dispatch-handlers]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.prompt-chain :as prompt-chain]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.turn :as turn]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.services :as services]
   [psi.agent-session.scheduler-time :as scheduler-time]
   [psi.agent-session.ui-capabilities :as ui-capabilities]
   [psi.session-state.model :as session]
   [psi.session-state.state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.session-runtime :as session-runtime]
   [psi.agent-session.tools]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]
   [psi.workflow-runtime.child-session-contract :as workflow-child-session-contract]
   [psi.workflow-runtime.execution-adapter :as workflow-execution-adapter]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-step-materialization.core :as workflow-step-materialization]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.tool-registry.defs :as tool-defs]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]
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

(defn all-mutations-in
  "Return the current all-mutations vector from `ctx`.
   Reads from the mutable `:all-mutations-atom` when present (updated by
   reload-code), falling back to the frozen `:all-mutations` snapshot."
  [ctx]
  (if-let [a (:all-mutations-atom ctx)]
    @a
    (:all-mutations ctx)))

(defn- resolve-session-defaults [session-defaults resolved-cwd ui-type]
  (let [session-defaults (or session-defaults {})]
    (cond-> session-defaults
      (not (contains? session-defaults :worktree-path))
      (assoc :worktree-path resolved-cwd)
      (some? ui-type) (assoc :ui-type ui-type))))

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
  [ctx parent-session-id request]
  (let [request'
        (workflow-child-session-contract/assert-valid-request!
         request
         :psi.agent-session.context/create-workflow-child-session!)
        {:keys [child-session-id session-name system-prompt prompt-mode response-mode logprobs top-logprobs tool-ids thinking-level speed-mode effort-override temperature model skills
                developer-prompt developer-prompt-source preloaded-messages
                cache-breakpoints prompt-component-selection
                workflow-run-id workflow-step-id workflow-attempt-id workflow-owned?
                inherited-snapshot?]}
        request']
    (dispatch/dispatch! ctx
                        :session/create-child
                        (cond-> {:session-id parent-session-id
                                 :child-session-id child-session-id
                                 :session-name session-name
                                 :worktree-path (or (some-> parent-session-id (ss/get-session-data-in ctx) :worktree-path)
                                                    (some-> (ss/list-context-sessions-in ctx) first :worktree-path)
                                                    (:worktree-path (:session-defaults ctx))
                                                    (:cwd ctx))
                                 :system-prompt system-prompt
                                 :tool-ids tool-ids
                                 :thinking-level thinking-level
                                 :skills skills}
                          (contains? request' :speed-mode)
                          (assoc :speed-mode speed-mode)
                          (contains? request' :effort-override)
                          (assoc :effort-override effort-override)
                          (some? prompt-mode) (assoc :prompt-mode prompt-mode)
                          (some? response-mode) (assoc :response-mode response-mode)
                          (contains? {:logprobs logprobs} :logprobs) (assoc :logprobs logprobs)
                          (some? top-logprobs) (assoc :top-logprobs top-logprobs)
                          (some? temperature) (assoc :temperature temperature)
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
                          (contains? {:workflow-owned? workflow-owned?} :workflow-owned?) (assoc :workflow-owned? workflow-owned?)
                          (some? inherited-snapshot?) (assoc :inherited-snapshot? inherited-snapshot?))
                        {:origin :mutations})
    (let [child-sd (ss/get-session-data-in ctx child-session-id)
          messages (vec (or preloaded-messages []))
          tool-source (ss/agent-tool-source-in ctx parent-session-id)
          resolved-tool-defs (tool-defs/resolve-tool-defs tool-source (:tool-ids child-sd))
          fresh (session-runtime/create-runtime! ctx child-session-id {:session-data child-sd :messages messages :agent-initial (:agent-initial ctx) :resolved-tool-defs resolved-tool-defs})
          result {:psi.agent-session/session-id child-session-id}]
      (swap! (:state* ctx)
             (fn [state]
               (-> state
                   (assoc-in [:agent-session :sessions child-session-id :agent-ctx] (:agent-ctx fresh))
                   (assoc-in [:agent-session :sessions child-session-id :sc-session-id] (:sc-session-id fresh)))))
      (when (seq messages)
        (agent-core/replace-messages-in! (:agent-ctx fresh) messages))
      (workflow-child-session-contract/assert-valid-result!
       result
       :psi.agent-session.context/create-workflow-child-session!))))

(defn workflow-execution-adapter
  [ctx]
  (workflow-execution-adapter/create
   {:create-child-session! (:create-workflow-child-session-fn ctx)
    :prompt-execution-result! (:workflow-prompt-execution-result-fn ctx)
    :get-session-data (:get-session-data-fn ctx)
    :list-context-sessions (:list-context-sessions-fn ctx)
    :find-skill (fn [adapter-ctx session-skills skill-name]
                  ((:find-skill-fn ctx) adapter-ctx session-skills skill-name))
    :set-session-model! (fn [ctx session-id model scope]
                          (dispatch/dispatch! ctx :session/set-model
                                              (cond-> {:session-id session-id :model model}
                                                scope (assoc :scope scope))
                                              {:origin :core}))
    :execute-judge! (:execute-workflow-judge-fn ctx)
    :abort-session! (fn [ctx session-id]
                      (turn-runtime/abort-active-turn-in! ctx session-id)
                      (when-let [agent-ctx (ss/agent-ctx-in ctx session-id)]
                        (agent-core/abort-in! agent-ctx)))}))

(defn- callback-fns [mutations projection-listeners*]
  {:apply-root-state-update-fn ss/apply-root-state-update-in!
   :read-session-state-fn ss/get-state-value-in
   :execute-dispatch-effect-fn (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
   :execute-effect-fn (fn [ctx effect] (dispatch-effects/execute-effect! ctx effect))
   :dispatch-statechart-event-fn dispatch-handlers/dispatch-statechart-event-in!
   :runtime-tool-executor-fn tool-plan/default-execute-runtime-tool-in!
   :execute-tool-runtime-fn #'tool-plan/execute-tool-runtime-in!
   :build-prepared-request-fn #'prompt-request/build-prepared-request
   :execute-prepared-request-fn #'turn/execute-prepared-request!
   :workflow-prompt-execution-result-fn #'turn/prompt-execution-result-in!
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
   :create-workflow-child-session-fn #'create-workflow-child-session!
   :execute-workflow-run-fn #'workflow-execution/execute-run!
   :resume-and-execute-workflow-run-fn #'workflow-execution/resume-and-execute-run!
   :get-session-data-fn #'ss/get-session-data-in
   :list-context-sessions-fn #'ss/list-context-sessions-in
   :find-skill-fn (fn [ctx session-skills skill-name]
                    (or (some (fn [skill]
                                (when (= skill-name (:name skill))
                                  skill))
                              session-skills)
                        (skill-storage/find-skill @(:state* ctx)
                                                  {:skill-ids (mapv :name (or session-skills []))}
                                                  skill-name)))
   :resolve-workflow-step-session-config-fn #'workflow-step-session-config/resolve-step-session-config
   ;; Nested/delegated inherited-defaults snapshot (task 207, S6). Derives the
   ;; delegating step's EFFECTIVE config (run snapshot ⊕ step overrides) then
   ;; projects it into the inherited-defaults snapshot field set, sourcing
   ;; speed-mode/effort-override from the parent run's snapshot (P2). Injected so
   ;; delegate.clj (in workflow-runtime) never requires
   ;; workflow-step-session-config (avoids the certain require cycle, P1).
   :resolve-inherited-defaults-fn
   (fn [ctx* parent-session-id* workflow-run* step-id*]
     (let [effective-config (workflow-step-session-config/resolve-step-session-config
                             ctx* parent-session-id* workflow-run* step-id*)]
       (workflow-step-session-config/effective-config->snapshot
        effective-config (:inherited-defaults workflow-run*))))
   :materialize-workflow-step-session-conversation-fn #'workflow-step-materialization/materialize-step-session-conversation
   :split-workflow-step-session-conversation-fn #'workflow-step-materialization/split-step-session-conversation
   :execute-workflow-judge-fn #'workflow-judge/execute-judge!
   :mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!
   :workflow-inflight-runs-handle workflow-runtime-state/inflight-runs
   :emit-background-job-terminal-messages-fn bg-rt/maybe-emit-background-job-terminal-messages!
   :reconcile-and-emit-background-job-terminals-fn bg-rt/reconcile-and-emit-background-job-terminals-in!
   :effective-cwd-fn (fn
                       ([_ctx] (throw (ex-info "effective-cwd-fn requires explicit session-id" {:callback :effective-cwd-fn})))
                       ([ctx session-id] (ss/session-worktree-path-in ctx session-id)))
   :now-fn java.time.Instant/now
   :scheduler-run-after-delay-fn (fn [ctx delay-ms f]
                                   ((:daemon-thread-fn ctx)
                                    (fn []
                                      (try
                                        (Thread/sleep ^long delay-ms)
                                        (f)
                                        (catch InterruptedException _
                                          ;; cancelled via scheduler-cancel-delay-fn — exit silently
                                          nil)))))
   :scheduler-cancel-delay-fn (fn [_ctx handle]
                                (when (instance? Thread handle)
                                  (.interrupt ^Thread handle)))
   :daemon-thread-fn dispatch-handlers/daemon-thread
   :drop-trailing-overflow-error-fn dispatch-effects/drop-trailing-overflow-error!
   :validate-dispatch-result-fn schema/validate-dispatch-schemas
   :validate-result-fn schema/validate-dispatch-schemas
   :register-projection-listener-fn (fn [_ctx listener-fn] (register-projection-listener! projection-listeners* listener-fn))
   :unregister-projection-listener-fn (fn [_ctx listener-id] (unregister-projection-listener! projection-listeners* listener-id))
   :publish-projection-change-fn (fn [_ctx change] (publish-projection-change! projection-listeners* change))
   :all-mutations mutations
   :all-mutations-atom (atom mutations)})

(defn- create-context* [{:keys [session-defaults compaction-fn branch-summary-fn agent-initial config cwd persist? session-root event-queue oauth-ctx recursion-ctx nrepl-runtime-atom ui-type mutations
                                create-workflow-child-session-fn execute-workflow-run-fn resume-and-execute-workflow-run-fn
                                scheduler-time-source install-default-ui-capability-provider?
                                get-session-data-fn list-context-sessions-fn find-skill-fn
                                resolve-workflow-step-session-config-fn materialize-workflow-step-session-conversation-fn
                                split-workflow-step-session-conversation-fn execute-workflow-judge-fn]
                         :or {persist? true mutations []
                              install-default-ui-capability-provider? true}
                         :as opts}]
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
                     :deterministic-operation-registry (deterministic-op-registry/create-registry)
                     :workflow-registry (extension-workflow-runtime/create-registry)
                     :service-registry (services/create-registry)
                     :project-nrepl-registry (project-nrepl-runtime/create-registry)
                     :post-tool-registry (post-tool/create-registry)
                     :event-queue event-queue
                     :cwd resolved-cwd
                     :persist? persist?
                     :session-root session-root
                     :oauth-ctx oauth-ctx
                     :recursion-ctx recursion-ctx
                     :compaction-fn (or compaction-fn compaction/stub-compaction-fn)
                     :branch-summary-fn (or branch-summary-fn compaction/stub-branch-summary-fn)
                     :config (merge session/default-config (or config {}))
                     :tool-batch-executor tool-batch-executor
                     :extension-run-fn-atom (atom nil)
                     :background-job-ui-refresh-fn (atom nil)
                     :ui-capability-provider* (atom (when install-default-ui-capability-provider?
                                                      (when-let [ui-type (:ui-type resolved-defaults)]
                                                        (case ui-type
                                                          :emacs ui-capabilities/emacs-make-visible-provider
                                                          (:tui :console) (ui-capabilities/unsupported-attached-provider ui-type)
                                                          nil))))
                     :scheduler-timers* (atom {})
                     :scheduler-time-source (or scheduler-time-source
                                                (scheduler-time/system-time-source))
                     :projection-listeners* projection-listeners*}
                    (callback-fns mutations projection-listeners*)
                    (cond-> {}
                      (contains? opts :create-workflow-child-session-fn)
                      (assoc :create-workflow-child-session-fn create-workflow-child-session-fn)

                      (contains? opts :execute-workflow-run-fn)
                      (assoc :execute-workflow-run-fn execute-workflow-run-fn)

                      (contains? opts :resume-and-execute-workflow-run-fn)
                      (assoc :resume-and-execute-workflow-run-fn resume-and-execute-workflow-run-fn)

                      (contains? opts :get-session-data-fn)
                      (assoc :get-session-data-fn get-session-data-fn)

                      (contains? opts :list-context-sessions-fn)
                      (assoc :list-context-sessions-fn list-context-sessions-fn)

                      (contains? opts :find-skill-fn)
                      (assoc :find-skill-fn find-skill-fn)

                      (contains? opts :resolve-workflow-step-session-config-fn)
                      (assoc :resolve-workflow-step-session-config-fn resolve-workflow-step-session-config-fn)

                      (contains? opts :materialize-workflow-step-session-conversation-fn)
                      (assoc :materialize-workflow-step-session-conversation-fn materialize-workflow-step-session-conversation-fn)

                      (contains? opts :split-workflow-step-session-conversation-fn)
                      (assoc :split-workflow-step-session-conversation-fn split-workflow-step-session-conversation-fn)

                      (contains? opts :execute-workflow-judge-fn)
                      (assoc :execute-workflow-judge-fn execute-workflow-judge-fn)))
        ctx0 (assoc ctx0 workflow-execution-adapter/adapter-key
                    (workflow-execution-adapter ctx0))
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
  (doseq [{:keys [session-id]} (ss/list-context-sessions-in ctx)]
    (turn-runtime/abort-active-turn-in! ctx session-id)
    (when-let [agent-ctx (ss/agent-ctx-in ctx session-id)]
      (agent-core/abort-in! agent-ctx))
    (psi.agent-session.tools/abort-bash!))
  (dispatch-effects/cancel-all-scheduler-timers!)
  (when-let [timers* (:scheduler-timers* ctx)]
    (reset! timers* {}))
  (shutdown-tool-batch-executor! (:tool-batch-executor ctx))
  nil)
