(ns extensions.mcp-tasks-run
  "mcp-tasks-run extension.

   Runs an mcp-tasks task/story workflow using Psi sub-agents (one sub-agent
   per workflow step), driven by derived task/story state.

   Command surface:
     /mcp-tasks-run <task-id>
     /mcp-tasks-run list
     /mcp-tasks-run pause <run-id>
     /mcp-tasks-run resume <run-id> [merge|<answer>]
     /mcp-tasks-run cancel <run-id>
     /mcp-tasks-run retry <run-id>

   Notes:
   - Reimplements derive-task-state/derive-story-state and flow logic.
   - Uses extension workflow runtime.
   - Prompts are loaded from `mcp-tasks prompts show --cli` with local copied
     prompts for `complete-story` and `squash-merge-on-gh` (and a local
     fallback for missing `create-task-pr`)."
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.tools :as session-tools]
   [psi.ai.models :as models]
   [taoensso.timbre :as timbre]))

(def ^:private run-workflow-type :mcp-tasks-run)
(def ^:private max-steps-default 50)

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
     :psi.extension.workflow/meta
     :psi.extension.workflow/data
     :psi.extension.workflow/result
     :psi.extension.workflow/elapsed-ms
     :psi.extension.workflow/started-at]}])

(defonce ^:private state
  (atom {:api           nil
         :ext-path      nil
         :query-fn      nil
         :mutate-fn     nil
         :ui            nil
         :next-run-id   (atom 1)
         :run-controls  (atom {})
         :live-progress (atom {})
         :prompt-cache  (atom {})
         :widget-ids    #{}}))

(def ^:private copied-prompts
  {"complete-story"
   "Complete the current story"

   "squash-merge-on-gh"
   (str "Squash merge the PR on GitHub, in the context of the current story\n\n"
        "Pull the merged remote changes to local.")

   ;; mcp-tasks currently does not expose a built-in create-task-pr prompt.
   "create-task-pr"
   (str "Create a pull request for the current task.\n\n"
        "Process:\n"
        "1) Verify you are on the task worktree branch (not main/master).\n"
        "2) Ensure changes are committed.\n"
        "3) Create PR with gh: gh pr create --fill (or equivalent).\n"
        "4) Extract PR number from PR URL/output.\n"
        "5) Record PR number on task: mcp-tasks update --task-id <id> --pr-num <num>.\n"
        "6) Summarize PR URL and PR number.")})

(def ^:private task-state->prompt
  {:unrefined              "refine-task"
   :refined                "execute-task"
   :wait-user-confirmation "execute-task"
   :done                   "review-task-implementation"
   :awaiting-pr            "create-task-pr"
   :merging-pr             "squash-merge-on-gh"
   :complete               "complete-story"})

(def ^:private story-state->prompt
  {:unrefined              "refine-task"
   :refined                "create-story-tasks"
   :has-tasks              "execute-story-child"
   :wait-user-confirmation "execute-story-child"
   :done                   "review-story-implementation"
   :awaiting-pr            "create-story-pr"
   :merging-pr             "squash-merge-on-gh"
   :complete               "complete-story"})

;; ---------------------------------------------------------------------------
;; Basics
;; ---------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- parse-int [x]
  (cond
    (number? x) (long x)
    (string? x) (try (Long/parseLong (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn- status-icon [phase]
  (case phase
    :running "●"
    :paused "‖"
    :done "✓"
    :error "✗"
    :cancelled "⊘"
    :idle "○"
    "?"))

(defn- task-preview [s n]
  (let [s (or s "")]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (- n 3))) "...")
      s)))

(defn- phase-of [wf]
  (or (:psi.extension.workflow/phase wf)
      (cond
        (:psi.extension.workflow/running? wf) :running
        (:psi.extension.workflow/error? wf) :error
        (:psi.extension.workflow/done? wf) :done
        :else :unknown)))

(defn- phase-label [phase]
  (-> phase name str/upper-case))

(defn- elapsed-seconds [wf]
  (let [running?   (:psi.extension.workflow/running? wf)
        elapsed-ms (or (:psi.extension.workflow/elapsed-ms wf) 0)
        started-at (:psi.extension.workflow/started-at wf)
        started-ms (when (instance? java.time.Instant started-at)
                     (.toEpochMilli ^java.time.Instant started-at))]
    (if (and running? started-ms)
      (quot (- (now-ms) started-ms) 1000)
      (quot elapsed-ms 1000))))

(defn- notify!
  [text level]
  (if-let [ui (:ui @state)]
    ((:notify ui) text level)
    (println text)))

(defn- query!
  [q]
  (when-let [qf (:query-fn @state)]
    (try
      (qf q)
      (catch Exception _ nil))))

(defn- mutate!
  [op params]
  (when-let [mf (:mutate-fn @state)]
    (try
      (mf op params)
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Workflow lookup helpers
;; ---------------------------------------------------------------------------

(defn- all-workflows []
  (or (:psi.extension/workflows (query! workflow-eql)) []))

(defn- mcp-run-workflows []
  (let [ext-path (:ext-path @state)]
    (->> (all-workflows)
         (filter (fn [wf]
                   (and (= ext-path (:psi.extension/path wf))
                        (= run-workflow-type (:psi.extension.workflow/type wf)))))
         (sort-by :psi.extension.workflow/id))))

(defn- workflow-by-id [id]
  (let [sid (str id)]
    (some #(when (= sid (:psi.extension.workflow/id %)) %) (mcp-run-workflows))))

(defn- active-running-workflow []
  (first (filter #(= :running (phase-of %)) (mcp-run-workflows))))

(defn- run-id->n [run-id]
  (some->> (re-find #"run-(\\d+)$" (str run-id)) second parse-int))

(defn- sync-next-run-id!
  []
  (let [max-id (->> (mcp-run-workflows)
                    (keep (comp run-id->n :psi.extension.workflow/id))
                    (reduce max 0))]
    (reset! (:next-run-id @state) (inc max-id))))

(defn- next-run-id! []
  (let [a (:next-run-id @state)
        existing (into #{} (map :psi.extension.workflow/id) (mcp-run-workflows))]
    (loop [n @a]
      (let [rid (str "run-" n)]
        (if (contains? existing rid)
          (recur (inc n))
          (do
            (reset! a (inc n))
            rid))))))

;; ---------------------------------------------------------------------------
;; Derived state (reimplemented from task-conductor)
;; ---------------------------------------------------------------------------

(defn refined?
  "True when task/story metadata contains a `refined` key (any namespace)."
  [task]
  (let [m (:meta task)]
    (boolean
     (some (fn [[k _]] (= "refined" (name k))) m))))

(defn- active-children [children]
  (remove #(#{:deleted "deleted"} (:status %)) children))

(defn count-completed-children
  "Count children with done/closed status (keyword or string)."
  [children]
  (count (filter #(#{:closed "closed" :done "done"} (:status %))
                 (active-children children))))

(defn- children-complete? [children]
  (let [active (active-children children)]
    (and (seq active)
         (every? #(#{:closed "closed" :done "done"} (:status %)) active))))

(defn- has-incomplete-children? [children]
  (let [active (active-children children)]
    (and (seq active)
         (some #(not (#{:closed "closed" :done "done"} (:status %))) active))))

(defn derive-task-state
  "Derive state for a standalone task.

   Returns one of:
   :unrefined :refined :done :awaiting-pr :wait-pr-merge :complete :terminated"
  [task]
  (cond
    (= :closed (:status task))
    :terminated

    (and (:pr-num task) (:pr-merged? task))
    :complete

    (:pr-num task)
    :wait-pr-merge

    (and (= :done (:status task)) (:code-reviewed task))
    :awaiting-pr

    (= :done (:status task))
    :done

    (refined? task)
    :refined

    :else
    :unrefined))

(defn derive-story-state
  "Derive state for a story.

   Returns one of:
   :unrefined :refined :has-tasks :done :awaiting-pr :wait-pr-merge
   :complete :terminated"
  [story children]
  (cond
    (= :closed (:status story))
    :terminated

    (and (:pr-num story) (:pr-merged? story))
    :complete

    (has-incomplete-children? children)
    :has-tasks

    (:pr-num story)
    :wait-pr-merge

    (and (children-complete? children) (:code-reviewed story))
    :awaiting-pr

    (children-complete? children)
    :done

    (refined? story)
    :refined

    :else
    :unrefined))

(defn- no-progress?
  [pre-state new-state pre-completed-children new-completed-children]
  (let [was-has-tasks (= :has-tasks pre-state)
        is-has-tasks  (= :has-tasks new-state)]
    (if (and was-has-tasks is-has-tasks)
      (= pre-completed-children new-completed-children)
      (= pre-state new-state))))

;; ---------------------------------------------------------------------------
;; Shell + mcp/gh helpers
;; ---------------------------------------------------------------------------

(defn- safe-read-string [s]
  (binding [*read-eval* false]
    (read-string (str/replace (or s "") #":::(\w)" ":$1"))))

(def ^:private user-confirmation-prefix "MCP_TASKS_RUN_USER_CONFIRMATION:")

(defn- parse-user-confirmation
  [text]
  (let [line (some->> (str/split-lines (or text ""))
                      (map str/trim)
                      (filter #(str/starts-with? % user-confirmation-prefix))
                      first)
        payload-str (some-> line
                            (subs (count user-confirmation-prefix))
                            str/trim)]
    (when (seq payload-str)
      (try
        (let [m (safe-read-string payload-str)]
          (when (map? m)
            {:question      (or (:question m) (:prompt m))
             :context       (:context m)
             :expected      (or (:expected-answer m)
                                (:expected-answer-shape m)
                                :free-text)
             :raw           m}))
        (catch Exception _ nil)))))

(defn- run-shell
  [{:keys [dir args]}]
  (try
    (let [result (apply proc/shell
                        {:dir      dir
                         :out      :string
                         :err      :string
                         :continue true
                         :in       (java.io.File. "/dev/null")}
                        args)
          out    (or (:out result) "")
          err    (or (:err result) "")
          parsed (try
                   (let [trimmed (str/trim out)]
                     (when (seq trimmed)
                       (safe-read-string trimmed)))
                   (catch Exception _ nil))]
      (cond-> {:exit   (:exit result)
               :out    out
               :err    err
               :parsed parsed}
        (not= 0 (:exit result))
        (assoc :error (str "Command failed: " (str/join " " args)))))
    (catch Exception e
      {:exit  1
       :out   ""
       :err   (ex-message e)
       :error (ex-message e)})))

(defn- run-mcp!
  [project-dir args]
  (let [res (run-shell {:dir  project-dir
                        :args (into ["mcp-tasks"] (conj (vec args) "--format" "edn"))})]
    (cond
      (:parsed res) (:parsed res)
      (:error res)  {:error (:error res)
                     :exit-code (:exit res)
                     :stderr (:err res)
                     :stdout (:out res)}
      :else         {:error "No parseable output from mcp-tasks"
                     :stderr (:err res)
                     :stdout (:out res)})))

(defn- run-gh-state!
  [project-dir pr-num]
  (let [res (run-shell {:dir  project-dir
                        :args ["gh" "pr" "view" (str pr-num)
                               "--json" "state" "--jq" ".state"]})]
    (when (zero? (:exit res))
      (str/trim (:out res)))))

(defn- pr-merged?
  [project-dir pr-num]
  (= "MERGED" (run-gh-state! project-dir pr-num)))

(defn- effective-dir
  [worktree-dir project-dir]
  (if (and worktree-dir (.exists (io/file worktree-dir)))
    worktree-dir
    project-dir))

(defn- normalize-status [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    :else :open))

(defn- normalize-type [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    :else :task))

(defn- task->derive-map
  [task project-dir]
  (let [pr-num (:pr-num task)]
    {:status        (normalize-status (:status task))
     :meta          (:meta task)
     :pr-num        pr-num
     :code-reviewed (:code-reviewed task)
     :pr-merged?    (when (and pr-num project-dir)
                      (pr-merged? project-dir pr-num))}))

(defn- story?
  [task]
  (= :story (normalize-type (:type task))))

(defn- fetch-task!
  [project-dir worktree-dir task-id]
  (let [dir      (effective-dir worktree-dir project-dir)
        show-res (run-mcp! dir ["show" "--task-id" (str task-id)])]
    (cond
      (:task show-res)
      {:task (:task show-res)}

      (:error show-res)
      (let [closed-res (run-mcp! project-dir ["list" "--task-id" (str task-id)
                                              "--status" "closed"])
            task       (first (:tasks closed-res))]
        (if task
          {:task task}
          {:error (or (:error show-res)
                      (:error closed-res)
                      (str "Task #" task-id " not found"))}))

      :else
      {:error (str "Task #" task-id " not found")})))

(defn- fetch-children!
  [project-dir worktree-dir task-id]
  (let [dir (effective-dir worktree-dir project-dir)
        res (run-mcp! dir ["list" "--parent-id" (str task-id) "--status" "any"])]
    (if (:error res)
      {:error (:error res) :children []}
      {:children (vec (or (:tasks res) []))})))

(defn- derive-current!
  [project-dir worktree-dir task-id]
  (let [{:keys [task error]} (fetch-task! project-dir worktree-dir task-id)]
    (if error
      {:error error}
      (let [entity-type (if (story? task) :story :task)
            task*       (task->derive-map task (effective-dir worktree-dir project-dir))]
        (if (= :story entity-type)
          (let [{:keys [children error]} (fetch-children! project-dir worktree-dir task-id)]
            (if error
              {:error error}
              (let [child* (mapv #(task->derive-map % nil) children)]
                {:task             task
                 :children         children
                 :entity-type      :story
                 :state            (derive-story-state task* child*)
                 :completed-count  (count-completed-children child*)})))
          {:task             task
           :children         []
           :entity-type      :task
           :state            (derive-task-state task*)
           :completed-count  nil})))))

(defn- ensure-worktree!
  [project-dir task-id]
  (let [res (run-mcp! project-dir ["work-on" "--task-id" (str task-id)])
        wt  (or (:worktree-path res) (:worktree-dir res))]
    (if (:error res)
      {:error (:error res)}
      {:worktree-dir (or wt project-dir)})))

;; ---------------------------------------------------------------------------
;; Prompt loading
;; ---------------------------------------------------------------------------

(defn- prompt-cache-key [project-dir prompt-name]
  [(or project-dir "") prompt-name])

(defn- prompt-content
  [res]
  (let [parsed (:parsed res)]
    (when (and (map? parsed)
               (string? (:content parsed)))
      (:content parsed))))

(defn- show-prompt
  [project-dir prompt-name cli?]
  (run-shell
   {:dir  project-dir
    :args (cond-> ["mcp-tasks" "prompts" "show" prompt-name]
            cli? (conj "--cli")
            true (conj "--format" "edn"))}))

(defn- load-mcp-prompt!
  [project-dir prompt-name]
  (let [k     (prompt-cache-key project-dir prompt-name)
        cache (:prompt-cache @state)]
    (if-let [cached (get @cache k)]
      cached
      (let [content (or (prompt-content (show-prompt project-dir prompt-name true))
                        ;; Backward compatibility with older mcp-tasks versions.
                        (prompt-content (show-prompt project-dir prompt-name false)))]
        (if (seq content)
          (do
            (swap! cache assoc k content)
            content)
          nil)))))

(defn- resolve-step-prompt
  [project-dir prompt-name]
  (or (get copied-prompts prompt-name)
      (load-mcp-prompt! project-dir prompt-name)
      (str "Run workflow step: " prompt-name)))

(defn- open-unblocked?
  [child]
  (and (#{:open "open"} (:status child))
       (not (:is-blocked child))))

(defn- resolve-category-for-step
  "Pre-resolve the category workflow prompt for execute-task/execute-story-child.
   Returns the category prompt content string, or nil on any failure."
  [step-name task children _entity-type project-dir]
  (let [category (case step-name
                   "execute-task"
                   (:category task)

                   "execute-story-child"
                   (:category (first (filter open-unblocked? children)))

                   nil)]
    (if-not category
      (do
        (when (#{"execute-task" "execute-story-child"} step-name)
          (timbre/warn "No category found for step" step-name))
        nil)
      (try
        (load-mcp-prompt! project-dir (str category))
        (catch Exception e
          (timbre/warn "Failed to load category prompt for" category ":" (ex-message e))
          nil)))))

(defn- interpolate-arguments
  [prompt-text task-id]
  (str/replace (or prompt-text "") "$ARGUMENTS" (str task-id)))

(defn- step-prompt-name
  [entity-type step-state]
  (get (if (= :story entity-type) story-state->prompt task-state->prompt)
       step-state))

;; ---------------------------------------------------------------------------
;; Sub-agent execution (one sub-agent per step)
;; ---------------------------------------------------------------------------

(defn- provider-name [p]
  (cond
    (keyword? p) (name p)
    (string? p) p
    :else nil))

(defn- resolve-active-model []
  (let [m        (:psi.agent-session/model (query! [:psi.agent-session/model]))
        provider (provider-name (:provider m))
        id       (:id m)]
    (or (some (fn [model]
                (when (and (= provider (provider-name (:provider model)))
                           (= id (:id model)))
                  model))
              (vals models/all-models))
        (get models/all-models :sonnet-4.6)
        (first (vals models/all-models))
        m)))

(defn- current-system-prompt []
  (:psi.agent-session/system-prompt
   (query! [:psi.agent-session/system-prompt])))

(defn- create-step-agent-ctx
  [worktree-dir]
  (let [ctx (agent/create-context)]
    (agent/create-agent-in! ctx)
    (agent/set-tools-in!
     ctx
     (session-tools/make-tools-with-cwd worktree-dir))
    (agent/set-thinking-level-in! ctx :off)
    (when-let [sp (current-system-prompt)]
      (agent/set-system-prompt-in! ctx sp))
    ctx))

(defn- result->text
  [result]
  (->> (:content result)
       (keep (fn [c]
               (case (:type c)
                 :text (:text c)
                 :error (:text c)
                 nil)))
       (str/join "\n")))

(declare refresh-widgets-later!)

(defn- set-live-progress!
  [run-id m]
  (swap! (:live-progress @state)
         assoc
         (str run-id)
         (assoc m :updated-at (java.time.Instant/now)))
  (refresh-widgets-later!))

(defn- clear-live-progress!
  [run-id]
  (swap! (:live-progress @state) dissoc (str run-id))
  (refresh-widgets-later!))

(defn- build-step-request
  [{:keys [step-name prompt-body task-id entity-type
           project-dir worktree-dir task children state
           user-confirmation user-answer category-prompt]}]
  (let [prompt* (interpolate-arguments prompt-body task-id)]
    (str
     "You are executing ONE mcp-tasks workflow step as a sub-agent.\n\n"
     "Step: " step-name "\n"
     "Entity: " (name entity-type) " #" task-id "\n"
     "Current derived state: " (name state) "\n"
     "Project root: " project-dir "\n"
     "Worktree dir: " worktree-dir "\n\n"

     "Constraints:\n"
     "- No slash commands are available.\n"
     "- Use the available tools (read/bash/edit/write).\n"
     "- Use `mcp-tasks` CLI via bash for task/story operations.\n"
     "- If a prompt mentions MCP tools, map them to CLI equivalents:\n"
     "  - select-tasks => mcp-tasks list/show\n"
     "  - update-task  => mcp-tasks update\n"
     "  - add-task     => mcp-tasks add\n"
     "  - complete-task=> mcp-tasks complete\n"
     "  - work-on      => mcp-tasks work-on\n"
     "  - why-blocked  => mcp-tasks why-blocked\n"
     "- For trivial/mechanical questions (overwrite branch, retry transient error,\n"
     "  confirm file overwrite), choose a safe default silently and continue.\n"
     "- For material questions (design choices, proceeding despite warnings like\n"
     "  \"task is unrefined\", ambiguous requirements, user preferences), output\n"
     "  the confirmation sentinel as the LAST line of your response:\n"
     "  MCP_TASKS_RUN_USER_CONFIRMATION: {:question \"...\" :context \"...\" :expected-answer \"...\"}\n"
     "  :question is required. :context and :expected-answer are optional.\n"
     "  Do not output anything after the sentinel line.\n"
     "- Keep changes scoped to this one step.\n\n"

     "Workflow prompt body:\n"
     "-----\n"
     prompt* "\n"
     "-----\n\n"

     (when category-prompt
       (str "Pre-resolved category instructions (referenced by workflow prompt above):\n"
            "-----\n"
            category-prompt "\n"
            "-----\n\n"))

     "Current task snapshot (EDN):\n"
     (pr-str task) "\n\n"

     (when (= :story entity-type)
       (str "Current story children snapshot (EDN):\n"
            (pr-str children) "\n\n"))

     (when (= :wait-user-confirmation state)
       (str "Workflow is waiting for explicit user confirmation answer.\n"
            "Current confirmation payload (EDN):\n"
            (pr-str user-confirmation) "\n\n"
            "You must provide a deterministic safe answer and call:\n"
            "mcp-tasks-run resume <run-id> <answer>\n"
            "using available extension command mechanism if present; otherwise set answer in workflow data via CLI-supported path.\n\n"))

     (when user-answer
       (str "Resume answer payload provided for this step (EDN):\n"
            (pr-str user-answer) "\n\n"))

     "Return a concise summary with:\n"
     "1) actions taken\n"
     "2) key outputs\n"
     "3) whether this likely advanced workflow state.\n")))

(defn- run-step-subagent!
  [{:keys [run-id step-name prompt-body task-id entity-type
           project-dir worktree-dir task children state
           user-confirmation user-answer get-api-key-fn
           category-prompt]}]
  (let [started (now-ms)
        model   (resolve-active-model)
        api-key (when (fn? get-api-key-fn)
                  (get-api-key-fn (:provider model)))
        req     (build-step-request {:step-name step-name
                                     :prompt-body prompt-body
                                     :task-id task-id
                                     :entity-type entity-type
                                     :project-dir project-dir
                                     :worktree-dir worktree-dir
                                     :task task
                                     :children children
                                     :state state
                                     :user-confirmation user-confirmation
                                     :user-answer user-answer
                                     :category-prompt category-prompt})
        user-msg {:role      "user"
                  :content   [{:type :text :text req}]
                  :timestamp (java.time.Instant/now)}]
    (set-live-progress!
     run-id
     {:status  :running
      :state   state
      :step    step-name
      :message (str "Executing step " step-name)})
    (try
      (let [agent-ctx (create-step-agent-ctx worktree-dir)
            result   (executor/run-agent-loop!
                      nil
                      agent-ctx
                      model
                      [user-msg]
                      (cond-> {:turn-ctx-atom nil}
                        api-key (assoc :api-key api-key)))
            elapsed  (- (now-ms) started)
            text     (result->text result)
            ok?      (not= :error (:stop-reason result))]
        (set-live-progress!
         run-id
         {:status  (if ok? :running :error)
          :state   state
          :step    step-name
          :message (task-preview (or (some-> text str/split-lines last) "") 100)})
        {:ok?           ok?
         :elapsed-ms    elapsed
         :text          text
         :error-message (:error-message result)})
      (catch Exception e
        (set-live-progress!
         run-id
         {:status  :error
          :state   state
          :step    step-name
          :message (str "Error: " (ex-message e))})
        {:ok?           false
         :elapsed-ms    (- (now-ms) started)
         :text          (str "Error: " (ex-message e))
         :error-message (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; Control helpers
;; ---------------------------------------------------------------------------

(defn- ensure-control!
  [run-id]
  (let [run-id (str run-id)
        a      (:run-controls @state)]
    (or (get @a run-id)
        (let [ctrl (atom {:pause? false :cancel? false :merge? false :answer nil})]
          (swap! a assoc run-id ctrl)
          ctrl))))

(defn- control-for [run-id]
  (get @(:run-controls @state) (str run-id)))

(defn- clear-control!
  [run-id]
  (swap! (:run-controls @state) dissoc (str run-id)))

(defn- set-control!
  [run-id f]
  (when-let [ctrl (control-for run-id)]
    (swap! ctrl f)
    true))

;; ---------------------------------------------------------------------------
;; Run loop job
;; ---------------------------------------------------------------------------

(defn- summarize-run
  [{:keys [status run-id task-id entity-type steps elapsed-ms final-state
           error-message pause-reason]}]
  (str "[mcp-tasks-run " run-id "] "
       (name status)
       " · " (name (or entity-type :task))
       " #" task-id
       " · steps " steps
       " · " (quot (or elapsed-ms 0) 1000) "s"
       (when final-state
         (str " · final " (name final-state)))
       (when pause-reason
         (str " · reason " (name pause-reason)))
       (when (seq error-message)
         (str " · " error-message))))

(defn- run-result
  [{:keys [status run-id task-id entity-type worktree-dir current-state
           steps history last-step last-output final-state pause-reason
           user-confirmation user-answer started-ms error-message]}]
  (let [elapsed (- (now-ms) started-ms)
        entity* (or entity-type :task)
        summary (summarize-run {:status        status
                                :run-id        run-id
                                :task-id       task-id
                                :entity-type   entity*
                                :steps         steps
                                :elapsed-ms    elapsed
                                :final-state   final-state
                                :pause-reason  pause-reason
                                :error-message error-message})]
    (cond-> {:status          status
             :run-id          run-id
             :task-id         task-id
             :entity-type     entity*
             :worktree-dir    worktree-dir
             :current-state   current-state
             :steps-completed steps
             :history         (vec (or history []))
             :last-step       last-step
             :last-output     last-output
             :elapsed-ms      elapsed
             :summary         summary}
      final-state       (assoc :final-state final-state)
      pause-reason      (assoc :pause-reason pause-reason)
      user-confirmation (assoc :user-confirmation user-confirmation)
      user-answer       (assoc :user-answer user-answer)
      error-message     (assoc :error-message error-message))))

;; ---------------------------------------------------------------------------
;; UI refresh
;; ---------------------------------------------------------------------------

(defn- widget-lines
  [wf]
  (let [id        (:psi.extension.workflow/id wf)
        phase     (phase-of wf)
        data      (or (:psi.extension.workflow/data wf) {})
        live      (get @(:live-progress @state) (str id))
        task-id   (or (:run/task-id data)
                      (get-in wf [:psi.extension.workflow/input :task-id]))
        entity    (or (:run/entity-type data) :task)
        cur-state (or (:state live)
                      (:run/current-state data)
                      :unknown)
        cur-step  (or (:step live)
                      (:run/last-step data)
                      "-")
        message   (or (:message live)
                      (:run/last-output data)
                      (:psi.extension.workflow/error-message wf)
                      "")
        elapsed   (elapsed-seconds wf)
        pause-rsn (:run/pause-reason data)
        top       (str (status-icon phase)
                       " mcp-run " id
                       " [" (phase-label phase) "]"
                       " · " (name entity) " #" task-id
                       " · state " (name (or cur-state :unknown))
                       " · step " cur-step
                       " · " elapsed "s")
        detail    (when (seq (str/trim (str message)))
                    (str "  " (task-preview (str/trim (str message)) 100)))
        question  (when (= pause-rsn :wait-user-confirmation)
                    (when-let [q (:question (:run/user-confirmation data))]
                      (when (seq (str/trim q))
                        (str "  ❓ " (task-preview (str/trim q) 200)))))
        actions   (cond
                    (= phase :running)
                    (str "  /mcp-tasks-run pause " id " · /mcp-tasks-run cancel " id)

                    (= phase :paused)
                    (cond
                      (= pause-rsn :wait-pr-merge)
                      (str "  /mcp-tasks-run resume " id " merge · /mcp-tasks-run cancel " id)

                      (= pause-rsn :wait-user-confirmation)
                      (str "  /mcp-tasks-run resume " id " <answer> · /mcp-tasks-run cancel " id)

                      :else
                      (str "  /mcp-tasks-run resume " id " · /mcp-tasks-run cancel " id))

                    (= phase :error)
                    (str "  /mcp-tasks-run retry " id " · /mcp-tasks-run cancel " id)

                    :else nil)]
    (cond-> [top]
      (seq detail)   (conj detail)
      (seq question) (conj question)
      (seq actions)  (conj actions))))

(defn- refresh-widgets!
  []
  (when-let [ui (:ui @state)]
    (let [wfs         (mcp-run-workflows)
          current-ids (into #{} (map #(str "mcp-run-" (:psi.extension.workflow/id %)) wfs))
          old-ids     (:widget-ids @state)
          removed     (set/difference old-ids current-ids)
          running     (count (filter :psi.extension.workflow/running? wfs))
          paused      (count (filter #(= :paused (phase-of %)) wfs))
          errors      (count (filter :psi.extension.workflow/error? wfs))]
      (doseq [wid removed]
        ((:clear-widget ui) wid))
      (doseq [wf wfs]
        ((:set-widget ui)
         (str "mcp-run-" (:psi.extension.workflow/id wf))
         :above-editor
         (widget-lines wf)))
      (when-let [set-status (:set-status ui)]
        (set-status (if (seq wfs)
                      (str "mcp-tasks-run: " (count wfs) " run(s) · "
                           running " running · " paused " paused · " errors " error")
                      "")))
      (when (and (empty? wfs) (fn? (:clear-status ui)))
        ((:clear-status ui)))
      (swap! state assoc :widget-ids current-ids))))

(defn refresh-widgets-later!
  []
  (future
    (Thread/sleep 25)
    (refresh-widgets!)))

;;; Workflow-native step runner

(defn- run-loop-job
  [{:keys [run-id task-id project-dir worktree-dir control max-steps
           current-state steps history user-confirmation user-answer
           get-api-key-fn]}]
  (let [started-ms (now-ms)
        max-steps* (long (or max-steps max-steps-default))
        steps      (long (or steps 0))
        history    (vec (or history []))
        phase      (or current-state :ensure-worktree)
        merge?     (boolean (:merge? @control))
        answer     (or (:answer @control) user-answer)]
    (try
      (cond
        (>= steps max-steps*)
        (run-result {:status        :error
                     :run-id        run-id
                     :task-id       task-id
                     :worktree-dir  worktree-dir
                     :current-state phase
                     :steps         steps
                     :history       history
                     :started-ms    started-ms
                     :error-message (str "Reached max steps (" max-steps* ")")})

        (:cancel? @control)
        (run-result {:status        :cancelled
                     :run-id        run-id
                     :task-id       task-id
                     :worktree-dir  worktree-dir
                     :current-state phase
                     :steps         steps
                     :history       history
                     :started-ms    started-ms
                     :final-state   :cancelled})

        (and (:pause? @control)
             (not (contains? #{:wait-pr-merge :wait-user-confirmation} phase)))
        (run-result {:status        :paused
                     :run-id        run-id
                     :task-id       task-id
                     :worktree-dir  worktree-dir
                     :current-state :paused
                     :steps         steps
                     :history       history
                     :started-ms    started-ms
                     :pause-reason  :user-paused})

        (= phase :ensure-worktree)
        (let [{wt :worktree-dir wt-error :error}
              (if (seq worktree-dir)
                {:worktree-dir worktree-dir}
                (ensure-worktree! project-dir task-id))]
          (if wt-error
            (run-result {:status        :error
                         :run-id        run-id
                         :task-id       task-id
                         :worktree-dir  worktree-dir
                         :current-state phase
                         :steps         steps
                         :history       history
                         :started-ms    started-ms
                         :error-message wt-error})
            (run-result {:status        :running
                         :run-id        run-id
                         :task-id       task-id
                         :worktree-dir  wt
                         :current-state :derive-state
                         :steps         steps
                         :history       history
                         :started-ms    started-ms})))

        (and (= phase :wait-user-confirmation)
             (str/blank? (str answer)))
        (run-result {:status            :paused
                     :run-id            run-id
                     :task-id           task-id
                     :worktree-dir      worktree-dir
                     :current-state     :wait-user-confirmation
                     :steps             steps
                     :history           history
                     :started-ms        started-ms
                     :pause-reason      :wait-user-confirmation
                     :user-confirmation user-confirmation
                     :user-answer       nil
                     :error-message     "Resume requires explicit answer payload for user confirmation."})

        (contains? #{:derive-state :select-step :run-step :rederive-state
                     :wait-pr-merge :wait-user-confirmation} phase)
        (let [derived (derive-current! project-dir worktree-dir task-id)]
          (if-let [err (:error derived)]
            (run-result {:status        :error
                         :run-id        run-id
                         :task-id       task-id
                         :worktree-dir  worktree-dir
                         :current-state phase
                         :steps         steps
                         :history       history
                         :started-ms    started-ms
                         :error-message err})
            (let [{:keys [task children entity-type state completed-count]} derived
                  step-state                                                (if (and (= state :wait-pr-merge) merge?) :merging-pr state)]
              (cond
                (= state :terminated)
                (run-result {:status        :done
                             :run-id        run-id
                             :task-id       task-id
                             :entity-type   entity-type
                             :worktree-dir  worktree-dir
                             :current-state :done
                             :steps         steps
                             :history       history
                             :started-ms    started-ms
                             :final-state   :terminated})

                (and (= state :wait-pr-merge) (not merge?))
                (run-result {:status        :paused
                             :run-id        run-id
                             :task-id       task-id
                             :entity-type   entity-type
                             :worktree-dir  worktree-dir
                             :current-state :wait-pr-merge
                             :steps         steps
                             :history       history
                             :started-ms    started-ms
                             :pause-reason  :wait-pr-merge})

                (= state :wait-user-confirmation)
                (run-result {:status            :paused
                             :run-id            run-id
                             :task-id           task-id
                             :entity-type       entity-type
                             :worktree-dir      worktree-dir
                             :current-state     :wait-user-confirmation
                             :steps             steps
                             :history           history
                             :started-ms        started-ms
                             :pause-reason      :wait-user-confirmation
                             :user-confirmation user-confirmation})

                :else
                (if-let [prompt-name (step-prompt-name entity-type step-state)]
                  (let [prompt-body    (resolve-step-prompt project-dir prompt-name)
                        cat-prompt     (resolve-category-for-step
                                        prompt-name task children entity-type project-dir)
                        step-start     (now-ms)
                        step-result    (run-step-subagent!
                                        {:run-id run-id
                                         :step-name prompt-name
                                         :prompt-body prompt-body
                                         :task-id task-id
                                         :entity-type entity-type
                                         :project-dir project-dir
                                         :worktree-dir worktree-dir
                                         :task task
                                         :children children
                                         :state step-state
                                         :user-confirmation user-confirmation
                                         :user-answer user-answer
                                         :get-api-key-fn get-api-key-fn
                                         :category-prompt cat-prompt})
                        step-elapsed (- (now-ms) step-start)
                        base-entry   {:state      step-state
                                      :step       prompt-name
                                      :elapsed-ms step-elapsed
                                      :output     (task-preview (:text step-result) 500)}]
                    (if-not (:ok? step-result)
                      (let [history' (conj history (assoc base-entry :ok? false))]
                        (run-result {:status        :error
                                     :run-id        run-id
                                     :task-id       task-id
                                     :entity-type   entity-type
                                     :worktree-dir  worktree-dir
                                     :current-state step-state
                                     :steps         steps
                                     :history       history'
                                     :last-step     prompt-name
                                     :last-output   (task-preview (:text step-result) 500)
                                     :started-ms    started-ms
                                     :error-message (or (:error-message step-result)
                                                        (:text step-result)
                                                        "Step failed")}))
                      (let [confirmation (parse-user-confirmation (:text step-result))
                            history'     (conj history (assoc base-entry :ok? true))]
                        (if confirmation
                          (do
                            (when (instance? clojure.lang.IAtom control)
                              (swap! control assoc :answer nil :pause? false))
                            (run-result {:status            :paused
                                         :run-id            run-id
                                         :task-id           task-id
                                         :entity-type       entity-type
                                         :worktree-dir      worktree-dir
                                         :current-state     :wait-user-confirmation
                                         :steps             (inc steps)
                                         :history           history'
                                         :last-step         prompt-name
                                         :last-output       (task-preview (:text step-result) 500)
                                         :started-ms        started-ms
                                         :pause-reason      :wait-user-confirmation
                                         :user-confirmation confirmation
                                         :user-answer       nil}))
                          (let [derived2 (derive-current! project-dir worktree-dir task-id)]
                            (if-let [err2 (:error derived2)]
                              (run-result {:status        :error
                                           :run-id        run-id
                                           :task-id       task-id
                                           :entity-type   entity-type
                                           :worktree-dir  worktree-dir
                                           :current-state step-state
                                           :steps         steps
                                           :history       history'
                                           :last-step     prompt-name
                                           :last-output   (task-preview (:text step-result) 500)
                                           :started-ms    started-ms
                                           :error-message err2})
                              (let [next-state     (:state derived2)
                                    next-completed (:completed-count derived2)
                                    progress?      (not (no-progress? state next-state
                                                                      completed-count
                                                                      next-completed))
                                    history-entry  (assoc base-entry
                                                          :ok? true
                                                          :next-state next-state
                                                          :completed-count next-completed)
                                    history''      (conj history history-entry)]
                                (if-not progress?
                                  (run-result {:status        :error
                                               :run-id        run-id
                                               :task-id       task-id
                                               :entity-type   entity-type
                                               :worktree-dir  worktree-dir
                                               :current-state next-state
                                               :steps         (inc steps)
                                               :history       history''
                                               :last-step     prompt-name
                                               :last-output   (task-preview (:text step-result) 500)
                                               :started-ms    started-ms
                                               :error-message (str "No progress after step " prompt-name
                                                                   ", state remained " (name next-state))})
                                  (do
                                    (when (= step-state :merging-pr)
                                      (swap! control assoc :merge? false))
                                    (run-result {:status        :running
                                                 :run-id        run-id
                                                 :task-id       task-id
                                                 :entity-type   entity-type
                                                 :worktree-dir  worktree-dir
                                                 :current-state :derive-state
                                                 :steps         (inc steps)
                                                 :history       history''
                                                 :last-step     prompt-name
                                                 :last-output   (task-preview (:text step-result) 500)
                                                 :started-ms    started-ms}))))))))))
                  (run-result {:status        :error
                               :run-id        run-id
                               :task-id       task-id
                               :entity-type   entity-type
                               :worktree-dir  worktree-dir
                               :current-state step-state
                               :steps         steps
                               :history       history
                               :started-ms    started-ms
                               :error-message (str "No prompt mapping for state " (name step-state))}))))))

        :else
        (run-result {:status        :error
                     :run-id        run-id
                     :task-id       task-id
                     :worktree-dir  worktree-dir
                     :current-state phase
                     :steps         steps
                     :history       history
                     :started-ms    started-ms
                     :error-message (str "Unknown workflow phase: " (name phase))}))
      (catch Exception e
        (run-result {:status        :error
                     :run-id        run-id
                     :task-id       task-id
                     :worktree-dir  worktree-dir
                     :current-state phase
                     :steps         steps
                     :history       history
                     :started-ms    started-ms
                     :error-message (or (ex-message e) "Unexpected run failure")})))))

;; ---------------------------------------------------------------------------
;; Workflow chart
;; ---------------------------------------------------------------------------

(defn- start-script
  [_ data]
  (let [run-id  (:run/id data)
        control (or (control-for run-id)
                    (ensure-control! run-id))]
    (when (instance? clojure.lang.IAtom control)
      (reset! control {:pause? false :cancel? false :merge? false :answer nil}))
    (set-live-progress!
     run-id
     {:status  :running
      :state   :ensure-worktree
      :step    "ensure-worktree"
      :message "Starting run"})
    [{:op :assign
      :data {:run/current-state   :ensure-worktree
             :run/entity-type     nil
             :run/pause-reason    nil
             :run/final-state     nil
             :run/last-step       nil
             :run/last-output     nil
             :run/steps-completed 0
             :run/history         []
             :run/user-confirmation nil
             :run/user-answer     nil
             :workflow/error-message nil
             :workflow/result     nil}}]))

(defn- invoke-params
  [_ data]
  {:run-id         (:run/id data)
   :task-id        (:run/task-id data)
   :project-dir    (:run/project-dir data)
   :worktree-dir   (:run/worktree-dir data)
   :control        (:run/control data)
   :max-steps      (:run/max-steps data)
   :current-state  (:run/current-state data)
   :steps          (:run/steps-completed data)
   :history        (:run/history data)
   :user-confirmation (:run/user-confirmation data)
   :user-answer    (:run/user-answer data)
   :get-api-key-fn (:run/get-api-key-fn data)})

(defn- ev-status [data]
  (keyword (or (some-> data (get-in [:_event :data :status]) name)
               (some-> data (get-in [:_event :data :status]))
               :error)))

(defn- result-running? [_ data] (= :running (ev-status data)))
(defn- result-done? [_ data] (= :done (ev-status data)))
(defn- result-paused? [_ data] (= :paused (ev-status data)))
(defn- result-cancelled? [_ data] (= :cancelled (ev-status data)))

(defn- merge-resume-outside-gate?
  [_ data]
  (let [merge?    (boolean (get-in data [:_event :data :merge?]))
        pause-rsn (:run/pause-reason data)]
    (and merge? (not= pause-rsn :wait-pr-merge))))

(defn- reject-merge-resume-script
  [_ data]
  (let [control (:run/control data)
        run-id  (:run/id data)
        msg     "Merge authorization is only valid at :wait-pr-merge gate. Resume without merge until gate is reached."]
    (when (instance? clojure.lang.IAtom control)
      (swap! control assoc :merge? false))
    (set-live-progress!
     run-id
     {:status  :paused
      :state   :paused
      :step    "resume"
      :message msg})
    [{:op :assign
      :data {:run/pause-reason :merge-not-authorized
             :run/last-output msg
             :workflow/error-message nil}}]))

(defn- apply-result-script
  [_ data]
  (let [ev     (get-in data [:_event :data])
        status (:status ev)
        run-id (:run-id ev)]
    (when (#{:done :cancelled} status)
      (clear-control! run-id)
      (clear-live-progress! run-id))
    (when (#{:error :paused} status)
      (clear-live-progress! run-id))
    (notify! (or (:summary ev)
                 (str "[mcp-tasks-run " run-id "] " (name (or status :unknown))))
             (case status
               :error :error
               :paused :info
               :cancelled :warn
               :info))
    [{:op :assign
      :data (cond-> {:run/entity-type       (:entity-type ev)
                     :run/current-state     (:current-state ev)
                     :run/pause-reason      (:pause-reason ev)
                     :run/final-state       (:final-state ev)
                     :run/last-step         (:last-step ev)
                     :run/last-output       (:last-output ev)
                     :run/worktree-dir      (:worktree-dir ev)
                     :run/steps-completed   (long (or (:steps-completed ev) 0))
                     :run/history           (vec (or (:history ev) []))
                     :run/user-confirmation (:user-confirmation ev)
                     :run/user-answer       (:user-answer ev)
                     :workflow/result       (:summary ev)
                     :workflow/error-message nil}
              (= status :error)
              (assoc :workflow/error-message
                     (or (:error-message ev)
                         (:summary ev)
                         "Run failed")))}]))

(defn- resume-script
  [_ data]
  (let [control (:run/control data)
        merge?  (boolean (get-in data [:_event :data :merge?]))
        answer  (get-in data [:_event :data :answer])
        run-id  (:run/id data)]
    (when (instance? clojure.lang.IAtom control)
      (swap! control assoc :pause? false :cancel? false :merge? merge? :answer answer))
    (set-live-progress!
     run-id
     {:status  :running
      :state   :resuming
      :step    "resume"
      :message (cond
                 merge? "Resuming with merge intent"
                 answer "Resuming with user answer"
                 :else "Resuming")})
    [{:op :assign
      :data {:run/pause-reason nil
             :run/user-answer answer
             :workflow/error-message nil}}]))

(defn- retry-script
  [_ data]
  (let [run-id  (:run/id data)
        control (or (control-for run-id)
                    (ensure-control! run-id))]
    (when (instance? clojure.lang.IAtom control)
      (swap! control assoc :pause? false :cancel? false :merge? false :answer nil))
    (set-live-progress!
     run-id
     {:status  :running
      :state   :retrying
      :step    "retry"
      :message "Retrying from current derived state"})
    [{:op :assign
      :data {:workflow/error-message nil
             :run/pause-reason nil}}]))

(defn- cancel-script
  [_ data]
  (let [run-id  (:run/id data)
        summary (str "[mcp-tasks-run " run-id "] cancelled")]
    (clear-control! run-id)
    (clear-live-progress! run-id)
    (notify! summary :warn)
    [{:op :assign
      :data {:run/final-state :cancelled
             :workflow/result summary
             :workflow/error-message nil}}]))

(def ^:private run-chart
  (chart/statechart
   {:id :mcp-tasks-run-chart}
   (ele/state {:id :idle}
              (ele/transition {:event :run/start :target :running}
                              (ele/script {:expr start-script})))

   (ele/state {:id :running}
              (ele/invoke {:id     :runner
                           :type   :future
                           :params invoke-params
                           :src    run-loop-job})
              (ele/transition {:event :done.invoke.runner
                               :target :running
                               :cond   result-running?}
                              (ele/script {:expr apply-result-script}))
              (ele/transition {:event :done.invoke.runner
                               :target :done
                               :cond   result-done?}
                              (ele/script {:expr apply-result-script}))
              (ele/transition {:event :done.invoke.runner
                               :target :paused
                               :cond   result-paused?}
                              (ele/script {:expr apply-result-script}))
              (ele/transition {:event :done.invoke.runner
                               :target :cancelled
                               :cond   result-cancelled?}
                              (ele/script {:expr apply-result-script}))
              (ele/transition {:event :done.invoke.runner
                               :target :error}
                              (ele/script {:expr apply-result-script})))

   (ele/state {:id :paused}
              (ele/transition {:event :run/resume :target :paused
                               :cond merge-resume-outside-gate?}
                              (ele/script {:expr reject-merge-resume-script}))
              (ele/transition {:event :run/resume :target :running}
                              (ele/script {:expr resume-script}))
              (ele/transition {:event :run/cancel :target :cancelled}
                              (ele/script {:expr cancel-script})))

   (ele/state {:id :error}
              (ele/transition {:event :run/retry :target :running}
                              (ele/script {:expr retry-script}))
              (ele/transition {:event :run/cancel :target :cancelled}
                              (ele/script {:expr cancel-script})))

   (ele/final {:id :done})
   (ele/final {:id :cancelled})))

(defn- register-workflow-type!
  []
  (let [r (mutate! 'psi.extension.workflow/register-type
                   {:type            run-workflow-type
                    :description     "Run mcp-tasks task/story via sub-agent step orchestration"
                    :chart           run-chart
                    :start-event     :run/start
                    :initial-data-fn (fn [input]
                                       (let [run-id (str (:run-id input))]
                                         {:run/id             run-id
                                          :run/task-id        (long (:task-id input))
                                          :run/project-dir    (:project-dir input)
                                          :run/worktree-dir   (:worktree-dir input)
                                          :run/get-api-key-fn (some-> @state :api :get-api-key)
                                          :run/entity-type    nil
                                          :run/current-state  :idle
                                          :run/pause-reason   nil
                                          :run/final-state    nil
                                          :run/last-step      nil
                                          :run/last-output    nil
                                          :run/steps-completed 0
                                          :run/history        []
                                          :run/user-confirmation nil
                                          :run/user-answer    nil
                                          :run/control        (ensure-control! run-id)
                                          :run/max-steps      (long (or (:max-steps input)
                                                                        max-steps-default))}))
                    :public-data-fn  (fn [data]
                                       (select-keys data
                                                    [:run/id
                                                     :run/task-id
                                                     :run/project-dir
                                                     :run/worktree-dir
                                                     :run/entity-type
                                                     :run/current-state
                                                     :run/pause-reason
                                                     :run/final-state
                                                     :run/last-step
                                                     :run/last-output
                                                     :run/steps-completed
                                                     :run/history
                                                     :run/user-confirmation
                                                     :run/user-answer]))})]
    (when-let [e (:psi.extension.workflow/error r)]
      (notify! (str "Failed to register mcp-tasks-run workflow type: " e) :error))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- usage []
  (str
   "Usage:\n"
   "  /mcp-tasks-run <task-id>\n"
   "  /mcp-tasks-run list\n"
   "  /mcp-tasks-run pause <run-id>\n"
   "  /mcp-tasks-run resume <run-id> [merge|<answer>]\n"
   "  /mcp-tasks-run cancel <run-id>\n"
   "  /mcp-tasks-run retry <run-id>"))

(defn- parse-args [args]
  (let [s (str/trim (or args ""))]
    (if (str/blank? s)
      []
      (str/split s #"\s+"))))

(defn- start-run!
  [task-id]
  (if (active-running-workflow)
    (println "A run is already in progress. Pause/cancel it first.")
    (let [run-id      (next-run-id!)
          project-dir (System/getProperty "user.dir")
          created     (mutate! 'psi.extension.workflow/create
                               {:type  run-workflow-type
                                :id    run-id
                                :meta  {:task-id task-id}
                                :input {:run-id run-id
                                        :task-id task-id
                                        :project-dir project-dir
                                        :max-steps max-steps-default}})]
      (if (:psi.extension.workflow/created? created)
        (do
          (println (str "Started mcp-tasks run " run-id " for task #" task-id "."))
          (refresh-widgets-later!))
        (println (str "Failed to start run: "
                      (or (:psi.extension.workflow/error created) "unknown error")))))))

(defn- list-runs!
  []
  (let [wfs (mcp-run-workflows)]
    (if (empty? wfs)
      (println "No mcp-tasks runs.")
      (doseq [wf wfs]
        (let [id       (:psi.extension.workflow/id wf)
              phase    (phase-of wf)
              data     (or (:psi.extension.workflow/data wf) {})
              task-id  (or (:run/task-id data)
                           (get-in wf [:psi.extension.workflow/input :task-id]))
              entity   (or (:run/entity-type data) :task)
              state    (or (:run/current-state data) :unknown)
              step     (or (:run/last-step data) "-")
              elapsed  (elapsed-seconds wf)
              reason   (:run/pause-reason data)
              msg      (or (:psi.extension.workflow/error-message wf)
                           (:run/last-output data)
                           "")]
          (println
           (str (status-icon phase) " " id
                " [" (phase-label phase) "] "
                (name entity) " #" task-id
                " · state " (name state)
                " · step " step
                " · " elapsed "s"
                (when reason
                  (str " · reason " (name reason)))))
          (when (seq (str/trim (str msg)))
            (println (str "   " (task-preview (str/trim (str msg)) 120))))
          (when (= reason :wait-user-confirmation)
            (when-let [q (:question (:run/user-confirmation data))]
              (when (seq (str/trim q))
                (println (str "   ❓ " (task-preview (str/trim q) 200)))))))))))

(defn- pause-run!
  [run-id]
  (if-let [wf (workflow-by-id run-id)]
    (if (:psi.extension.workflow/running? wf)
      (if (set-control! run-id #(assoc % :pause? true))
        (do
          (println (str "Pause requested for " run-id ". It will pause at step boundary."))
          (refresh-widgets-later!))
        (println (str "Run control not found for " run-id ".")))
      (println (str run-id " is not running.")))
    (println (str "Run not found: " run-id))))

(defn- resume-run!
  [run-id merge? answer]
  (if-let [wf (workflow-by-id run-id)]
    (let [phase      (phase-of wf)
          data       (or (:psi.extension.workflow/data wf) {})
          pause-rsn  (:run/pause-reason data)]
      (if (= phase :running)
        (println (str run-id " is already running."))
        (if (not= phase :paused)
          (println (str run-id " is not paused."))
          (cond
            (and (= pause-rsn :wait-pr-merge) (not merge?))
            (println (str "Run " run-id " is waiting for PR merge. "
                          "Use: /mcp-tasks-run resume " run-id " merge"))

            (and (= pause-rsn :wait-user-confirmation)
                 (str/blank? (str answer)))
            (println (str "Run " run-id " is waiting for user confirmation. "
                          "Use: /mcp-tasks-run resume " run-id " <answer>"))

            :else
            (let [r (mutate! 'psi.extension.workflow/send-event
                             {:id    (str run-id)
                              :event :run/resume
                              :data  (cond-> {:merge? (boolean merge?)}
                                       (seq (str answer)) (assoc :answer answer))})]
              (if (:psi.extension.workflow/event-accepted? r)
                (do
                  (println (str "Resumed " run-id
                                (cond
                                  merge? " with merge intent"
                                  (seq (str answer)) " with answer"
                                  :else "")
                                "."))
                  (refresh-widgets-later!))
                (println (str "Failed to resume " run-id ": "
                              (or (:psi.extension.workflow/error r) "unknown error")))))))))
    (println (str "Run not found: " run-id))))

(defn- cancel-run!
  [run-id]
  (if-let [wf (workflow-by-id run-id)]
    (let [phase (phase-of wf)]
      (cond
        (= phase :running)
        (if (set-control! run-id #(assoc % :cancel? true))
          (do
            (println (str "Cancel requested for " run-id "."))
            (refresh-widgets-later!))
          (println (str "Run control not found for " run-id ".")))

        (#{:paused :error} phase)
        (let [r (mutate! 'psi.extension.workflow/send-event
                         {:id    (str run-id)
                          :event :run/cancel
                          :data  {}})]
          (if (:psi.extension.workflow/event-accepted? r)
            (do
              (println (str "Cancelled " run-id "."))
              (refresh-widgets-later!))
            (println (str "Failed to cancel " run-id ": "
                          (or (:psi.extension.workflow/error r) "unknown error")))))

        :else
        (println (str run-id " cannot be cancelled in phase " (name phase) "."))))
    (println (str "Run not found: " run-id))))

(defn- retry-run!
  [run-id]
  (if-let [wf (workflow-by-id run-id)]
    (cond
      (= :running (phase-of wf))
      (println (str run-id " is already running."))

      (not= :error (phase-of wf))
      (println (str run-id " is not in error phase."))

      :else
      (let [r (mutate! 'psi.extension.workflow/send-event
                       {:id    (str run-id)
                        :event :run/retry
                        :data  {}})]
        (if (:psi.extension.workflow/event-accepted? r)
          (do
            (println (str "Retrying " run-id " from current derived state."))
            (refresh-widgets-later!))
          (println (str "Failed to retry " run-id ": "
                        (or (:psi.extension.workflow/error r) "unknown error"))))))
    (println (str "Run not found: " run-id))))

(defn- remove-run-workflows!
  []
  (doseq [wf (mcp-run-workflows)]
    (mutate! 'psi.extension.workflow/remove
             {:id (:psi.extension.workflow/id wf)}))
  (reset! (:run-controls @state) {})
  (reset! (:live-progress @state) {})
  (swap! state assoc :widget-ids #{})
  (refresh-widgets-later!))

(defn- handle-command
  [args]
  (let [parts (parse-args args)
        cmd   (first parts)]
    (cond
      (empty? parts)
      (println (usage))

      (= "list" cmd)
      (list-runs!)

      (= "pause" cmd)
      (if-let [rid (second parts)]
        (pause-run! rid)
        (println "Usage: /mcp-tasks-run pause <run-id>"))

      (= "resume" cmd)
      (if-let [rid (second parts)]
        (let [arg3 (nth parts 2 nil)]
          (resume-run! rid (= "merge" arg3) (when (and arg3 (not= "merge" arg3)) arg3)))
        (println "Usage: /mcp-tasks-run resume <run-id> [merge|<answer>]"))

      (= "cancel" cmd)
      (if-let [rid (second parts)]
        (cancel-run! rid)
        (println "Usage: /mcp-tasks-run cancel <run-id>"))

      (= "retry" cmd)
      (if-let [rid (second parts)]
        (retry-run! rid)
        (println "Usage: /mcp-tasks-run retry <run-id>"))

      :else
      (if-let [task-id (parse-int cmd)]
        (start-run! task-id)
        (println (usage))))))

;; ---------------------------------------------------------------------------
;; init
;; ---------------------------------------------------------------------------

(defn init
  [api]
  (swap! state assoc
         :api        api
         :ext-path   (:path api)
         :query-fn   (:query api)
         :mutate-fn  (:mutate api)
         :ui         (:ui api)
         :next-run-id (atom 1)
         :run-controls (atom {})
         :live-progress (atom {})
         :prompt-cache (atom {})
         :widget-ids #{})

  (register-workflow-type!)
  (sync-next-run-id!)

  ((:register-command api)
   "mcp-tasks-run"
   {:description "Run mcp-tasks story/task workflow via sub-agents"
    :handler     handle-command})

  ((:on api)
   "session_switch"
   (fn [_ev]
     (remove-run-workflows!)
     (reset! (:next-run-id @state) 1)))

  (refresh-widgets-later!)
  (notify! "mcp-tasks-run loaded" :info))
