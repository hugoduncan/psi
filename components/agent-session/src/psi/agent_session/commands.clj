(ns psi.agent-session.commands
  "Central command dispatcher for /commands.

   All commands return data maps — no side effects, no printing.
   REPL and TUI are renderers of command results.

   `:post-command-fn` in opts, when present, is invoked as:
   `(post-command-fn ctx session-id cmd-result)` after command dispatch.
   This is used by runtimes to project command-owned shared UI state.

   Result types:
     :text          — {:type :text :message string}
     :new-session   — {:type :new-session :message string :rehydrate {:messages [...] :tool-calls {...} :tool-order [...]}}
     :quit          — {:type :quit}
     :resume        — {:type :resume}
     :tree-open     — {:type :tree-open}
     :tree-switch   — {:type :tree-switch :session-id string}
     :tree-rename   — {:type :tree-rename :session-id string :session-name string}
     :login-start   — {:type :login-start :provider map :url string :login-state map
                        :uses-callback-server bool}
     :login-error   — {:type :login-error :message string}
     :logout        — {:type :logout :message string}
     :extension-cmd — {:type :extension-cmd :name string :args string :handler fn}
     nil            — not a command (pass through to agent)"
  (:require
   [clojure.string :as str]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.app-runtime.background-job-view :as app-bg-view]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as ext-runtime-fns]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.project-nrepl-commands :as project-nrepl-commands]
   [psi.ai.models :as ai-models]
   [psi.ai.model-registry :as model-registry]))

;; ============================================================
;; Formatting helpers (pure — return strings, no side effects)
;; ============================================================

(defn format-status
  "Return a status string for the current session."
  [ctx session-id]
  (let [d (session/query-in ctx session-id
                            [:psi.agent-session/phase
                             :psi.agent-session/session-id
                             :psi.agent-session/model
                             :psi.agent-session/thinking-level
                             :psi.agent-session/model-reasoning
                             :psi.agent-session/effective-reasoning-effort
                             :psi.agent-session/extension-summary
                             :psi.agent-session/session-entry-count
                             :psi.agent-session/context-tokens
                             :psi.agent-session/context-window
                             :psi.agent-session/context-fraction
                             :git.worktree/current
                             :psi.graph/root-seeds])]
    (str "── Session status ─────────────────────\n"
         "  Phase   : " (:psi.agent-session/phase d) "\n"
         "  ID      : " (:psi.agent-session/session-id d) "\n"
         "  Model   : " (get-in d [:psi.agent-session/model :id] "none")
         (if (:psi.agent-session/model-reasoning d)
           (str " (thinking "
                (or (:psi.agent-session/effective-reasoning-effort d) "off")
                ")")
           "")
         "\n"
         "  Entries : " (:psi.agent-session/session-entry-count d)
         (when-let [frac (:psi.agent-session/context-fraction d)]
           (str "\n  Context : " (int (* 100 frac)) "%"))
         (when-let [wt-path (get-in d [:git.worktree/current :git.worktree/path])]
           (str "\n  Worktree: " wt-path))
         (when-let [root-seeds (:psi.graph/root-seeds d)]
           (str "\n  Roots   : " (str/join ", " (map name root-seeds))))
         "\n───────────────────────────────────────")))

(defn format-history
  "Return a history string for the current message list."
  [ctx session-id]
  (let [msgs (:psi.agent-session/message-history
              (session/query-in ctx session-id [:psi.agent-session/message-history]))]
    (str "── Message history ────────────────────\n"
         (if (empty? msgs)
           "  (empty)"
           (str/join "\n"
                     (map (fn [msg]
                            (let [role (:role msg)
                                  text (or (some #(when (= :text (:type %)) (:text %))
                                                 (:content msg))
                                           (str "[" role "]"))]
                              (str "  [" role "] "
                                   (subs text 0 (min 120 (count text))))))
                          msgs)))
         "\n───────────────────────────────────────")))

(def ^:private valid-job-statuses
  #{:running :pending-cancel :completed :failed :cancelled :timed-out})

(defn- parse-job-statuses
  [s]
  (when-let [trimmed (some-> s str/trim not-empty)]
    (let [tokens   (str/split trimmed #"[\s,]+")
          statuses (mapv keyword tokens)]
      (when (every? valid-job-statuses statuses)
        statuses))))

(defn format-help
  "Return a help string listing all available commands."
  [ctx session-id]
  (let [d             (session/query-in ctx session-id
                                        [:psi.agent-session/prompt-templates
                                         :psi.agent-session/skills
                                         :psi.extension/command-names])
        templates     (:psi.agent-session/prompt-templates d)
        loaded-skills (:psi.agent-session/skills d)
        ext-cmds      (:psi.extension/command-names d)]
    (str "── Commands ───────────────────────────\n"
         "  /quit    — exit the session\n"
         "  /status  — show session diagnostics\n"
         "  /history — show message history\n"
         "  /prompts — list available prompt templates\n"
         "  /skills  — list available skills\n"
         "  /new     — start a fresh session\n"
         "  /resume  — resume a previous session\n"
         "  /tree [session-id] — open/switch live session tree (TUI)\n"
         "  /login [provider] — login with an OAuth provider\n"
         "  /logout  — logout from an OAuth provider\n"
         "  /model [provider model-id] — show current model or set model\n"
         "  /thinking [level] — show current thinking level or set level\n"
         "  /remember [text] — capture a memory note for future ψ\n"
         "  /worktree — show git worktree context\n"
         "  /reload-models — reload custom model definitions from ~/.psi/agent/models.edn and .psi/models.edn\n"
         "  /reload-extension-installs — reload/apply extension installs from extensions.edn\n"
         "  /jobs [status ...] — list background jobs (default: running,pending-cancel)\n"
         "  /job <job-id> — inspect a background job\n"
         "  /cancel-job <job-id> — request background job cancellation\n"
         "  /help    — show this help\n"
         "  /skill:name — invoke a skill (loads full content)"
         (when (seq templates)
           (str "\n  ── Prompt Templates ─────────────────\n"
                (str/join "\n"
                          (map (fn [t]
                                 (str "  /" (:name t) " — " (:description t)))
                               templates))))
         (when (seq loaded-skills)
           (str "\n  ── Skills ───────────────────────────\n"
                (str/join "\n"
                          (map (fn [s]
                                 (str "  /skill:" (:name s)
                                      (when (:disable-model-invocation s) " [hidden]")
                                      " — " (:description s)))
                               loaded-skills))))
         (when (seq ext-cmds)
           (str "\n  ── Extension Commands ───────────────\n"
                (str/join "\n"
                          (map (fn [c]
                                 (str "  /" c))
                               ext-cmds))))
         "\n  (anything else is sent to the agent)\n"
         "───────────────────────────────────────")))

(defn format-prompts
  "Return a string listing available prompt templates."
  [ctx session-id]
  (let [templates (:psi.agent-session/prompt-templates
                   (session/query-in ctx session-id [:psi.agent-session/prompt-templates]))]
    (str "── Prompt Templates ───────────────────\n"
         (if (empty? templates)
           "  (none discovered)"
           (str/join "\n"
                     (map (fn [t]
                            (let [placeholders
                                  (if (pt/has-placeholders? (:content t))
                                    (str " [$" (pt/placeholder-count (:content t)) " args]")
                                    "")]
                              (str "  /" (:name t) placeholders " — " (:description t)
                                   " [" (name (:source t)) "]")))
                          templates)))
         "\n───────────────────────────────────────")))

(defn format-skills
  "Return a string listing available skills."
  [ctx session-id]
  (let [loaded-skills (:psi.agent-session/skills
                       (session/query-in ctx session-id [:psi.agent-session/skills]))]
    (str "── Skills ─────────────────────────────\n"
         (if (empty? loaded-skills)
           "  (none discovered)"
           (str/join "\n"
                     (map (fn [s]
                            (str "  /skill:" (:name s)
                                 (when (:disable-model-invocation s) " [hidden]")
                                 " — " (:description s)
                                 " [" (name (:source s)) "]"))
                          loaded-skills)))
         "\n───────────────────────────────────────")))

(defn format-worktree
  "Return a deterministic git worktree status string for the session worktree path."
  [ctx session-id]
  (let [d (session/query-in ctx session-id
                            [:psi.agent-session/worktree-path
                             :psi.agent-session/git-branch
                             :git.worktree/inside-repo?
                             :git.worktree/list
                             :git.worktree/current
                             :git.worktree/count])
        inside?       (boolean (:git.worktree/inside-repo? d))
        worktree-path (:psi.agent-session/worktree-path d)
        branch        (:psi.agent-session/git-branch d)
        current   (:git.worktree/current d)
        worktrees (vec (or (:git.worktree/list d) []))
        count*    (or (:git.worktree/count d) (count worktrees))]
    (if-not inside?
      (str "── Git worktrees ─────────────────────\n"
           "  cwd      : " worktree-path "\n"
           "  inside   : false\n"
           "  worktrees: 0\n"
           "───────────────────────────────────────")
      (str "── Git worktrees ─────────────────────\n"
           "  cwd      : " worktree-path "\n"
           "  branch   : " (or branch "(none)") "\n"
           "  current  : " (or (:git.worktree/path current) "(unknown)") "\n"
           "  worktrees: " count* "\n"
           (when (seq worktrees)
             (str "\n"
                  (str/join "\n"
                            (map (fn [wt]
                                   (let [cur?    (:git.worktree/current? wt)
                                         det?    (:git.worktree/detached? wt)
                                         path    (:git.worktree/path wt)
                                         bname   (:git.worktree/branch-name wt)
                                         marker  (if cur? "*" "-")
                                         branch* (if det?
                                                   "detached"
                                                   (or bname "(no-branch)"))]
                                     (str "  " marker " " path " [" branch* "]")))
                                 worktrees))))
           "\n───────────────────────────────────────"))))

;; ============================================================
;; Reload models
;; ============================================================

(defn- format-reload-models
  "Reload user + project custom models from disk and return a status string."
  [ctx session-id]
  (let [worktree-path        (ss/session-worktree-path-in ctx session-id)
        {:keys [error count]} (session/reload-models-in! ctx session-id)]
    (if error
      (str "── Models reloaded (with errors) ─────\n"
           "  worktree : " worktree-path "\n"
           "  error : " error "\n"
           "  count : " count "\n"
           "───────────────────────────────────────")
      (str "── Models reloaded ───────────────────\n"
           "  worktree : " worktree-path "\n"
           "  count : " count "\n"
           "───────────────────────────────────────"))))

(defn- format-reload-extension-installs
  "Reload/apply extension installs from extensions.edn and return a status string."
  [ctx session-id]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)
        result        (session/reload-extension-installs-in! ctx session-id)
        install-state (:install-state result)
        last-apply    (:psi.extensions/last-apply install-state)
        diagnostics   (vec (or (:psi.extensions/diagnostics install-state) []))
        entries       (vals (get-in install-state [:psi.extensions/effective :entries-by-lib]))
        status-counts (frequencies (map :status entries))]
    (if last-apply
      (str "── Extension installs reloaded ───────\n"
           "  worktree : " worktree-path "\n"
           "  status   : " (:status last-apply) "\n"
           "  counts   : " (pr-str status-counts) "\n"
           "  diagnostics : " (count diagnostics) "\n"
           "───────────────────────────────────────")
      (str "── Extension install reload failed ──\n"
           "  worktree : " worktree-path "\n"
           "  counts   : " (pr-str status-counts) "\n"
           "  diagnostics : " (count diagnostics) "\n"
           "───────────────────────────────────────"))))

;; ============================================================
;; Provider env var hint (for login error messages)
;; ============================================================

(def ^:private provider-env-vars
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :google    "GOOGLE_API_KEY"})

(defn- memory-ready?
  [ctx]
  (= :ready (:psi.memory/status (session/query-in ctx [:psi.memory/status] {}))))

(defn- format-remember-result
  [result]
  (let [store-result (:store result)]
    (if (:ok? result)
      (if (false? (:ok? store-result))
        (str "⚠ Remembered with store fallback"
             "\n  record-id: " (get-in result [:record :record-id])
             (when-let [provider-id (:provider-id store-result)]
               (str "\n  provider: " provider-id))
             (when-let [store-error (:error store-result)]
               (str "\n  store-error: " (name store-error)))
             (when-let [message (:message store-result)]
               (str "\n  detail: " message)))
        (str "✓ Remembered"
             "\n  record-id: " (get-in result [:record :record-id])))
      (str "✗ Remember failed"
           "\n  reason: " (name (:error result))))))
(def ^:private canonical-thinking-levels
  [:off :minimal :low :medium :high :xhigh])

(defn- known-thinking-level?
  [level]
  (contains? (set canonical-thinking-levels) level))

(defn- normalize-thinking-level
  [s]
  (some-> s str/trim str/lower-case keyword))

(defn- unknown-thinking-level-message
  [input]
  (str "Unknown thinking level: " input
       ". Allowed: "
       (str/join ", " (map name canonical-thinking-levels))))

(defn- resolve-runtime-model
  [provider model-id]
  (let [provider* (some-> provider keyword)]
    (or (model-registry/find-model provider* model-id)
        (some (fn [[_ model]]
                (when (and (= provider* (:provider model))
                           (= model-id (:id model)))
                  model))
              ai-models/all-models))))

;; ============================================================
;; Login provider selection (pure — returns data)
;; ============================================================

(defn select-login-provider
  "Select OAuth provider for /login.

   Returns one of:
     {:provider provider-map}
     {:error message-string}"
  [providers active-provider requested-provider]
  (let [by-id        (into {} (map (juxt :id identity) providers))
        available    (->> (keys by-id) (map name) sort)
        requested-id (some-> requested-provider str/trim not-empty keyword)
        active-id    (keyword active-provider)]
    (cond
      requested-id
      (if-let [provider (get by-id requested-id)]
        {:provider provider}
        {:error (str "Unknown OAuth provider: " (name requested-id)
                     (when (seq available)
                       (str ". Available: " (str/join ", " available))))})

      :else
      (if-let [provider (get by-id active-id)]
        {:provider provider}
        {:error (str "OAuth login is not available for model provider "
                     (name active-id) ". "
                     (when-let [env-var (get provider-env-vars active-id)]
                       (str "Set " env-var " for this model. "))
                     (when (seq available)
                       (str "Available OAuth providers: "
                            (str/join ", " available) ".")))}))))

;; ============================================================
;; Central command dispatch
;; ============================================================

(defn- tree-sessions
  [ctx session-id]
  (let [d         (session/query-in ctx session-id
                                    [:psi.agent-session/session-id
                                     :psi.agent-session/session-name
                                     :psi.agent-session/worktree-path
                                     {:psi.agent-session/context-sessions
                                      [:psi.session-info/id
                                       :psi.session-info/worktree-path
                                       :psi.session-info/name]}])
        sessions0 (mapv (fn [s]
                          {:session-id    (:psi.session-info/id s)
                           :session-name  (:psi.session-info/name s)
                           :worktree-path (:psi.session-info/worktree-path s)})
                        (or (:psi.agent-session/context-sessions d) []))]
    (if (seq sessions0)
      sessions0
      [{:session-id    (:psi.agent-session/session-id d)
        :session-name  (:psi.agent-session/session-name d)
        :worktree-path (:psi.agent-session/worktree-path d)}])))

(defn- match-session-in-context
  [sessions arg]
  (or (some #(when (= arg (:session-id %)) %) sessions)
      (let [matches (filterv #(str/starts-with? (or (:session-id %) "") arg) sessions)]
        (when (= 1 (count matches))
          (first matches)))))

(defn- dispatch-tree-command
  [ctx session-id trimmed supports-session-tree?]
  (if-not supports-session-tree?
    {:type :text
     :message "[/tree is only available in TUI mode (--tui)]"}
    (let [arg      (-> (str/replace trimmed #"^/tree\s*" "") str/trim)
          sessions (tree-sessions ctx session-id)]
      (cond
        (str/blank? arg)
        {:type :tree-open}

        (= arg "name")
        {:type :text :message "Usage: /tree name <session-id|prefix> <name>"}

        (str/starts-with? arg "name ")
        (let [tail        (-> (subs arg 5) str/trim)
              [sid-part name-part] (let [[a b] (str/split tail #"\s+" 2)]
                                     [a (some-> b str/trim)])]
          (cond
            (str/blank? sid-part)
            {:type :text :message "Usage: /tree name <session-id|prefix> <name>"}

            (str/blank? name-part)
            {:type :text :message "Usage: /tree name <session-id|prefix> <name>"}

            :else
            (let [chosen (match-session-in-context sessions sid-part)
                  sid    (:session-id chosen)]
              (if-not chosen
                {:type :text :message (str "Session not found in context: " sid-part)}
                (let [result (session/set-session-name-in! ctx sid name-part)]
                  {:type :tree-rename
                   :session-id sid
                   :session-name (:session-name result)})))))

        :else
        (let [chosen (match-session-in-context sessions arg)
              sid    (:session-id chosen)]
          (cond
            (nil? chosen)
            {:type :text :message (str "Session not found in context: " arg)}

            (= sid session-id)
            {:type :text :message (str "Already active session: " sid)}

            :else
            {:type :tree-switch :session-id sid}))))))

(defn- eql-job->runtime-job
  [job]
  (bg-jobs/eql->job job))

(defn- dispatch-jobs-command
  [ctx session-id trimmed]
  (let [tail      (-> (str/replace trimmed #"^/jobs\s*" "") str/trim)
        statuses  (parse-job-statuses tail)
        invalid?  (and (not (str/blank? tail)) (empty? statuses))
        jobs-eql  (:psi.agent-session/background-jobs
                   (session/query-in ctx session-id [:psi.agent-session/background-jobs]))
        jobs      (mapv eql-job->runtime-job
                        (if statuses
                          (filter #(contains? (set statuses) (:psi.background-job/status %)) jobs-eql)
                          jobs-eql))]
    (if invalid?
      {:type :text :message "Usage: /jobs [status ...]"}
      {:type :text
       :message (:jobs/text (app-bg-view/jobs-summary jobs {:statuses (or statuses [:running :pending-cancel])}))})))

(defn- dispatch-job-command
  [ctx session-id trimmed]
  (let [job-id (-> (str/replace trimmed #"^/job\s*" "") str/trim)]
    (if (str/blank? job-id)
      {:type :text :message "Usage: /job <job-id>"}
      (let [jobs (:psi.agent-session/background-jobs
                  (session/query-in ctx session-id [:psi.agent-session/background-jobs]))
            job  (some #(when (= job-id (:psi.background-job/id %)) (eql-job->runtime-job %)) jobs)]
        {:type :text
         :message (:job/text (app-bg-view/job-detail job))}))))

(defn- dispatch-cancel-job-command
  [ctx session-id trimmed]
  (let [job-id (-> (str/replace trimmed #"^/cancel-job\s*" "") str/trim)]
    (if (str/blank? job-id)
      {:type :text :message "Usage: /cancel-job <job-id>"}
      (let [job (session/cancel-job-in! ctx session-id job-id :user)]
        {:type :text
         :message (:job/message (app-bg-view/cancel-job-summary job-id job))}))))

(defn- dispatch-remember-command
  [ctx session-id trimmed]
  (if-let [memory-ctx (:memory-ctx ctx)]
    (if-not (memory-ready? ctx)
      {:type :text
       :message (str "✗ Remember blocked"
                     "\n  reason: memory_capture_prerequisites_not_ready"
                     "\n  detail: :psi.memory/status must be :ready")}
      (let [reason (-> (str/replace trimmed #"^/remember\s*" "") str/trim not-empty)
            result (session/remember-in! (assoc ctx :memory-ctx memory-ctx) session-id reason)]
        {:type :text
         :message (format-remember-result result)}))
    {:type :text
     :message "✗ Memory context not configured."}))

(defn- dispatch-model-command
  [ctx session-id trimmed]
  (let [args (-> (str/replace trimmed #"^/model\s*" "") str/trim)]
    (if (str/blank? args)
      (let [m (:psi.agent-session/model
               (session/query-in ctx session-id [:psi.agent-session/model]))]
        {:type :text
         :message (if m
                    (str "Current model: " (:provider m) " " (:id m))
                    "No model selected.")})
      (let [tokens (str/split args #"\s+")]
        (if (not= 2 (count tokens))
          {:type :text
           :message "Usage: /model OR /model <provider> <model-id>"}
          (let [[provider model-id] tokens
                resolved (resolve-runtime-model provider model-id)]
            (if-not resolved
              {:type :text
               :message (str "Unknown model: " provider " " model-id)}
              (let [provider-str (name (:provider resolved))
                    model {:provider provider-str
                           :id (:id resolved)
                           :reasoning (:supports-reasoning resolved)}
                    result (session/set-model-in! ctx session-id model)]
                {:type :text
                 :message (str "✓ Model set to "
                               (get-in result [:model :provider]) " "
                               (get-in result [:model :id]))}))))))))

(defn- dispatch-thinking-command
  [ctx session-id trimmed]
  (let [args (-> (str/replace trimmed #"^/thinking\s*" "") str/trim)]
    (if (str/blank? args)
      {:type :text
       :message (str "Current thinking level: "
                     (name (or (:psi.agent-session/thinking-level
                                (session/query-in ctx session-id [:psi.agent-session/thinking-level]))
                               :off)))}
      (let [tokens (str/split args #"\s+")]
        (if (not= 1 (count tokens))
          {:type :text
           :message "Usage: /thinking OR /thinking <level>"}
          (let [level-input (first tokens)
                level (normalize-thinking-level level-input)]
            (if-not (known-thinking-level? level)
              {:type :text
               :message (unknown-thinking-level-message level-input)}
              (let [result (session/set-thinking-level-in! ctx session-id level)]
                {:type :text
                 :message (str "✓ Thinking level set to "
                               (name (:thinking-level result)))}))))))))

(defn- dispatch-login-command
  [ctx session-id oauth-ctx ai-model trimmed]
  (if-not oauth-ctx
    {:type :login-error :message "OAuth not available."}
    (let [provider-arg (second (str/split trimmed #"\s+" 2))
          providers    (oauth/available-providers oauth-ctx)
          {:keys [provider error]}
          (select-login-provider providers (:provider ai-model) provider-arg)]
      (if error
        {:type :login-error :message (str "✗ " error)}
        (try
          (let [{:keys [url login-state]} (session/login-begin-in! (assoc ctx :oauth-ctx oauth-ctx) session-id (:id provider))]
            {:type                 :login-start
             :provider             provider
             :url                  url
             :login-state          login-state
             :uses-callback-server (and (:uses-callback-server provider)
                                        (:callback-server login-state))})
          (catch Exception e
            {:type :login-error
             :message (str "✗ Login failed: " (ex-message e))}))))))

(defn- dispatch-logout-command
  [ctx session-id oauth-ctx]
  (if-not oauth-ctx
    {:type :text :message "OAuth not available."}
    (let [logged-in (oauth/logged-in-providers oauth-ctx)]
      (if (empty? logged-in)
        {:type :text :message "No OAuth providers logged in. Use /login first."}
        (do
          (session/logout-in! (assoc ctx :oauth-ctx oauth-ctx) session-id (mapv :id logged-in))
          {:type    :logout
           :message (str "✓ Logged out of: "
                         (str/join ", " (map :name logged-in)))})))))

(defn- dispatch-extension-command
  [ctx session-id trimmed]
  (let [parts    (str/split (subs trimmed 1) #"\s" 2)
        cmd-name (first parts)
        args-str (or (second parts) "")
        cmd      (ext/get-command-in (:extension-registry ctx) cmd-name)
        handler   (:handler cmd)]
    {:type :extension-cmd
     :name cmd-name
     :args args-str
     :handler (when handler
                (fn [args]
                  (ext-runtime-fns/with-active-extension-session-id
                    session-id
                    #(handler args))))}))

(defn- new-session-result
  [ctx session-id on-new-session!]
  (let [rehydrate (if on-new-session!
                    (on-new-session! session-id)
                    (let [sd (session/new-session-in! ctx session-id {})]
                      {:session-id (:session-id sd) :messages [] :tool-calls {} :tool-order []}))]
    {:type :new-session
     :message "[New session started]"
     :rehydrate rehydrate}))

(defn- exact-command-handler
  [trimmed]
  (case trimmed
    "/quit" :quit
    "/exit" :quit
    "/new" :new
    "/resume" :resume
    "/status" :status
    "/history" :history
    "/help" :help
    "/?" :help
    "/prompts" :prompts
    "/skills" :skills
    "/worktree" :worktree
    "/logout" :logout
    "/reload-models" :reload-models
    "/reload-extension-installs" :reload-extension-installs
    "/project-repl" :project-repl
    nil))

(defn- prefixed-command
  [trimmed]
  (some (fn [prefix]
          (when (or (= trimmed prefix)
                    (str/starts-with? trimmed (str prefix " ")))
            prefix))
        ["/tree" "/jobs" "/job" "/cancel-job" "/remember" "/model" "/thinking" "/login" "/project-repl"]))

(defn- dispatch-prefixed-command
  [ctx session-id trimmed {:keys [oauth-ctx ai-model supports-session-tree?]}]
  (case (prefixed-command trimmed)
    "/tree" (dispatch-tree-command ctx session-id trimmed supports-session-tree?)
    "/jobs" (dispatch-jobs-command ctx session-id trimmed)
    "/job" (dispatch-job-command ctx session-id trimmed)
    "/cancel-job" (dispatch-cancel-job-command ctx session-id trimmed)
    "/remember" (dispatch-remember-command ctx session-id trimmed)
    "/model" (dispatch-model-command ctx session-id trimmed)
    "/thinking" (dispatch-thinking-command ctx session-id trimmed)
    "/login" (dispatch-login-command ctx session-id oauth-ctx ai-model trimmed)
    "/project-repl" (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id trimmed)
    nil))

(defn- dispatch*
  "Single command dispatch pipeline.

   Requires explicit `session-id` for session-scoped commands."
  [ctx session-id text {:keys [oauth-ctx on-new-session!] :as opts}]
  (let [trimmed (str/trim text)]
    (or
     (case (exact-command-handler trimmed)
       :quit {:type :quit}
       :new (new-session-result ctx session-id on-new-session!)
       :resume {:type :resume}
       :status {:type :text :message (format-status ctx session-id)}
       :history {:type :text :message (format-history ctx session-id)}
       :help {:type :text :message (format-help ctx session-id)}
       :prompts {:type :text :message (format-prompts ctx session-id)}
       :skills {:type :text :message (format-skills ctx session-id)}
       :worktree {:type :text :message (format-worktree ctx session-id)}
       :reload-models {:type :text :message (format-reload-models ctx session-id)}
       :reload-extension-installs {:type :text :message (format-reload-extension-installs ctx session-id)}
       :project-repl (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id trimmed)
       :logout (dispatch-logout-command ctx session-id oauth-ctx)
       nil)
     (dispatch-prefixed-command ctx session-id trimmed opts)
     (when (str/starts-with? trimmed "/")
       (let [cmd-name (first (str/split (subs trimmed 1) #"\s" 2))]
         (when (ext/get-command-in (:extension-registry ctx) cmd-name)
           (dispatch-extension-command ctx session-id trimmed)))))))

(defn dispatch-in
  "Explicit session-targeted command dispatch over the shared pipeline."
  [ctx session-id text opts]
  (let [result (dispatch* ctx session-id text opts)]
    (when-let [f (:post-command-fn opts)]
      (f ctx session-id result))
    result))

