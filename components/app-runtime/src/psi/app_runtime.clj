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

   Env:
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
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.extensions :as extensions]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.session-state.state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.ui-capabilities :as ui-capabilities]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.app-runtime.background-job-ui :as background-job-ui]
   [psi.app-runtime.cli :as cli]
   [psi.app-runtime.nrepl-runtime :as app-nrepl]
   [psi.app-runtime.output :as output]
   [psi.app-runtime.transcript :as transcript]
   [psi.app-runtime.tui-session-nav :as tui-session-nav]
   [psi.app-runtime.tui-wiring :as tui-wiring]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.shared-config.resolution :as config-res]
   [psi.prompt-assets.skills :as skills]
   [psi.session-state.model :as session-data]
   [psi.prompt-assets.system-prompt :as sys-prompt]
   [psi.agent-session.tools :as tools]
   [psi.agent-core.core :as agent]
   [psi.ai.models :as models]
   [psi.ai.model-registry :as model-registry]
   [psi.system-bootstrap.core :as bootstrap]
   [psi.memory.runtime :as memory-runtime]
   [psi.recursion.core :as recursion])
   ;; [psi.tui.app :as tui-app]  ; Removed - circular dependency fix

  (:gen-class))

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
  "Return an ai.schemas.Model map for `model-key` keyword.

   Initializes the model registry (built-in + user-global + project-local)
   before querying, so that custom models from models.edn are visible even
   when this is called before create-runtime-session-context."
  [model-key]
  (model-registry/init!
   {:user-models-path    (model-registry/default-user-models-path)
    :project-models-path (str (System/getProperty "user.dir") "/.psi/models.edn")})
  (let [all (model-registry/all-models-by-key)]
    (or (get all model-key)
        (throw (ex-info (str "Unknown model: " model-key
                             "
Available: " (str/join ", " (map name (keys all))))
                        {:model-key model-key})))))

(defn- resolve-model-by-provider+id
  "Find a runtime model map by provider string + model-id string.
   Auth-aware so runtime execution can select the correct transport variant."
  [ctx provider model-id]
  (let [provider* (some-> provider keyword)]
    (or (model-registry/resolve-runtime-model ctx provider* model-id)
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
          (resolve-model-by-provider+id ctx provider id))
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
                   (session/dispatch-in! ctx :session/set-model
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

(defn start-new-session-with-startup!
  "Create a fresh session branch and return rehydrate payload.

   Snapshots the new session's transcript/tool state after bootstrap.
   Reloads project-local custom models from the new session's worktree path so
   that models.edn changes are picked up when switching between sessions with
   different worktree paths.

   Returns map:
   {:session-id     string
    :agent-messages [...]
    :messages [...]
    :tool-calls {...}
    :tool-order [...]}"
  [ctx source-session-id _ai-ctx ai-model]
  (let [sd            (session/new-session-in! ctx source-session-id {})
        sid           (:session-id sd)
        worktree-path (ss/session-worktree-path-in ctx sid)]
    (model-registry/load-project-models!
     (str worktree-path "/.psi/models.edn")
     (model-registry/default-user-models-path))
    (assoc (startup-rehydrate-from-current-session! ctx sid _ai-ctx ai-model)
           :session-id sid)))

(defn create-runtime-session-context
  "Create a live runtime/session context with runtime state prepared, but without
   creating the initial session.

   Options:
   - :event-queue optional TUI/RPC event queue
   - :session-config optional session config overrides (merged with defaults)
   - :cwd optional cwd override (primarily for tests)
   - :ui-type runtime UI type hint (:console | :tui | :emacs)
   - :thinking-level-override explicit thinking level (CLI/env); overrides config when set
   - :persist? optional persistence toggle (defaults true; primarily for tests)
   - :session-root optional explicit persisted session root (primarily for tests)
   - :install-default-ui-capability-provider? optional context default UI provider toggle (defaults true)"
  [ai-model {:keys [event-queue session-config cwd ui-type thinking-level-override persist? session-root install-default-ui-capability-provider?]}]
  (let [cwd                      (or cwd (System/getProperty "user.dir"))
        ;; Initialize model registry with user-global + project-local custom models
        _                        (model-registry/init!
                                  {:user-models-path    (model-registry/default-user-models-path)
                                   :project-models-path (str cwd "/.psi/models.edn")})
        oauth-ctx                (oauth/create-context)
        cfg                      (config-res/resolve-config cwd)
        effective-model          (if-let [{:keys [provider id]} (config-res/resolved-model cfg)]
                                   ;; oauth-ctx not yet created here; config-time resolution stays catalog-based
                                   (or (model-registry/resolve-runtime-model nil provider id) ai-model)
                                   ai-model)
        effective-thinking-level (session-data/clamp-thinking-level
                                  (or thinking-level-override
                                      (config-res/resolved-thinking-level cfg))
                                  {:reasoning (:supports-reasoning effective-model)})
        effective-prompt-mode    (config-res/resolved-prompt-mode cfg)
        resolved-speed-mode      (config-res/resolved-speed-mode cfg)
        resolved-effort-override (config-res/resolved-effort-override cfg)
        nucleus-prelude-override (config-res/resolved-nucleus-prelude-override cfg)
        session-defaults         (cond-> {:model {:provider  (name (:provider effective-model))
                                                  :id        (:id effective-model)
                                                  :reasoning (:supports-reasoning effective-model)}
                                          :thinking-level           effective-thinking-level
                                          :prompt-mode              effective-prompt-mode
                                          :nucleus-prelude-override nucleus-prelude-override
                                          :ui-type                  (or ui-type :console)}
                                   (:present? resolved-speed-mode)
                                   (assoc :speed-mode (:value resolved-speed-mode))
                                   (:present? resolved-effort-override)
                                   (assoc :effort-override (:value resolved-effort-override)))
        ctx                      (session/create-context
                                  {:session-defaults session-defaults
                                   :config session-config
                                   :event-queue event-queue
                                   :oauth-ctx oauth-ctx
                                   :nrepl-runtime-atom nrepl-runtime
                                   :persist? (if (some? persist?) persist? true)
                                   :session-root session-root
                                   :ui-type ui-type
                                   :install-default-ui-capability-provider? (if (some? install-default-ui-capability-provider?)
                                                                              install-default-ui-capability-provider?
                                                                              true)
                                   :mutations mutations/all-mutations})
        recursion-ctx            (recursion/create-hosted-context ctx (ss/state-path :recursion))
        ctx                      (assoc ctx :recursion-ctx recursion-ctx)
        _                        (when-not (sa/recursion-state-in ctx)
                                   (sa/set-recursion-state-in! ctx nil (recursion/initial-state)))]
    {:ctx       ctx
     :oauth-ctx oauth-ctx
     :cwd       cwd}))

(defn- build-startup-plan
  "Assemble pre-session startup inputs without creating or mutating a live session."
  [ctx {:keys [cwd]}]
  (let [templates                   (pt/discover-templates)
        {:keys [skills diagnostics]} (skills/discover-skills)
        context-files               (sys-prompt/discover-context-files cwd)
        developer-prompt            (developer-prompt-from-env)
        {:keys [prompt-mode nucleus-prelude-override]} (:session-defaults ctx)
        base-tools                  (vec tools/all-tools)]
    {:cwd                      cwd
     :templates                templates
     :skills                   skills
     :diagnostics              diagnostics
     :context-files            context-files
     :developer-prompt         developer-prompt
     :developer-prompt-source  (if developer-prompt :env :fallback)
     :prompt-mode              (or prompt-mode :lambda)
     :nucleus-prelude-override nucleus-prelude-override
     :base-tools               base-tools}))

(defn- create-initial-startup-session!
  [ctx]
  (:session-id (session/new-session-in! ctx nil {})))

(defn- log-startup-plan-diagnostics!
  [startup-plan]
  (doseq [d (:diagnostics startup-plan)]
    (timbre/warn "Skill" (:type d) ":" (:message d) (:path d))))

(defn- make-session-scoped-psi-tool
  [ctx session-id cwd]
  (tools/make-psi-tool (fn
                         ([q] (session/query-in ctx session-id q))
                         ([q entity] (session/query-in ctx q entity)))
                       {:ctx ctx
                        :session-id session-id
                        :cwd cwd}))

(defn- startup-base-prompt-opts
  [ctx {:keys [cwd prompt-mode nucleus-prelude-override context-files skills]} tool-defs]
  {:cwd                      cwd
   :session-instant          (:started-at ctx)
   :prompt-mode              prompt-mode
   :nucleus-prelude-override nucleus-prelude-override
   :context-files            context-files
   :skills                   skills
   :tool-defs                tool-defs})

(defn- merge-tool-defs-by-name
  [base-tool-defs ext-tools]
  (->> (concat base-tool-defs ext-tools)
       (reduce (fn [acc tool]
                 (if (some #(= (:name tool) (:name %)) acc)
                   acc
                   (conj acc tool)))
               [])))

(defn- persist-system-prompt!
  [ctx session-id prompt]
  (session/dispatch-in! ctx :session/set-system-prompt {:session-id session-id :prompt prompt} {:origin :core}))

;; finalize-startup-system-prompt! removed — prompt build inlined into
;; adopt-startup-plan-into-session! (task 161: single-pass startup)

(defn- log-startup-summary!
  [summary]
  (doseq [{:keys [path error]} (:extension-errors summary)]
    (timbre/warn "Extension error:" path error))
  (when (pos? (:extension-loaded-count summary))
    (timbre/debug "Extensions loaded:" (:extension-loaded-count summary))))

(defn- build-startup-summary
  "Build a startup summary from resource-loading counts and extension results."
  [ctx session-id extension-results]
  (let [sd          (ss/get-session-data-in ctx session-id)
        ext-errors  (keep (fn [r]
                            (when-let [e (:psi.extension/error r)]
                              {:path  (:psi.extension/path r)
                               :error e}))
                          extension-results)]
    {:timestamp              (java.time.Instant/now)
     :prompt-count           (count (:prompt-templates sd))
     :skill-count            (count (:skill-ids sd))
     :tool-count             0   ;; tools not yet set at summary-build time
     :extension-loaded-count (count (filter :psi.extension/loaded? extension-results))
     :extension-error-count  (count ext-errors)
     :extension-errors       (vec ext-errors)}))

(defn- adopt-startup-plan-into-session!
  "Adopt a startup plan into an already-created session.

   Single-pass startup: each concern (prompt build, tool composition, summary
   persistence) happens exactly once. See task 161 design for the full rationale.

   Ordering:
   1. Infrastructure (background-job UI, built-in workflows, psi-tool)
   2. Seed developer-prompt into session state
   3. Load templates + skills (no tools — set-active-tools overwrites the full set)
   4. Bootstrap manifest extensions
   5. Compose final tool set (base + extension)
   6. Register domains + query graph-capabilities
   7. Build system prompt (once, with all inputs)
   8. Persist prompt, build-opts
   9. Set active tools (after build-opts so side-effect refresh rebuilds correctly)
   10. Build + persist summary (once)
   11. Memory sync, extension run fn, rehydrate"
  [ctx session-id ai-model startup-plan {:keys [memory-runtime-opts]}]
  (let [{:keys [cwd templates skills developer-prompt developer-prompt-source base-tools]} startup-plan
        ;; 1. Infrastructure
        _                  (background-job-ui/install-background-job-ui-refresh! ctx)
        _                  (workflow-bootstrap/init-built-in! ctx session-id)
        psi-tool           (make-session-scoped-psi-tool ctx session-id cwd)
        base-tool-defs     (conj base-tools psi-tool)

        ;; 2. Seed developer-prompt + developer-prompt-source into session state.
        ;; System-prompt is empty here — the real prompt is built once below after
        ;; all inputs (graph-caps, extension tools) are known.
        _                  (session/dispatch-in! ctx
                                                 :session/bootstrap-prompt-state
                                                 {:session-id              session-id
                                                  :system-prompt           ""
                                                  :developer-prompt        developer-prompt
                                                  :developer-prompt-source developer-prompt-source}
                                                 {:origin :core})

        ;; 3. Load templates + skills only. Tools are excluded because
        ;; :session/set-active-tools (step 9) replaces the full set, making
        ;; individual :session/add-tool dispatches redundant.
        _                  (session-bootstrap/load-startup-resources-in!
                            ctx session-id
                            {:templates templates
                             :skills    skills})

        ;; 4. Bootstrap manifest extensions
        {:keys [summary-updates]}
        (extension-runtime/bootstrap-manifest-extensions-in! ctx session-id cwd)

        ;; 5. Compose final tool set (base + extension, once)
        refreshed-tool-defs (merge-tool-defs-by-name
                             base-tool-defs
                             (extensions/all-tools-in (:extension-registry ctx)))

        ;; 6. Register domains + query graph-capabilities
        _                  (bootstrap/register-all-domains!)
        graph-caps         (graph-capabilities-in ctx session-id)

        ;; 7. Build system prompt (once, with all inputs)
        base-prompt-opts   (startup-base-prompt-opts ctx startup-plan base-tool-defs)
        build-opts         (assoc base-prompt-opts
                                  :graph-capabilities graph-caps
                                  :tool-defs refreshed-tool-defs)
        system-prompt      (sys-prompt/build-system-prompt build-opts)

        ;; 8. Persist prompt + build-opts
        _                  (persist-system-prompt! ctx session-id system-prompt)
        _                  (session/dispatch-in! ctx :session/set-system-prompt-build-opts
                                                 {:session-id session-id
                                                  :opts (dissoc build-opts :prompt-mode)}
                                                 {:origin :core})

        ;; 9. Set active tools — placed AFTER prompt build + build-opts persist
        ;; so the side-effect :runtime/refresh-system-prompt finds build-opts in
        ;; session state and rebuilds an equivalent prompt (not an empty one).
        _                  (session/dispatch-in! ctx
                                                 :session/set-active-tools
                                                 {:session-id session-id
                                                  :tool-maps refreshed-tool-defs}
                                                 {:origin :core})

        ;; 10. Build + persist summary (once)
        ;; build-startup-summary provides prompt/skill counts from session state;
        ;; manifest summary-updates provides extension-loaded/error counts.
        ;; merge-startup-summary combines them (scalars overwritten, seqs concatenated).
        summary-base       (build-startup-summary ctx session-id [])
        summary            (merge-startup-summary summary-base summary-updates)
        _                  (session/dispatch-in! ctx :session/set-startup-bootstrap-summary
                                                 {:session-id session-id :summary summary}
                                                 {:origin :core})

        ;; 11. Memory sync, extension run fn, rehydrate
        _                  (memory-runtime/sync-memory-layer! (merge {:cwd cwd}
                                                                     (or memory-runtime-opts {})))
        _                  (runtime/register-extension-run-fn-in! ctx session-id nil ai-model)
        startup-rehydrate  (startup-rehydrate-from-current-session! ctx session-id nil ai-model)]
    (log-startup-summary! summary)
    {:ctx               ctx
     :session-id        session-id
     :templates         templates
     :skills            skills
     :summary           summary
     :startup-plan      startup-plan
     :startup-rehydrate startup-rehydrate
     :cwd               cwd}))

(defn bootstrap-runtime-session!
  "Bootstrap a live session context shared by CLI/TUI/RPC modes.

   Builds a pre-session startup plan, creates (or reuses) the initial session,
   and adopts the startup plan into it.

   Options:
   - :session-id optional pre-created session-id (defaults to creating a new one)
   - :memory-runtime-opts optional memory/runtime sync opts
   - :cwd optional cwd override (primarily for tests)"
  [ctx ai-model opts]
  (let [cwd          (or (:cwd opts) (:cwd ctx) (System/getProperty "user.dir"))
        startup-plan (build-startup-plan ctx {:cwd cwd})
        _            (log-startup-plan-diagnostics! startup-plan)
        session-id   (or (:session-id opts) (create-initial-startup-session! ctx))]
    (adopt-startup-plan-into-session! ctx session-id ai-model startup-plan opts)))

;; ============================================================
;; Main prompt loop
;; ============================================================

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
         {:keys [ctx oauth-ctx]}
         (create-runtime-session-context ai-model {:session-config          session-config
                                                   :ui-type                 :console
                                                   :persist?                false
                                                   :thinking-level-override (:thinking-level-override startup-opts)})
         {:keys [templates skills startup-rehydrate session-id]}
         (bootstrap-runtime-session! ctx ai-model {:memory-runtime-opts memory-runtime-opts})
         cli-focus* (atom session-id)
         cmd-opts   (cli/cli-command-opts start-new-session-with-startup! ctx cli-focus* nil ai-model oauth-ctx)]
     (reset! session-state {:ctx ctx :ai-model ai-model
                            :oauth-ctx oauth-ctx
                            :nrepl-runtime-atom nrepl-runtime})
     (output/print-banner ai-model templates skills ctx)
     (output/print-initial-transcript! startup-rehydrate)
     (cli/run-cli-loop! run-prompt! runtime/journal-user-message-in! ctx cli-focus* nil ai-model oauth-ctx cmd-opts))))

;; TUI session (charm.clj Elm Architecture)
;; ============================================================

(defn- nullable-execution-mode
  "Read PSI_NULLABLE_EXECUTION_MODE env var, trimmed and nil-punned."
  []
  (some-> (System/getenv "PSI_NULLABLE_EXECUTION_MODE") str/trim not-empty))

(defn- maybe-install-nullable-execution-mode
  "When PSI_NULLABLE_EXECUTION_MODE=deterministic, install a stub executor that
   echoes user text back as the assistant response. Used by TUI integration test
   harness to run without a real AI provider."
  [ctx]
  (let [mode (nullable-execution-mode)]
    (if (= "deterministic" mode)
      (assoc ctx :execute-prepared-request-fn
             (fn [_ai-ctx _ctx sid prepared-request _progress-queue]
               (let [user-text (or (get-in prepared-request [:prepared-request/user-message :content 0 :text]) "")]
                 {:execution-result/turn-id (or (:prepared-request/id prepared-request)
                                                (str (java.util.UUID/randomUUID)))
                  :execution-result/session-id sid
                  :execution-result/assistant-message {:role "assistant" :content [{:type :text :text user-text}]
                                                       :stop-reason :stop :timestamp (java.time.Instant/now)}
                  :execution-result/turn-outcome :turn.outcome/stop
                  :execution-result/tool-calls []
                  :execution-result/stop-reason :stop})))
      ctx)))

(defn start-tui-runtime!
  "Create a session and run it with a provided TUI interface function.
   The caller supplies resolved runtime config; this namespace stays CLI-free.

   Runtime setup (model resolution, context creation, session bootstrap) happens
   here; TUI callback construction and options assembly are delegated to
   `psi.app-runtime.tui-wiring`.

   startup-opts:
   - :thinking-level-override explicit thinking level keyword (overrides config)
   - :cwd optional startup worktree/cwd override (primarily for tests/harnesses)
   - :session-root optional persisted session root override (primarily for tests/harnesses)"
  ([tui-start-fn! model-key]
   (start-tui-runtime! tui-start-fn! model-key {} {} {}))
  ([tui-start-fn! model-key memory-runtime-opts]
   (start-tui-runtime! tui-start-fn! model-key memory-runtime-opts {} {}))
  ([tui-start-fn! model-key memory-runtime-opts session-config]
   (start-tui-runtime! tui-start-fn! model-key memory-runtime-opts session-config {}))
  ([tui-start-fn! model-key memory-runtime-opts session-config startup-opts]
   (let [ai-model    (resolve-model model-key)
         event-queue (java.util.concurrent.LinkedBlockingQueue.)
         {:keys [ctx oauth-ctx cwd]}
         (create-runtime-session-context ai-model {:event-queue             event-queue
                                                   :session-config          session-config
                                                   :cwd                     (:cwd startup-opts)
                                                   :session-root            (:session-root startup-opts)
                                                   :ui-type                 :tui
                                                   :persist?                true
                                                   :thinking-level-override (:thinking-level-override startup-opts)
                                                   :install-default-ui-capability-provider? false})
         ctx (maybe-install-nullable-execution-mode ctx)
         {:keys [startup-rehydrate session-id]}
         (bootstrap-runtime-session! ctx ai-model {:memory-runtime-opts memory-runtime-opts
                                                   :cwd cwd})
         tui-focus* (atom session-id)]
     (reset! session-state {:ctx ctx :ai-model ai-model
                            :oauth-ctx oauth-ctx
                            :nrepl-runtime-atom nrepl-runtime
                            :tui-focus* tui-focus*})
     (let [context-event!     (partial tui-session-nav/context-event! ctx event-queue)
           resume-fn!         (tui-session-nav/resume-fn! ctx tui-focus* event-queue)
           switch-session-fn! (tui-session-nav/switch-session-fn! ctx tui-focus* event-queue)
           fork-session-fn!   (tui-session-nav/fork-session-fn! ctx tui-focus* event-queue)
           cmd-opts           {:oauth-ctx oauth-ctx
                               :ai-model ai-model
                               :supports-session-tree? true
                               ;; Intentionally reads @tui-focus* rather than using the
                               ;; callback parameter — the TUI always forks from the
                               ;; currently focused session, which may differ from the
                               ;; session that dispatched the /new command.
                               :on-new-session! (fn [_source-session-id]
                                                  (let [source-session-id @tui-focus*
                                                        result             (start-new-session-with-startup! ctx source-session-id nil ai-model)]
                                                    (reset! tui-focus* (:session-id result))
                                                    (context-event! (:session-id result))
                                                    result))}
           wiring-deps        {:ctx ctx
                               :tui-focus* tui-focus*
                               :session-state session-state
                               :ai-model ai-model
                               :oauth-ctx oauth-ctx
                               :resolve-model-by-provider+id resolve-model-by-provider+id
                               :switch-session-fn! switch-session-fn!
                               :fork-session-fn! fork-session-fn!
                               :submit-prompt-fn! submit-prompt-in!
                               :cmd-opts cmd-opts}
           dispatch-fn        (tui-wiring/make-dispatch-fn wiring-deps)
           run-agent-fn       (tui-wiring/make-run-agent-fn wiring-deps)
           on-interrupt-fn!   (tui-wiring/make-on-interrupt-fn wiring-deps)
           frontend-action-handler-fn!
           (tui-wiring/make-frontend-action-handler-fn wiring-deps)
           tui-opts           (tui-wiring/build-tui-opts
                               {:ctx ctx
                                :tui-focus* tui-focus*
                                :event-queue event-queue
                                :cwd cwd
                                :startup-rehydrate startup-rehydrate
                                :dispatch-fn dispatch-fn
                                :on-interrupt-fn! on-interrupt-fn!
                                :frontend-action-handler-fn! frontend-action-handler-fn!
                                :resume-fn! resume-fn!
                                :switch-session-fn! switch-session-fn!
                                :fork-session-fn! fork-session-fn!
                                :current-context-widget (tui-session-nav/current-context-widget ctx session-id)})]
       (ui-capabilities/install-provider! ctx (ui-capabilities/unsupported-attached-provider :tui))
       (try
         (tui-start-fn! run-agent-fn tui-opts)
         (finally
           (ui-capabilities/clear-provider! ctx)))))))
;; RPC runtime moved to psi.rpc.
