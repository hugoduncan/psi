(ns psi.agent-session.prompt-request
  "Pure prompt/request projection helpers.

   This namespace is the architectural home for request preparation:
   canonical session state + journal + prompt layers -> prepared request."
  (:require
   [clojure.string :as str]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.conversation :as conv]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as prompt-templates]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as system-prompt]))

(defn journal->provider-messages
  "Project persisted journal entries into agent/provider message maps."
  [journal]
  (into []
        (keep (fn [entry]
                (when (= :message (:kind entry))
                  (get-in entry [:data :message]))))
        journal))

(defn session->provider-messages
  "Project the persisted journal for `session-id` into provider-visible messages."
  [ctx session-id]
  (journal->provider-messages
   (or (ss/get-state-value-in ctx [:agent-session :sessions session-id :persistence :journal])
       [])))

(defn- resolve-api-key
  "Resolve API key in priority order:
   1. Explicit runtime-opts :api-key
   2. Session-stored key from prior turn
   3. OAuth context
   4. Custom provider auth from model-registry"
  [ctx session-data runtime-opts]
  (or (:api-key runtime-opts)
      (:runtime-api-key session-data)
      (when-let [oauth-ctx (:oauth-ctx ctx)]
        (when-let [provider (:provider (:model session-data))]
          (oauth/get-api-key oauth-ctx provider)))
      (when-let [provider (:provider (:model session-data))]
        (let [auth (model-registry/get-auth provider)]
          (when (:auth-header? auth)
            (:api-key auth))))))

(defn- resolve-llm-stream-idle-timeout-ms
  [ctx runtime-opts]
  (let [runtime-timeout (:llm-stream-idle-timeout-ms runtime-opts)
        config-timeout  (get-in ctx [:config :llm-stream-idle-timeout-ms])]
    (cond
      (and (number? runtime-timeout) (pos? runtime-timeout)) (long runtime-timeout)
      (and (number? config-timeout) (pos? config-timeout))   (long config-timeout)
      :else nil)))

(defn- resolve-custom-provider-options
  "Extract custom provider options from model-registry auth.
   Returns a map to merge into request options, or nil."
  [session-data]
  (when-let [provider (:provider (:model session-data))]
    (when-let [auth (model-registry/get-auth provider)]
      (cond-> {}
        ;; When auth-header? is false, signal to transport to skip Authorization
        (false? (:auth-header? auth))
        (assoc :no-auth-header true)

        ;; Merge custom request headers
        (seq (:headers auth))
        (assoc :headers (:headers auth))))))

(defn session->request-options
  "Build request/runtime options from canonical session data.
   This is the canonical projection for provider request/runtime shaping."
  [ctx session-data runtime-opts]
  (let [api-key          (resolve-api-key ctx session-data runtime-opts)
        idle-timeout-ms  (resolve-llm-stream-idle-timeout-ms ctx runtime-opts)
        custom-opts      (resolve-custom-provider-options session-data)]
    (cond-> {}
      (contains? session-data :thinking-level)
      (assoc :thinking-level (:thinking-level session-data))

      (some? api-key)
      (assoc :api-key api-key)

      idle-timeout-ms
      (assoc :llm-stream-idle-timeout-ms idle-timeout-ms)

      ;; Merge custom provider options (headers, no-auth-header)
      (some? custom-opts)
      (merge custom-opts))))

(defn- resolve-runtime-model
  [session-model]
  (let [provider (some-> (:provider session-model) keyword)
        model-id (:id session-model)]
    (model-registry/find-model provider model-id)))

(defn- sorted-contributions
  [session-data]
  (-> (:prompt-contributions session-data)
      (system-prompt/filter-prompt-contributions (:prompt-component-selection session-data))
      ss/sorted-prompt-contributions))

(defn- developer-prompt-section
  [session-data]
  (let [dev (:developer-prompt session-data)]
    (when-not (str/blank? dev)
      dev)))

(defn effective-system-prompt
  "Assemble the effective provider-visible system prompt from canonical
   request-preparation inputs.

   This makes request preparation the explicit home for the final
   base-plus-developer-plus-contributions projection used for provider execution."
  [session-data]
  (let [base     (:base-system-prompt session-data)
        dev      (developer-prompt-section session-data)
        base+dev (cond-> (or base "")
                   dev (str "\n\n" dev))]
    (system-prompt/apply-prompt-contributions
     base+dev
     (sorted-contributions session-data))))

(defn build-provider-conversation
  "Project canonical session prompt state, messages, and tools into the
   provider-facing conversation shape used for prompt execution."
  [session-data messages]
  (let [cache-bps (set (or (:cache-breakpoints session-data) #{}))
        tool-defs (system-prompt/filter-tool-defs (:tool-defs session-data)
                                                  (:prompt-component-selection session-data))]
    (conv/agent-messages->ai-conversation
     (effective-system-prompt session-data)
     messages
     tool-defs
     {:cache-breakpoints cache-bps})))

(defn build-prompt-layers
  "Return prompt layers for the prepared request.

   Current shape makes the main assembled layers explicit for introspection while
   request preparation assembles the effective provider system prompt from those
   canonical session-owned layers.

   Layers currently surfaced:
   - :system/base          assembled base system prompt
   - :system/developer     optional developer prompt layer
   - :system/contributions optional rendered prompt-contribution section"
  [session-data _opts]
  (let [base    (:base-system-prompt session-data)
        dev     (:developer-prompt session-data)
        contrib (->> (sorted-contributions session-data)
                     (map :content)
                     (remove str/blank?)
                     (str/join "\n\n"))]
    (cond-> []
      (some? base)
      (conj {:id      :system/base
             :kind    :system
             :stable? true
             :content base})

      (not (str/blank? dev))
      (conj {:id      :system/developer
             :kind    :developer
             :stable? true
             :source  (:developer-prompt-source session-data)
             :content dev})

      (not (str/blank? contrib))
      (conj {:id      :system/contributions
             :kind    :contributions
             :stable? true
             :content contrib}))))

(defn- input-expansion
  [session-data text commands]
  (let [loaded-skills (:skills session-data)
        templates     (:prompt-templates session-data)]
    (if-let [skill-result (skills/invoke-skill loaded-skills text)]
      {:text      (:content skill-result)
       :expansion {:kind :skill :name (:skill-name skill-result)}}
      (if-let [tpl-result (prompt-templates/invoke-template templates commands text)]
        {:text      (:content tpl-result)
         :expansion {:kind :template :name (:source-template tpl-result)}}
        {:text      text
         :expansion nil}))))

(defn expand-user-message
  "Expand canonical user-message text through request-preparation-owned skill
   and template expansion.

   Returns {:user-message message :expansion expansion?} or nil when the input
   message is nil or has no text block to expand."
  [session-data user-message commands]
  (when-let [text (some->> (:content user-message)
                           (keep (fn [block]
                                   (when (= :text (:type block))
                                     (:text block))))
                           first)]
    (let [{expanded-text :text expansion :expansion} (input-expansion session-data text commands)]
      {:user-message (update user-message :content
                             (fn [blocks]
                               (let [replaced? (atom false)]
                                 (mapv (fn [block]
                                         (if (and (= :text (:type block)) (not @replaced?))
                                           (do (reset! replaced? true)
                                               (assoc block :text expanded-text))
                                           block))
                                       blocks))))
       :expansion expansion})))

(defn- replace-current-user-message
  [base-messages user-message]
  (if (and user-message
           (seq base-messages)
           (= "user" (:role (peek base-messages))))
    (conj (pop (vec base-messages)) user-message)
    base-messages))

(defn- queued-steering-messages
  [session-data user-message]
  (when (nil? user-message)
    (->> (:steering-messages session-data)
         (keep (fn [text]
                 (when (and (string? text)
                            (not (str/blank? text)))
                   {:role "user"
                    :content [{:type :text :text text}]})))
         vec
         not-empty)))

(defn build-prepared-request
  "Build a prepared-request artifact from canonical session state.

   Input opts:
   - :turn-id
   - :user-message
   - :runtime-opts
   - :runtime-model"
  [ctx session-id {:keys [turn-id user-message runtime-opts runtime-model commands] :as opts}]
  (let [session-data       (ss/get-session-data-in ctx session-id)
        {:keys [user-message expansion]} (or (expand-user-message session-data user-message commands)
                                             {:user-message user-message
                                              :expansion nil})
        base-messages      (-> (session->provider-messages ctx session-id)
                               (replace-current-user-message user-message))
        steering-messages  (queued-steering-messages session-data user-message)
        messages           (cond-> base-messages
                             (seq steering-messages) (into steering-messages))
        ai-options         (session->request-options ctx session-data (or runtime-opts {}))
        cache-bps          (set (or (:cache-breakpoints session-data) #{}))
        prompt-layers      (build-prompt-layers session-data opts)
        provider-conv      (build-provider-conversation session-data messages)
        runtime-model      (or runtime-model
                               (resolve-runtime-model (:model session-data)))]
    {:prepared-request/id                       turn-id
     :prepared-request/session-id               session-id
     :prepared-request/user-message             user-message
     :prepared-request/input-expansion          expansion
     :prepared-request/queued-steering-messages steering-messages
     :prepared-request/session-snapshot         {:model                   (:model session-data)
                                                 :thinking-level          (:thinking-level session-data)
                                                 :prompt-mode             (:prompt-mode session-data)
                                                 :cache-breakpoints       cache-bps
                                                 :active-tools            (:active-tools session-data)
                                                 :developer-prompt        (:developer-prompt session-data)
                                                 :developer-prompt-source (:developer-prompt-source session-data)}
     :prepared-request/prompt-layers            prompt-layers
     :prepared-request/system-prompt            (:system-prompt provider-conv)
     :prepared-request/system-prompt-blocks     (:system-prompt-blocks provider-conv)
     :prepared-request/messages                 (:messages provider-conv)
     :prepared-request/tools                    (:tools provider-conv)
     :prepared-request/model                    runtime-model
     :prepared-request/ai-options               ai-options
     :prepared-request/cache-projection         {:cache-breakpoints cache-bps
                                                 :system-cached?    (contains? cache-bps :system)
                                                 :tools-cached?     (contains? cache-bps :tools)
                                                 :message-breakpoint-count
                                                 (count (filter #(= :user (:role %))
                                                                (:messages provider-conv)))}
     :prepared-request/provider-conversation    provider-conv}))
