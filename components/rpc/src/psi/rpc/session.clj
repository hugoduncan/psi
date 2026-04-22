(ns psi.rpc.session
  "Session-bound RPC op routing, event emission, and async request handling."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.app-runtime.background-job-view :as app-bg-view]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.ai.models :as ai-models]
   [psi.ai.model-registry :as model-registry]
   [psi.rpc.events :as events]
   [psi.rpc.session.commands :as rpc.commands]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.session.frontend-actions :as frontend-actions]
   [psi.rpc.session.login :as login]
   [psi.rpc.session.navigation :as navigation]
   [psi.rpc.session.ops :as ops]
   [psi.rpc.session.prompt :as prompt]
   [psi.rpc.session.projections :as projections]
   [psi.rpc.session.streams :as streams]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [default-session-id-in error-frame response-frame targetable-rpc-ops]]))

(defn- start-daemon-thread!
  "Start a daemon thread running f with an optional name. Returns the Thread."
  ([f] (start-daemon-thread! f nil))
  ([f thread-name]
   (doto (Thread. ^Runnable f)
     (.setDaemon true)
     (cond-> thread-name (.setName thread-name))
     (.start))))

(defn- params-map
  [request]
  (let [params (:params request)]
    (when-not (or (nil? params) (map? params))
      (throw (ex-info "request :params must be a map"
                      {:error-code "request/invalid-params"})))
    (or params {})))

(defn- req-arg!
  [_request params k pred desc]
  (let [v (get params k)]
    (when-not (pred v)
      (throw (ex-info (str "invalid request parameter " k ": " desc)
                      {:error-code "request/invalid-params"})))
    v))

(defn- parse-query-edn!
  [query-str]
  (let [q (try
            (binding [*read-eval* false]
              (edn/read-string query-str))
            (catch Throwable _
              (throw (ex-info "query must be valid EDN"
                              {:error-code "request/invalid-query"}))))]
    (when-not (vector? q)
      (throw (ex-info "query must be an EDN vector"
                      {:error-code "request/invalid-query"})))
    q))

(defn- dialog-result-valid?
  [v]
  (or (nil? v) (boolean? v) (string? v)))

(defn- resolve-active-dialog!
  [ctx session-id dialog-id result]
  (sa/resolve-active-dialog-in! ctx session-id dialog-id result))

(defn- cancel-active-dialog!
  [ctx session-id]
  (sa/cancel-active-dialog-in! ctx session-id))

(defn- active-dialog-or-error!
  [ctx]
  (let [active (get-in (events/ui-snapshot ctx) [:active-dialog])]
    (when-not (map? active)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    active))

(defn- handle-resolve-dialog!
  [ctx request params session-id]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        result    (get params :result ::missing)
        _         (when (= ::missing result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        _         (when-not (dialog-result-valid? result)
                    (throw (ex-info "invalid request parameter :result: boolean, string, or nil"
                                    {:error-code "request/invalid-params"})))
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (resolve-active-dialog! ctx session-id dialog-id result)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "resolve_dialog" true {:accepted true})))

(defn- handle-cancel-dialog!
  [ctx request params session-id]
  (let [dialog-id (req-arg! request params :dialog-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        active    (active-dialog-or-error! ctx)
        active-id (:id active)]
    (when-not (= dialog-id active-id)
      (throw (ex-info "dialog-id mismatch"
                      {:error-code "request/dialog-id-mismatch"})))
    (when-not (cancel-active-dialog! ctx session-id)
      (throw (ex-info "no active dialog"
                      {:error-code "request/no-active-dialog"})))
    (response-frame (:id request) "cancel_dialog" true {:accepted true})))

(defn- request-thread-id
  [ctx session-id params]
  (let [target-id (:session-id params)
        sid       (or target-id session-id (default-session-id-in ctx))
        sd        (ss/get-session-data-in ctx sid)]
    (:session-id sd)))

(defn- normalize-statuses-param
  [statuses]
  (cond
    (nil? statuses) nil
    (sequential? statuses) (mapv (fn [s]
                                   (cond
                                     (keyword? s) s
                                     (string? s) (keyword s)
                                     :else s))
                                 statuses)
    :else ::invalid))

(defn- background-job->rpc-view
  [job]
  (let [summary (app-bg-view/job-summary job)]
    (merge
     (-> job
         (select-keys [:job-id
                       :thread-id
                       :tool-call-id
                       :tool-name
                       :job-kind
                       :workflow-ext-path
                       :workflow-id
                       :job-seq
                       :started-at
                       :completed-at
                       :completed-seq
                       :status
                       :terminal-payload
                       :terminal-payload-file
                       :cancel-requested-at
                       :terminal-message-emitted
                       :terminal-message-emitted-at])
         (update :started-at str)
         (update :completed-at #(when % (str %)))
         (update :cancel-requested-at #(when % (str %)))
         (update :terminal-message-emitted-at #(when % (str %))))
     {:summary {:job-id (:job/id summary)
                :tool-name (:job/tool-name summary)
                :status (:job/status summary)
                :status-label (:job/status-label summary)
                :is-terminal (:job/is-terminal summary)
                :is-running (:job/is-running summary)
                :is-cancelling (:job/is-cancelling summary)
                :list-line (:job/list-line summary)}})))

(defn- handle-list-background-jobs!
  [ctx request params session-id]
  (let [statuses* (normalize-statuses-param (:statuses params))]
    (when (= ::invalid statuses*)
      (throw (ex-info "invalid request parameter :statuses: sequential of keywords/strings"
                      {:error-code "request/invalid-params"})))
    (let [thread-id (request-thread-id ctx session-id params)
          jobs      (if statuses*
                      (bg-rt/list-background-jobs-in! ctx thread-id statuses*)
                      (bg-rt/list-background-jobs-in! ctx thread-id))]
      (response-frame (:id request) "list_background_jobs" true
                      {:jobs (mapv (comp background-job->rpc-view bg-jobs/eql->job bg-jobs/job->eql) jobs)}))))

(defn- handle-inspect-background-job!
  [ctx request params session-id]
  (let [job-id    (req-arg! request params :job-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        thread-id (request-thread-id ctx session-id params)
        job       (bg-rt/inspect-background-job-in! ctx thread-id job-id)]
    (response-frame (:id request) "inspect_background_job" true
                    {:job (background-job->rpc-view (bg-jobs/eql->job (bg-jobs/job->eql job)))})))

(defn- handle-cancel-background-job!
  [ctx request params session-id]
  (let [job-id    (req-arg! request params :job-id #(and (string? %) (not (str/blank? %))) "non-empty string")
        thread-id (request-thread-id ctx session-id params)
        job       (bg-rt/cancel-background-job-in! ctx thread-id job-id :user)]
    (response-frame (:id request) "cancel_background_job" true
                    {:accepted true
                     :job (background-job->rpc-view (bg-jobs/eql->job (bg-jobs/job->eql job)))})))

(defn- normalize-provider [provider]
  (cond
    (keyword? provider) provider
    (string? provider)  (keyword provider)
    :else               nil))

(defn resolve-model
  [provider model-id]
  (let [provider* (normalize-provider provider)]
    (or (model-registry/find-model provider* model-id)
        (some (fn [[_ model]]
                (when (and (= provider* (:provider model))
                           (= model-id (:id model)))
                  model))
              ai-models/all-models))))

(defn- current-ai-model
  ([ctx session-deps]
   (current-ai-model ctx session-deps nil))
  ([ctx {:keys [rpc-ai-model]} session-id]
   (let [sd (when session-id
              (ss/get-session-data-in ctx session-id))]
     (or (when-let [provider (get-in sd [:model :provider])]
           (when-let [model-id (get-in sd [:model :id])]
             (resolve-model provider model-id)))
         rpc-ai-model)))
  ([ctx session-deps _state session-id]
   (current-ai-model ctx session-deps session-id)))

(defn- effective-sync-on-git-head-change?
  [{:keys [sync-on-git-head-change?]}]
  (if (= ::default sync-on-git-head-change?)
    true
    (boolean sync-on-git-head-change?)))

(defn- exception->error-frame
  [request e]
  (let [code    (or (:error-code (ex-data e))
                    (when (= "Session is not idle" (ex-message e))
                      "request/session-not-idle")
                    "runtime/failed")
        message (or (ex-message e) "runtime request failed")
        conflict-data (when (= code "request/session-routing-conflict")
                        (select-keys (ex-data e)
                                     [:inflight-request-id :inflight-session-id :requested-session-id]))]
    (error-frame (cond-> {:id            (:id request)
                          :op            (:op request)
                          :error-code    code
                          :error-message message}
                   (seq conflict-data) (assoc :data conflict-data)))))

(defn- register-rpc-extension-run-fn!
  "Re-register the extension run-fn with an emit-frame!-aware implementation.

   The default run-fn registered in main.clj has no progress-queue, so
   extension-initiated agent runs (e.g. PSL munera/mementum sync) produce no streaming events
   visible to the RPC client. This version creates a progress-queue, polls
   it in a background loop, and routes events to emit-frame! — giving the
   PSL response the same streaming visibility as a normal user prompt.

   Called once from the subscribe handler. Guard via RPC-local state so it is
   only set up once per session connection."
  [ctx emit-frame! state session-id session-deps]
  (when-not (rpc.state/rpc-run-fn-registered? state)
    (rpc.state/mark-rpc-run-fn-registered! state)
    (let [ai-model-fn (fn [sid] (current-ai-model ctx session-deps sid))
          run-fn      (fn [text _source]
                        (try
                          (loop [attempt 0]
                            (if (ss/idle-in? ctx session-id)
                              (let [ai-model   (ai-model-fn session-id)
                                    session-model {:provider  (some-> (:provider ai-model) name)
                                                   :id        (:id ai-model)
                                                   :reasoning (boolean (:supports-reasoning ai-model))}
                                    api-key    (runtime/resolve-api-key-in ctx session-id ai-model)
                                    emit!      (emit/make-request-emitter emit-frame! state nil)
                                    progress-q (java.util.concurrent.LinkedBlockingQueue.)
                                    {:keys [stop? thread]}
                                    (streams/start-progress-loop!
                                     {:start-daemon-thread! start-daemon-thread!
                                      :ctx ctx
                                      :state state
                                      :session-id session-id
                                      :emit! emit!
                                      :progress-q progress-q
                                      :thread-name "rpc-poll-loop"})]
                                (dispatch/dispatch! ctx :session/set-model
                                                    {:session-id session-id
                                                     :model session-model
                                                     :scope :session}
                                                    {:origin :core})
                                (session/prompt-in! ctx session-id text nil
                                                    {:progress-queue progress-q
                                                     :runtime-opts (cond-> {}
                                                                     api-key (assoc :api-key api-key))})
                                (streams/stop-progress-loop! {:stop? stop?
                                                              :thread thread
                                                              :progress-q progress-q
                                                              :emit! emit!
                                                              :session-id session-id})
                                (when-let [assistant (session/last-assistant-message-in ctx session-id)]
                                  (emit/emit-assistant-message! emit! session-id assistant))
                                (emit/emit-session-snapshots! emit! ctx state session-id))
                              (when (< attempt 1200)
                                (Thread/sleep 250)
                                (recur (inc attempt)))))
                          (catch Exception e
                            (events/emit-event! emit-frame! state
                                                {:event "error"
                                                 :data  {:error-code    "runtime/failed"
                                                         :error-message (or (ex-message e) "extension run failed")}}))))]
      (ext-rt/set-extension-run-fn-in! ctx session-id run-fn))))

(defn- ensure-projection-listener!
  [ctx emit-frame! state]
  (projections/ensure-projection-listener! ctx emit-frame! state))

(defn- maybe-start-external-event-loop!
  [ctx emit-frame! state session-id]
  (when (and (:event-queue ctx)
             (nil? (rpc.state/external-event-loop state)))
    (let [event-queue (:event-queue ctx)
          loop-fut    (future
                        (binding [*out* (rpc.state/err-writer state)
                                  *err* (rpc.state/err-writer state)]
                          (loop []
                            (when-let [evt (.poll ^java.util.concurrent.LinkedBlockingQueue
                                            event-queue
                                                  20
                                                  java.util.concurrent.TimeUnit/MILLISECONDS)]
                              (when (= :external-message (:type evt))
                                (let [message (:message evt)
                                      target-session-id (or (:session-id evt) session-id)]
                                  (events/emit-event! emit-frame! state
                                                      {:event "assistant/message"
                                                       :data  (events/external-message->assistant-payload target-session-id message)})
                                  (events/emit-event! emit-frame! state
                                                      {:event "session/updated"
                                                       :data  (events/session-updated-payload ctx target-session-id)})
                                  (events/emit-event! emit-frame! state
                                                      {:event "footer/updated"
                                                       :data  (events/footer-updated-payload ctx target-session-id)}))))
                            (recur))))]
      (rpc.state/set-external-event-loop! state loop-fut)
      ;; Emit an immediate footer snapshot after starting the loop so subscribers
      ;; always observe footer state even if the loop is stopped immediately after
      ;; a single external assistant message is processed.
      (events/emit-event! emit-frame! state
                          {:event "footer/updated"
                           :data  (events/footer-updated-payload ctx session-id)}))))

(defn- valid-session-id-param!
  [session-id]
  (when session-id
    (when-not (string? session-id)
      (throw (ex-info "invalid request parameter :session-id: non-empty string"
                      {:error-code "request/invalid-params"})))
    (when (str/blank? session-id)
      (throw (ex-info "invalid request parameter :session-id: non-empty string"
                      {:error-code "request/invalid-params"}))))
  session-id)

(defn- request-session-id
  [ctx request state]
  (let [params (params-map request)]
    (or (valid-session-id-param! (:session-id params))
        (when state (events/focus-session-id state))
        (default-session-id-in ctx))))

(defn- prompt-request-args
  [ctx request emit-frame! state session-id session-deps]
  {:ctx ctx
   :request request
   :emit-frame! emit-frame!
   :state state
   :session-id session-id
   :session-deps session-deps
   :current-ai-model current-ai-model
   :effective-sync-on-git-head-change? effective-sync-on-git-head-change?
   :start-daemon-thread! start-daemon-thread!
   :login-handle-start-command! login/handle-login-start-command!
   :login-pending-state login/pending-login-state
   :login-complete-pending! login/complete-pending-login!})

(defn- navigation-request
  [ctx request emit-frame! state session-id]
  {:ctx ctx
   :request (assoc request :emit-frame! emit-frame!)
   :params (params-map request)
   :state state
   :session-id session-id})

(defn- prompt-ops-dispatch
  [ctx request params emit-frame! state session-id session-deps]
  (case (:op request)
    "prompt"
    (let [_message (req-arg! request params :message #(and (string? %) (not (str/blank? %))) "non-empty string")]
      (prompt/run-prompt-async! (prompt-request-args ctx request emit-frame! state session-id session-deps)))
    "prompt_while_streaming"
    (ops/handle-prompt-while-streaming {:ctx ctx :request request :params params :state state :session-id session-id})
    "steer"
    (ops/handle-steer {:ctx ctx :request request :params params :state state :session-id session-id})
    "follow_up"
    (ops/handle-follow-up {:ctx ctx :request request :params params :state state :session-id session-id})
    nil))

(defn- session-mutation-dispatch
  [ctx request params state session-id]
  (case (:op request)
    "abort" (ops/handle-abort {:ctx ctx :request request :state state :session-id session-id})
    "interrupt" (ops/handle-interrupt {:ctx ctx :request request :state state :session-id session-id})
    "set_session_name" (ops/handle-set-session-name {:ctx ctx :request request :params params :state state :session-id session-id})
    "set_model" (ops/handle-set-model {:ctx ctx :request request :params params :state state :session-id session-id :resolve-model resolve-model})
    "cycle_model" (ops/handle-cycle-model {:ctx ctx :request request :params params :state state :session-id session-id})
    "set_thinking_level" (ops/handle-set-thinking-level {:ctx ctx :request request :params params :state state :session-id session-id})
    "cycle_thinking_level" (ops/handle-cycle-thinking-level {:ctx ctx :request request :state state :session-id session-id})
    "compact" (ops/handle-compact {:ctx ctx :request request :params params :state state :session-id session-id})
    "set_auto_compaction" (ops/handle-set-auto-compaction {:ctx ctx :request request :params params :state state :session-id session-id})
    "set_auto_retry" (ops/handle-set-auto-retry {:ctx ctx :request request :params params :state state :session-id session-id})
    "get_state" (ops/handle-get-state {:ctx ctx :request request :state state :session-id session-id})
    "get_messages" (ops/handle-get-messages {:ctx ctx :request request :state state :session-id session-id})
    "get_session_stats" (ops/handle-get-session-stats {:ctx ctx :request request :state state :session-id session-id})
    nil))

(defn- background-job-dispatch
  [ctx request params session-id]
  (case (:op request)
    "list_background_jobs" (handle-list-background-jobs! ctx request params session-id)
    "inspect_background_job" (handle-inspect-background-job! ctx request params session-id)
    "cancel_background_job" (handle-cancel-background-job! ctx request params session-id)
    nil))

(defn- login-dispatch
  [ctx request params state session-id session-deps]
  (case (:op request)
    "login_begin" (login/handle-login-begin! {:ctx ctx :request request :params params :state state :session-id session-id :session-deps session-deps :current-ai-model current-ai-model})
    "login_complete" (login/handle-login-complete! {:ctx ctx :request request :params params :state state})
    nil))

(defn- navigation-dispatch
  [ctx request emit-frame! state session-id on-new-session!]
  (case (:op request)
    "new_session" (navigation/handle-new-session! {:ctx ctx
                                                   :request (assoc request :emit-frame! emit-frame!)
                                                   :state state
                                                   :session-id session-id
                                                   :on-new-session! on-new-session!})
    "switch_session" (navigation/handle-switch-session! (navigation-request ctx request emit-frame! state session-id))
    "list_sessions" (ops/handle-list-sessions {:ctx ctx :request request :state state :session-id session-id})
    "fork" (navigation/handle-fork! (navigation-request ctx request emit-frame! state session-id))
    nil))

(defn- subscribe-dispatch
  [ctx request emit-frame! params state session-id session-deps]
  (case (:op request)
    "subscribe" (ops/handle-subscribe {:ctx ctx :request (assoc request :emit-frame! emit-frame!) :params params :state state :session-id session-id :session-deps session-deps :maybe-start-external-event-loop! maybe-start-external-event-loop! :register-rpc-extension-run-fn! register-rpc-extension-run-fn! :ensure-projection-listener! ensure-projection-listener!})
    "unsubscribe" (ops/handle-unsubscribe {:ctx ctx :request request :params params :state state})
    nil))

(defn- dialog-dispatch
  [ctx request params session-id]
  (case (:op request)
    "resolve_dialog" (handle-resolve-dialog! ctx request params session-id)
    "cancel_dialog" (handle-cancel-dialog! ctx request params session-id)
    nil))

(defn- dispatch-rpc-op
  [{:keys [ctx request emit-frame! state session-id session-deps on-new-session!]}]
  (let [op     (:op request)
        params (params-map request)]
    (or
     (case op
       "ping" (ops/handle-ping request)
       "query_eql" (ops/handle-query-eql {:ctx ctx :request request :params params :session-id session-id :parse-query-edn! parse-query-edn!})
       "command" (rpc.commands/run-command! {:ctx ctx
                                             :request request
                                             :emit-frame! emit-frame!
                                             :state state
                                             :session-id session-id
                                             :session-deps session-deps
                                             :current-ai-model current-ai-model
                                             :start-daemon-thread! start-daemon-thread!
                                             :login-handler login/handle-login-start-command!})
       "frontend_action_result" (frontend-actions/handle-frontend-action-result! {:ctx ctx :request (assoc request :emit-frame! emit-frame!) :params params :state state :session-id session-id :resolve-model resolve-model})
       nil)
     (prompt-ops-dispatch ctx request params emit-frame! state session-id session-deps)
     (session-mutation-dispatch ctx request params state session-id)
     (background-job-dispatch ctx request params session-id)
     (login-dispatch ctx request params state session-id session-deps)
     (navigation-dispatch ctx request emit-frame! state session-id on-new-session!)
     (subscribe-dispatch ctx request emit-frame! params state session-id session-deps)
     (dialog-dispatch ctx request params session-id)
     (ops/handle-op-not-supported request))))

(defn make-session-request-handler
  "Create a canonical op router bound to an agent-session context.

   Returned fn signature matches `run-stdio-loop!` request-handler:
   (fn [request emit-frame! state] -> frame | [frame*] | nil)

   Runtime state mutations used by this handler:
   - subscribed topics / focus session / worker handles in RPC-local state

   OAuth pending-login for login_begin/login_complete and /login prompt flow
   is stored in the canonical session oauth projection, not in transport state.

   opts:
   - :rpc-ai-model fallback model when session model is absent
   - :on-new-session! optional callback used by /new command and new_session op
   - :sync-on-git-head-change? optional policy flag; ::default => enabled"
  ([ctx] (make-session-request-handler ctx {}))
  ([ctx session-deps]
   (let [session-deps (if (contains? session-deps :sync-on-git-head-change?)
                        session-deps
                        (assoc session-deps :sync-on-git-head-change? ::default))
         on-new-session! (:on-new-session! session-deps)]
     (fn [request emit-frame! state]
       (try
         (let [op         (:op request)
               _          (ensure-projection-listener! ctx emit-frame! state)
               session-id (when (contains? targetable-rpc-ops op)
                            (request-session-id ctx request state))]
           (dispatch-rpc-op {:ctx ctx
                             :request request
                             :emit-frame! emit-frame!
                             :state state
                             :session-id session-id
                             :session-deps session-deps
                             :on-new-session! on-new-session!}))
         (catch Throwable e
           (exception->error-frame request e)))))))
