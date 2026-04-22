(ns extensions.plan-state-learning
  "Automate munera + mementum working-memory maintenance after non-PSL commits.

   Trigger:
   - extension event: git_commit_created

   Behavior:
   - skip when latest commit subject contains [psi:psl-auto]
   - create a PSL workflow that runs after the current agent turn completes
   - workflow job: sends one user-style prompt turn to the agent with source commit context
   - agent updates munera/plan.md and mementum/state.md and commits changes
   - agent may suggest mementum memory/knowledge candidates, but does not auto-write them
   - emit visible transcript messages via psi.extension/notify

   Ordering:
   - git_commit_created fires during/after the triggering agent turn
   - the workflow job runs after the session goes idle (deferred send-prompt path)
   - this ensures PSL output appears after the commit turn, not before it"
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [extensions.workflow-display :as workflow-display]))

(def ^:private marker "[psi:psl-auto]")
(def ^:private custom-type "plan-state-learning")
(def ^:private workflow-type :psl)

;; ---------------------------------------------------------------------------
;; Module state (set during init)
;; ---------------------------------------------------------------------------

(def ^:private workflow-eql
  [{:psi.extension/workflows
    [:psi.extension/path
     :psi.extension.workflow/id
     :psi.extension.workflow/type
     :psi.extension.workflow/phase
     :psi.extension.workflow/running?
     :psi.extension.workflow/done?
     :psi.extension.workflow/error?
     :psi.extension.workflow/error-message
     :psi.extension.workflow/input
     :psi.extension.workflow/data
     :psi.extension.workflow/result
     :psi.extension.workflow/elapsed-ms
     :psi.extension.workflow/started-at]}])

(def ^:private widget-id "psl")

(defonce ^:private state
  (atom {:api      nil
         :ext-path nil
         :ui       nil
         :ui-type  :console
         :mutate-fn nil
         :query-fn  nil}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- squish
  [s]
  (-> (str (or s ""))
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- tool-content
  [tool-result]
  (str (or (:psi.extension.tool/content tool-result) "")))

(defn- bash!
  [mutate-fn command]
  (mutate-fn 'psi.extension.tool/bash {:command command :timeout 30}))

(defn- notify!
  [mutate-fn text]
  (mutate-fn 'psi.extension/notify
             {:role "assistant"
              :content text
              :custom-type custom-type}))

(defn- run-agent-psl!
  [mutate-fn task]
  (mutate-fn 'psi.extension.tool/chain
             {:steps [{:id "psl-agent"
                       :tool "agent"
                       :args {"action" "create"
                              "task" task
                              "mode" "sync"
                              "fork_session" true
                              "timeout_ms" 600000}}]
              :stop-on-error? true}))

(defn- latest-commit
  [mutate-fn]
  (let [result (bash! mutate-fn "git log -1 --pretty=format:%H%n%s")
        out    (tool-content result)
        [sha subject] (str/split-lines out)]
    {:sha (squish sha)
     :subject (squish subject)}))

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- all-workflows []
  (or (:psi.extension/workflows (query! workflow-eql)) []))

(defn- psl-workflows []
  (let [ext-path (:ext-path @state)]
    (->> (all-workflows)
         (filter (fn [wf]
                   (and (= ext-path (:psi.extension/path wf))
                        (= workflow-type (:psi.extension.workflow/type wf))))))))

(defn- workflow-public-display
  [{:psl/keys [source-sha subject phase result]}]
  (let [source7 (subs (or source-sha "unknown") 0 (min 7 (count (or source-sha "unknown"))))
        top-line (case phase
                   :idle (str "… PSL pending for " source7)
                   :running (str "… PSL running for " source7)
                   :done (if (:accepted? result)
                           (str "✓ PSL updated artifacts for " source7)
                           (str "✗ PSL failed for " source7))
                   :error (str "✗ PSL errored for " source7)
                   (str "… PSL " (name (or phase :unknown)) " for " source7))
        detail-line (when (seq (squish subject))
                      (str "commit: " (squish subject)))
        action-line (case phase
                      :idle "waiting for session idle"
                      :running "updating munera/mementum state"
                      :done (when-not (:accepted? result)
                              "inspect PSL agent result")
                      :error "inspect PSL error"
                      nil)]
    {:top-line    top-line
     :detail-line detail-line
     :action-line action-line}))

(defn- workflow-lines
  [wf]
  (-> (workflow-display/merged-display
       {:top-line    (str "… PSL " (or (:psi.extension.workflow/id wf) ""))
        :detail-line nil
        :action-line nil}
       (:psl/display (or (:psi.extension.workflow/data wf) {})))
      (workflow-display/display-lines)))

(defn- list-psl-runs!
  []
  (let [runs (psl-workflows)]
    (if (empty? runs)
      (println "No PSL runs.")
      (doseq [wf runs]
        (doseq [line (workflow-display/text-lines (workflow-lines wf))]
          (println line))))))

(defn- widget-placement []
  (if (= :emacs (or (:ui-type @state) :console))
    :below-editor
    :above-editor))

(defn- widget-lines []
  (let [runs (psl-workflows)]
    (if (seq runs)
      (into ["⊕ PSL"]
            (mapcat (fn [wf]
                      (map (fn [line] (str "  " (:text line line)))
                           (workflow-lines wf)))
                    runs))
      ["⊕ PSL"])))

(defn- refresh-widget! []
  (when-let [ui (:ui @state)]
    (when-let [set-widget (:set-widget ui)]
      (set-widget widget-id (widget-placement) (widget-lines)))))

(defn- refresh-widget-later! []
  (future
    (Thread/sleep 30)
    (refresh-widget!)))

(defn- psl-prompt
  [{:keys [source-sha]}]
  (let [source7 (subs source-sha 0 (min 7 (count source-sha)))]
    (str
     "PSL follow-up for commit " source7 "\n"
     "\n"
     "Task:\n"
     "1) Update munera/plan.md to reflect current open work and ordering\n"
     "2) Update mementum/state.md to reflect current orientation and active work\n"
     "3) Commit those changes together if meaningful changes were made\n"
     "4) If the commit suggests durable memory or knowledge, mention candidate mementum follow-ups in your final response, but do not create or edit mementum/memories/* or mementum/knowledge/*\n"
     "\n"
     "Constraints:\n"
     "- Commit message title must summarise the munera/mementum update and finish with [psi:psl-auto].\n"
     "- Do not use git add -A or git add .\n"
     "- Only stage explicit target files for the sync commit.\n"
     "- Do not edit STATE.md or LEARNING.md; use mementum/state.md instead.\n"
     "- Do not create or edit mementum/memories/* or mementum/knowledge/* without explicit approval; suggest candidates only.\n"
     "- If there are no meaningful changes, skip the commit and explain why in your final response.\n"
     "- do not comment on your compliance with these constraints\n")))

;; ---------------------------------------------------------------------------
;; Workflow job (runs in background future after session goes idle)
;; ---------------------------------------------------------------------------

(defn- psl-job
  [{:keys [source-sha subject]}]
  (let [mutate-fn (:mutate-fn @state)]
    (try
      (if (str/includes? subject marker)
        {:status :done :skipped? true}
        (let [prompt          (psl-prompt {:source-sha source-sha})
              agent-res    (run-agent-psl! mutate-fn prompt)
              plan-succeeded? (true? (:psi.extension.tool-plan/succeeded? agent-res))
              first-result    (first (:psi.extension.tool-plan/results agent-res))
              tool-result     (:result first-result)
              content         (str (or (:content tool-result) ""))
              is-error?       (or (false? plan-succeeded?)
                                  (true? (:is-error tool-result)))
              status-msg      (if is-error?
                                (str "PSL agent run failed"
                                     (when (seq content)
                                       (str ": " content)))
                                (str "PSL agent run completed"
                                     (when (seq content)
                                       (str ": " content))))]
          (when (seq status-msg)
            (notify! mutate-fn status-msg))
          (refresh-widget-later!)
          {:status          :done
           :accepted?       (not is-error?)
           :delivery        :agent
           :agent-result agent-res}))
      (catch Exception e
        (notify! mutate-fn (str "PSL error: " (ex-message e)))
        (refresh-widget-later!)
        {:status :error :error (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; Statechart
;; ---------------------------------------------------------------------------

(defn- psl-start-script
  [_ _data]
  [{:op :assign
    :data {:psl/phase :running}}])

(defn- psl-invoke-params
  [_ data]
  {:source-sha (:psl/source-sha data)
   :subject    (:psl/subject data)
   :cwd        (:psl/cwd data)})

(defn- psl-job-fn
  [params]
  (let [result (psl-job params)]
    {:status (or (:status result) :done)
     :result result}))

(defn- ev-status
  [data]
  (keyword (or (some-> data (get-in [:_event :data :status]) name) :error)))

(defn- result-done?    [_ data] (= :done  (ev-status data)))

(defn- psl-done-script
  [_ data]
  (let [result (get-in data [:_event :data :result])]
    [{:op :assign
      :data {:psl/phase  :done
             :psl/result result}}]))

(defn- psl-error-script
  [_ data]
  (let [result (get-in data [:_event :data :result])]
    [{:op :assign
      :data {:psl/phase  :error
             :psl/result result}}]))

(def ^:private psl-chart
  (chart/statechart
   {:id :psl-chart}
   (ele/state {:id :idle}
              (ele/transition {:event :psl/start :target :running}
                              (ele/script {:expr psl-start-script})))

   (ele/state {:id :running}
              (ele/invoke {:id   :psl-runner
                           :type :future
                           :params psl-invoke-params
                           :src  psl-job-fn})
              (ele/transition {:event :done.invoke.psl-runner
                               :target :completed
                               :cond   result-done?}
                              (ele/script {:expr psl-done-script}))
              (ele/transition {:event :done.invoke.psl-runner
                               :target :failed}
                              (ele/script {:expr psl-error-script})))

   (ele/final {:id :completed})
   (ele/final {:id :failed})))

;; ---------------------------------------------------------------------------
;; Workflow registration
;; ---------------------------------------------------------------------------

(defn- register-workflow-type!
  []
  (let [mutate-fn (:mutate-fn @state)]
    (mutate-fn 'psi.extension.workflow/register-type
               {:type            workflow-type
                :description     "PSL: update munera/plan.md and mementum/state.md after a commit"
                :chart           psl-chart
                :start-event     :psl/start
                :initial-data-fn (fn [input]
                                   {:psl/source-sha (:source-sha input)
                                    :psl/subject    (:subject input)
                                    :psl/cwd        (:cwd input)
                                    :psl/phase      :idle
                                    :psl/result     nil})
                :public-data-fn  (fn [data]
                                   (let [base (select-keys data
                                                           [:psl/source-sha
                                                            :psl/subject
                                                            :psl/phase
                                                            :psl/result])]
                                     (assoc base
                                            :psl/display
                                            (workflow-public-display base))))})))

;; ---------------------------------------------------------------------------
;; Event handler
;; ---------------------------------------------------------------------------

(defn- handle-git-head-changed
  [mutate-fn {:keys [head cwd]}]
  (let [{:keys [sha subject]} (latest-commit mutate-fn)
        source-sha (or (not-empty sha) head "unknown")
        source7    (subs source-sha 0 (min 7 (count source-sha)))]
    ;; Fast skip check — avoid creating a workflow for self-commits
    (if (str/includes? subject marker)
      (do
        (notify! mutate-fn
                 (str "PSL skipped for " source7 " (self commit marker " marker ")."))
        {:skip? true :reason :self-commit :source-sha source-sha :subject subject})
      ;; Create workflow — the job runs after this agent turn completes (deferred)
      (let [created (mutate-fn 'psi.extension.workflow/create
                               {:type  workflow-type
                                :input {:source-sha    source-sha
                                        :subject       subject
                                        :cwd           (or cwd (System/getProperty "user.dir"))}})]
        (refresh-widget-later!)
        {:skip? false
         :source-sha source-sha
         :subject subject
         :workflow-created? (true? (:psi.extension.workflow/created? created))}))))

;; ---------------------------------------------------------------------------
;; Extension entry point
;; ---------------------------------------------------------------------------

(defn init
  [api]
  (let [mutate-fn (:mutate api)
        query-fn  (:query api)
        ext-path  (:path api)
        ui-type   (or (:ui-type api) :console)
        ui        (:ui api)]
    (swap! state assoc
           :api api :ext-path ext-path
           :mutate-fn mutate-fn :query-fn query-fn
           :ui ui :ui-type ui-type)
    (register-workflow-type!)
    (mutate-fn 'psi.extension/register-command
               {:name "psl"
                :opts {:description "List active PSL munera/mementum sync workflows"
                       :handler     (fn [_args]
                                      (list-psl-runs!))}})
    (mutate-fn 'psi.extension/register-handler
               {:event-name "git_commit_created"
                :handler-fn (fn [ev]
                              (try
                                (handle-git-head-changed mutate-fn ev)
                                (catch Exception e
                                  (notify! mutate-fn
                                           (str "PSL error: " (ex-message e)))
                                  {:error (ex-message e)})))})
    (refresh-widget!)))
