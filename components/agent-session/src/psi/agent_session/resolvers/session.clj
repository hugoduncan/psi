(ns psi.agent-session.resolvers.session
  "Session-focused Pathom3 resolvers extracted from the main agent-session resolver namespace."
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-core.core :as agent]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.resolvers.support :as support]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as accessors]
   [psi.agent-session.statechart :as sc]
   [psi.ai.models :as ai-models]
   [psi.ai.model-registry :as model-registry]
   [psi.history.git :as git]))

;; ── Core session fields ─────────────────────────────────

(pco/defresolver agent-session-identity
  "Resolve stable identity, naming, and context session registry fields.
   Note: :context-active-session-id removed — adapters (RPC, TUI) own focus locally."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/session-id
                 :psi.agent-session/session-file
                 :psi.agent-session/session-name
                 :psi.agent-session/session-display-name
                 :psi.agent-session/context-session-count
                 {:psi.agent-session/context-sessions
                  [:psi.session-info/id
                   :psi.session-info/path
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/display-name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created]}]}
  (let [resolver-sid session-id
        sd           (support/session-data agent-session-ctx session-id)
        hs           (ss/list-context-sessions-in agent-session-ctx)
        messages     (when resolver-sid
                       (support/agent-core-messages agent-session-ctx session-id))]
    {:psi.agent-session/session-id                 (:session-id sd)
     :psi.agent-session/session-file               (:session-file sd)
     :psi.agent-session/session-name               (:session-name sd)
     :psi.agent-session/session-display-name       (message-text/session-display-name
                                                    (:session-name sd)
                                                    messages)
     :psi.agent-session/context-session-count      (count hs)
     :psi.agent-session/context-sessions
     (mapv (fn [m]
             {:psi.session-info/id                  (:session-id m)
              :psi.session-info/path                (:session-file m)
              :psi.session-info/worktree-path       (:worktree-path m)
              :psi.session-info/name                (:session-name m)
              :psi.session-info/display-name        (:display-name m)
              :psi.session-info/parent-session-id   (:parent-session-id m)
              :psi.session-info/parent-session-path (:parent-session-path m)
              :psi.session-info/created             (:created-at m)})
           hs)}))

;; ── Phase and streaming state ───────────────────────────

(pco/defresolver agent-session-phase
  "Resolve statechart phase and derived boolean flags."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/phase
                 :psi.agent-session/is-streaming
                 :psi.agent-session/is-compacting
                 :psi.agent-session/is-idle]}
  (let [sc-env        (:sc-env agent-session-ctx)
        sc-session-id (ss/sc-session-id-in agent-session-ctx session-id)
        phase         (sc/sc-phase sc-env sc-session-id)]
    {:psi.agent-session/phase         phase
     :psi.agent-session/is-streaming  (= phase :streaming)
     :psi.agent-session/is-compacting (= phase :compacting)
     :psi.agent-session/is-idle       (= phase :idle)}))

;; ── Model and thinking level ────────────────────────────

(pco/defresolver agent-session-model
  "Resolve model, thinking level, prompt mode, and UI type."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/model
                 :psi.agent-session/thinking-level
                 :psi.agent-session/prompt-mode
                 :psi.agent-session/ui-type]}
  (let [sd (support/session-data agent-session-ctx session-id)]
    {:psi.agent-session/model          (:model sd)
     :psi.agent-session/thinking-level (:thinking-level sd)
     :psi.agent-session/prompt-mode    (:prompt-mode sd)
     :psi.agent-session/ui-type        (:ui-type sd)}))

;; ── Queues and message counts ───────────────────────────

(defn- prompt-summary-fields
  [sd]
  (let [prepared  (:last-prepared-request-summary sd)
        executed  (:last-execution-result-summary sd)]
    {:psi.agent-session/last-prepared-request-summary prepared
     :psi.agent-session/last-prepared-turn-id         (:turn-id prepared)
     :psi.agent-session/last-prepared-message-count   (:message-count prepared)
     :psi.agent-session/last-prepared-tool-count      (:tool-count prepared)
     :psi.agent-session/last-prepared-system-prompt-chars (:system-prompt-chars prepared)
     :psi.agent-session/last-prepared-input-expansion (:input-expansion prepared)
     :psi.agent-session/last-prepared-at              (:prepared-at prepared)
     :psi.agent-session/last-execution-result-summary executed
     :psi.agent-session/last-execution-turn-id        (:turn-id executed)
     :psi.agent-session/last-execution-turn-outcome   (:turn-outcome executed)
     :psi.agent-session/last-execution-stop-reason    (:stop-reason executed)
     :psi.agent-session/last-execution-tool-call-count (:tool-call-count executed)
     :psi.agent-session/last-execution-recorded-at    (:recorded-at executed)}))

(pco/defresolver agent-session-queues
  "Resolve queue depths, prompt layers, and prompt lifecycle summaries."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/base-system-prompt
                 :psi.agent-session/system-prompt
                 :psi.agent-session/developer-prompt
                 :psi.agent-session/developer-prompt-source
                 :psi.agent-session/prompt-layers
                 :psi.agent-session/prompt-contributions
                 :psi.agent-session/pending-message-count
                 :psi.agent-session/has-pending-messages
                 :psi.agent-session/steering-messages
                 :psi.agent-session/follow-up-messages
                 :psi.agent-session/last-prepared-request-summary
                 :psi.agent-session/last-prepared-turn-id
                 :psi.agent-session/last-prepared-message-count
                 :psi.agent-session/last-prepared-tool-count
                 :psi.agent-session/last-prepared-system-prompt-chars
                 :psi.agent-session/last-prepared-input-expansion
                 :psi.agent-session/last-prepared-at
                 :psi.agent-session/last-execution-result-summary
                 :psi.agent-session/last-execution-turn-id
                 :psi.agent-session/last-execution-turn-outcome
                 :psi.agent-session/last-execution-stop-reason
                 :psi.agent-session/last-execution-tool-call-count
                 :psi.agent-session/last-execution-recorded-at]}
  (let [sd         (support/session-data agent-session-ctx session-id)
        base       (:base-system-prompt sd)
        sys        (:system-prompt sd)
        dev        (:developer-prompt sd)
        dev-source (:developer-prompt-source sd)
        contribs   (vec (:prompt-contributions sd))]
    (merge
     {:psi.agent-session/base-system-prompt      base
      :psi.agent-session/system-prompt           sys
      :psi.agent-session/developer-prompt        dev
      :psi.agent-session/developer-prompt-source dev-source
      :psi.agent-session/prompt-layers           {:base-system-prompt base
                                                  :system-prompt sys
                                                  :developer-prompt dev
                                                  :developer-prompt-source dev-source
                                                  :prompt-contributions (mapv support/contribution->attrs contribs)}
      :psi.agent-session/prompt-contributions    (mapv support/contribution->attrs contribs)
      :psi.agent-session/pending-message-count   (session/pending-message-count sd)
      :psi.agent-session/has-pending-messages    (session/has-pending-messages? sd)
      :psi.agent-session/steering-messages       (:steering-messages sd)
      :psi.agent-session/follow-up-messages      (:follow-up-messages sd)}
     (prompt-summary-fields sd))))

;; ── Retry and compaction config ─────────────────────────

(pco/defresolver agent-session-retry-compact
  "Resolve retry and compaction operational fields."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/retry-attempt
                 :psi.agent-session/auto-retry-enabled
                 :psi.agent-session/auto-compaction-enabled
                 :psi.agent-session/scoped-models]}
  (let [sd (support/session-data agent-session-ctx session-id)]
    {:psi.agent-session/retry-attempt           (:retry-attempt sd)
     :psi.agent-session/auto-retry-enabled      (:auto-retry-enabled sd)
     :psi.agent-session/auto-compaction-enabled (:auto-compaction-enabled sd)
     :psi.agent-session/scoped-models           (:scoped-models sd)}))

;; ── Resources ──────────────────────────────────────────

(pco/defresolver agent-session-resources
  "Resolve registered skills and prompt templates."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/skills
                 :psi.agent-session/prompt-templates]}
  (let [sd (support/session-data agent-session-ctx session-id)]
    {:psi.agent-session/skills           (:skills sd)
     :psi.agent-session/prompt-templates (:prompt-templates sd)}))

(pco/defresolver agent-session-message-history
  "Resolve the current agent-core message history for the session."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/message-history]}
  {:psi.agent-session/message-history
   (vec (or (support/agent-core-messages agent-session-ctx session-id) []))})

;; ── Extension summary ───────────────────────────────────

(pco/defresolver agent-session-extensions
  "Resolve extension registry summary and extension prompt telemetry."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/extension-summary
                 :psi.agent-session/extension-last-prompt-source
                 :psi.agent-session/extension-last-prompt-delivery
                 :psi.agent-session/extension-last-prompt-at]}
  (let [sd (support/session-data agent-session-ctx session-id)]
    {:psi.agent-session/extension-summary              (ext/summary-in (:extension-registry agent-session-ctx))
     :psi.agent-session/extension-last-prompt-source   (:extension-last-prompt-source sd)
     :psi.agent-session/extension-last-prompt-delivery (:extension-last-prompt-delivery sd)
     :psi.agent-session/extension-last-prompt-at       (:extension-last-prompt-at sd)}))

(pco/defresolver agent-session-context-usage
  "Resolve context token usage and fraction."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/context-tokens
                 :psi.agent-session/context-window
                 :psi.agent-session/context-fraction]}
  (let [sd (support/session-data agent-session-ctx session-id)]
    {:psi.agent-session/context-tokens   (:context-tokens sd)
     :psi.agent-session/context-window   (:context-window sd)
     :psi.agent-session/context-fraction (session/context-fraction-used sd)}))

;; ── Footer data ───────────────────────────────────────

(defn- usage-number
  [usage k1 k2]
  (let [v (or (get usage k1) (get usage k2) 0)]
    (if (number? v) v 0)))

(defn- usage-cost-total
  [usage]
  (let [v (or (get-in usage [:cost :total])
              (:cost-total usage)
              0.0)]
    (if (number? v) v 0.0)))

(defn- session-usage-totals
  [agent-session-ctx session-id]
  (reduce
   (fn [acc entry]
     (let [msg       (get-in entry [:data :message])
           entry-sid (:session-id entry)]
       (if (and (= :message (:kind entry))
                (or (nil? entry-sid)
                    (= session-id entry-sid))
                (= "assistant" (:role msg))
                (map? (:usage msg)))
         (let [u (:usage msg)]
           (-> acc
               (update :input + (usage-number u :input-tokens :input))
               (update :output + (usage-number u :output-tokens :output))
               (update :cache-read + (usage-number u :cache-read-tokens :cache-read))
               (update :cache-write + (usage-number u :cache-write-tokens :cache-write))
               (update :cost + (usage-cost-total u))))
         acc)))
   {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
   (session/get-state-value-in agent-session-ctx (session/state-path :journal session-id))))

(defn- find-git-head-path
  [cwd]
  (loop [dir (when cwd (java.io.File. cwd))]
    (when dir
      (let [git-path (java.io.File. dir ".git")]
        (if (.exists git-path)
          (try
            (cond
              (.isDirectory git-path)
              (let [head (java.io.File. git-path "HEAD")]
                (when (.exists head)
                  (.getAbsolutePath head)))

              (.isFile git-path)
              (let [content (str/trim (slurp git-path))]
                (when (str/starts-with? content "gitdir: ")
                  (let [git-dir (subs content 8)
                        head    (java.io.File. (java.io.File. dir git-dir) "HEAD")]
                    (when (.exists head)
                      (.getAbsolutePath head)))))

              :else nil)
            (catch Exception _ nil))
          (recur (.getParentFile dir)))))))

(defn- git-branch-from-cwd
  [cwd]
  (try
    (when-let [head-path (find-git-head-path cwd)]
      (let [content (str/trim (slurp head-path))]
        (if (str/starts-with? content "ref: refs/heads/")
          (subs content 16)
          "detached")))
    (catch Exception _ nil)))

(pco/defresolver agent-session-cwd
  "Resolve the session worktree path for the current session context.
   Sessions must carry an explicit worktree-path."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/worktree-path]}
  {:psi.agent-session/worktree-path (support/session-worktree-path agent-session-ctx session-id)})

(pco/defresolver agent-session-git-branch
  "Resolve current git branch for the explicit session worktree path.
   Returns nil outside git repos and \"detached\" for detached HEAD."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/git-branch]}
  (let [cwd (support/session-worktree-path agent-session-ctx session-id)]
    {:psi.agent-session/git-branch (git-branch-from-cwd cwd)}))

(pco/defresolver runtime-nrepl-info
  "Expose runtime nREPL endpoint information from canonical runtime metadata on session context.
   Returns nil attrs when nREPL is not running/registered."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.runtime/nrepl-host
                 :psi.runtime/nrepl-port
                 :psi.runtime/nrepl-endpoint]}
  (let [runtime* (accessors/nrepl-runtime-in agent-session-ctx)
        host     (:host runtime*)
        port     (:port runtime*)
        endpoint (or (:endpoint runtime*)
                     (when (and (string? host) (integer? port))
                       (str host ":" port)))]
    {:psi.runtime/nrepl-host host
     :psi.runtime/nrepl-port port
     :psi.runtime/nrepl-endpoint endpoint}))

(pco/defresolver agent-session-git-context
  "Bridge resolver: derive :git/context from the explicit session worktree path so
   history resolvers can be queried from in-session psi-tool roots."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:git/context]}
  (let [cwd (support/session-worktree-path agent-session-ctx session-id)]
    (if (seq cwd)
      {:git/context (git/create-context cwd)}
      {})))

(pco/defresolver agent-session-usage
  "Resolve cumulative token usage and cost across assistant messages in the session journal.
   Single journal scan produces all five usage attrs."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/usage-input
                 :psi.agent-session/usage-output
                 :psi.agent-session/usage-cache-read
                 :psi.agent-session/usage-cache-write
                 :psi.agent-session/usage-cost-total]}
  (let [totals (session-usage-totals agent-session-ctx session-id)]
    {:psi.agent-session/usage-input       (:input totals)
     :psi.agent-session/usage-output      (:output totals)
     :psi.agent-session/usage-cache-read  (:cache-read totals)
     :psi.agent-session/usage-cache-write (:cache-write totals)
     :psi.agent-session/usage-cost-total  (:cost totals)}))

(pco/defresolver agent-session-model-provider
  "Resolve active model provider string from session state."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/model-provider]}
  {:psi.agent-session/model-provider (:provider (:model (support/session-data agent-session-ctx session-id)))})

(pco/defresolver agent-session-model-id
  "Resolve active model id from session state."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/model-id]}
  {:psi.agent-session/model-id (:id (:model (support/session-data agent-session-ctx session-id)))})

(def ^:private thinking-level->reasoning-effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(defn- effective-reasoning-effort
  [model thinking-level]
  (when (:reasoning model)
    (get thinking-level->reasoning-effort thinking-level "medium")))

(pco/defresolver agent-session-model-reasoning
  "Resolve whether the active model supports reasoning and effective effort."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/model-reasoning
                 :psi.agent-session/effective-reasoning-effort]}
  (let [sd    (support/session-data agent-session-ctx session-id)
        model (:model sd)
        level (:thinking-level sd)]
    {:psi.agent-session/model-reasoning
     (boolean (:reasoning model))
     :psi.agent-session/effective-reasoning-effort
     (effective-reasoning-effort model level)}))

(defn- runtime-model-catalog
  "Return deterministic runtime model catalog for frontend selectors."
  []
  (->> (model-registry/all-models-seq)
       (map (fn [m]
              {:provider  (name (:provider m))
               :id        (:id m)
               :name      (:name m)
               :reasoning (boolean (:supports-reasoning m))}))
       (sort-by (juxt :provider :id))
       vec))

(defn- authenticated-provider-ids
  "Return provider ids with configured auth for this session context.
   Delegates oauth projection refresh to the session core API."
  [agent-session-ctx]
  (accessors/refresh-oauth-authenticated-providers-in! agent-session-ctx))

(defn- registry-configured-provider-ids
  "Return provider ids that have explicit auth configured in the model registry.
   Includes custom/local providers defined in models.edn — API-key or no-auth.
   Built-in providers (anthropic, openai) only appear here when the user
   explicitly overrides their auth via models.edn."
  []
  (->> (model-registry/all-models-seq)
       (map :provider)
       distinct
       (filter #(some? (model-registry/get-auth %)))
       (map name)
       sort
       vec))

(pco/defresolver agent-session-model-catalog
  "Resolve runtime model catalog for frontend model selectors."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-catalog]}
  (let [_ agent-session-ctx]
    {:psi.agent-session/model-catalog (runtime-model-catalog)}))

(pco/defresolver agent-session-authenticated-providers
  "Resolve provider ids with configured auth for this session.
   Merges OAuth-authenticated providers with model-registry-configured providers
   (custom/local providers from models.edn with explicit API keys or no-auth)."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/authenticated-providers
                 :psi.oauth/authenticated-providers
                 :psi.oauth/last-login-provider
                 :psi.oauth/last-login-at
                 :psi.oauth/pending-login]}
  (let [oauth-ids   (authenticated-provider-ids agent-session-ctx)
        reg-ids     (registry-configured-provider-ids)
        ids         (-> (concat oauth-ids reg-ids) distinct sort vec)
        oauth-state (or (accessors/oauth-projection-in agent-session-ctx) {})]
    {:psi.agent-session/authenticated-providers ids
     :psi.oauth/authenticated-providers         ids
     :psi.oauth/last-login-provider             (:last-login-provider oauth-state)
     :psi.oauth/last-login-at                   (:last-login-at oauth-state)
     :psi.oauth/pending-login                   (:pending-login oauth-state)}))

(pco/defresolver agent-session-rpc-trace
  "Resolve RPC trace runtime config for the current session transport."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/rpc-trace-enabled
                 :psi.agent-session/rpc-trace-file]}
  (let [trace-state (or (accessors/rpc-trace-state-in agent-session-ctx) {})]
    {:psi.agent-session/rpc-trace-enabled (boolean (:enabled? trace-state))
     :psi.agent-session/rpc-trace-file    (:file trace-state)}))

(pco/defresolver startup-bootstrap-resolver
  "Resolve startup bootstrap summary and derived fields."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.startup/bootstrap-summary
                 :psi.startup/bootstrap-timestamp
                 :psi.startup/prompt-count
                 :psi.startup/skill-count
                 :psi.startup/tool-count
                 :psi.startup/extension-loaded-count
                 :psi.startup/extension-error-count
                 :psi.startup/extension-errors
                 :psi.startup/mutations]}
  (let [summary (:startup-bootstrap (support/session-data agent-session-ctx session-id))]
    {:psi.startup/bootstrap-summary      summary
     :psi.startup/bootstrap-timestamp    (:timestamp summary)
     :psi.startup/prompt-count           (:prompt-count summary 0)
     :psi.startup/skill-count            (:skill-count summary 0)
     :psi.startup/tool-count             (:tool-count summary 0)
     :psi.startup/extension-loaded-count (:extension-loaded-count summary 0)
     :psi.startup/extension-error-count  (:extension-error-count summary 0)
     :psi.startup/extension-errors       (:extension-errors summary [])
     :psi.startup/mutations              (:mutations summary [])}))

(def resolvers
  [agent-session-identity
   agent-session-phase
   agent-session-model
   agent-session-queues
   agent-session-retry-compact
   agent-session-resources
   agent-session-message-history
   agent-session-extensions
   agent-session-context-usage
   agent-session-cwd
   agent-session-git-branch
   runtime-nrepl-info
   agent-session-git-context
   agent-session-usage
   agent-session-model-provider
   agent-session-model-id
   agent-session-model-reasoning
   agent-session-model-catalog
   agent-session-authenticated-providers
   agent-session-rpc-trace
   startup-bootstrap-resolver])
