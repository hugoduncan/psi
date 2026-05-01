(ns extensions.workflow-loader
  "Workflow Loader — unified workflow definition discovery and delegate tool.

   Replaces the `agent` and `agent-chain` extensions with a single surface.
   Discovers `.psi/workflows/*.md` files, parses/compiles them into canonical
   workflow definitions, registers them with the deterministic workflow runtime,
   and exposes a `delegate` tool and `/delegate` command.

   File format: YAML frontmatter + optional EDN config + body text.
   Single-step profiles and multi-step orchestrations use the same format.

   Tool: delegate(action, workflow, prompt, ...)
   Command: /delegate <workflow> <prompt>"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [extensions.workflow-loader.delivery :as delivery]
   [extensions.workflow-loader.orchestration :as orchestration]
   [extensions.workflow-loader.text :as text]
   [psi.agent-session.workflow-file-loader :as loader]))

;;; Extension state

(defonce ^:private state (atom nil))

(def ^:private prompt-contribution-id "workflow-loader-workflows")

;; Minimal non-authoritative sync wait registry: {run-id {:future ... :job-id ...}}
(def ^:private inflight-runs (atom {}))

;;; Helpers

(defn- query-fn [] (:query-fn @state))
(defn- mutate! [sym params] ((:mutate-fn @state) sym params))
(defn- log! [msg] ((:log-fn @state) msg))
(defn- notify!
  [msg level]
  (let [notify-fn (:notify-fn @state)]
    (try
      (notify-fn msg {:role "assistant"
                      :custom-type "workflow-loader"
                      :level level})
      (catch clojure.lang.ArityException _
        (notify-fn msg level)))))
(defn- ui-notify! [msg level]
  (if-let [notify-fn (some-> @state :ui :notify)]
    (notify-fn msg level)
    (notify! msg level)))

(defn- worktree-path []
  (when-let [qf (query-fn)]
    (:psi.agent-session/worktree-path
     (qf [:psi.agent-session/worktree-path]))))

(defn- current-session-id []
  (when-let [qf (query-fn)]
    (:psi.agent-session/session-id
     (qf [:psi.agent-session/session-id]))))

(defn- query-session-fn [] (:query-session-fn @state))
(defn- mutate-session-fn [] (:mutate-session-fn @state))

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
        old-definitions (:loaded-definitions @state)
        {:keys [definitions errors warnings]} (loader/load-workflow-definitions wtp)
        retired-definition-ids (retire-removed-definitions! old-definitions definitions)]
    ;; Register each definition via mutation
    (doseq [[_name definition] definitions]
      (mutate! 'psi.workflow/register-definition {:definition definition}))
    ;; Store loaded definitions in extension state for prompt contribution
    (swap! state assoc :loaded-definitions definitions)
    {:registered-count (count definitions)
     :definition-names (sort (keys definitions))
     :retired-definition-ids retired-definition-ids
     :errors errors
     :warnings warnings}))

(defn- build-prompt-contribution
  "Build the prompt contribution text listing available workflows."
  []
  (text/build-prompt-contribution (:loaded-definitions @state)))

(defn- register-prompt-contribution! []
  (when-let [register-fn (:register-prompt-contribution @state)]
    (register-fn {:id prompt-contribution-id
                  :section "Extension Capabilities"
                  :content (build-prompt-contribution)})))

(defn- reload-definitions!
  "Reload workflow definitions from disk and update prompt contribution."
  []
  (let [result (register-definitions!)]
    (register-prompt-contribution!)
    result))

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

(defn- delegate-list
  "Handle action=list: list available workflows and active runs."
  []
  (let [runs-result (mutate! 'psi.workflow/list-runs {})
        runs (:psi.workflow/runs runs-result)
        jobs-result (when-let [qf (query-fn)]
                      (qf [:psi.agent-session/background-jobs]))
        jobs (:psi.agent-session/background-jobs jobs-result)]
    (text/delegate-list-text (:loaded-definitions @state) runs jobs)))

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

      (nil? prompt-text)
      {:error "prompt is required"}

      (= ::invalid mode*)
      {:error "mode must be one of: sync, async"}

      (nil? (get (:loaded-definitions @state) workflow-name))
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

(defn- delegate-remove
  "Handle action=remove: remove a run by id.

   Removal clears the canonical workflow run. Delegate projection then derives
   disappearance from canonical workflow + non-terminal background-job state,
   so terminal background-job history does not keep removed runs visible here."
  [{:keys [id]}]
  (let [run-id (some-> id str str/trim not-empty)]
    (if (nil? run-id)
      {:error "id is required for remove"}
      (let [result (mutate! 'psi.workflow/remove-run {:run-id run-id})]
        (if (:psi.workflow/error result)
          {:error (:psi.workflow/error result)}
          (do
            (swap! inflight-runs dissoc run-id)
            (refresh-widgets!)
            {:ok true :run-id run-id}))))))

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
  (when-let [ui (:ui @state)]
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
          old-wids (or (:widget-ids @state) #{})]
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
      (swap! state assoc :widget-ids current-wids))))

;;; Delegate command

;;; Extension init

(defn init [api]
  (swap! state assoc
         :api api
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
         :loaded-definitions {}
         :widget-ids #{})

  ;; Load and register all workflow definitions
  (let [{:keys [registered-count errors]} (reload-definitions!)]
    (when (seq errors)
      (ui-notify! (str "Workflow loader: " (count errors) " error(s) loading definitions")
                  :warn))
    (ui-notify! (str "workflow-loader: " registered-count " workflows loaded")
                :info))

  ;; Register delegate tool
  ((:register-tool api)
   {:name        "delegate"
    :label       "Delegate"
    :description "Run, list, continue, or remove workflow-based delegations. `continue` pushes a stopped run forward with a new prompt; `remove` deletes a run. Covers single-step agent profiles and multi-step orchestrations."
    :parameters  {:type       "object"
                  :properties {"action"                   {:type "string"
                                                           :enum ["run" "list" "continue" "remove"]
                                                           :description "Operation: run (default when omitted), list, continue, remove"}
                               "workflow"                 {:type "string"
                                                           :description "Workflow name to run (action=run)"}
                               "prompt"                   {:type "string"
                                                           :description "Input/request text (action=run, action=continue)"}
                               "name"                     {:type "string"
                                                           :description "Optional label for this run (action=run)"}
                               "id"                       {:type "string"
                                                           :description "Run id (action=continue, action=remove)"}
                               "mode"                     {:type "string"
                                                           :enum ["sync" "async"]
                                                           :description "Execution mode (default async)"}
                               "fork_session"             {:type "boolean"
                                                           :description "When true, child session starts from a fork of the parent conversation"}
                               "include_result_in_context" {:type "boolean"
                                                            :description "When true, inject result into the originating parent session context"}
                               "timeout_ms"               {:type "integer"
                                                           :description "Sync mode timeout in milliseconds (default 300000)"}}}
    :execute     (fn
                   ([args] (execute-delegate-tool args nil))
                   ([args opts] (execute-delegate-tool args opts)))})

  ;; Register /delegate command
  ((:register-command api) "delegate"
                           {:description "Delegate to a workflow: /delegate [list|<workflow> <prompt>]"
                            :handler (fn [args]
                                       (let [{:keys [workflow prompt]} (text/parse-delegate-command args)]
                                         (cond
                                           (nil? workflow)
                                           (str "Available workflows:\n"
                                                (text/available-workflows-text
                                                 (:loaded-definitions @state)))

                                           (= "list" workflow)
                                           (delegate-list)

                                           (nil? prompt)
                                           (str "Usage: /delegate " workflow " <prompt>")

                                           :else
                                           ;; Slash-command delegation is conversational: successful final
                                           ;; results should be posted back into the originating chat.
                                           (let [result (delegate-run {:workflow workflow
                                                                       :prompt prompt
                                                                       :mode "async"
                                                                       :include_result_in_context true})]
                                             (if (:error result)
                                               (str "Error: " (:error result))
                                               (str "Delegated to " workflow " — run " (:run-id result)))))))})

  ;; Register /delegate-reload command
  ((:register-command api) "delegate-reload"
                           {:description "Reload workflow definitions from disk and retire removed definitions"
                            :handler (fn [_args]
                                       (let [{:keys [registered-count retired-definition-ids errors]} (reload-definitions!)]
                                         (log! (str "Reloaded: " registered-count " workflows"
                                                    (when (seq retired-definition-ids)
                                                      (str ", retired " (count retired-definition-ids) " definition(s)"))
                                                    (when (seq errors)
                                                      (str ", " (count errors) " errors"))))))})

  ;; Session lifecycle cleanup
  ((:on api) "session_switch"
             (fn [_event]
               (reload-definitions!)
               nil)))
