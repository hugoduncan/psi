(ns psi.agent-session.prompt-request
  "Pure prompt/request projection helpers.

   This namespace is the architectural home for request preparation:
   canonical session state + journal + prompt layers -> prepared request."
  (:require
   [clojure.string :as str]
   [psi.ai.model-registry :as model-registry]
   [psi.turn-runtime.request :as turn-request]
   [psi.prompt-assets.prompt-templates :as prompt-templates]
   [psi.provider-auth.core :as provider-auth]
   [psi.session-state.state :as ss]
   [psi.prompt-assets.skills :as skills]
   [psi.prompt-assets.system-prompt :as system-prompt]))

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
   3. Shared provider-scoped auth resolution"
  [ctx session-data runtime-opts]
  (let [provider (:provider (:model session-data))]
    (or (:api-key runtime-opts)
        (:runtime-api-key session-data)
        (provider-auth/provider-api-key ctx provider))))

(defn- resolve-llm-stream-idle-timeout-ms
  [ctx runtime-opts]
  (let [runtime-timeout (:llm-stream-idle-timeout-ms runtime-opts)
        config-timeout  (get-in ctx [:config :llm-stream-idle-timeout-ms])]
    (cond
      (and (number? runtime-timeout) (pos? runtime-timeout)) (long runtime-timeout)
      (and (number? config-timeout) (pos? config-timeout))   (long config-timeout)
      :else nil)))

(defn session->request-options
  "Build request/runtime options from canonical session data.
   This is the canonical projection for provider request/runtime shaping."
  [ctx session-data runtime-opts]
  (let [api-key          (resolve-api-key ctx session-data runtime-opts)
        idle-timeout-ms  (resolve-llm-stream-idle-timeout-ms ctx runtime-opts)
        provider-options (some-> (:provider (:model session-data))
                                 provider-auth/provider-request-options)]
    (cond-> {}
      (contains? session-data :thinking-level)
      (assoc :thinking-level (:thinking-level session-data))

      (some? api-key)
      (assoc :api-key api-key)

      idle-timeout-ms
      (assoc :llm-stream-idle-timeout-ms idle-timeout-ms)

      ;; Merge custom provider options (headers, no-auth-header)
      (some? provider-options)
      (merge provider-options))))

(defn- resolve-runtime-model
  [session-model]
  (let [provider (provider-auth/normalize-provider-id (:provider session-model))
        model-id (:id session-model)]
    (model-registry/find-model provider model-id)))

(defn- sorted-contributions
  [session-data]
  (-> (:prompt-contributions session-data)
      (system-prompt/filter-prompt-contributions (:prompt-component-selection session-data))
      ss/sorted-prompt-contributions))

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
  [ctx session-id {:keys [turn-id user-message runtime-opts runtime-model commands]}]
  (let [session-data               (ss/get-session-data-in ctx session-id)
        {:keys [user-message expansion]} (or (expand-user-message session-data user-message commands)
                                             {:user-message user-message
                                              :expansion nil})
        base-messages              (-> (session->provider-messages ctx session-id)
                                       (replace-current-user-message user-message))
        steering-messages          (queued-steering-messages session-data user-message)
        messages                   (cond-> base-messages
                                     (seq steering-messages) (into steering-messages))
        cache-bps                  (set (or (:cache-breakpoints session-data) #{}))
        normalized-turn            {:turn/id                          turn-id
                                    :turn/session-id                  session-id
                                    :turn/user-message                user-message
                                    :turn/input-expansion             expansion
                                    :turn/queued-steering-messages    steering-messages
                                    :turn/messages                    messages
                                    :turn/runtime-model               (or runtime-model
                                                                          (resolve-runtime-model (:model session-data)))
                                    :turn/ai-options                  (session->request-options ctx session-data (or runtime-opts {}))
                                    :turn/cache-breakpoints          cache-bps
                                    :turn/session-model              (:model session-data)
                                    :turn/thinking-level             (:thinking-level session-data)
                                    :turn/prompt-mode                (:prompt-mode session-data)
                                    :turn/active-tools               (:active-tools session-data)
                                    :turn/developer-prompt           (:developer-prompt session-data)
                                    :turn/developer-prompt-source    (:developer-prompt-source session-data)
                                    :turn/base-system-prompt         (:base-system-prompt session-data)
                                    :turn/sorted-prompt-contributions (sorted-contributions session-data)
                                    :turn/filtered-tool-defs         (system-prompt/filter-tool-defs (:tool-defs session-data)
                                                                                                     (:prompt-component-selection session-data))}]
    (turn-request/build-prepared-request normalized-turn)))
