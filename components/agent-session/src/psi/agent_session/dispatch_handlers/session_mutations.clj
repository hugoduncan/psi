(ns psi.agent-session.dispatch-handlers.session-mutations
  "Handlers for session mutation events:
   model, thinking-level, tools, prompt-mode, bootstrap, telemetry,
   steering/follow-up messages, compaction, runtime projections, interrupt,
   tool execution, skills, context usage, etc."
  (:require
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.journal-append-effect :as journal-append-effect]
   [psi.agent-session.model-capabilities :as model-capabilities]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.session-persistence.core :as persist]
   [psi.session-state.init :as ss]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.post-tool :as post-tool]
   [psi.session-state.model :as session-data]
   [psi.session-state.state :as session]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.tool-registry.defs :as tool-defs]
   [psi.agent-session.tool-runtime-adapter :as tool-runtime-adapter]))

(defn- register-core-handler! [event handler]
  (kernel/register-handler! event handler))

(defn- schedule-record
  [session-data schedule-id]
  (get-in session-data [:scheduler :schedules schedule-id]))

(defn- conversational-entry
  [entry]
  (case (:kind entry)
    :message
    (let [role (get-in entry [:data :message :role])]
      (when (#{{"user" "assistant"} "user" "assistant"} role)
        {:kind :message :role role}))

    :mid-system
    {:kind :mid-system :role "system"}

    nil))

(defn- latest-conversational-entry
  [entries]
  (last (keep conversational-entry entries)))

(defn- mid-system-placement-error
  [entries]
  (let [latest (latest-conversational-entry entries)]
    (case (:role latest)
      nil         {:ok false :error :invalid-placement :reason :no-preceding-user}
      "assistant" {:ok false :error :invalid-placement :reason :after-assistant}
      "system"    {:ok false :error :invalid-placement :reason :pending-mid-system}
      "user"      nil
      {:ok false :error :invalid-placement :reason :no-preceding-user})))

(defn- mid-system-entry
  [text source]
  (session-data/make-entry :mid-system {:text (str text) :source source}))

(defn- update-session-profile-settings
  [session-data settings]
  (let [model            (:model settings)
        model?           (contains? settings :model)
        requested-level  (:thinking-level settings)
        level?           (contains? settings :thinking-level)
        current-level    (:thinking-level session-data)
        after-model      (if model?
                           (assoc session-data
                                  :model model
                                  :thinking-level (session-data/clamp-thinking-level current-level model))
                           session-data)
        after-thinking   (if level?
                           (assoc after-model
                                  :thinking-level (session-data/clamp-thinking-level requested-level (:model after-model)))
                           after-model)]
    (cond-> after-thinking
      (contains? settings :speed-mode)
      (assoc :speed-mode (when (= :fast (:speed-mode settings)) :fast))

      (contains? settings :effort-override)
      (assoc :effort-override (:effort-override settings)))))

(defn- session-profile-effects
  [session-id profile final-session-data]
  (let [settings (:settings profile)]
    (cond-> []
      (contains? settings :model)
      (conj {:effect/type :runtime/agent-set-model
             :model (:model settings)}
            (journal-append-effect/append-model-effect session-id
                                                       (:provider (:model settings))
                                                       (:id (:model settings)))
            {:effect/type :notify/extension-dispatch
             :event-name "model_select"
             :payload {:model (:model settings) :source :session-profile}})

      (contains? settings :thinking-level)
      (conj {:effect/type :runtime/agent-set-thinking-level
             :level (:thinking-level final-session-data)}
            (journal-append-effect/append-thinking-level-effect
             session-id
             (:thinking-level final-session-data)))

      (contains? settings :speed-mode)
      (conj {:effect/type :runtime/agent-set-speed-mode
             :mode (:speed-mode final-session-data)})

      (contains? settings :effort-override)
      (conj {:effect/type :runtime/agent-set-effort-override
             :effort (:effort-override final-session-data)}))))

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
     (let [runtime-model   (cond-> model
                             (and (not (contains? model :reasoning))
                                  (contains? model :supports-reasoning))
                             (assoc :reasoning (boolean (:supports-reasoning model))))
           clamped-level  (session-data/clamp-thinking-level
                           (:thinking-level (session/get-session-data-in ctx session-id))
                           runtime-model)
           persist-effect (case (or scope :project)
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:model-provider  (:provider runtime-model)
                                              :model-id        (:id runtime-model)
                                              :thinking-level  clamped-level}}
                            :session nil
                            {:effect/type :persist/project-prefs-update
                             :prefs {:model-provider (:provider runtime-model)
                                     :model-id       (:id runtime-model)
                                     :thinking-level clamped-level}})]
       {:root-state-update (session/session-update session-id #(assoc % :model runtime-model :thinking-level clamped-level))
        :return {:model runtime-model :thinking-level clamped-level}
        :effects (cond-> [{:effect/type :runtime/agent-set-model
                           :model runtime-model}
                          (journal-append-effect/append-model-effect session-id (:provider runtime-model) (:id runtime-model))
                          {:effect/type :notify/extension-dispatch
                           :event-name "model_select"
                           :payload {:model runtime-model :source :set}}]
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
                          (journal-append-effect/append-thinking-level-effect session-id clamped)]
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/apply-session-profile
   (fn [ctx {:keys [session-id profile]}]
     (let [current-sd (session/get-session-data-in ctx session-id)
           final-sd   (assoc (update-session-profile-settings current-sd (:settings profile))
                             :selected-session-profile profile)]
       {:root-state-update (session/session-update session-id (constantly final-sd))
        :return {:selected-session-profile profile
                 :model (:model final-sd)
                 :thinking-level (:thinking-level final-sd)
                 :speed-mode (:speed-mode final-sd)
                 :effort-override (:effort-override final-sd)}
        :effects (session-profile-effects session-id profile final-sd)})))

  (register-core-handler!
   :session/clear-session-profile
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :selected-session-profile nil))
      :return {:selected-session-profile nil}}))

  (register-core-handler!
   :session/set-speed-mode
   (fn [_ctx {:keys [session-id mode scope]}]
     (let [session-mode   (if (= :session (or scope :session))
                            (when (= :fast mode) :fast)
                            mode)
           persist-effect (case scope
                            :project {:effect/type :persist/project-prefs-update
                                      :prefs {:speed-mode mode}}
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:speed-mode mode}}
                            nil)]
       {:root-state-update (session/session-update session-id #(assoc % :speed-mode session-mode))
        :return {:speed-mode session-mode}
        :effects (cond-> []
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/set-effort-override
   (fn [_ctx {:keys [session-id effort scope]}]
     (let [persist-effect (case scope
                            :project {:effect/type :persist/project-prefs-update
                                      :prefs {:effort-override effort}}
                            :user    {:effect/type :persist/user-config-update
                                      :prefs {:effort-override effort}}
                            nil)]
       {:root-state-update (session/session-update session-id #(assoc % :effort-override effort))
        :return {:effort-override effort}
        :effects (cond-> []
                   persist-effect (conj persist-effect))})))

  (register-core-handler!
   :session/set-worktree-path
   (fn [_ctx {:keys [session-id worktree-path]}]
     {:root-state-update (session/session-update session-id #(assoc % :worktree-path (str worktree-path)))}))

  (register-core-handler!
   :session/inject-mid-system-message
   (fn [ctx {:keys [session-id text source]}]
     (cond
       (not (model-capabilities/session-supports-mid-system-messages? ctx session-id))
       {:return {:ok false :error :capability-not-supported}}

       :else
       (let [entries (persist/all-entries-in ctx session-id)]
         (if-let [placement-error (mid-system-placement-error entries)]
           {:return placement-error}
           (let [entry       (mid-system-entry text (or source :extension))
                 next-entries (conj entries entry)
                 flush-state  (session/get-state-value-in ctx (session/state-path :flush-state session-id))
                 session-data (session/get-session-data-in ctx session-id)
                 io-request   (persist/persistence-io-request {:entries next-entries
                                                               :flush-state flush-state
                                                               :session-id session-id
                                                               :worktree-path (:worktree-path session-data)
                                                               :parent-session-id (:parent-session-id session-data)
                                                               :parent-session-path (:parent-session-path session-data)})]
             (cond-> {:root-state-update (persist/append-journal-entry-root-update session-id entry)
                      :return {:ok true}}
               io-request
               (assoc :effects [{:effect/type :persist/session-journal-io
                                 :session-id session-id
                                 :request io-request}]))))))))

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
      :effects [(journal-append-effect/append-session-info-effect session-id name)
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
   :session/reload-prompts
   (fn [ctx {:keys [session-id]}]
     (let [worktree-path (session/session-worktree-path-in ctx session-id)
           opts          {:global-prompts-dir  (:global-prompts-dir pt/default-config)
                          :project-prompts-dir (str worktree-path "/.psi/prompts")}
           discovered    (pt/discover-templates opts)]
       {:root-state-update (session/session-update
                            session-id
                            #(assoc % :prompt-templates discovered))
        :return {:reloaded? true
                 :count     (count discovered)
                 :worktree  worktree-path}})))

  (register-core-handler!
   :session/set-active-tools
   (fn [_ctx {:keys [session-id tool-maps]}]
     (let [normalized (tool-defs/normalize-tool-defs tool-maps)]
       {:root-state-update (session/session-update session-id #(assoc % :tool-ids (mapv :name normalized)))
        :effects [{:effect/type :runtime/agent-set-tools
                   :tool-maps normalized}
                  {:effect/type :runtime/refresh-system-prompt
                   :session-id session-id}]})))

  (register-core-handler!
   :session/set-skills
   (fn [_ctx {:keys [session-id skills]}]
     (let [{:keys [root-state skills]} (skill-storage/set-skills-in-root-state _ctx session-id skills)]
       {:root-state-update (constantly root-state)
        :effects [{:effect/type :runtime/refresh-system-prompt
                   :session-id session-id}]
        :return {:skills skills}})))

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
                           (assoc-in state (session/session-turn-ctx-path session-id) turn-ctx))})))

(defn- register-telemetry-handlers! []
  (register-core-handler!
   :session/append-tool-call-attempt
   (fn [_ctx {:keys [session-id attempt]}]
     {:root-state-update (fn [state]
                           (update-in state (session/session-telemetry-path session-id :tool-call-attempts)
                                      (fnil conj [])
                                      (assoc attempt :timestamp (java.time.Instant/now))))}))

  (register-core-handler!
   :session/append-provider-request-capture
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (session/session-telemetry-path session-id :provider-requests)
                     #(ss/bounded-append 100 % entry)))})))

  (register-core-handler!
   :session/append-provider-reply-capture
   (fn [_ctx {:keys [session-id capture]}]
     (let [entry (assoc capture :timestamp (java.time.Instant/now))]
       {:root-state-update
        (fn [state]
          (update-in state (session/session-telemetry-path session-id :provider-replies)
                     #(ss/bounded-append 1000 % entry)))})))

  (register-core-handler!
   :session/record-tool-output-stat
   (fn [_ctx {:keys [session-id stat context-bytes-added limit-hit?]}]
     {:root-state-update
      (fn [state]
        (update-in state (session/session-telemetry-path session-id :tool-output-stats)
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
                           (update-in state (session/session-telemetry-path session-id :tool-lifecycle-events)
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
   (fn [ctx {:keys [session-id tool-result-msg]}]
     ;; At-most-once toolResult per tool-call-id (first-writer-wins). The
     ;; canonical recorded-tool-result-ids set in :state* is the single source
     ;; of truth; every producer (interrupt paths + real-result re-dispatch)
     ;; funnels through this event, so a pure guard here covers them all.
     ;; Atomicity comes from dispatch serialization (single writer to :state*),
     ;; not a runtime test-and-set.
     (let [tool-call-id    (:tool-call-id tool-result-msg)
           recorded-ids-path (session/session-recorded-tool-result-ids-path
                              session-id)
           recorded-ids    (or (session/get-state-value-in ctx recorded-ids-path)
                               #{})]
       (if (contains? recorded-ids tool-call-id)
         {:effects []}
         {:root-state-update
          (fn [state]
            (update-in state recorded-ids-path (fnil conj #{}) tool-call-id))
          :effects [{:effect/type :runtime/agent-record-tool-result
                     :tool-result-msg tool-result-msg}
                    (journal-append-effect/append-message-effect session-id tool-result-msg)]}))))

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
     {:return (tool-runtime-adapter/execute-tool-call-prepared! ctx session-id tool-call parsed-args progress-queue)}))

  (register-core-handler!
   :session/tool-record-result
   (fn [ctx {:keys [session-id shaped-result progress-queue]}]
     {:return (tool-runtime-adapter/record-tool-call-prepared-result! ctx session-id shaped-result progress-queue)}))

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
     (let [{:keys [root-state added? changed? count skills]}
           (skill-storage/register-skill-in-root-state @(:state* ctx) session-id skill)]
       {:root-state-update (constantly root-state)
        :return {:added? added? :changed? changed? :count count :skills skills}
        :effects (when changed?
                   [{:effect/type :runtime/refresh-system-prompt
                     :session-id session-id}])})))

  (register-core-handler!
   :session/request-interrupt
   (fn [ctx {:keys [session-id already-pending? requested-at reason]}]
     (let [sd (session/get-session-data-in ctx session-id)]
       {:root-state-update
        (session/session-update session-id
                                (fn [_]
                                  (cond-> (assoc sd
                                                 :interrupt-pending true
                                                 :interrupt-reason (or reason :deferred-interrupt)
                                                 :steering-messages [])
                                    (not already-pending?)
                                    (assoc :interrupt-requested-at requested-at))))
        :effects [{:effect/type :runtime/agent-clear-steering-queue}]})))

  (register-core-handler!
   :session/notify-extension
   (fn [_ctx {:keys [session-id message]}]
     {:effects [{:effect/type :runtime/agent-append-message
                 :message message}
                (journal-append-effect/append-message-effect session-id message)
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
                {:effect/type :runtime/dispatch-event
                 :event-type :session/append-journal-entry
                 :event-data {:session-id session-id
                              :entry (persist/message-entry message)}
                 :origin :core}
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
     (let [sd           (session/get-session-data-in ctx session-id)
           tool-source  (session/agent-tool-source-in ctx session-id)
           current      (tool-defs/resolve-tool-defs tool-source (:tool-ids sd))
           existing?    (some #(= (:name %) (:name tool)) current)]
       (if existing?
         {:return {:added? false :count (count current)}}
         (let [normalized (tool-defs/normalize-tool-defs (conj (vec current) tool))]
           {:root-state-update (session/session-update session-id #(assoc % :tool-ids (mapv :name normalized)))
            :effects [{:effect/type :runtime/agent-set-tools
                       :tool-maps normalized}]
            :return {:added? true :count (count normalized)}}))))))

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
