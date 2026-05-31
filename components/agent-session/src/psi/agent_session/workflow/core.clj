(ns psi.agent-session.workflow.core
  "Workflow Loader — unified workflow definition discovery and delegate tool.

   Replaces the `agent` and `agent-chain` extensions with a single surface.
   Discovers `.psi/workflows/` definitions (`.md` single-step prompt workflows
   and `.edn` multi-step orchestration workflows), parses/compiles them into
   canonical workflow definitions, registers them with the deterministic
   workflow runtime, and exposes a `delegate` tool and `/delegate` command.

   Tool: delegate(action, workflow, prompt, ...)
   Command: /delegate <workflow> [<prompt>]"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [psi.tool-runtime.call-summary :as call-summary]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.delegate-list :as delegate-list-projection]
   [psi.agent-session.workflow.delivery :as delivery]
   [psi.agent-session.workflow.orchestration :as orchestration]
   [psi.agent-session.workflow.runtime-state :as runtime-state]
   [psi.agent-session.workflow.text :as text]
   [psi.workflow-loader.core :as loader]))

;;; Runtime state aliases

(def state runtime-state/state)
(def built-in-workflow-path runtime-state/built-in-workflow-path)
(def prompt-contribution-id runtime-state/prompt-contribution-id)
(def inflight-runs runtime-state/inflight-runs)

;;; Helpers

(def query-fn runtime-state/query-fn)
(def mutate! runtime-state/mutate!)
(def log! runtime-state/log!)
(def notify! runtime-state/notify!)
(def ui-notify! runtime-state/ui-notify!)
(def worktree-path runtime-state/worktree-path)
(defn current-session-id []
  (or runtime-state/*active-workflow-session-id*
      (runtime-state/current-session-id)))
(def query-session-fn runtime-state/query-session-fn)
(def mutate-session-fn runtime-state/mutate-session-fn)

(defn- start-background-job!
  [session-id run-id workflow-name]
  (orchestration/start-background-job! mutate! session-id run-id workflow-name))

(defn- mark-background-job-terminal!
  ([job-id status payload]
   (orchestration/mark-background-job-terminal! mutate! job-id status payload {}))
  ([job-id status payload opts]
   (orchestration/mark-background-job-terminal! mutate! job-id status payload opts)))

;;; Definition loading and registration

(defn- retire-removed-definitions!
  [old-definitions new-definitions]
  (let [old-ids (set (map (comp :definition-id val) old-definitions))
        new-ids (set (map (comp :definition-id val) new-definitions))
        retired-ids (sort (set/difference old-ids new-ids))]
    (doseq [definition-id retired-ids]
      (mutate! 'psi.workflow/remove-definition {:definition-id definition-id}))
    retired-ids))

(defn- register-definitions!
  "Load workflow files from disk and register them with the canonical workflow runtime.
   Returns {:registered-count ... :errors [...] :warnings [...] :retired-definition-ids [...]}"
  []
  (let [wtp (worktree-path)
        old-definitions (runtime-state/loaded-definitions)
        {:keys [definitions errors warnings]} (loader/load-workflow-definitions wtp)
        retired-definition-ids (retire-removed-definitions! old-definitions definitions)]
    ;; Register each definition via mutation
    (doseq [[_name definition] definitions]
      (mutate! 'psi.workflow/register-definition {:definition definition}))
    ;; Store loaded definitions in extension state for prompt contribution
    (runtime-state/assoc-state! :loaded-definitions definitions)
    {:registered-count (count definitions)
     :definition-names (sort (keys definitions))
     :retired-definition-ids retired-definition-ids
     :errors errors
     :warnings warnings}))

(defn- build-prompt-contribution
  "Build the prompt contribution text listing available workflows."
  []
  (text/build-prompt-contribution (runtime-state/loaded-definitions)))

(defn- register-prompt-contribution! []
  (when-let [register-fn (runtime-state/register-prompt-contribution-fn)]
    (register-fn {:id prompt-contribution-id
                  :section "Extension Capabilities"
                  :content (build-prompt-contribution)})))

(defn- reload-definitions!
  "Reload workflow definitions from disk and update prompt contribution."
  []
  (let [result (register-definitions!)]
    (register-prompt-contribution!)
    result))

(def ^:private pass-status-prefix "PASS_STATUS:")
(def ^:private known-pass-status->route
  {"REVIEW_COMPLETE" "DONE"
   "ACTIONABLE_FEEDBACK" "REPEAT"
   "IMPLEMENTATION_COMPLETE" "DONE"
   "MORE_WORK_REMAINS" "REPEAT"})

(defn- pass-status-line-value
  [line]
  (when-let [idx (str/index-of line pass-status-prefix)]
    (when (= 0 idx)
      (subs line (count pass-status-prefix)))))

(defn- parse-pass-status-routing
  [text allowed-statuses]
  (let [lines (str/split-lines (or text ""))
        allowed-statuses-set (when (seq allowed-statuses)
                               (set allowed-statuses))
        status-lines (keep (fn [line]
                             (when-let [raw-value (pass-status-line-value line)]
                               {:line line
                                :raw-value raw-value
                                :trimmed-value (str/trim raw-value)}))
                           lines)]
    (cond
      (empty? status-lines)
      {:status :error
       :reason :missing-pass-status
       :message "PASS_STATUS missing"
       :details {:text text}}

      (> (count status-lines) 1)
      {:status :error
       :reason :ambiguous-pass-status
       :message "Multiple PASS_STATUS lines found"
       :details {:text text
                 :pass-status-lines (mapv :line status-lines)}}

      :else
      (let [{:keys [line raw-value trimmed-value]} (first status-lines)
            route (get known-pass-status->route trimmed-value)
            exact-known? (= raw-value (str " " trimmed-value))
            allowed? (or (nil? allowed-statuses-set)
                         (contains? allowed-statuses-set trimmed-value))]
        (cond
          (and route exact-known? allowed?)
          {:status :ok
           :data route
           :summary route}

          (and route exact-known? (not allowed?))
          {:status :error
           :reason :invalid-pass-status
           :message "PASS_STATUS token is not valid for this workflow step"
           :details {:text text
                     :line line
                     :value trimmed-value
                     :allowed-statuses (vec allowed-statuses)}}

          :else
          {:status :error
           :reason :malformed-pass-status
           :message "PASS_STATUS line must contain exactly one known token"
           :details {:text text
                     :line line
                     :value trimmed-value}})))))

(defn- register-built-in-deterministic-operations!
  [api]
  (when-let [register-operation (:register-operation api)]
    (register-operation
     {:id "workflow/pass-status-routing"
      :handler (fn [{:keys [args]}]
                 (parse-pass-status-routing (:text args) (:allowed-statuses args)))})
    (register-operation
     {:id "workflow/constant-routing"
      :handler (fn [{:keys [args]}]
                 (if (string? (:route args))
                   {:status :ok
                    :data (:route args)
                    :summary (:route args)}
                   {:status :error
                    :reason :invalid-route
                    :message "route must be a string"
                    :details {:route (:route args)}}))})))

(declare refresh-widgets!)

;;; Result injection

(defn- inject-result-into-context!
  [parent-session-id run-id result-text]
  (delivery/inject-result-into-context!
   {:query-fn (query-fn)
    :query-session-fn (query-session-fn)
    :mutate-fn (:mutate-fn @state)
    :mutate-session-fn (mutate-session-fn)}
   parent-session-id
   run-id
   result-text))

;;; Async execution

(defn- on-async-completion!
  [run-id workflow-name parent-session-id include-result? exec-result]
  (orchestration/on-async-completion!
   {:mutate! (:mutate-fn @state)
    :notify! notify!
    :mark-background-job-terminal! mark-background-job-terminal!
    :inject-result-into-context! inject-result-into-context!
    :refresh-widgets! refresh-widgets!
    :inflight-runs inflight-runs}
   run-id workflow-name parent-session-id include-result? exec-result))

(defn- execute-async!
  [run-id session-id workflow-name include-result?]
  (orchestration/execute-async!
   {:mutate! mutate!
    :start-background-job! start-background-job!
    :mark-background-job-terminal! mark-background-job-terminal!
    :notify! notify!
    :refresh-widgets! refresh-widgets!
    :inflight-runs inflight-runs
    :inject-result-into-context! inject-result-into-context!
    :on-async-completion-fn on-async-completion!}
   run-id session-id workflow-name include-result?))

;;; Sync execution

(defn- await-run-completion
  [run-id timeout-ms]
  (orchestration/await-run-completion inflight-runs run-id timeout-ms))

;;; Delegate tool implementation

(defn- background-job-query-error
  [message details]
  {:status :error
   :reason :background-job-query-unavailable
   :message message
   :details details})

(defn- background-jobs-payload-shaped?
  [jobs]
  (and (sequential? jobs)
       (every? map? jobs)))

(defn- query-background-jobs
  [action-name]
  (if-let [qf (query-fn)]
    (try
      (let [result (qf [:psi.agent-session/background-jobs])]
        (cond
          (not (and (map? result) (contains? result :psi.agent-session/background-jobs)))
          (background-job-query-error
           (str action-name " could not read the background-job visibility surface")
           {:query-result result})

          (not (background-jobs-payload-shaped?
                (:psi.agent-session/background-jobs result)))
          (background-job-query-error
           (str action-name " background-job visibility surface returned a non-shaped jobs payload")
           {:jobs (:psi.agent-session/background-jobs result)})

          :else
          {:status :ok
           :jobs (:psi.agent-session/background-jobs result)}))
      (catch Exception e
        (background-job-query-error
         (str action-name " background-job query failed")
         {:exception-message (ex-message e)
          :exception-data (ex-data e)})))
    (background-job-query-error
     (str action-name " requires a background-job query surface")
     {:query :psi.agent-session/background-jobs})))

(defn- query-background-jobs-for-list
  []
  (query-background-jobs "delegate list"))

(defn- delegate-list
  "Handle action=list: list available workflows and visible delegate runs."
  []
  (let [runs-result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs runs-result)
        jobs-result (query-background-jobs-for-list)]
    (if (= :error (:status jobs-result))
      (text/error-text (:message jobs-result))
      (let [projection (delegate-list-projection/project-visible-runs
                        {:session-id (current-session-id)
                         :runs runs
                         :background-jobs (mapv delegate-list-projection/normalize-query-job
                                                (:jobs jobs-result))})]
        (if (= :error (:status projection))
          (text/error-text (:message projection))
          (text/delegate-list-text (runtime-state/loaded-definitions)
                                   (:runs projection)))))))

(defn- delegate-run
  "Handle action=run: resolve workflow, create + execute canonical workflow run.
   Supports async (default) and sync modes, fork_session, and include_result_in_context."
  [{:keys [workflow prompt name mode fork_session include_result_in_context timeout_ms]}]
  (let [workflow-name (some-> workflow str str/trim not-empty)
        prompt-text   (some-> prompt str str/trim not-empty)
        mode*         (text/parse-mode mode)
        fork?         (true? fork_session)
        include?      (true? include_result_in_context)
        timeout       (or (when (number? timeout_ms) (long timeout_ms)) 300000)]
    (cond
      (nil? workflow-name)
      {:error "workflow is required"}

      (= ::invalid mode*)
      {:error "mode must be one of: sync, async"}

      (nil? (get (runtime-state/loaded-definitions) workflow-name))
      {:error (str "Unknown workflow '" workflow-name "'. Use action=list to see available workflows.")}

      :else
      (let [run-name    (or name (str workflow-name "-" (System/currentTimeMillis)))
            session-id  (current-session-id)
            workflow-input (cond-> {:input prompt-text
                                    :original prompt-text}
                             fork? (assoc :fork-session true))
            ;; Create the canonical workflow run
            create-result (mutate! 'psi.workflow/create-run
                                   {:definition-id workflow-name
                                    :workflow-input workflow-input
                                    :run-id run-name})
            run-id (:psi.workflow/run-id create-result)]
        (if-not run-id
          {:error (or (:psi.workflow/error create-result)
                      "Failed to create workflow run")}
          ;; Execute based on mode
          (case mode*
            :async
            (do
              (execute-async! run-id session-id workflow-name include?)
              {:ok true
               :run-id run-id
               :mode :async
               :status :running})

            :sync
            (do
              ;; Launch async then await completion
              (execute-async! run-id session-id workflow-name include?)
              (let [exec-result (await-run-completion run-id timeout)]
                (cond
                  (= :timeout (:psi.workflow/status exec-result))
                  {:error (str "Timed out waiting for workflow '" workflow-name "' after " timeout "ms")
                   :run-id run-id
                   :mode :sync}

                  (:psi.workflow/error exec-result)
                  {:error (:psi.workflow/error exec-result)
                   :run-id run-id
                   :mode :sync}

                  :else
                  {:ok true
                   :run-id run-id
                   :mode :sync
                   :status (:psi.workflow/status exec-result)
                   :result (:psi.workflow/result exec-result)})))))))))

(defn- find-run-summary
  [run-id]
  (let [result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs result)]
    (some #(when (= run-id (:run-id %)) %) runs)))

(defn- delegate-continue
  "Handle action=continue: push a stopped run forward with new prompt.

   - blocked runs: update workflow input and resume the existing run
   - terminal runs: create a fresh run from the original definition and execute it"
  [{:keys [id prompt include_result_in_context]}]
  (let [run-id (some-> id str str/trim not-empty)
        prompt-text (some-> prompt str str/trim not-empty)
        include? (true? include_result_in_context)]
    (cond
      (nil? run-id)
      {:error "id is required for continue"}

      (nil? prompt-text)
      {:error "prompt is required for continue"}

      :else
      (let [session-id (current-session-id)
            run-summary (find-run-summary run-id)
            status (:status run-summary)]
        (cond
          (nil? run-summary)
          {:error (str "Unknown run '" run-id "'")}

          (= :blocked status)
          (orchestration/continue-blocked-run-async!
           {:mutate! mutate!
            :start-background-job! start-background-job!
            :mark-background-job-terminal! mark-background-job-terminal!
            :notify! notify!
            :refresh-widgets! refresh-widgets!
            :inflight-runs inflight-runs
            :on-async-completion-fn on-async-completion!}
           run-id session-id prompt-text include?)

          (contains? #{:completed :failed :cancelled} status)
          (orchestration/continue-terminal-run-async!
           {:mutate! mutate!
            :execute-async! execute-async!
            :find-run-summary-fn find-run-summary}
           run-id session-id prompt-text include?)

          :else
          {:error (str "Run '" run-id "' is not stopped; current status is " (name (or status :unknown)))})))))

(defn- active-delegate-background-jobs
  [session-id run-id jobs]
  (->> jobs
       (map delegate-list-projection/normalize-query-job)
       (filter #(and (= (str session-id) (:thread-id %))
                     (= "delegate" (:tool-name %))
                     (= :workflow (:job-kind %))
                     (= delegate-list-projection/workflow-provenance-id (:workflow-ext-path %))
                     (= run-id (:workflow-id %))
                     (contains? #{:running :pending-cancel} (:status %))))
       vec))

(defn- terminalize-active-delegate-background-jobs!
  [jobs]
  (try
    (doseq [job jobs]
      (mark-background-job-terminal!
       (:job-id job)
       :cancelled
       {:workflow-id (:workflow-id job)
        :status :cancelled
        :delegate-status :cancelled
        :reason :delegate-remove}
       {:suppress-terminal-message? true}))
    {:status :ok}
    (catch Exception e
      {:status :error
       :message "delegate remove could not clean up active delegate background jobs"
       :details {:exception-message (ex-message e)
                 :exception-data (ex-data e)
                 :job-ids (mapv :job-id jobs)}})))

(defn- cleanup-active-delegate-background-jobs-before-remove!
  [session-id run-id]
  (let [jobs-result (query-background-jobs "delegate remove")]
    (if (= :error (:status jobs-result))
      jobs-result
      (terminalize-active-delegate-background-jobs!
       (active-delegate-background-jobs session-id run-id (:jobs jobs-result))))))

(defn- delegate-remove
  "Handle action=remove: remove a run by id.

   Removal clears the canonical workflow run only after any same-session active
   delegate background jobs for the target have been resolved to terminal
   history, so later list calls cannot observe non-terminal missing-canonical
   corruption."
  [{:keys [id]}]
  (let [run-id (some-> id str str/trim not-empty)]
    (if (nil? run-id)
      {:error "id is required for remove"}
      (let [session-id (current-session-id)
            cleanup-result (cleanup-active-delegate-background-jobs-before-remove! session-id run-id)]
        (if (= :error (:status cleanup-result))
          {:error (:message cleanup-result)}
          (let [result (mutate! 'psi.workflow/remove-run {:run-id run-id})]
            (if (:psi.workflow/error result)
              {:error (:psi.workflow/error result)}
              (do
                (swap! inflight-runs dissoc run-id)
                (refresh-widgets!)
                {:ok true :run-id run-id}))))))))

(defn- execute-delegate-tool
  "Main delegate tool execution dispatcher.

   Defaults missing action to `run`."
  [args _opts]
  (let [action (or (some-> (:action args) str str/lower-case str/trim) "run")]
    (case action
      "list"     (delegate-list)
      "run"      (let [result (delegate-run args)]
                   (if (:error result)
                     (text/error-text (:error result))
                     (case (:mode result)
                       :async
                       (text/workflow-run-started-text (:run-id result))

                       :sync
                       (text/workflow-run-result-text (:run-id result)
                                                      (:status result)
                                                      (:result result))

                       ;; fallback
                       (text/workflow-run-result-text (:run-id result)
                                                      (:status result)
                                                      (:result result)))))
      "continue" (let [result (delegate-continue args)]
                   (if (:error result)
                     (text/error-text (:error result))
                     (text/delegate-continued-text (:run-id result))))
      "remove"   (let [result (delegate-remove args)]
                   (if (:error result)
                     (text/error-text (:error result))
                     (text/delegate-removed-text (:run-id result))))
      (text/unknown-action-text action))))

;;; Widget

(defn- refresh-widgets!
  "Update widgets for workflow-loader background jobs using canonical workflow and background-job state."
  []
  (when-let [ui (runtime-state/ui)]
    (let [runs-result (try
                        (mutate! 'psi.workflow/list-runs {})
                        (catch Exception _ {:psi.workflow/runs []}))
          canonical-runs (:psi.workflow/runs runs-result)
          run-info-by-id (into {} (map (fn [r] [(:run-id r) r]) canonical-runs))
          jobs-result (try
                        (when-let [qf (query-fn)]
                          (qf [:psi.agent-session/background-jobs]))
                        (catch Exception _ nil))
          delegate-jobs (->> (:psi.agent-session/background-jobs jobs-result)
                             (filter #(= "delegate" (:psi.background-job/tool-name %)))
                             (filter #(contains? #{:running :pending-cancel}
                                                 (:psi.background-job/status %)))
                             (sort-by :psi.background-job/started-at)
                             vec)
          current-wids (into #{} (map #(str "delegate-" (:psi.background-job/workflow-id %)) delegate-jobs))
          old-wids (or (runtime-state/widget-ids) #{})]
      (doseq [wid (set/difference old-wids current-wids)]
        ((:clear-widget ui) wid))
      (doseq [job delegate-jobs]
        (let [run-id (:psi.background-job/workflow-id job)
              run-info (get run-info-by-id run-id)
              wid (str "delegate-" run-id)
              lines (text/run-widget-lines run-id
                                           (System/currentTimeMillis)
                                           job
                                           (or run-info {}))]
          ((:set-widget ui) wid text/widget-placement lines)))
      (runtime-state/assoc-state! :widget-ids current-wids))))

;;; Delegate command

;;; Built-in + compatibility init

(defn init [api]
  (runtime-state/swap-state! merge
                             {:api api
                              :query-fn (:query api)
                              :query-session-fn (:query-session api)
                              :mutate-fn (:mutate api)
                              :mutate-session-fn (:mutate-session api)
                              :log-fn (or (:log api) println)
                              :notify-fn (or (:notify api) (fn [m _] (println m)))
                              :append-message-fn (:append-message api)
                              :ui (:ui api)
                              :register-prompt-contribution
                              (when-let [rpc (:register-prompt-contribution api)]
                                rpc)
                              :loaded-definitions (or (runtime-state/loaded-definitions) {})
                              :widget-ids (or (runtime-state/widget-ids) #{})})

  (register-built-in-deterministic-operations! api)

  ;; Load and register all workflow definitions
  (let [{:keys [registered-count errors]} (reload-definitions!)]
    (when (seq errors)
      (ui-notify! (str "Workflow loader: " (count errors) " error(s) loading definitions")
                  :warn))
    (ui-notify! (str "workflow-loader: " registered-count " workflows loaded")
                :info))

  ;; Register delegate tool
  ((:register-tool api)
   {:name               "delegate"
    :label              "Delegate"
    :description        "Run, list, continue, or remove workflow-based delegations. `continue` pushes a stopped run forward with a new prompt; `remove` deletes a run. Covers single-step agent profiles and multi-step orchestrations."
    :lambda-description "λ{action id workflow prompt mode name fork_session timeout_ms include_result_in_context}. manage_delegation(action, id, workflow, prompt, mode, name, fork_session, timeout_ms, include_result_in_context) | action ∈ {run list continue remove} ∧ continue(id, prompt) → push_stopped_run_forward ∧ remove(id) → delete_run ∧ covers(single_step_agents ∨ multi_step_orchestrations)"
    :format-request     call-summary/delegate-format-request
    :parameters         {:type       "object"
                         :properties {"action"                    {:type        "string"
                                                                   :enum        ["run" "list" "continue" "remove"]
                                                                   :description "Operation: run (default when omitted), list, continue, remove"}
                                      "workflow"                  {:type        "string"
                                                                   :description "Workflow name to run (action=run)"}
                                      "prompt"                    {:type        "string"
                                                                   :description "Input/request text (action=run, action=continue)"}
                                      "name"                      {:type        "string"
                                                                   :description "Optional label for this run (action=run)"}
                                      "id"                        {:type        "string"
                                                                   :description "Run id (action=continue, action=remove)"}
                                      "mode"                      {:type        "string"
                                                                   :enum        ["sync" "async"]
                                                                   :description "Execution mode (default async)"}
                                      "fork_session"              {:type        "boolean"
                                                                   :description "When true, child session starts from a fork of the parent conversation"}
                                      "include_result_in_context" {:type        "boolean"
                                                                   :description "When true, inject result into the originating parent session context"}
                                      "timeout_ms"                {:type        "integer"
                                                                   :description "Sync mode timeout in milliseconds (default 300000)"}}}
    :execute            (fn
                          ([args] (execute-delegate-tool args nil))
                          ([args opts]
                           (runtime-fns/with-active-extension-session-id
                             (:session-id opts)
                             #(binding [runtime-state/*active-workflow-session-id* (:session-id opts)]
                                (execute-delegate-tool args opts)))))})

  ;; Register /delegate command
  ((:register-command api) "delegate"
                           {:description "Delegate to a workflow: /delegate [list|<workflow> [<prompt>]]"
                            :handler     (fn [args]
                                           (let [{:keys [workflow prompt]} (text/parse-delegate-command args)]
                                             (cond
                                               (nil? workflow)
                                               (str "Available workflows:\n"
                                                    (text/available-workflows-text
                                                     (runtime-state/loaded-definitions)))

                                               (= "list" workflow)
                                               (delegate-list)

                                               :else
                       ;; Slash-command delegation is conversational: successful final
                       ;; results should be posted back into the originating chat.
                                               (let [result (delegate-run {:workflow                  workflow
                                                                           :prompt                    prompt
                                                                           :mode                      "async"
                                                                           :include_result_in_context true})]
                                                 (if (:error result)
                                                   (str "Error: " (:error result))
                                                   (str "Delegated to " workflow " — run " (:run-id result)))))))})

  ;; Register /delegate-reload command
  ((:register-command api) "delegate-reload"
                           {:description "Reload workflow definitions from disk and retire removed definitions"
                            :handler     (fn [_args]
                                           (let [{:keys [registered-count retired-definition-ids errors]} (reload-definitions!)]
                                             (log! (str "Reloaded: " registered-count " workflows"
                                                        (when (seq retired-definition-ids)
                                                          (str ", retired " (count retired-definition-ids) " definition(s)"))
                                                        (when (seq errors)
                                                          (str ", " (count errors) " errors"))))))})

  ;; Session lifecycle cleanup
  ;; Guard against nil state: if the built-in workflow runtime has been reset
  ;; (e.g. between tests or after a hard reset), skip the reload rather than
  ;; propagating a NPE from nil mutate-fn.
  ((:on api) "session_switch"
             (fn [{:keys [session-id]}]
               (when (runtime-state/query-fn)
                 (runtime-state/assoc-state! :current-session-id session-id)
                 (reload-definitions!))
               nil)))
