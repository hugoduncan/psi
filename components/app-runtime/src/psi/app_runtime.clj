(ns psi.app-runtime
  "Agent session application runtime for console and TUI execution.

   This namespace owns live session startup/state for interactive runtimes.
   RPC transport concerns live in `psi.rpc`.

   Usage:
     psi
     psi --model sonnet-4.6
     psi --log-level DEBUG
     psi --tui
     psi --nrepl            # random port
     psi --nrepl 7888       # specific port
     psi --tui --nrepl      # TUI + nREPL
     psi --memory-store in-memory
     psi --memory-store-fallback off --memory-retention-snapshots 500

   Development/non-canonical alternatives may still use repo-local
   `clojure -M:run ...` invocation paths.

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
     ANTHROPIC_API_KEY    — required for Anthropic models
     OPENAI_API_KEY       — required for OpenAI models
     PSI_MODEL            — model key override (e.g. claude-3-5-haiku, gpt-4o, gpt-5.4)
     PSI_DEVELOPER_PROMPT — optional developer instruction text
     PSI_MEMORY_STORE     — optional memory provider (in-memory)
     PSI_MEMORY_STORE_AUTO_FALLBACK
     PSI_MEMORY_HISTORY_COMMIT_LIMIT
     PSI_MEMORY_RETENTION_SNAPSHOTS
     PSI_MEMORY_RETENTION_DELTAS

   nREPL introspection (from a connected REPL):
     @psi.app-runtime/session-state  — live session context
     (require '[psi.agent-session.core :as s])
     (s/query-in (:ctx @psi.app-runtime/session-state)
       [:psi.agent-session/phase :psi.agent-session/session-id])

   Introspection (plain mode only):
     /status  — print session diagnostics via EQL
     /history — print message history
     /help    — print available commands"
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.agent-session.bootstrap :as session-bootstrap]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]

   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.oauth.core :as oauth]
   [psi.app-runtime.background-job-ui :as background-job-ui]
   [psi.app-runtime.context :as app-context]
   [psi.app-runtime.context-summary :as context-summary]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.nrepl-runtime :as app-nrepl]
   [psi.app-runtime.output :as output]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.selectors :as selectors]
   [psi.app-runtime.transcript :as transcript]
   [psi.app-runtime.tui-frontend-actions :as tui-frontend-actions]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.config-resolution :as config-res]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.ai.model-registry :as model-registry]
   [psi.system-bootstrap.core :as bootstrap]
   [psi.memory.runtime :as memory-runtime]
   [psi.recursion.core :as recursion])
   ;; [psi.tui.app :as tui-app]  ; Removed - circular dependency fix

  (:gen-class))

;; ============================================================
;; Live session state — accessible from nREPL
;; ============================================================

(defonce session-state
  (atom nil))

(defonce nrepl-runtime
  (atom nil))

(defn- default-session-id-in
  [ctx]
  (some-> (ss/list-context-sessions-in ctx) first :session-id))

(defn- merge-startup-summary
  "Merge manifest/bootstrap startup summary maps.

   Scalar startup fields are overwritten by the newer summary contribution.
   Sequential fields accumulate in order so bootstrap diagnostics and manifest
   activation errors both remain visible."
  [base delta]
  (merge-with (fn [base-value delta-value]
                (if (and (sequential? base-value) (sequential? delta-value))
                  (into (vec base-value) delta-value)
                  delta-value))
              base
              delta))

;; ============================================================
;; nREPL server (started conditionally via --nrepl)
;; ============================================================

(defn start-nrepl!
  "Start an nREPL server on `port` (0 = random). Returns the server.
   Writes the bound port to .nrepl-port for editor auto-discovery.
   Any startup chatter from nREPL is redirected to stderr so RPC stdout stays protocol-only."
  [port]
  (app-nrepl/start-nrepl! session-state nrepl-runtime default-session-id-in port))

(defn stop-nrepl! [server]
  (app-nrepl/stop-nrepl! session-state nrepl-runtime default-session-id-in server))

(defn- developer-prompt-from-env
  "Return optional developer prompt text from PSI_DEVELOPER_PROMPT.
   Blank values are treated as nil."
  []
  (let [v (System/getenv "PSI_DEVELOPER_PROMPT")]
    (when-not (str/blank? v)
      v)))

(defn resolve-model
  "Return an ai.schemas.Model map for `model-key` keyword."
  [model-key]
  (let [all (model-registry/all-models-by-key)]
    (or (get all model-key)
        (throw (ex-info (str "Unknown model: " model-key
                             "
Available: " (str/join ", " (map name (keys all))))
                        {:model-key model-key})))))

(defn- resolve-model-by-provider+id
  "Find a runtime model map by provider string + model-id string."
  [provider model-id]
  (let [provider* (some-> provider keyword)]
    (or (model-registry/find-model provider* model-id)
        (some (fn [[_ model]]
                (when (and (= provider* (:provider model))
                           (= model-id (:id model)))
                  model))
              models/all-models))))

(defn- current-ai-model-in
  "Resolve the effective runtime model for `session-id`, falling back to
   `fallback-ai-model` when the session model is absent or not in the runtime
   catalog."
  [ctx session-id fallback-ai-model]
  (let [{:keys [provider id]} (:model (ss/get-session-data-in ctx session-id))]
    (or (when (and provider id)
          (resolve-model-by-provider+id provider id))
        fallback-ai-model)))

(defn- submit-prompt-in!
  "Submit prompt text through the shared prompt lifecycle used by app-runtime
   callers.

   Request preparation owns prompt expansion and memory recovery.

   Returns:
   {:assistant-message message?
    :prepared-request map?
    :ai-model model?}"
  [ctx session-id fallback-ai-model text images {:keys [progress-queue]}]
  (let [ai-model (current-ai-model-in ctx session-id fallback-ai-model)
        _        (when ai-model
                   (dispatch/dispatch! ctx :session/set-model
                                       {:session-id session-id
                                        :model {:provider  (some-> (:provider ai-model) name)
                                                :id        (:id ai-model)
                                                :reasoning (boolean (:supports-reasoning ai-model))}
                                        :scope :session}
                                       {:origin :core}))
        prepared  (session/prompt-in! ctx session-id text images
                                      (cond-> {}
                                        progress-queue
                                        (assoc :progress-queue progress-queue)))]
    {:assistant-message (session/last-assistant-message-in ctx session-id)
     :prepared-request (:prepared-request prepared)
     :ai-model ai-model}))

;; print-status, print-history, print-help, print-prompts, print-skills
;; moved to psi.agent-session.commands as format-* functions

;; select-login-provider moved to psi.agent-session.commands

;; ============================================================
;; Core: one prompt → response cycle
;; ============================================================

(defn- run-prompt!
  "Send `text` to `session-id` and block until done, printing the response.
   Uses the shared prompt lifecycle for parity with RPC/TUI."
  [ctx session-id _ai-ctx ai-model text]
  (let [{:keys [assistant-message prepared-request]}
        (submit-prompt-in! ctx session-id ai-model text nil
                           {:sync-on-git-head-change? true})]
    (output/print-expansion-banner! (:prepared-request/input-expansion prepared-request))
    (output/print-assistant-message assistant-message)))

(defn- graph-capabilities-in
  "Best-effort read of current capability summaries from the live graph.
   Requires explicit session-id for session-scoped query resolution."
  [ctx session-id]
  (try
    (or (:psi.graph/capabilities (session/query-in ctx session-id [:psi.graph/capabilities]))
        [])
    (catch Exception e
      (timbre/warn e "Unable to query :psi.graph/capabilities for system prompt enrichment")
      [])))

(defn- startup-rehydrate-from-current-session!
  "Return rehydrate payload from the current session state.

   Startup prompts have been removed from runtime bootstrap. This helper now
   only snapshots the already-bootstrapped session transcript/tool state.

   Returns map:
   {:agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx session-id _ai-ctx _ai-model]
  (let [agent-messages (:messages (agent/get-data-in (ss/agent-ctx-in ctx session-id)))
        tui-state      (transcript/agent-messages->tui-resume-state agent-messages)]
    (assoc tui-state :agent-messages agent-messages)))

(defn- start-new-session-with-startup!
  "Create a fresh session branch and return rehydrate payload.

   Startup prompts have been removed; the new session relies on the canonical
   session bootstrap/runtime state and then snapshots its transcript/tool state.

   Reloads project-local custom models from the new session's worktree path so
   that models.edn changes are picked up when switching between sessions with
   different worktree paths.

   Returns map:
   {:session-id     string
    :agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx source-session-id ai-ctx ai-model]
  (let [sd            (session/new-session-in! ctx source-session-id {})
        sid           (:session-id sd)
        worktree-path (ss/session-worktree-path-in ctx sid)]
    (model-registry/load-project-models!
     (str worktree-path "/.psi/models.edn")
     (model-registry/default-user-models-path))
    (assoc (startup-rehydrate-from-current-session! ctx sid ai-ctx ai-model)
           :session-id sid)))

(defn create-runtime-session-context
  "Create a live session context with runtime/session state prepared, but not bootstrapped.

   Options:
   - :event-queue optional TUI/RPC event queue
   - :session-config optional session config overrides (merged with defaults)
   - :cwd optional cwd override (primarily for tests)
   - :ui-type runtime UI type hint (:console | :tui | :emacs)
   - :thinking-level-override explicit thinking level (CLI/env); overrides config when set"
  [ai-model {:keys [event-queue session-config cwd ui-type thinking-level-override]}]
  (let [cwd                      (or cwd (System/getProperty "user.dir"))
        ;; Initialize model registry with user-global + project-local custom models
        _                        (model-registry/init!
                                  {:user-models-path    (model-registry/default-user-models-path)
                                   :project-models-path (str cwd "/.psi/models.edn")})
        oauth-ctx                (oauth/create-context)
        cfg                      (config-res/resolve-config cwd)
        effective-model          (if-let [{:keys [provider id]} (config-res/resolved-model cfg)]
                                   (or (resolve-model-by-provider+id provider id) ai-model)
                                   ai-model)
        effective-thinking-level (session-data/clamp-thinking-level
                                  (or thinking-level-override
                                      (config-res/resolved-thinking-level cfg))
                                  {:reasoning (:supports-reasoning effective-model)})
        effective-prompt-mode    (config-res/resolved-prompt-mode cfg)
        nucleus-prelude-override (config-res/resolved-nucleus-prelude-override cfg)
        ctx                    (session/create-context
                                {:session-defaults {:model {:provider  (name (:provider effective-model))
                                                            :id        (:id effective-model)
                                                            :reasoning (:supports-reasoning effective-model)}
                                                    :thinking-level effective-thinking-level
                                                    :prompt-mode              effective-prompt-mode
                                                    :nucleus-prelude-override nucleus-prelude-override
                                                    :ui-type                  (or ui-type :console)}
                                 :config session-config
                                 :event-queue event-queue
                                 :oauth-ctx oauth-ctx
                                 :nrepl-runtime-atom nrepl-runtime
                                 :ui-type ui-type
                                 :mutations mutations/all-mutations})
        recursion-ctx            (recursion/create-hosted-context ctx (ss/state-path :recursion))
        ctx                      (assoc ctx :recursion-ctx recursion-ctx)
        _                        (when-not (sa/recursion-state-in ctx)
                                   (sa/set-recursion-state-in! ctx nil (recursion/initial-state)))
        sd                       (session/new-session-in! ctx nil {})
        session-id               (:session-id sd)]
    {:ctx        ctx
     :oauth-ctx  oauth-ctx
     :cwd        cwd
     :session-id session-id}))

(defn bootstrap-runtime-session!
  "Bootstrap a live session context shared by CLI/TUI/RPC modes.

   Calling forms:
   - (bootstrap-runtime-session! ai-model opts)
       creates a fresh runtime session context, bootstraps it, and returns result map
   - (bootstrap-runtime-session! ctx ai-model)
       bootstraps an existing ctx with default opts
   - (bootstrap-runtime-session! ctx ai-model opts)
       bootstraps an existing ctx with explicit opts

   Options:
   - :memory-runtime-opts optional memory/runtime sync opts
   - :cwd optional cwd override (primarily for tests)"
  ([x y]
   (if (:state* x)
     (bootstrap-runtime-session! x nil y {})
     (let [ai-model x
           opts     y
           {:keys [ctx oauth-ctx cwd session-id]} (create-runtime-session-context ai-model {:cwd (:cwd opts)
                                                                                            :session-config (:session-config opts)
                                                                                            :ui-type (or (:ui-type opts) :console)})
           result (bootstrap-runtime-session! ctx session-id ai-model opts)]
       (assoc result :ctx ctx :oauth-ctx oauth-ctx :cwd cwd :session-id session-id))))
  ([ctx session-id ai-model {:keys [memory-runtime-opts cwd]}]
   (let [templates        (pt/discover-templates)
         {:keys [skills diagnostics]} (skills/discover-skills)
         _                (doseq [d diagnostics]
                            (timbre/warn "Skill" (:type d) ":" (:message d) (:path d)))
         cwd              (or cwd (System/getProperty "user.dir"))
         _                (background-job-ui/install-background-job-ui-refresh! ctx)
         ctx-files        (sys-prompt/discover-context-files cwd)
         sd               (ss/get-session-data-in ctx session-id)
         prompt-mode      (or (:prompt-mode sd) :lambda)
         prelude-override (:nucleus-prelude-override sd)
         base-prompt-opts {:cwd                      cwd
                           :session-instant          (:started-at ctx)
                           :prompt-mode              prompt-mode
                           :nucleus-prelude-override prelude-override
                           :context-files            ctx-files
                           :skills                   skills}
         base-prompt      (sys-prompt/build-system-prompt base-prompt-opts)
         developer-prompt (developer-prompt-from-env)
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt base-prompt} {:origin :core})
         psi-tool         (tools/make-psi-tool (fn
                                                 ([q] (session/query-in ctx session-id q))
                                                 ([q entity] (session/query-in ctx q entity)))
                                               {:ctx ctx
                                                :session-id session-id
                                                :cwd cwd})
         summary-base     (session-bootstrap/bootstrap-in!
                           ctx session-id
                           {:register-global-query? false
                            :base-tools             (conj (vec tools/all-tools) psi-tool)
                            :system-prompt          base-prompt
                            :developer-prompt       developer-prompt
                            :developer-prompt-source (if developer-prompt :env :fallback)
                            :templates              templates
                            :skills                 skills
                            :extension-paths        []
                            :extension-targets      []})
         {:keys [summary-updates]}
         (extension-runtime/bootstrap-manifest-extensions-in! ctx session-id cwd)
         summary          (merge-startup-summary summary-base summary-updates)
         _                (dispatch/dispatch! ctx :session/set-startup-bootstrap-summary {:session-id session-id :summary summary} {:origin :core})
         _                (bootstrap/register-all-domains!)
         graph-caps       (graph-capabilities-in ctx session-id)
         build-opts       (assoc base-prompt-opts :graph-capabilities graph-caps)
         system-prompt    (sys-prompt/build-system-prompt build-opts)
         _                (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt system-prompt} {:origin :core})
         _                (dispatch/dispatch! ctx :session/set-system-prompt-build-opts
                                              {:session-id session-id :opts (dissoc build-opts :prompt-mode)}
                                              {:origin :core})
         _                (memory-runtime/sync-memory-layer! (merge {:cwd cwd}
                                                                    (or memory-runtime-opts {})))
         _                (runtime/register-extension-run-fn-in! ctx session-id nil ai-model)
         startup-rehydrate (startup-rehydrate-from-current-session! ctx session-id nil ai-model)]
     (doseq [{:keys [path error]} (:extension-errors summary)]
       (timbre/warn "Extension error:" path error))
     (when (pos? (:extension-loaded-count summary))
       (timbre/debug "Extensions loaded:" (:extension-loaded-count summary)))
     {:ctx               ctx
      :templates         templates
      :skills            skills
      :summary           summary
      :startup-rehydrate startup-rehydrate
      :cwd               cwd})))

;; ============================================================
;; Main prompt loop
;; ============================================================

(defn- complete-cli-login!
  [oauth-ctx {:keys [provider url login-state uses-callback-server]}]
  (println "\n── OAuth Login ────────────────────────")
  (println (str "  Open: " url "\n"))
  (if uses-callback-server
    (do
      (println "  Waiting for browser callback…")
      (try
        (oauth/complete-login! oauth-ctx (:id provider) nil login-state)
        (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
        (catch Exception e
          (println (str "\n  ✗ Login failed: " (ex-message e) "\n")))))
    (do
      (print "  Paste authorization code: ")
      (flush)
      (when-let [code (try (read-line) (catch Exception _ nil))]
        (try
          (oauth/complete-login! oauth-ctx (:id provider) (str/trim code) login-state)
          (println (str "\n  ✓ Logged in to " (:name provider) "\n"))
          (catch Exception e
            (println (str "\n  ✗ Login failed: " (ex-message e) "\n"))))))))

(defn- run-cli-prompt! [ctx sid ai-ctx ai-model trimmed]
  (try
    (run-prompt! ctx sid ai-ctx ai-model trimmed)
    (catch Exception e
      (println (str "\n[Error: " (ex-message e) "]\n")))))

(defn- handle-cli-command-result!
  [oauth-ctx result]
  (case (:type result)
    :quit false
    :resume (do (output/print-command-message! "  /resume is only available in TUI mode (--tui).") true)
    (:text :new-session :logout)
    (do
      (when (= :new-session (:type result))
        (output/print-initial-transcript! (:rehydrate result)))
      (output/print-command-message! (:message result))
      true)
    :login-start (do (complete-cli-login! oauth-ctx result) true)
    :login-error (do (output/print-command-message! (str "  " (:message result))) true)
    :extension-cmd
    (do
      (try
        (when-let [handler (:handler result)]
          (let [result*  (atom nil)
                captured (with-out-str
                           (reset! result* (handler (:args result))))
                returned @result*]
            (cond
              (and (string? returned) (not (str/blank? returned)))
              (output/print-command-message! returned)

              (and (map? returned)
                   (string? (:message returned))
                   (not (str/blank? (:message returned))))
              (output/print-command-message! (:message returned))

              (not (str/blank? captured))
              (output/print-command-message! (str/trimr captured)))))
        (catch Exception e
          (println (str "\n[Command error: " (ex-message e) "]\n"))))
      true)
    (do
      (output/print-command-message! result)
      true)))

(defn- cli-command-opts
  [ctx cli-focus* ai-ctx ai-model oauth-ctx]
  {:oauth-ctx oauth-ctx
   :ai-model ai-model
   :supports-session-tree? false
   :on-new-session! (fn [_source-session-id]
                      (let [source-session-id @cli-focus*
                            result             (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model)]
                        (reset! cli-focus* (:session-id result))
                        result))})

(defn- run-cli-loop!
  [ctx cli-focus* ai-ctx ai-model oauth-ctx cmd-opts]
  (loop []
    (print "刀: ")
    (flush)
    (when-let [line (try (read-line) (catch Exception _ nil))]
      (let [trimmed (str/trim line)
            sid     @cli-focus*
            result  (when-not (str/blank? trimmed)
                      (commands/dispatch-in ctx sid trimmed cmd-opts))]
        (when result
          (runtime/journal-user-message-in! ctx sid trimmed nil))
        (cond
          (str/blank? trimmed)
          (recur)

          (nil? result)
          (do
            (run-cli-prompt! ctx sid ai-ctx ai-model trimmed)
            (recur))

          (handle-cli-command-result! oauth-ctx result)
          (recur)

          :else
          (println "\nψ: Goodbye.\n"))))))

(defn run-session
  "Create a session and enter the interactive prompt loop.
  Returns when the user exits.

  startup-opts:
  - :thinking-level-override explicit thinking level keyword (overrides config)"
  ([model-key]
   (run-session model-key {} {} {}))
  ([model-key memory-runtime-opts]
   (run-session model-key memory-runtime-opts {} {}))
  ([model-key memory-runtime-opts session-config]
   (run-session model-key memory-runtime-opts session-config {}))
  ([model-key memory-runtime-opts session-config startup-opts]
   (let [ai-model   (resolve-model model-key)
         ai-ctx     nil
         {:keys [ctx oauth-ctx session-id]}
         (create-runtime-session-context ai-model {:session-config          session-config
                                                   :ui-type                 :console
                                                   :thinking-level-override (:thinking-level-override startup-opts)})
         {:keys [templates skills startup-rehydrate]}
         (bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts memory-runtime-opts})
         cli-focus* (atom session-id)
         cmd-opts   (cli-command-opts ctx cli-focus* ai-ctx ai-model oauth-ctx)]
     (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                            :oauth-ctx oauth-ctx
                            :nrepl-runtime-atom nrepl-runtime})
     (output/print-banner ai-model templates skills ctx)
     (output/print-initial-transcript! startup-rehydrate)
     (run-cli-loop! ctx cli-focus* ai-ctx ai-model oauth-ctx cmd-opts))))

;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn new-session-with-startup-in!
  "Public helper for runtimes/tests: create new session and run startup prompts.
   Returns rehydrate payload map with :agent-messages + TUI projection."
  [ctx source-session-id ai-ctx ai-model]
  (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model))

(defn start-tui-runtime!
  "Create a session and run it with a provided TUI interface function.
   The caller supplies resolved runtime config; this namespace stays CLI-free.

   startup-opts:
   - :thinking-level-override explicit thinking level keyword (overrides config)"
  ([tui-start-fn! model-key]
   (start-tui-runtime! tui-start-fn! model-key {} {} {}))
  ([tui-start-fn! model-key memory-runtime-opts]
   (start-tui-runtime! tui-start-fn! model-key memory-runtime-opts {} {}))
  ([tui-start-fn! model-key memory-runtime-opts session-config]
   (start-tui-runtime! tui-start-fn! model-key memory-runtime-opts session-config {}))
  ([tui-start-fn! model-key memory-runtime-opts session-config startup-opts]
   (let [ai-model    (resolve-model model-key)
         ai-ctx      nil
         event-queue (java.util.concurrent.LinkedBlockingQueue.)
         {:keys [ctx oauth-ctx cwd session-id]}
         (create-runtime-session-context ai-model {:event-queue             event-queue
                                                   :session-config          session-config
                                                   :ui-type                 :tui
                                                   :thinking-level-override (:thinking-level-override startup-opts)})
         {:keys [startup-rehydrate]}
         (bootstrap-runtime-session! ctx session-id ai-model {:memory-runtime-opts memory-runtime-opts
                                                              :cwd cwd})

         ;; TUI-local focus atom — tracks active session-id
         tui-focus* (atom session-id)

         ;; Expose state for nREPL introspection
         _         (reset! session-state {:ctx ctx :ai-ctx ai-ctx :ai-model ai-model
                                          :oauth-ctx oauth-ctx
                                          :nrepl-runtime-atom nrepl-runtime
                                          :tui-focus* tui-focus*})

         ;; Helper: put an immediate assistant message on the TUI queue.
         reply!    (fn [^java.util.concurrent.LinkedBlockingQueue queue text]
                     (.put queue {:kind :done
                                  :result {:role    "assistant"
                                           :content [{:type :text :text text}]}}))

         current-context-widget
         (fn [active-session-id]
           (let [snapshot (app-context/context-snapshot ctx active-session-id active-session-id)
                 widget   (context-summary/context-widget snapshot)]
             (when (:widget/visible? widget)
               {:placement (some-> (:widget/placement widget) name)
                :extension-id (:widget/extension-id widget)
                :widget-id (:widget/widget-id widget)
                :content-lines (:widget/content-lines widget)})))

         context-event!
         (fn [active-session-id]
           (.put event-queue {:type :context-updated
                              :active-session-id active-session-id
                              :session-tree-widget (current-context-widget active-session-id)}))

         ;; Resume callback used by the TUI /resume selector.
         ;; Returns TUI resume state maps to display immediately after loading.
         resume-fn! (fn [session-path]
                      (try
                        (let [current-sid @tui-focus*
                              sd          (session/resume-session-in! ctx current-sid session-path)
                              sid         (:session-id sd)
                              _           (reset! tui-focus* sid)
                              _           (context-event! sid)
                              msgs        (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                          (transcript/agent-messages->tui-resume-state msgs))
                        (catch Exception e
                          (timbre/error e "Resume failed:" session-path)
                          {:messages [{:role :assistant
                                       :text (str "✗ Resume failed: " (ex-message e))}]
                           :tool-calls {}
                           :tool-order []})))

         switch-session-fn! (fn [session-id]
                              (try
                                (let [source-session-id @tui-focus*
                                      sd                 (session/ensure-session-loaded-in! ctx source-session-id session-id)
                                      sid                (:session-id sd)
                                      _                  (reset! tui-focus* sid)
                                      _                  (context-event! sid)
                                      msgs               (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                  (transcript/agent-messages->tui-resume-state msgs))
                                (catch Exception e
                                  (timbre/error e "Session switch failed:" session-id)
                                  {:messages [{:role :assistant
                                               :text (str "✗ Session switch failed: " (ex-message e))}]
                                   :tool-calls {}
                                   :tool-order []})))

         fork-session-fn! (fn [entry-id]
                            (try
                              (let [source-session-id @tui-focus*
                                    sd                (session/fork-session-in! ctx source-session-id entry-id)
                                    sid               (:session-id sd)
                                    _                 (reset! tui-focus* sid)
                                    _                 (context-event! sid)
                                    msgs              (:messages (agent/get-data-in (ss/agent-ctx-in ctx sid)))]
                                (assoc (transcript/agent-messages->tui-resume-state msgs)
                                       :session-id sid))
                              (catch Exception e
                                (timbre/error e "Session fork failed:" entry-id)
                                {:messages [{:role :assistant
                                             :text (str "✗ Session fork failed: " (ex-message e))}]
                                 :tool-calls {}
                                 :tool-order []})))

         cmd-opts  {:oauth-ctx oauth-ctx
                    :ai-model ai-model
                    :supports-session-tree? true
                    :on-new-session! (fn [_source-session-id]
                                       (let [source-session-id @tui-focus*
                                             result             (start-new-session-with-startup! ctx source-session-id ai-ctx ai-model)]
                                         (reset! tui-focus* (:session-id result))
                                         (context-event! (:session-id result))
                                         result))}

         ;; dispatch-fn — called synchronously by the TUI on submit.
         ;; Returns a command result map, or nil if not a command.
         ;; When a login is pending (waiting for auth code), returns nil
         ;; so the input falls through to run-agent-fn! which handles it.
         frontend-action-handler-fn!
         (fn [action-result]
           (tui-frontend-actions/handle-action-result
            {:ctx ctx
             :sid @tui-focus*
             :action-result action-result
             :resolve-model-by-provider+id resolve-model-by-provider+id
             :switch-session-fn! switch-session-fn!
             :fork-session-fn! fork-session-fn!
             :set-focus! #(reset! tui-focus* %)}))

         dispatch-fn (fn [text]
                       (if (:pending-login @session-state)
                         nil  ;; fall through to run-agent-fn! for login code
                         (let [sid    @tui-focus*
                               result (tui-frontend-actions/command-result
                                       {:ctx ctx
                                        :sid sid
                                        :text text
                                        :cmd-opts cmd-opts})]
                           (tui-frontend-actions/journal-command-result!
                            {:ctx ctx :sid sid :text text :result result})
                           (when (= :login-start (:type result))
                             (if (:uses-callback-server result)
                               nil
                               (swap! session-state assoc :pending-login
                                      {:provider-id   (get-in result [:provider :id])
                                       :provider-name (get-in result [:provider :name])
                                       :login-state   (:login-state result)})))
                           result)))

         ;; Called by TUI Escape during active work.
         on-interrupt-fn! (fn [_state]
                            (let [sid @tui-focus*]
                              (session/abort-in! ctx sid)
                              {:queued-text (session/consume-queued-input-text-in! ctx sid)
                               :message "Interrupted active work."}))

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
                                 (let [sid @tui-focus*
                                       {:keys [assistant-message]}
                                       (submit-prompt-in! ctx sid ai-model text nil
                                                          {:progress-queue queue
                                                           :sync-on-git-head-change? true})]
                                   (.put queue {:kind :done :result assistant-message}))
                                 (catch Exception e
                                   (.put queue {:kind :error :message (ex-message e)})))))))]

     (tui-start-fn! (:name ai-model) run-agent-fn!
                    {:query-fn             (fn [q] (session/query-in ctx @tui-focus* q))
                     :footer-model-fn      (fn [] (footer/footer-model ctx @tui-focus*))
                     :session-selector-fn  (fn [] (ui-actions/context-session-action
                                                   (selectors/context-session-selector ctx @tui-focus*)))
                     :initial-context-session-tree-widget (current-context-widget @tui-focus*)
                     :ui-read-fn       (fn [] (projections/extension-ui-snapshot ctx))
                     :ui-dispatch-fn   (fn [event-type payload]
                                         (dispatch/dispatch! ctx event-type payload {:origin :tui}))
                     :frontend-action-handler-fn! frontend-action-handler-fn!
                     :dispatch-fn          dispatch-fn
                     :on-interrupt-fn!     on-interrupt-fn!
                     :on-queue-input-fn!   (fn [text _state]
                                             (let [sid @tui-focus*]
                                               (if (= :streaming (ss/sc-phase-in ctx sid))
                                                 (do
                                                   (session/queue-while-streaming-in! ctx sid text :steer)
                                                   {:message "Queued steering message."})
                                                 (do
                                                   (session/queue-while-streaming-in! ctx sid text :queue)
                                                   {:message "Queued follow-up message."}))))
                     :double-press-window-ms 500
                     :double-escape-action :none
                     :cwd                  cwd
                     :focus-session-id     @tui-focus*
                     :current-session-file (:session-file (ss/get-session-data-in ctx @tui-focus*))
                     :initial-messages     (vec (or (:messages startup-rehydrate) []))
                     :initial-tool-calls   (or (:tool-calls startup-rehydrate) {})
                     :initial-tool-order   (vec (or (:tool-order startup-rehydrate) []))
                     :resume-fn!           resume-fn!
                     :switch-session-fn!   switch-session-fn!
                     :fork-session-fn!     fork-session-fn!
                     :event-queue          event-queue
                     :alt-screen           false}))))

;; RPC runtime moved to psi.rpc.
