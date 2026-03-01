(ns psi.agent-session.main
  "Entry point: run an interactive agent session on the terminal.

   Usage:
     clojure -M:run
     clojure -M:run --model sonnet-4.6
     clojure -M:run --log-level DEBUG
     clojure -M:run --tui
     clojure -M:run --nrepl            # random port
     clojure -M:run --nrepl 7888       # specific port
     clojure -M:run --tui --nrepl      # TUI + nREPL

   What it does:
     1. Creates an agent session (statechart + agent-core + extension registry)
     2. Wires the ai provider (Anthropic by default) into the executor
     3. Registers the four built-in tools: read, bash, edit, write
     4. Optionally starts an nREPL server for live introspection
     5. Drops into a REPL-style prompt loop — type a message, get a response
        OR (with --tui) renders an interactive TUI session
     6. /quit or EOF exits (plain mode); TUI uses Escape interrupt/cancel,
        Ctrl+C clear-then-quit, Ctrl+D exit-when-empty

   Environment variables:
     ANTHROPIC_API_KEY   — required for Anthropic models
     OPENAI_API_KEY      — required for OpenAI models
     PSI_MODEL           — model key override (e.g. claude-3-5-haiku, gpt-4o, gpt-5.3-codex)
     PSI_DEVELOPER_PROMPT — optional developer instruction text

   nREPL introspection (from a connected REPL):
     @psi.agent-session.main/session-state  — live session context
     (require '[psi.agent-session.core :as s])
     (s/query-in (:ctx @psi.agent-session.main/session-state)
       [:psi.agent-session/phase :psi.agent-session/session-id])

   Introspection (plain mode only):
     /status  — print session diagnostics via EQL
     /history — print message history
     /help    — print available commands"
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.tui.app :as tui-app])
  (:gen-class))

;; ============================================================
;; Live session state — accessible from nREPL
;; ============================================================

(defonce session-state
  (atom nil))

;; ============================================================
;; nREPL server (started conditionally via --nrepl)
;; ============================================================

(defn- start-nrepl!
  "Start an nREPL server on `port` (0 = random). Returns the server.
   Writes the bound port to .nrepl-port for editor auto-discovery."
  [port]
  (let [start-server (requiring-resolve 'nrepl.server/start-server)
        server       (start-server :port port)
        port-file    (java.io.File. ".nrepl-port")]
    (spit port-file (str (:port server)))
    (.deleteOnExit port-file)
    (println (str "  nREPL : localhost:" (:port server)
                  " (connect with your editor)"))
    server))

(defn- stop-nrepl! [server]
  (when server
    (let [stop-server (requiring-resolve 'nrepl.server/stop-server)
          port-file   (java.io.File. ".nrepl-port")]
      (stop-server server)
      (when (.exists port-file)
        (when (= (str/trim (slurp port-file)) (str (:port server)))
          (.delete port-file))))))

(defn- nrepl-port-from-args
  "If --nrepl is present, return the port (next arg if numeric, else 0).
   Returns nil if --nrepl is absent."
  [args]
  (when (some #(= "--nrepl" %) args)
    (let [after (second (drop-while #(not= "--nrepl" %) args))]
      (if (and after (re-matches #"\d+" after))
        (Integer/parseInt after)
        0))))

;; ============================================================
;; Logging
;; ============================================================

(def ^:private valid-log-levels
  #{:trace :debug :info :warn :error :fatal :report})

(defn- set-log-level!
  "Set Timbre's minimum log level (case-insensitive keyword).
  Defaults to :info if the string is unrecognised."
  [level-str]
  (let [kw    (keyword (str/lower-case (or level-str "info")))
        level (if (valid-log-levels kw) kw :info)]
    (timbre/set-min-level! level)))

(defn- log-level-from-args
  "Extract --log-level <LEVEL> from CLI args, or fall back to INFO."
  [args]
  (or (second (drop-while #(not= "--log-level" %) args))
      "INFO"))

;; ============================================================
;; Model resolution
;; ============================================================

(def ^:private default-model-key :sonnet-4.6)

(defn- resolve-model
  "Return an ai.schemas.Model map for `model-key` keyword."
  [model-key]
  (or (get models/all-models model-key)
      (throw (ex-info (str "Unknown model: " model-key
                           "\nAvailable: " (str/join ", " (map name (keys models/all-models))))
                      {:model-key model-key}))))

(defn- model-key-from-args
  "Extract --model <key> from CLI args vector, or fall back to PSI_MODEL env var."
  [args]
  (let [env-model (System/getenv "PSI_MODEL")]
    (keyword
     (or (second (drop-while #(not= "--model" %) args))
         env-model
         (name default-model-key)))))

(defn- developer-prompt-from-env
  "Return optional developer prompt text from PSI_DEVELOPER_PROMPT.
   Blank values are treated as nil."
  []
  (let [v (System/getenv "PSI_DEVELOPER_PROMPT")]
    (when-not (str/blank? v)
      v)))

;; ============================================================
;; Output helpers
;; ============================================================

(defn- print-banner [model templates loaded-skills ctx]
  (println)
  (println "╔══════════════════════════════════════╗")
  (println "║  ψ  Psi Agent Session                ║")
  (println "╚══════════════════════════════════════╝")
  (println (str "  Model   : " (:name model)))
  (println (str "  Tools   : " (str/join ", " (map :name tools/all-tool-schemas))))
  (when (seq templates)
    (println (str "  Prompts : " (count templates) " loaded"))
    (doseq [t templates]
      (println (str "    /" (:name t) " — " (:description t)))))
  (when (seq loaded-skills)
    (let [visible (skills/visible-skills loaded-skills)
          hidden  (skills/hidden-skills loaded-skills)]
      (println (str "  Skills  : " (count visible) " available"
                    (when (seq hidden) (str ", " (count hidden) " hidden"))))))
  (let [ext-details (ext/extension-details-in (:extension-registry ctx))]
    (when (seq ext-details)
      (println (str "  Exts    : " (count ext-details) " loaded"))
      (doseq [d ext-details]
        (let [parts (cond-> []
                      (pos? (:tool-count d))    (conj (str (:tool-count d) " tools"))
                      (pos? (:command-count d)) (conj (str (:command-count d) " cmds"))
                      (pos? (:handler-count d)) (conj (str (:handler-count d) " handlers")))
              suffix (when (seq parts) (str " (" (str/join ", " parts) ")"))]
          (println (str "    " (.getName (java.io.File. ^String (:path d))) suffix))))))
  (println "  /help for commands, /quit to exit")
  (println))

;; print-status, print-history, print-help, print-prompts, print-skills
;; moved to psi.agent-session.commands as format-* functions

;; ============================================================
;; Response printing — streams to stdout as tokens arrive
;; ============================================================

(defn- print-assistant-message
  "Print the text content of an assistant message map.
  If the message carries an error, print it clearly instead of (or after) any partial text."
  [msg]
  (let [text   (str/join (keep #(when (= :text  (:type %)) (:text %)) (:content msg)))
        errors (keep     #(when (= :error (:type %)) (:text %)) (:content msg))]
    (when (seq text)
      (println (str "\nψ: " text "\n")))
    (doseq [err errors]
      (println (str "\n[Provider error: " err "]\n")))
    (when (and (empty? text) (empty? errors))
      (println "\nψ: (no response)\n"))))

(defn- message->display-text
  "Extract display text from an agent-core message map.
   Includes :text blocks and :error blocks."
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content)
      content

      (sequential? content)
      (->> content
           (keep (fn [block]
                   (case (:type block)
                     :text  (:text block)
                     :error (str "[error] " (:text block))
                     nil)))
           (str/join "\n"))

      :else
      "")))

(defn- tool-result->display-text
  "Best-effort display text for a toolResult message content vector."
  [msg]
  (let [text (message->display-text msg)]
    (when-not (str/blank? text)
      text)))

(defn- agent-messages->tui-resume-state
  "Convert agent-core history to TUI resume state.
   Returns {:messages [...], :tool-calls {...}, :tool-order [...]}.
   Tool rows are reconstructed by correlating assistant tool-call blocks
   with toolResult messages by tool-call-id."
  [messages]
  (reduce
   (fn [{:keys [messages tool-calls tool-order] :as acc} msg]
     (case (:role msg)
       "user"
       (let [text (message->display-text msg)]
         (update acc :messages conj {:role :user
                                     :text (if (str/blank? text) "[user]" text)}))

       "assistant"
       (let [text (message->display-text msg)
             content (:content msg)
             tool-blocks (filter #(= :tool-call (:type %)) content)
             acc' (if (str/blank? text)
                    acc
                    (update acc :messages conj {:role :assistant :text text}))]
         (reduce
          (fn [a block]
            (let [id (:id block)
                  tc {:name      (:name block)
                      :args      (or (:arguments block) "")
                      :status    :pending
                      :result    nil
                      :is-error  false
                      :expanded? false}]
              (-> a
                  (update :tool-calls #(if (contains? % id) % (assoc % id tc)))
                  (update :tool-order #(if (some #{id} %) % (conj % id))))))
          acc'
          tool-blocks))

       "toolResult"
       (let [id      (:tool-call-id msg)
             text    (tool-result->display-text msg)
             content (:content msg)
             details (:details msg)
             err?    (boolean (:is-error msg))
             fallback {:name      (:tool-name msg)
                       :args      ""
                       :status    (if err? :error :success)
                       :result    text
                       :content   content
                       :details   details
                       :is-error  err?
                       :expanded? false}]
         (-> acc
             (update :tool-calls
                     (fn [m]
                       (if-let [tc (get m id)]
                         (assoc m id
                                (-> tc
                                    (assoc :status (if err? :error :success)
                                           :content content
                                           :details details
                                           :is-error err?)
                                    (cond-> text (assoc :result text))))
                         (assoc m id fallback))))
             (update :tool-order #(if (some #{id} %) % (conj % id)))))

       ;; unknown roles — ignore
       acc))
   {:messages [] :tool-calls {} :tool-order []}
   messages))

;; select-login-provider moved to psi.agent-session.commands

;; ============================================================
;; Prompt template expansion
;; ============================================================

(defn- expand-input
  "Expand user input through skills (/skill:name) or prompt templates (/name).
   Returns the (possibly expanded) text to send to the agent.
   Extension commands are excluded — they are handled by the command dispatch."
  [ctx text]
  (let [sd            (session/get-session-data-in ctx)
        loaded-skills (:skills sd)
        templates     (:prompt-templates sd)
        commands      (ext/command-names-in (:extension-registry ctx))]
    ;; Try skill expansion first (/skill:name)
    (if-let [skill-result (skills/invoke-skill loaded-skills text)]
      (do
        (println (str "[Skill: " (:skill-name skill-result) "]\n"))
        (:content skill-result))
      ;; Then try prompt template expansion (/name)
      (if-let [tpl-result (pt/invoke-template templates commands text)]
        (do
          (println (str "[Template: " (:source-template tpl-result) "]\n"))
          (:content tpl-result))
        text))))

;; ============================================================
;; Core: one prompt → response cycle
;; ============================================================

(defn- resolve-api-key
  "Resolve the API key for the current model's provider via OAuth context.
   Returns the key string, or nil (provider falls back to env var)."
  [oauth-ctx ai-model]
  (when oauth-ctx
    (oauth/get-api-key oauth-ctx (:provider ai-model))))

(defn- usage->context-tokens
  "Best-effort context token count from an assistant usage map.
   Returns nil when usage is missing or unknown."
  [usage]
  (when (map? usage)
    (let [input  (or (:input-tokens usage) 0)
          output (or (:output-tokens usage) 0)
          read   (or (:cache-read-tokens usage) 0)
          write  (or (:cache-write-tokens usage) 0)
          total  (or (:total-tokens usage)
                     (+ input output read write))]
      (when (and (number? total) (pos? total))
        total))))

(defn- update-context-usage-from-result!
  "Update session context usage from a completed assistant result when usage is available.
   Keeps context tokens nil when providers do not return reliable usage."
  [ctx ai-model result]
  (let [tokens (usage->context-tokens (:usage result))
        window (:context-window ai-model)]
    (when (and (some? tokens) (number? window) (pos? window))
      (session/update-context-usage-in! ctx tokens window))))

(defn- run-prompt!
  "Send `text` to the agent and block until done, printing the response.
   Expands prompt templates before sending."
  [ctx ai-ctx ai-model oauth-ctx text]
  (let [expanded (expand-input ctx text)
        user-msg {:role      "user"
                  :content   [{:type :text :text expanded}]
                  :timestamp (java.time.Instant/now)}
        _        (session/journal-append-in! ctx (persist/message-entry user-msg))
        api-key  (resolve-api-key oauth-ctx ai-model)
        result   (executor/run-agent-loop! ai-ctx ctx (:agent-ctx ctx) ai-model [user-msg]
                                           {:turn-ctx-atom (:turn-ctx-atom ctx)
                                            :api-key       api-key})]
    (update-context-usage-from-result! ctx ai-model result)
    (print-assistant-message result)))

;; ============================================================
;; Main prompt loop
;; ============================================================

(defn run-session
  "Create a session and enter the interactive prompt loop.
  Returns when the user exits."
  [model-key]
  (let [ai-model  (resolve-model model-key)
        ;; ai context: nil signals the executor to use the public ai/stream-response API
        ai-ctx    nil
        ;; OAuth credential store (file-backed, auto-refresh)
        oauth-ctx (oauth/create-context)
        ;; Discover prompt templates from global + project dirs
        templates (pt/discover-templates)
        ;; Discover skills from global + project dirs
        {:keys [skills diagnostics]} (skills/discover-skills)
        _         (doseq [d diagnostics]
                    (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
        ;; Build system prompt with context files and skills
        cwd       (System/getProperty "user.dir")
        ctx-files (sys-prompt/discover-context-files cwd)
        system-prompt (sys-prompt/build-system-prompt
                       {:cwd           cwd
                        :context-files ctx-files
                        :skills        skills})
        developer-prompt (developer-prompt-from-env)
        ;; session context — agent-core + statechart + extension registry
        ctx      (session/create-context
                  {:initial-session {:model {:provider (name (:provider ai-model))
                                             :id       (:id ai-model)
                                             :reasoning (:supports-reasoning ai-model)}
                                     :system-prompt   system-prompt}
                   :oauth-ctx oauth-ctx})
        ext-paths (ext/discover-extension-paths [] cwd)]
    ;; Reusable bootstrap: session file, query graph, base tools, system prompt,
    ;; mutation-driven startup loading, extension tool merge.
    ;; eql_query tool closes over ctx for live session introspection.
    (let [eql-tool (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
          summary  (session/bootstrap-session-in!
                    ctx {:register-global-query? true
                         :base-tools             (conj (vec tools/all-tools) eql-tool)
                         :system-prompt          system-prompt
                         :developer-prompt       developer-prompt
                         :developer-prompt-source (if developer-prompt :env :fallback)
                         :templates              templates
                         :skills                 skills
                         :extension-paths        ext-paths})]
      (doseq [{:keys [path error]} (:extension-errors summary)]
        (timbre/warn "Extension error:" path error))
      (when (pos? (:extension-loaded-count summary))
        (timbre/debug "Extensions loaded:" (:extension-loaded-count summary))))
    ;; Expose state for nREPL introspection
    (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                           :oauth-ctx oauth-ctx})
    (print-banner ai-model templates skills ctx)
    (let [cmd-opts {:oauth-ctx oauth-ctx :ai-model ai-model}]
      (loop []
        (print "刀: ")
        (flush)
        (when-let [line (try (read-line) (catch Exception _ nil))]
          (let [trimmed (str/trim line)
                result  (when-not (str/blank? trimmed)
                          (commands/dispatch ctx trimmed cmd-opts))]
            (cond
              (nil? result)
              (if (str/blank? trimmed)
                (recur)
                (do (try
                      (run-prompt! ctx ai-ctx ai-model oauth-ctx trimmed)
                      (catch Exception e
                        (println (str "\n[Error: " (ex-message e) "]\n"))))
                    (recur)))

              (= :quit (:type result))
              (println "\nψ: Goodbye.\n")

              (= :resume (:type result))
              (do (println "\n  /resume is only available in TUI mode (--tui).\n")
                  (recur))

              (#{:text :new-session :logout} (:type result))
              (do (println (str "\n" (:message result) "\n"))
                  (recur))

              (= :login-start (:type result))
              (do (println "\n── OAuth Login ────────────────────────")
                  (let [{:keys [provider url login-state uses-callback-server]} result]
                    (println (str "  Open: " url "\n"))
                    (if uses-callback-server
                      (do (println "  Waiting for browser callback…")
                          (try
                            (oauth/complete-login! oauth-ctx (:id provider) nil login-state)
                            (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
                            (catch Exception e
                              (println (str "\n  ✗ Login failed: " (ex-message e) "\n")))))
                      (do (print "  Paste authorization code: ")
                          (flush)
                          (when-let [code (try (read-line) (catch Exception _ nil))]
                            (try
                              (oauth/complete-login! oauth-ctx (:id provider) (str/trim code) login-state)
                              (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
                              (catch Exception e
                                (println (str "\n  ✗ Login failed: " (ex-message e) "\n"))))))))
                  (recur))

              (= :login-error (:type result))
              (do (println (str "\n  " (:message result) "\n"))
                  (recur))

              (= :extension-cmd (:type result))
              (do (try
                    (when-let [handler (:handler result)]
                      (let [captured (with-out-str (handler (:args result)))]
                        (when-not (str/blank? captured)
                          (println (str "\n" (str/trimr captured) "\n")))))
                    (catch Exception e
                      (println (str "\n[Command error: " (ex-message e) "]\n"))))
                  (recur))

              :else
              (do (println (str "\n" result "\n"))
                  (recur)))))))))

;; ============================================================
;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn run-tui-session
  "Create a session and run it as a full-screen TUI via charm.clj.
  Blocks until the user exits (Ctrl+C second press or Ctrl+D on empty input)."
  [model-key]
  (let [ai-model  (resolve-model model-key)
        ai-ctx    nil
        oauth-ctx (oauth/create-context)
        event-queue (java.util.concurrent.LinkedBlockingQueue.)
        templates (pt/discover-templates)
        {:keys [skills diagnostics]} (skills/discover-skills)
        _         (doseq [d diagnostics]
                    (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
        cwd       (System/getProperty "user.dir")
        ctx-files (sys-prompt/discover-context-files cwd)
        system-prompt (sys-prompt/build-system-prompt
                       {:cwd           cwd
                        :context-files ctx-files
                        :skills        skills})
        developer-prompt (developer-prompt-from-env)
        ctx       (session/create-context
                   {:initial-session {:model {:provider (name (:provider ai-model))
                                              :id       (:id ai-model)
                                              :reasoning (:supports-reasoning ai-model)}
                                      :system-prompt   system-prompt}
                    :event-queue event-queue
                    :oauth-ctx oauth-ctx})
        ext-paths (ext/discover-extension-paths [] cwd)
        ;; Reusable bootstrap: session file, query graph, base tools, system prompt,
        ;; mutation-driven startup loading, extension tool merge.
        ;; eql_query tool closes over ctx for live session introspection.
        eql-tool  (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
        summary   (session/bootstrap-session-in!
                   ctx {:register-global-query? true
                        :base-tools             (conj (vec tools/all-tools) eql-tool)
                        :system-prompt          system-prompt
                        :developer-prompt       developer-prompt
                        :developer-prompt-source (if developer-prompt :env :fallback)
                        :templates              templates
                        :skills                 skills
                        :extension-paths        ext-paths})
        _         (doseq [{:keys [path error]} (:extension-errors summary)]
                    (timbre/warn "Extension error:" path error))
        _         (when (pos? (:extension-loaded-count summary))
                    (timbre/debug "Extensions loaded:" (:extension-loaded-count summary)))

        ;; Expose state for nREPL introspection
        _         (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                                         :oauth-ctx oauth-ctx})

        ;; Helper: put an immediate assistant message on the TUI queue.
        reply!    (fn [^java.util.concurrent.LinkedBlockingQueue queue text]
                    (.put queue {:kind :done
                                 :result {:role    "assistant"
                                          :content [{:type :text :text text}]}}))

        ;; Resume callback used by the TUI /resume selector.
        ;; Returns TUI resume state maps to display immediately after loading.
        resume-fn! (fn [session-path]
                     (try
                       (session/resume-session-in! ctx session-path)
                       (let [messages (:messages (agent/get-data-in (:agent-ctx ctx)))]
                         (agent-messages->tui-resume-state messages))
                       (catch Exception e
                         (timbre/error e "Resume failed:" session-path)
                         {:messages [{:role :assistant
                                      :text (str "✗ Resume failed: " (ex-message e))}]
                          :tool-calls {}
                          :tool-order []})))

        cmd-opts  {:oauth-ctx oauth-ctx :ai-model ai-model}

        ;; dispatch-fn — called synchronously by the TUI on submit.
        ;; Returns a command result map, or nil if not a command.
        ;; When a login is pending (waiting for auth code), returns nil
        ;; so the input falls through to run-agent-fn! which handles it.
        dispatch-fn (fn [text]
                      (if (:pending-login @session-state)
                        nil  ;; fall through to run-agent-fn! for login code
                        (let [result (commands/dispatch ctx text cmd-opts)]
                          (when (= :login-start (:type result))
                            (if (:uses-callback-server result)
                              ;; Callback-server: start async completion in background.
                              ;; TUI shows URL immediately; future will put completion
                              ;; message on the session when callback arrives.
                              ;; (The TUI can't receive this — it's fire-and-forget.)
                              nil
                              ;; Manual-code: store pending state so next input is the code.
                              (swap! session-state assoc :pending-login
                                     {:provider-id   (get-in result [:provider :id])
                                      :provider-name (get-in result [:provider :name])
                                      :login-state   (:login-state result)})))
                          result)))

        ;; Called by TUI Escape during active work.
        ;; Aborts current run and returns queued steering/follow-up text
        ;; so input can be restored in the editor.
        on-interrupt-fn! (fn [_state]
                           (session/abort-in! ctx)
                           {:queued-text (session/consume-queued-input-text-in! ctx)
                            :message "Interrupted active work."})

        ;; run-agent-fn! — called by the TUI for non-command input.
        ;; Handles pending-login step 2 and agent execution.
        run-agent-fn! (fn [text ^java.util.concurrent.LinkedBlockingQueue queue]
                        (let [trimmed (str/trim text)
                              pending (:pending-login @session-state)]
                          (cond
                            ;; Step 2: pending login — this input IS the auth code
                            pending
                            (future
                              (try
                                (let [{:keys [provider-id provider-name login-state]} pending]
                                  (swap! session-state dissoc :pending-login)
                                  (oauth/complete-login! oauth-ctx provider-id trimmed login-state)
                                  (reply! queue (str "✓ Logged in to " provider-name)))
                                (catch Exception e
                                  (swap! session-state dissoc :pending-login)
                                  (reply! queue (str "✗ Login failed: " (ex-message e))))))

                            ;; Everything else — send to agent
                            :else
                            (future
                              (try
                                (let [expanded (expand-input ctx text)
                                      user-msg {:role      "user"
                                                :content   [{:type :text :text expanded}]
                                                :timestamp (java.time.Instant/now)}
                                      _        (session/journal-append-in!
                                                ctx (persist/message-entry user-msg))
                                      api-key  (resolve-api-key oauth-ctx ai-model)
                                      result   (executor/run-agent-loop!
                                                ai-ctx ctx (:agent-ctx ctx)
                                                ai-model [user-msg]
                                                {:turn-ctx-atom  (:turn-ctx-atom ctx)
                                                 :api-key        api-key
                                                 :progress-queue queue})]
                                  (update-context-usage-from-result! ctx ai-model result)
                                  (.put queue {:kind :done :result result}))
                                (catch Exception e
                                  (.put queue {:kind :error :message (ex-message e)})))))))]

    (tui-app/start! (:name ai-model) run-agent-fn!
                    {:query-fn             (fn [q] (session/query-in ctx q))
                     :ui-state-atom        (:ui-state-atom ctx)
                     :dispatch-fn          dispatch-fn
                     :on-interrupt-fn!     on-interrupt-fn!
                     :double-press-window-ms 500
                     :double-escape-action :none
                     :cwd                  cwd
                     :current-session-file (:session-file (session/get-session-data-in ctx))
                     :resume-fn!           resume-fn!
                     :event-queue          event-queue
                     :alt-screen           false})))

;; ============================================================
;; -main
;; ============================================================

(defn -main
  "Entry point. Accepts optional --model <key>, --log-level <LEVEL>, --tui, --nrepl [port]."
  [& args]
  (set-log-level! (log-level-from-args args))
  (let [model-key  (model-key-from-args args)
        tui?       (some #(= "--tui" %) args)
        nrepl-port (nrepl-port-from-args args)
        nrepl-srv  (when nrepl-port (start-nrepl! nrepl-port))]
    (try
      (if tui?
        (run-tui-session model-key)
        (run-session model-key))
      (finally
        (stop-nrepl! nrepl-srv)))
    ;; clj-http (Apache HttpClient) parks a non-daemon connection-eviction thread.
    ;; Explicitly exit so the JVM does not hang after /quit.
    (System/exit 0)))
