(ns psi.agent-session.mutations.session
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.ai.models :as models]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as core]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-runtime :as runtime]
   [psi.agent-session.session-state :as ss]))

(pco/defmutation set-session-name
  "Set the human-readable name of the current session."
  [_ {:keys [psi/agent-session-ctx session-id name]}]
  {::pco/op-name 'psi.extension/set-session-name
   ::pco/params  [:psi/agent-session-ctx :session-id :name]
   ::pco/output  [:psi.agent-session/session-name]}
  (dispatch/dispatch! agent-session-ctx :session/set-session-name {:session-id session-id :name name} {:origin :mutations})
  {:psi.agent-session/session-name name})

(pco/defmutation set-model
  "Set the active model and clamp thinking-level for the current session.
   Optional :scope — :session (runtime only), :project (default), :user (user-global)."
  [_ {:keys [psi/agent-session-ctx session-id model scope]}]
  {::pco/op-name 'psi.extension/set-model
   ::pco/params  [:psi/agent-session-ctx :session-id :model]
   ::pco/output  [:psi.agent-session/model
                  :psi.agent-session/thinking-level]}
  (let [result (dispatch/dispatch! agent-session-ctx :session/set-model
                                   (cond-> {:session-id session-id :model model}
                                     scope (assoc :scope scope))
                                   {:origin :mutations})]
    {:psi.agent-session/model          (:model result)
     :psi.agent-session/thinking-level (:thinking-level result)}))

(pco/defmutation set-worktree-path
  "Set the canonical worktree-path for the current session."
  [_ {:keys [psi/agent-session-ctx session-id worktree-path]}]
  {::pco/op-name 'psi.extension/set-worktree-path
   ::pco/params  [:psi/agent-session-ctx :session-id :worktree-path]
   ::pco/output  [:psi.agent-session/worktree-path]}
  (dispatch/dispatch! agent-session-ctx
                      :session/set-worktree-path
                      {:session-id session-id
                       :worktree-path worktree-path}
                      {:origin :mutations})
  {:psi.agent-session/worktree-path worktree-path})

(pco/defmutation create-session
  "Create a new session branch with optional name, worktree, system prompt, and thinking level."
  [_ {:keys [psi/agent-session-ctx parent-session-id session-name worktree-path system-prompt thinking-level]}]
  {::pco/op-name 'psi.extension/create-session
   ::pco/params  [:psi/agent-session-ctx :parent-session-id]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/worktree-path
                  :psi.agent-session/thinking-level]}
  (let [sd      (core/new-session-in! agent-session-ctx parent-session-id {:session-name  session-name
                                                                           :worktree-path worktree-path})
        new-sid (:session-id sd)
        _       (when system-prompt
                  (dispatch/dispatch! agent-session-ctx :session/set-system-prompt {:session-id new-sid :prompt system-prompt} {:origin :mutations}))
        _       (when thinking-level
                  (dispatch/dispatch! agent-session-ctx :session/set-thinking-level {:session-id new-sid :level thinking-level} {:origin :mutations}))
        sd      (ss/get-session-data-in agent-session-ctx new-sid)]
    {:psi.agent-session/session-id     (:session-id sd)
     :psi.agent-session/session-name   (:session-name sd)
     :psi.agent-session/worktree-path  (:worktree-path sd)
     :psi.agent-session/thinking-level (:thinking-level sd)}))

(pco/defmutation create-child-session
  "Create a child session for agent execution without switching active session.
  Returns the child session-id. The child shares the parent's context but has
  its own journal, telemetry, and session data."
  [_ {:keys [psi/agent-session-ctx session-id session-name system-prompt tool-defs thinking-level model skills developer-prompt developer-prompt-source preloaded-messages cache-breakpoints prompt-component-selection workflow-run-id workflow-step-id workflow-attempt-id workflow-owned?]}]
  {::pco/op-name 'psi.extension/create-child-session
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/session-id]}
  (let [child-sid (str (java.util.UUID/randomUUID))]
    (dispatch/dispatch! agent-session-ctx
                        :session/create-child
                        (cond-> {:session-id       session-id
                                 :child-session-id child-sid
                                 :session-name     session-name
                                 :system-prompt    system-prompt
                                 :tool-defs        tool-defs
                                 :thinking-level   thinking-level}
                          (some? model)
                          (assoc :model model)

                          (some? skills)
                          (assoc :skills skills)

                          (some? preloaded-messages)
                          (assoc :preloaded-messages preloaded-messages)

                          (some? cache-breakpoints)
                          (assoc :cache-breakpoints cache-breakpoints)

                          (some? prompt-component-selection)
                          (assoc :prompt-component-selection prompt-component-selection)

                          (some? developer-prompt)
                          (assoc :developer-prompt developer-prompt)

                          (some? developer-prompt-source)
                          (assoc :developer-prompt-source developer-prompt-source)

                          (some? workflow-run-id)
                          (assoc :workflow-run-id workflow-run-id)

                          (some? workflow-step-id)
                          (assoc :workflow-step-id workflow-step-id)

                          (some? workflow-attempt-id)
                          (assoc :workflow-attempt-id workflow-attempt-id)

                          (contains? {:workflow-owned? workflow-owned?} :workflow-owned?)
                          (assoc :workflow-owned? workflow-owned?))
                        {:origin :mutations})
    (let [sd       (ss/get-session-data-in agent-session-ctx child-sid)
          messages (vec (or preloaded-messages []))
          fresh    (runtime/create-runtime!
                    agent-session-ctx child-sid
                    {:session-data  sd
                     :messages      messages
                     :agent-initial (:agent-initial agent-session-ctx)})]
      (swap! (:state* agent-session-ctx)
             (fn [state]
               (-> state
                   (assoc-in [:agent-session :sessions child-sid :agent-ctx] (:agent-ctx fresh))
                   (assoc-in [:agent-session :sessions child-sid :sc-session-id] (:sc-session-id fresh)))))
      (when (seq messages)
        (agent/replace-messages-in! (:agent-ctx fresh) messages)))
    {:psi.agent-session/session-id child-sid}))

(pco/defmutation run-agent-loop-in-session
  "Run the prompt lifecycle for a specific child session.
  Scopes the ctx to the target session-id and blocks until the turn completes.
  Returns the final assistant result summary."
  [_ {:keys [psi/agent-session-ctx session-id prompt model api-key]}]
  {::pco/op-name 'psi.extension/run-agent-loop-in-session
   ::pco/params  [:psi/agent-session-ctx :session-id :prompt]
   ::pco/output  [:psi.agent-session/agent-run-ok?
                  :psi.agent-session/agent-run-text
                  :psi.agent-session/agent-run-elapsed-ms
                  :psi.agent-session/agent-run-error-message]}
  (let [session-model  (:model (ss/get-session-data-in agent-session-ctx session-id))
        resolved-model (or model
                           (when session-model
                             (or (model-registry/find-model
                                  (keyword (:provider session-model))
                                  (:id session-model))
                                 (some (fn [m]
                                         (when (and (= (:provider m) (keyword (:provider session-model)))
                                                    (= (:id m) (:id session-model)))
                                           m))
                                       (vals models/all-models))))
                           (get models/all-models :sonnet-4.6))
        started-ms     (System/currentTimeMillis)]
    (try
      (dispatch/dispatch! agent-session-ctx :session/set-model
                          {:session-id session-id
                           :model resolved-model
                           :scope :session}
                          {:origin :mutations})
      (core/prompt-in! agent-session-ctx session-id (or prompt "") nil
                       {:runtime-opts (cond-> {}
                                        api-key (assoc :api-key api-key))})
      (let [result (core/last-assistant-message-in agent-session-ctx session-id)
            text   (->> (:content result)
                        (keep (fn [c]
                                (case (:type c)
                                  :text (:text c)
                                  :error (:text c)
                                  nil)))
                        (clojure.string/join "\n"))
            ok?    (not= :error (:stop-reason result))]
        {:psi.agent-session/agent-run-ok?           ok?
         :psi.agent-session/agent-run-text          text
         :psi.agent-session/agent-run-elapsed-ms    (- (System/currentTimeMillis) started-ms)
         :psi.agent-session/agent-run-error-message (:error-message result)})
      (catch Throwable e
        {:psi.agent-session/agent-run-ok?           false
         :psi.agent-session/agent-run-text          (str "Error: " (or (ex-message e) (.getMessage e) (str e)))
         :psi.agent-session/agent-run-elapsed-ms    (- (System/currentTimeMillis) started-ms)
         :psi.agent-session/agent-run-error-message (or (ex-message e) (.getMessage e) (str e))}))))

(pco/defmutation switch-session
  "Switch the active session to the given session-id."
  [_ {:keys [psi/agent-session-ctx source-session-id session-id]}]
  {::pco/op-name 'psi.extension/switch-session
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/worktree-path]}
  (let [origin-session-id (or source-session-id session-id)
        sd                (core/ensure-session-loaded-in! agent-session-ctx origin-session-id session-id)]
    {:psi.agent-session/session-id    (:session-id sd)
     :psi.agent-session/session-name  (:session-name sd)
     :psi.agent-session/worktree-path (:worktree-path sd)}))

(pco/defmutation set-rpc-trace
  "Enable, disable, or toggle RPC trace logging for the current session."
  [_ {:keys [psi/agent-session-ctx session-id enabled file] :as params}]
  {::pco/op-name 'psi.extension/set-rpc-trace
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/rpc-trace-enabled
                  :psi.agent-session/rpc-trace-file]}
  (let [current       (or (session/get-state-value-in agent-session-ctx
                                                      (session/state-path :rpc-trace))
                          {:enabled? false :file nil})
        enabled?      (if (contains? params :enabled)
                        (boolean enabled)
                        (not (boolean (:enabled? current))))
        file-present? (contains? params :file)
        file*         (if file-present?
                        (when-not (str/blank? file)
                          file)
                        (:file current))]
    (when (and enabled? (str/blank? file*))
      (throw (ex-info "rpc trace requires a non-empty :file when enabled"
                      {:error-code "request/invalid-params"})))
    (dispatch/dispatch! agent-session-ctx :session/set-rpc-trace {:session-id session-id :enabled? enabled? :file file*} {:origin :mutations})
    {:psi.agent-session/rpc-trace-enabled enabled?
     :psi.agent-session/rpc-trace-file    file*}))

(pco/defmutation interrupt
  "Request an interrupt at the next turn boundary."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/interrupt
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/interrupt-pending
                  :psi.agent-session/is-idle]}
  (let [{:keys [pending?]} (core/request-interrupt-in! agent-session-ctx session-id)]
    {:psi.agent-session/interrupt-pending (boolean pending?)
     :psi.agent-session/is-idle           (ss/idle-in? agent-session-ctx session-id)}))

(pco/defmutation compact
  "Trigger manual context compaction for the current session."
  [_ {:keys [psi/agent-session-ctx session-id instructions]}]
  {::pco/op-name 'psi.extension/compact
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/is-compacting
                  :psi.agent-session/session-entry-count]}
  (core/manual-compact-in! agent-session-ctx session-id instructions)
  {:psi.agent-session/is-compacting     false
   :psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal session-id)))})

(pco/defmutation append-entry
  "Append a custom journal entry to the current session."
  [_ {:keys [psi/agent-session-ctx session-id custom-type data]}]
  {::pco/op-name 'psi.extension/append-entry
   ::pco/params  [:psi/agent-session-ctx :session-id :custom-type]
   ::pco/output  [:psi.agent-session/session-entry-count]}
  (ss/journal-append-in! agent-session-ctx session-id
                         (persist/custom-message-entry custom-type (str data) nil false))
  {:psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal session-id)))})

(pco/defmutation reload-models
  "Reload user + project custom models from disk for the session worktree path.
   Use after editing .psi/models.edn or ~/.psi/agent/models.edn."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/reload-models
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.model-registry/load-error
                  :psi.agent-session/model-catalog]}
  (let [{:keys [error]} (core/reload-models-in! agent-session-ctx session-id)]
    {:psi.model-registry/load-error  error
     :psi.agent-session/model-catalog
     (->> (model-registry/all-models-seq)
          (map (fn [m]
                 {:provider  (name (:provider m))
                  :id        (:id m)
                  :name      (:name m)
                  :reasoning (boolean (:supports-reasoning m))}))
          (sort-by (juxt :provider :id))
          vec)}))

(pco/defmutation reload-extension-installs
  "Reload/apply extension installs from user/project extensions.edn for the session worktree path."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/reload-extension-installs
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.extensions/last-apply
                  :psi.extensions/diagnostics
                  :psi.extensions/effective]}
  (let [result (core/reload-extension-installs-in! agent-session-ctx session-id)
        install-state (:install-state result)]
    {:psi.extensions/last-apply (:psi.extensions/last-apply install-state)
     :psi.extensions/diagnostics (vec (or (:psi.extensions/diagnostics install-state) []))
     :psi.extensions/effective (:psi.extensions/effective install-state)}))

(pco/defmutation cancel-job
  "Cancel a background job by job-id."
  [_ {:keys [psi/agent-session-ctx session-id job-id]}]
  {::pco/op-name 'psi.extension/cancel-job
   ::pco/params  [:psi/agent-session-ctx :session-id :job-id]
   ::pco/output  [:psi.background-job/job-id
                  :psi.background-job/status]}
  (let [job (core/cancel-job-in! agent-session-ctx session-id job-id :user)]
    {:psi.background-job/job-id (:job-id job)
     :psi.background-job/status (:status job)}))

(pco/defmutation start-background-job
  "Create a canonical background job entry for extension-owned async work."
  [_ {:keys [psi/agent-session-ctx session-id tool-call-id tool-name job-id job-kind
             workflow-ext-path workflow-id]}]
  {::pco/op-name 'psi.extension/start-background-job
   ::pco/params  [:psi/agent-session-ctx :session-id :tool-call-id :tool-name]
   ::pco/output  [:psi.background-job/job-id
                  :psi.background-job/status]}
  (let [store  (ss/get-state-value-in agent-session-ctx (ss/state-path :background-jobs))
        state' (:state (bg-jobs/start-background-job
                        store
                        {:tool-call-id      tool-call-id
                         :thread-id         session-id
                         :tool-name         tool-name
                         :job-id            job-id
                         :job-kind          job-kind
                         :workflow-ext-path workflow-ext-path
                         :workflow-id       workflow-id}))
        job    (bg-jobs/find-job-by-tool-call-in state' tool-call-id)]
    (dispatch/dispatch! agent-session-ctx
                        :session/update-background-jobs-state
                        {:update-fn (constantly state')}
                        {:origin :mutations})
    {:psi.background-job/job-id (:job-id job)
     :psi.background-job/status (:status job)}))

(pco/defmutation mark-background-job-terminal
  "Mark a canonical background job as terminal for extension-owned async work."
  [_ {:keys [psi/agent-session-ctx job-id outcome payload terminal-history-max-per-thread suppress-terminal-message?]}]
  {::pco/op-name 'psi.extension/mark-background-job-terminal
   ::pco/params  [:psi/agent-session-ctx :job-id :outcome]
   ::pco/output  [:psi.background-job/job-id
                  :psi.background-job/status]}
  (let [store  (ss/get-state-value-in agent-session-ctx (ss/state-path :background-jobs))
        state' (bg-jobs/mark-terminal
                store
                (cond-> {:job-id job-id
                         :outcome outcome
                         :payload payload}
                  terminal-history-max-per-thread
                  (assoc :terminal-history-max-per-thread terminal-history-max-per-thread)
                  (some? suppress-terminal-message?)
                  (assoc :suppress-terminal-message? suppress-terminal-message?)))
        job    (bg-jobs/get-job-in state' job-id)]
    (dispatch/dispatch! agent-session-ctx
                        :session/update-background-jobs-state
                        {:update-fn (constantly state')}
                        {:origin :mutations})
    {:psi.background-job/job-id (:job-id job)
     :psi.background-job/status (:status job)}))

(pco/defmutation remember
  "Capture a memory note for future sessions."
  [_ {:keys [psi/agent-session-ctx session-id text]}]
  {::pco/op-name 'psi.extension/remember
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.memory.remember/ok?]}
  (let [result (core/remember-in! agent-session-ctx session-id text)]
    {:psi.memory.remember/ok? (boolean (:ok? result))}))

(pco/defmutation login-begin
  "Begin OAuth login flow for a provider."
  [_ {:keys [psi/agent-session-ctx session-id provider-id]}]
  {::pco/op-name 'psi.extension/login-begin
   ::pco/params  [:psi/agent-session-ctx :session-id :provider-id]
   ::pco/output  [:psi.oauth/url
                  :psi.oauth/provider-id]}
  (let [result (core/login-begin-in! agent-session-ctx session-id provider-id)]
    {:psi.oauth/url         (:url result)
     :psi.oauth/provider-id (:provider-id result)}))

(pco/defmutation logout
  "Logout one or more OAuth providers."
  [_ {:keys [psi/agent-session-ctx session-id provider-ids]}]
  {::pco/op-name 'psi.extension/logout
   ::pco/params  [:psi/agent-session-ctx :session-id :provider-ids]
   ::pco/output  [:psi.oauth/logged-out?]}
  (core/logout-in! agent-session-ctx session-id provider-ids)
  {:psi.oauth/logged-out? true})

(def all-mutations
  [set-session-name
   set-model
   set-worktree-path
   create-session
   create-child-session
   run-agent-loop-in-session
   switch-session
   set-rpc-trace
   interrupt
   compact
   append-entry
   reload-models
   reload-extension-installs
   cancel-job
   start-background-job
   mark-background-job-terminal
   remember
   login-begin
   logout])
