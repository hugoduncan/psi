(ns psi.agent-session.commands
  "Central command dispatcher for /commands.

   All commands return data maps — no side effects, no printing.
   REPL and TUI are renderers of command results.

   Result types:
     :text          — {:type :text :message string}
     :new-session   — {:type :new-session :message string}
     :quit          — {:type :quit}
     :resume        — {:type :resume}
     :login-start   — {:type :login-start :provider map :url string :login-state map
                        :uses-callback-server bool}
     :login-error   — {:type :login-error :message string}
     :logout        — {:type :logout :message string}
     :extension-cmd — {:type :extension-cmd :name string :args string :handler fn}
     nil            — not a command (pass through to agent)"
  (:require
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-core.core :as agent]
   [psi.recursion.core :as recursion]))

;; ============================================================
;; Formatting helpers (pure — return strings, no side effects)
;; ============================================================

(defn format-status
  "Return a status string for the current session."
  [ctx]
  (let [d (session/query-in ctx
                            [:psi.agent-session/phase
                             :psi.agent-session/session-id
                             :psi.agent-session/model
                             :psi.agent-session/thinking-level
                             :psi.agent-session/extension-summary
                             :psi.agent-session/session-entry-count
                             :psi.agent-session/context-tokens
                             :psi.agent-session/context-window
                             :psi.agent-session/context-fraction])]
    (str "── Session status ─────────────────────\n"
         "  Phase   : " (:psi.agent-session/phase d) "\n"
         "  ID      : " (:psi.agent-session/session-id d) "\n"
         "  Model   : " (get-in d [:psi.agent-session/model :id] "none") "\n"
         "  Entries : " (:psi.agent-session/session-entry-count d)
         (when-let [frac (:psi.agent-session/context-fraction d)]
           (str "\n  Context : " (int (* 100 frac)) "%"))
         "\n───────────────────────────────────────")))

(defn format-history
  "Return a history string for the current message list."
  [ctx]
  (let [msgs (:messages (agent/get-data-in (:agent-ctx ctx)))]
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

(defn format-help
  "Return a help string listing all available commands."
  [ctx]
  (let [sd            (session/get-session-data-in ctx)
        templates     (:prompt-templates sd)
        loaded-skills (:skills sd)
        ext-cmds      (ext/all-commands-in (:extension-registry ctx))]
    (str "── Commands ───────────────────────────\n"
         "  /quit    — exit the session\n"
         "  /status  — show session diagnostics\n"
         "  /history — show message history\n"
         "  /prompts — list available prompt templates\n"
         "  /skills  — list available skills\n"
         "  /new     — start a fresh session\n"
         "  /resume  — resume a previous session\n"
         "  /login [provider] — login with an OAuth provider\n"
         "  /logout  — logout from an OAuth provider\n"
         "  /feed-forward [reason] — trigger a manual feed-forward cycle\n"
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
                                 (str "  /" (:name c)
                                      (when (:description c)
                                        (str " — " (:description c)))))
                               ext-cmds))))
         "\n  (anything else is sent to the agent)\n"
         "───────────────────────────────────────")))

(defn format-prompts
  "Return a string listing available prompt templates."
  [ctx]
  (let [templates (:prompt-templates (session/get-session-data-in ctx))]
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
  [ctx]
  (let [loaded-skills (:skills (session/get-session-data-in ctx))]
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

;; ============================================================
;; Provider env var hint (for login error messages)
;; ============================================================

(def ^:private provider-env-vars
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :google    "GOOGLE_API_KEY"})

(def ^:private default-feed-forward-readiness
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(defn- feed-forward-trigger-signal
  [reason]
  (recursion/manual-trigger-signal reason {:source :runtime-command}))

(defn- format-feed-forward-trigger-result
  [result]
  (case (:result result)
    :accepted
    (str "✓ Feed-forward trigger accepted"
         "\n  cycle-id: " (:cycle-id result)
         "\n  prompt: " recursion/feed-forward-manual-trigger-prompt-name)

    :blocked
    (str "‖ Feed-forward trigger blocked"
         "\n  cycle-id: " (:cycle-id result)
         "\n  reason: recursion_prerequisites_not_ready")

    :ignored
    "… Feed-forward trigger ignored (manual hook disabled)."

    :rejected
    (str "✗ Feed-forward trigger rejected"
         "\n  reason: " (name (:reason result)))

    (str result)))

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

(defn dispatch
  "Dispatch a /command string. Returns a result map, or nil if not a command.

   Arguments:
     ctx       — session context
     text      — raw user input (trimmed)
     opts      — {:oauth-ctx   oauth context
                  :ai-model    resolved model map}

   Result types — see ns docstring."
  [ctx text {:keys [oauth-ctx ai-model]}]
  (let [trimmed (str/trim text)]
    (cond
      ;; Quit
      (or (= trimmed "/quit") (= trimmed "/exit"))
      {:type :quit}

      ;; New session
      (= trimmed "/new")
      (do (session/new-session-in! ctx)
          {:type :new-session :message "[New session started]"})

      ;; Resume
      (= trimmed "/resume")
      {:type :resume}

      ;; Status
      (= trimmed "/status")
      {:type :text :message (format-status ctx)}

      ;; History
      (= trimmed "/history")
      {:type :text :message (format-history ctx)}

      ;; Help
      (or (= trimmed "/help") (= trimmed "/?"))
      {:type :text :message (format-help ctx)}

      ;; Prompts
      (= trimmed "/prompts")
      {:type :text :message (format-prompts ctx)}

      ;; Skills
      (= trimmed "/skills")
      {:type :text :message (format-skills ctx)}

      ;; Feed-forward manual trigger
      (or (= trimmed "/feed-forward")
          (str/starts-with? trimmed "/feed-forward "))
      (if-let [recursion-ctx (:recursion-ctx ctx)]
        (let [reason (-> (str/replace trimmed #"^/feed-forward\s*" "")
                         (str/trim)
                         (not-empty))
              result (recursion/handle-trigger-in! recursion-ctx
                                                   (feed-forward-trigger-signal reason)
                                                   default-feed-forward-readiness)]
          {:type :text
           :message (format-feed-forward-trigger-result result)})
        {:type :text
         :message "✗ Feed-forward recursion context not configured."})

      ;; Login
      (or (= trimmed "/login")
          (str/starts-with? trimmed "/login "))
      (if-not oauth-ctx
        {:type :login-error :message "OAuth not available."}
        (let [provider-arg (second (str/split trimmed #"\s+" 2))
              providers    (oauth/available-providers oauth-ctx)
              {:keys [provider error]}
              (select-login-provider providers (:provider ai-model) provider-arg)]
          (if error
            {:type :login-error :message (str "✗ " error)}
            (try
              (let [{:keys [url login-state]}
                    (oauth/begin-login! oauth-ctx (:id provider))]
                {:type                 :login-start
                 :provider             provider
                 :url                  url
                 :login-state          login-state
                 :uses-callback-server (and (:uses-callback-server provider)
                                            (:callback-server login-state))})
              (catch Exception e
                {:type :login-error
                 :message (str "✗ Login failed: " (ex-message e))})))))

      ;; Logout
      (= trimmed "/logout")
      (if-not oauth-ctx
        {:type :text :message "OAuth not available."}
        (let [logged-in (oauth/logged-in-providers oauth-ctx)]
          (if (empty? logged-in)
            {:type :text :message "No OAuth providers logged in. Use /login first."}
            (do (doseq [p logged-in]
                  (oauth/logout! oauth-ctx (:id p)))
                {:type    :logout
                 :message (str "✓ Logged out of: "
                               (str/join ", " (map :name logged-in)))}))))

      ;; Extension command: /name args
      (and (str/starts-with? trimmed "/")
           (let [cmd-name (first (str/split (subs trimmed 1) #"\s" 2))]
             (ext/get-command-in (:extension-registry ctx) cmd-name)))
      (let [parts    (str/split (subs trimmed 1) #"\s" 2)
            cmd-name (first parts)
            args-str (or (second parts) "")
            cmd      (ext/get-command-in (:extension-registry ctx) cmd-name)]
        {:type :extension-cmd :name cmd-name :args args-str :handler (:handler cmd)})

      ;; Not a command
      :else
      nil)))

