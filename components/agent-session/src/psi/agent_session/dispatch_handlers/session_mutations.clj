(ns psi.agent-session.dispatch-handlers.session-mutations
  "Handlers for session mutation events:
   model, thinking-level, tools, prompt-mode, bootstrap, telemetry,
   steering/follow-up messages, compaction, runtime projections, interrupt,
   tool execution, skills, context usage, etc."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.session-state :as ss]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.tool-defs :as tool-defs]
   [psi.agent-session.tool-execution :as tool-exec]))

(defn- register-core-handler! [event handler]
  (dispatch/register-handler! event handler))

(defn- schedule-record
  [session-data schedule-id]
  (get-in session-data [:scheduler :schedules schedule-id]))

(defn- register-session-config-handlers! []
  (register-core-handler!
   :session/set-auto-compaction
   (fn [_ctx {:keys [session-id enabled?]}]
     (let [v (boolean enabled?)]
       {:root-state-update (session/session-update session-id #(assoc % :auto-compaction-enabled v))
        :return {:auto-compaction-enabled v}})))

  (register-core-handler!
   :session/set-auto-retry
   (fn [_ctx {:keys [session-id enabled?]}]
     (let [v (boolean enabled?)]
       {:root-state-update (session/session-update session-id #(assoc % :auto-retry-enabled v))
        :return {:auto-retry-enabled v}})))

  (register-core-handler!
   :session/set-ui-type
   (fn [_ctx {:keys [session-id ui-type]}]
     {:root-state-update (session/session-update session-id #(assoc % :ui-type ui-type))}))

  (register-core-handler!
   :session/set-model
   (fn [ctx {:keys [session-id model scope]}]
     (let [clamped-level  (session-data/clamp-thinking-level
                           (:thinking-level (session/get-session-data-in ctx session-id))
                           model)
           persist-effect (case (or scope :project)
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:model-provider  (:provider model)
                                              :model-id        (:id model)
                                              :thinking-level  clamped-level}}
                            :session nil
                            {:effect/type :persist/project-prefs-update
                             :prefs {:model-provider (:provider model)
                                     :model-id       (:id model)
                                     :thinking-level clamped-level}})]
       {:root-state-update (session/session-update session-id #(assoc % :model model :thinking-level clamped-level))
        :return {:model model :thinking-level clamped-level}
        :effects (cond-> [{:effect/type :runtime/agent-set-model
                           :model model}
                          {:effect/type :persist/journal-append-model-entry
                           :provider (:provider model)
                           :model-id (:id model)}
                          {:effect/type :notify/extension-dispatch
                           :event-name "model_select"
                           :payload {:model model :source :set}}]
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/set-thinking-level
   (fn [ctx {:keys [session-id level scope]}]
     (let [sd             (session/get-session-data-in ctx session-id)
           model          (:model sd)
           clamped        (if model (session-data/clamp-thinking-level level model) level)
           persist-effect (case (or scope :project)
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:thinking-level clamped}}
                            :session nil
                            {:effect/type :persist/project-prefs-update
                             :prefs {:thinking-level clamped}})]
       {:root-state-update (session/session-update session-id #(assoc % :thinking-level clamped))
        :return {:thinking-level clamped}
        :effects (cond-> [{:effect/type :runtime/agent-set-thinking-level
                           :level clamped}
                          {:effect/type :persist/journal-append-thinking-level-entry
                           :level clamped}]
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/set-worktree-path
   (fn [_ctx {:keys [session-id worktree-path]}]
     {:root-state-update (session/session-update session-id #(assoc % :worktree-path (str worktree-path)))}))

  (register-core-handler!
   :session/set-cache-breakpoints
   (fn [_ctx {:keys [session-id breakpoints]}]
     {:root-state-update (session/session-update session-id #(assoc % :cache-breakpoints (set (or breakpoints #{}))))}))

  (register-core-handler!
   :session/set-prompt-mode
   (fn [_ctx {:keys [session-id mode scope]}]
     (let [validated      (if (#{:lambda :prose} mode) mode :lambda)
           persist-effect (case (or scope :session)
                            :project {:effect/type :persist/project-prefs-update
                                      :prefs {:prompt-mode validated}}
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:prompt-mode validated}}
                            nil)]
       {:root-state-update (session/session-update session-id #(assoc % :prompt-mode validated))
        :effects (cond-> [{:effect/type :runtime/refresh-system-prompt
                           :session-id session-id}]
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/set-session-name
   (fn [_ctx {:keys [session-id name]}]
     {:root-state-update (session/session-update session-id #(assoc % :session-name name))
      :effects [{:effect/type :persist/journal-append-session-info-entry
                 :name name}
                {:effect/type :projection/context-changed
                 :session-id session-id
                 :reason :session/set-session-name}]
      :return {:session-name name}}))

  (register-core-handler!
   :session/cancel-job
   (fn [ctx {:keys [session-id job-id reason]}]
     (let [sd       (session/get-session-data-in ctx session-id)
           schedule (schedule-record sd job-id)]
       (if (contains? #{:pending :queued} (:status schedule))
         {:effects [{:effect/type :runtime/dispatch-event-with-effect-result
                     :event-type :scheduler/cancel
                     :event-data {:session-id session-id
                                  :schedule-id job-id}
                     :origin :core}]
          :return-effect-result? true}
         (let [store  (session/get-state-value-in ctx (session/state-path :background-jobs))
               state' (bg-jobs/request-cancel store {:thread-id session-id
                                                     :job-id    job-id
                                                     :requested-by (or reason :user)})
               job    (get-in state' [:jobs-by-id job-id])]
           {:root-state-update #(assoc-in % [:background-jobs :store] state')
            :effects [{:effect/type :background-job/cancel
                       :job-id      job-id
                       :job         job
                       :reason      (or reason :user)}]
            :return job})))))

  (register-core-handler!
   :session/login-begin
   (fn [_ctx {:keys [provider-id oauth-ctx]}]
     {:effects [{:effect/type :oauth/begin-login
                 :provider-id provider-id
                 :oauth-ctx   oauth-ctx}]
      :return-effect-result? true}))

  (register-core-handler!
   :session/logout
   (fn [_ctx {:keys [provider-ids oauth-ctx]}]
     {:effects [{:effect/type :oauth/logout
                 :provider-ids provider-ids
                 :oauth-ctx    oauth-ctx}]}))

  (register-core-handler!
   :session/remember
   (fn [_ctx {:keys [text memory-ctx provenance]}]
     {:effects [{:effect/type :memory/capture
                 :text        text
                 :memory-ctx  memory-ctx
                 :provenance  provenance}]
      :return-effect-result? true}))

  (register-core-handler!
   :session/login-begin
   (fn [_ctx {:keys [provider-id oauth-ctx]}]
     {:effects [{:effect/type :oauth/begin-login
                 :provider-id provider-id
                 :oauth-ctx oauth-ctx}]
      :return-effect-result? true}))

  (register-core-handler!
   :session/logout
   (fn [_ctx {:keys [provider-ids oauth-ctx]}]
     {:effects [{:effect/type :oauth/logout
                 :provider-ids provider-ids
                 :oauth-ctx oauth-ctx}]}))

  (register-core-handler!
   :session/reload-models
   (fn [ctx {:keys [session-id]}]
     (let [cwd (session/session-worktree-path-in ctx session-id)]
       {:effects [{:effect/type :model-registry/reload
                   :cwd cwd}]
        :return-effect-result? true})))

  (register-core-handler!
   :session/set-active-tools
   (fn [_ctx {:keys [session-id tool-maps]}]
     (let [tool-defs (tool-defs/normalize-tool-defs tool-maps)]
       {:root-state-update (session/session-update session-id #(assoc %
                                                                      :active-tools (->> tool-defs (map :name) set)
                                                                      :tool-defs tool-defs))
        :effects [{:effect/type :runtime/agent-set-tools
                   :tool-maps tool-defs}
                  {:effect/type :runtime/refresh-system-prompt
                   :session-id session-id}]})))

  (register-core-handler!
   :session/set-skills
   (fn [_ctx {:keys [session-id skills]}]
     {:root-state-update (session/session-update session-id #(assoc % :skills (vec (or skills []))))
      :effects [{:effect/type :runtime/refresh-system-prompt
                 :session-id session-id}]
      :return {:skills (vec (or skills []))}}))

  (register-core-handler!
   :session/set-prompt-component-selection
   (fn [_ctx {:keys [session-id selection]}]
     {:root-state-update (session/session-update session-id #(assoc % :prompt-component-selection selection))
      :effects [{:effect/type :runtime/refresh-system-prompt
                 :session-id session-id}]
      :return {:prompt-component-selection selection}})))

(defn- register-session-state-handlers! []
  (register-core-handler!
   :session/set-startup-bootstrap-summary
   (fn [_ctx {:keys [session-id summary]}]
     {:root-state-update (session/session-update session-id #(assoc % :startup-bootstrap summary))}))

  (register-core-handler!
   :session/update-context-usage
   (fn [_ctx {:keys [session-id tokens window]}]
     {:root-state-update (session/session-update session-id #(assoc % :context-tokens tokens :context-window window))}))

  (register-core-handler!
   :session/record-extension-prompt
   (fn [_ctx {:keys [session-id source delivery at]}]
     {:root-state-update (session/session-update session-id #(assoc %
                                                                    :extension-last-prompt-source   (some-> source str)
                                                                    :extension-last-prompt-delivery delivery
                                                                    :extension-last-prompt-at       at))}))

  (register-core-handler!
   :session/retarget-runtime-prompt-metadata
   (fn [_ctx _data]
     {:effects []})))

(defn- register-runtime-projection-handlers! []
  (register-core-handler!
   :session/set-rpc-trace
   (fn [_ctx {:keys [enabled? file]}]
     {:root-state-update #(ss/update-runtime-rpc-trace-state % enabled? file)}))

  (register-core-handler!
   :session/set-nrepl-runtime
   (fn [_ctx {:keys [runtime]}]
     {:root-state-update #(ss/update-nrepl-runtime-state % runtime)}))

  (register-core-handler!
   :session/set-oauth-projection
   (fn [_ctx {:keys [oauth]}]
     {:root-state-update #(ss/update-oauth-projection-state % oauth)}))

  (register-core-handler!
   :session/set-recursion-state
   (fn [_ctx {:keys [recursion-state]}]
     {:root-state-update #(ss/update-recursion-projection-state % recursion-state)}))

  (register-core-handler!
   :session/update-background-jobs-state
   (fn [_ctx {:keys [update-fn]}]
     {:root-state-update #(ss/update-background-jobs-store-state % update-fn)}))

  (register-core-handler!
   :session/set-turn-context
   (fn [_ctx {:keys [session-id turn-ctx]}]
     {:root-state-update (fn [state]
                           (assoc-in state (ss/session-turn-ctx-path session-id) turn-ctx))})))

(defn- register-telemetry-handlers! []
  (register-core-handler!
   :session/append-tool-call-attempt
   (fn [_ctx {:keys [session-id attempt]}]
     {:root-state-update (fn [state]
                           (update-in state (ss/session-telemetry-path session-id :tool-call-attempts)
                                      (fnil conj [])
                                      (assoc attempt :timestamp (java.time.Instant/now))))}))

  (register-core-handler!
   :session/append-provider-request-capture
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (ss/session-telemetry-path session-id :provider-requests)
                     #(ss/bounded-append 100 % entry)))})))

  (register-core-handler!
   :session/append-provider-reply-capture
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (ss/session-telemetry-path session-id :provider-replies)
                     #(ss/bounded-append 1000 % entry)))})))

  (register-core-handler!
   :session/record-tool-output-stat
   (fn [_ctx {:keys [session-id stat context-bytes-added limit-hit?]}]
     {:root-state-update
      (fn [state]
        (update-in state (ss/session-telemetry-path session-id :tool-output-stats)
                   (fn [ts]
                     (-> ts
                         (update :calls (fnil conj []) stat)
                         (update-in [:aggregates :total-context-bytes] (fnil + 0) context-bytes-added)
                         (update-in [:aggregates :by-tool (:tool-name stat)] (fnil + 0) context-bytes-added)
                         (update-in [:aggregates :limit-hits-by-tool (:tool-name stat)]
                                    (fnil + 0)
                                    (if limit-hit? 1 0))))))}))

  (register-core-handler!
   :session/tool-lifecycle-event
   (fn [_ctx {:keys [session-id entry]}]
     {:root-state-update (fn [state]
                           (update-in state (ss/session-telemetry-path session-id :tool-lifecycle-events)
                                      (fnil conj [])
                                      (assoc entry :timestamp (java.time.Instant/now))))})))

(defn- register-tool-execution-handlers! []
  (register-core-handler!
   :session/tool-agent-start
   (fn [_ctx {:keys [tool-call]}]
     {:effects [{:effect/type :runtime/agent-emit-tool-start
                 :tool-call tool-call}]}))

  (register-core-handler!
   :session/tool-agent-end
   (fn [_ctx {:keys [tool-call result is-error?]}]
     {:effects [{:effect/type :runtime/agent-emit-tool-end
                 :tool-call  tool-call
                 :result     result
                 :is-error?  is-error?}]}))

  (register-core-handler!
   :session/tool-agent-record-result
   (fn [_ctx {:keys [tool-result-msg]}]
     {:effects [{:effect/type :runtime/agent-record-tool-result
                 :tool-result-msg tool-result-msg}
                {:effect/type :persist/journal-append-message-entry
                 :message tool-result-msg}]}))

  (register-core-handler!
   :session/tool-execute
   (fn [_ctx {:keys [session-id tool-name args opts]}]
     {:effects [{:effect/type :runtime/tool-execute
                 :session-id session-id
                 :tool-name  tool-name
                 :args       args
                 :opts       opts}]
      :return-effect-result? true}))

  (register-core-handler!
   :session/post-tool-run
   (fn [ctx {:keys [session-id tool-name tool-call-id tool-args tool-result worktree-path dispatch-id] :as input}]
     {:return (post-tool/run-post-tool-processing-direct-in!
               ctx
               (assoc input
                      :session-id session-id
                      :tool-name tool-name
                      :tool-call-id tool-call-id
                      :tool-args tool-args
                      :tool-result tool-result
                      :worktree-path worktree-path
                      :dispatch-id dispatch-id))}))

  (register-core-handler!
   :session/tool-execute-prepared
   (fn [ctx {:keys [session-id tool-call parsed-args progress-queue]}]
     {:return (tool-exec/execute-tool-call-prepared! ctx session-id tool-call parsed-args progress-queue)}))

  (register-core-handler!
   :session/tool-record-result
   (fn [ctx {:keys [session-id shaped-result progress-queue]}]
     {:return (tool-exec/record-tool-call-prepared-result! ctx session-id shaped-result progress-queue)}))

  (register-core-handler!
   :session/tool-run
   (fn [ctx {:keys [session-id tool-call parsed-args progress-queue]}]
     {:return (let [shaped-result (dispatch/dispatch! ctx :session/tool-execute-prepared
                                                      {:session-id     session-id
                                                       :tool-call      tool-call
                                                       :parsed-args    parsed-args
                                                       :progress-queue progress-queue}
                                                      {:origin :core})]
                (dispatch/dispatch! ctx :session/tool-record-result
                                    {:session-id     session-id
                                     :shaped-result  shaped-result
                                     :progress-queue progress-queue}
                                    {:origin :core}))})))

(defn- register-message-and-skill-handlers! []
  (register-core-handler!
   :session/enqueue-steering-message
   (fn [_ctx {:keys [session-id text]}]
     {:root-state-update (session/session-update session-id #(update % :steering-messages (fnil conj []) text))
      :effects [{:effect/type :runtime/agent-queue-steering
                 :message {:role      "user"
                           :content   [{:type :text :text text}]
                           :timestamp (java.time.Instant/now)}}]}))

  (register-core-handler!
   :session/enqueue-follow-up-message
   (fn [_ctx {:keys [session-id text]}]
     {:root-state-update (session/session-update session-id #(update % :follow-up-messages (fnil conj []) text))
      :effects [{:effect/type :runtime/agent-queue-follow-up
                 :message {:role      "user"
                           :content   [{:type :text :text text}]
                           :timestamp (java.time.Instant/now)}}]}))

  (register-core-handler!
   :session/clear-queued-messages
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :steering-messages [] :follow-up-messages []))
      :effects [{:effect/type :runtime/agent-clear-steering-queue}
                {:effect/type :runtime/agent-clear-follow-up-queue}]}))

  (register-core-handler!
   :session/compaction-finished
   (fn [_ctx {:keys [session-id messages]}]
     (cond-> {:root-state-update (session/session-update session-id #(assoc % :is-compacting false :context-tokens nil))}
       messages
       (assoc :effects [{:effect/type :runtime/agent-replace-messages
                         :messages messages}]))))

  (register-core-handler!
   :session/register-skill
   (fn [ctx {:keys [session-id skill]}]
     (let [skills     (vec (:skills (session/get-session-data-in ctx session-id)))
           existing?  (some #(= (:name %) (:name skill)) skills)
           next-count (if existing? (count skills) (inc (count skills)))]
       (cond-> {:return {:added? (not existing?) :count next-count}}
         (not existing?)
         (assoc :root-state-update (session/session-update session-id #(update % :skills (fnil conj []) skill))
                :effects [{:effect/type :runtime/refresh-system-prompt
                           :session-id session-id}])))))

  (register-core-handler!
   :session/request-interrupt
   (fn [ctx {:keys [session-id already-pending? requested-at]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       {:root-state-update
        (session/session-update session-id
                                (fn [_]
                                  (cond-> (assoc sd
                                                 :interrupt-pending true
                                                 :steering-messages [])
                                    (not already-pending?)
                                    (assoc :interrupt-requested-at requested-at))))
        :effects [{:effect/type :runtime/agent-clear-steering-queue}]})))

  (register-core-handler!
   :session/notify-extension
   (fn [_ctx {:keys [session-id message]}]
     {:effects [{:effect/type :runtime/agent-append-message
                 :message message}
                {:effect/type :persist/journal-append-message-entry
                 :message message}
                {:effect/type :runtime/agent-emit
                 :event {:type :message-start :message message}}
                {:effect/type :runtime/agent-emit
                 :event {:type :message-end :message message}}
                {:effect/type :runtime/event-queue-offer
                 :event {:type :external-message
                         :session-id session-id
                         :message message}}]}))

  (register-core-handler!
   :session/append-extension-message
   (fn [_ctx {:keys [session-id message]}]
     {:effects [{:effect/type :runtime/agent-append-message
                 :message message}
                {:effect/type :persist/journal-append-message-entry
                 :message message}
                {:effect/type :runtime/agent-emit
                 :event {:type :message-start :message message}}
                {:effect/type :runtime/agent-emit
                 :event {:type :message-end :message message}}
                {:effect/type :runtime/event-queue-offer
                 :event {:type :external-message
                         :session-id session-id
                         :message message}}]}))

  (register-core-handler!
   :session/schedule-extension-event
   (fn [_ctx {:keys [delay-ms event-name payload]}]
     {:effects [{:effect/type :runtime/schedule-extension-dispatch
                 :delay-ms delay-ms
                 :event-name event-name
                 :payload payload}]
      :return {:scheduled? true
               :delay-ms delay-ms
               :event-name event-name}}))

  (register-core-handler!
   :session/add-tool
   (fn [ctx {:keys [session-id tool]}]
     (let [tools     (:tools (agent/get-data-in (session/agent-ctx-in ctx session-id)))
           existing? (some #(= (:name %) (:name tool)) tools)]
       (cond-> {:return {:added? (not existing?)
                         :count  (if existing? (count tools) (inc (count tools)))}}
         (not existing?)
         (assoc :effects [{:effect/type :runtime/agent-set-tools
                           :tool-maps (conj (vec tools) tool)}]))))))

(defn register!
  "Register all session mutation handlers. Called once during context creation."
  [_ctx]
  ;; prompt lifecycle registration now lives in psi.agent-session.dispatch-handlers.prompt-lifecycle
  (register-session-config-handlers!)
  (register-session-state-handlers!)
  (register-runtime-projection-handlers!)
  (register-telemetry-handlers!)
  (register-tool-execution-handlers!)
  (register-message-and-skill-handlers!))
