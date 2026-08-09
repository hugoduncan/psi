(ns psi.agent-session.prompt-request
  "Pure prompt/request projection helpers.

   This namespace is the architectural home for request preparation:
   canonical session state + journal + prompt layers -> prepared request."
  (:require
   [clojure.string :as str]
   [psi.ai.model-registry :as model-registry]
   [psi.turn-runtime.request :as turn-request]
   [psi.prompt-assets.prompt-templates :as prompt-templates]
   [psi.prompt-assets.skills :as prompt-skills]
   [psi.provider-auth.core :as provider-auth]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.augmentation :as turn-augmentation]
   [psi.prompt-assets.system-prompt :as system-prompt]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.tool-registry.defs :as tool-defs]))

(defn- assistant-tool-call-ids
  [message]
  (into []
        (comp (filter #(= :tool-call (:type %)))
              (map :id)
              (filter string?))
        (:content message)))

(defn- tool-result-message?
  [message]
  (= "toolResult" (:role message)))

(defn- tool-result-id
  [message]
  (:tool-call-id message))

(defn- interrupted-tool-result
  [tool-call-id timestamp]
  {:role "toolResult"
   :tool-call-id tool-call-id
   :tool-name "interrupted"
   :content [{:type :text
              :text "Tool execution interrupted before completion."}]
   :is-error true
   :timestamp (or timestamp (java.time.Instant/now))})

(defn dangling-tool-result-repairs
  "Return synthetic error toolResult messages needed to repair dangling
   assistant tool-call blocks.

   A dangling block is an assistant message with one or more tool calls not
   fully covered by the immediately following contiguous toolResult messages."
  [messages]
  (loop [remaining (seq messages)
         repairs   []]
    (if-let [message (first remaining)]
      (let [tool-call-ids (seq (assistant-tool-call-ids message))]
        (if (and (= "assistant" (:role message)) tool-call-ids)
          (let [[tool-results tail] (split-with tool-result-message? (rest remaining))
                present-ids         (into #{} (keep tool-result-id) tool-results)
                missing-results     (->> tool-call-ids
                                         (remove present-ids)
                                         (mapv #(interrupted-tool-result % (:timestamp message))))]
            (recur tail (into repairs missing-results)))
          (recur (next remaining) repairs)))
      repairs)))

(defn tail-dangling-tool-result-repairs
  "Return synthetic error toolResult messages needed to repair a dangling tail
   tool-use before appending a later user message to the journal.

   This only repairs the final assistant tool-use segment at the end of the
   current history, preserving immediate adjacency in the persisted journal."
  [messages]
  (let [[trailing-tool-results reversed-prefix] (split-with tool-result-message? (rseq (vec messages)))
        assistant-msg                           (first reversed-prefix)]
    (if (and assistant-msg
             (= "assistant" (:role assistant-msg))
             (seq (assistant-tool-call-ids assistant-msg)))
      (let [present-ids (into #{} (keep tool-result-id) trailing-tool-results)]
        (->> (assistant-tool-call-ids assistant-msg)
             (remove present-ids)
             (mapv #(interrupted-tool-result % (:timestamp assistant-msg)))))
      [])))

(defn- repair-dangling-tool-uses
  "Ensure every assistant tool-call block is followed by corresponding
   contiguous toolResult messages before any later non-toolResult message.

   When a session was interrupted after journaling an assistant tool-use but
   before journaling its matching toolResult, synthesize an error toolResult in
   the provider-facing projection so follow-on prompts remain valid."
  [messages]
  (loop [remaining (seq messages)
         repaired  []]
    (if-let [message (first remaining)]
      (let [tool-call-ids (seq (assistant-tool-call-ids message))]
        (if (and (= "assistant" (:role message)) tool-call-ids)
          (let [[tool-results tail] (split-with tool-result-message? (rest remaining))
                present-ids         (into #{} (keep tool-result-id) tool-results)
                missing-results     (->> tool-call-ids
                                         (remove present-ids)
                                         (mapv #(interrupted-tool-result % (:timestamp message))))]
            (recur tail (into repaired (concat [message] tool-results missing-results))))
          (recur (next remaining) (conj repaired message))))
      repaired)))

(defn- dedupe-tool-results
  "Drop any toolResult message whose tool-call-id already appeared earlier
   (first occurrence wins). Defensive projection guard so already-persisted
   journals containing duplicate toolResult entries for one tool-call-id project
   to at most one provider `tool_result` per id. Applied to
   `repair-dangling-tool-uses`'s output (de-dup-after-repair) so synthetic
   results repair adds for non-contiguous ids are also de-duped; ordering and
   non-toolResult messages are preserved."
  [messages]
  (first
   (reduce (fn [[acc seen] message]
             (if (tool-result-message? message)
               (let [id (tool-result-id message)]
                 (if (contains? seen id)
                   [acc seen]
                   [(conj acc message) (conj seen id)]))
               [(conj acc message) seen]))
           [[] #{}]
           messages)))

(defn- mid-system-provider-message
  [entry]
  (let [text (get-in entry [:data :text])]
    {:role "system"
     :content [{:type :text :text (or text "")}]}))

(defn journal->provider-messages
  "Project persisted journal entries into agent/provider message maps.
   :message entries become provider messages directly.
   :mid-system entries become inline system messages.
   :logprobs entries are persisted but not projected into provider messages.
   Dangling assistant tool uses are repaired with synthetic error tool results
   in the provider-facing projection so interrupted sessions remain usable."
  [journal]
  (dedupe-tool-results
   (repair-dangling-tool-uses
    (into []
          (keep (fn [entry]
                  (case (:kind entry)
                    :message (get-in entry [:data :message])
                    :mid-system (mid-system-provider-message entry)
                    nil)))
          journal))))

(defn session->provider-messages
  "Project the persisted journal for `session-id` into provider-visible messages."
  [ctx session-id]
  (journal->provider-messages
   (or (ss/get-state-value-in ctx [:agent-session :sessions session-id :persistence :journal])
       [])))

(defn session-model-custom?
  "True when the session's current model is a custom models.edn provider.

   The persistable session model map carries only `{:provider (name provider)
   :id model-id :reasoning bool}` (persistable-model: `:id` holds the
   model-id string and `:reasoning` is a separate boolean key) — no origin
   marker (review 36) — so the built-in/custom origin is resolved through the
   model registry's `:custom?` origin tag (review 14). A custom models.edn
   provider literally named \"anthropic\"/\"openai\" is tagged `:custom?
   true` and resolves true; built-in catalog models resolve false. Unknown
   models (not in the registry) resolve false."
  [session-data]
  (boolean
   (:custom?
    (model-registry/find-model
     (provider-auth/normalize-provider-id (get-in session-data [:model :provider]))
     (get-in session-data [:model :id])))))

(defn- session-runtime-api-key
  "Return the session-stored runtime API key only when it is scoped to the
   session's current model provider AND origin.

   `:runtime-api-key` is recorded at prompt prepare together with
   `:runtime-api-key-provider` and `:runtime-api-key-custom?` (the session
   model's provider and built-in/custom origin at that moment, reviews
   35/36). A stored key is reused only when the recorded provider AND origin
   BOTH still match the session's current model — so a mid-session
   `/model`/session-profile provider switch can never inject the previous
   model's key (or OAuth token) into a different provider's request, and a
   custom models.edn provider literally named \"anthropic\"/\"openai\"
   (tagged `:custom? true`, review 14) can never reuse a key recorded for
   the built-in same-named origin (review 36) or vice versa — the same
   cross-provider disclosure class already closed for the env-var fallback
   and OAuth content-sniffing. An unscoped stored key (no recorded provider,
   e.g. legacy session data) is never reused: without a recorded provider we
   cannot prove it belongs to the current model."
  [session-data]
  (let [runtime-provider (some-> (:runtime-api-key-provider session-data)
                                 provider-auth/normalize-provider-id)
        runtime-custom?  (boolean (:runtime-api-key-custom? session-data))
        session-provider (provider-auth/normalize-provider-id
                          (get-in session-data [:model :provider]))
        session-custom?  (session-model-custom? session-data)]
    (when (and (:runtime-api-key session-data)
               (= session-provider runtime-provider)
               (= session-custom? runtime-custom?))
      (:runtime-api-key session-data))))

(defn- resolve-api-key
  "Resolve API key in priority order:
   1. Explicit runtime-opts :api-key
   2. Session-stored key from a prior turn, ONLY when it is scoped to the
      session's current model provider AND origin (reviews 35/36 — an
      unscoped, cross-provider or cross-origin stored key is never reused)
      and not contradicted by the current provider-auth resolution (review 36
      — a models.edn `:auth` change or OAuth refresh wins over the stale
      stored spec; the stored key is reused only when the current resolution
      is nil — e.g. an RPC/extension-threaded key that lives only in
      runtime-opts / session-data — or equals it, which is how OAuth
      stability is preserved: provider-auth re-resolves the same token from
      the OAuth store)
   3. Shared provider-scoped auth resolution

   Raw-spec contract (review 26): for custom models.edn providers the
   registry stores the RAW `:api-key` spec, so this may return a literal key
   or an \"env:VAR\" string — NOT yet a concrete key (the `:runtime-api-key`
   session-data flow stores the raw spec too). It becomes concrete only when
   the transport re-resolves it per request via
   `request-support/resolve-key-spec`. Callers that need a concrete key must
   route through that shared helper."
  [ctx session-data runtime-opts]
  (let [provider (:provider (:model session-data))
        custom?  (session-model-custom? session-data)
        current  (provider-auth/provider-api-key ctx provider custom?)]
    (or (:api-key runtime-opts)
        (when-let [stored (session-runtime-api-key session-data)]
          ;; The stored key is a cache of a prior prepare-time resolution for
          ;; this (provider, origin). Reuse it only when it is NOT
          ;; contradicted by a fresher current resolution: a models.edn
          ;; `:auth` change (or OAuth refresh) wins over the stale stored
          ;; spec (review 36), while a nil current resolution (e.g. an RPC /
          ;; extension-threaded key that lives only in runtime-opts /
          ;; session-data, not in provider-auth) lets the stored key keep the
          ;; session working across continuation turns.
          (when (or (nil? current) (= stored current))
            stored))
        current)))

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
        provider-options (when-let [provider (:provider (:model session-data))]
                           (provider-auth/provider-request-options
                            provider
                            (session-model-custom? session-data)))]
    (cond-> {}
      (contains? session-data :thinking-level)
      (assoc :thinking-level (:thinking-level session-data))

      (some? (:temperature session-data))
      (assoc :temperature (:temperature session-data))

      (some? (:speed-mode session-data))
      (assoc :speed-mode (:speed-mode session-data))

      (some? (:effort-override session-data))
      (assoc :effort-override (:effort-override session-data))

      (:logprobs-enabled session-data)
      (assoc :logprobs-enabled true
             :top-logprobs (or (:top-logprobs session-data) 3))

      (some? api-key)
      (assoc :api-key api-key)

      idle-timeout-ms
      (assoc :llm-stream-idle-timeout-ms idle-timeout-ms)

      ;; Merge custom provider options (headers, no-auth-header)
      (some? provider-options)
      (merge provider-options)

      (:structured-output runtime-opts)
      (assoc :structured-output (:structured-output runtime-opts)))))

(defn- resolve-runtime-model
  [ctx session-model]
  (let [provider (provider-auth/normalize-provider-id (:provider session-model))
        model-id (:id session-model)]
    (model-registry/resolve-runtime-model ctx provider model-id)))

(defn- sorted-contributions
  [ctx session-id session-data]
  (-> (ss/list-prompt-contributions-in ctx session-id)
      (system-prompt/filter-prompt-contributions (:prompt-component-selection session-data))
      ss/sorted-prompt-contributions))

(defn- input-expansion
  [root-state session-data text commands]
  (let [templates (:prompt-templates session-data)
        skills    (skill-storage/all-skills root-state session-data)]
    (if-let [skill-result (prompt-skills/invoke-skill skills text)]
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
  [root-state session-data user-message commands]
  (when-let [text (some->> (:content user-message)
                           (keep (fn [block]
                                   (when (= :text (:type block))
                                     (:text block))))
                           first)]
    (let [{expanded-text :text expansion :expansion} (input-expansion root-state session-data text commands)]
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
  (let [messages (vec base-messages)]
    (cond
      (not (and user-message (seq messages)))
      base-messages

      (= "user" (:role (peek messages)))
      (conj (pop messages) user-message)

      (and (= "system" (:role (peek messages)))
           (<= 2 (count messages))
           (= "user" (:role (nth messages (- (count messages) 2)))))
      (assoc messages (- (count messages) 2) user-message)

      :else
      base-messages)))

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

(defn- expanded-turn-input
  [root-state session-data user-message commands]
  (or (expand-user-message root-state session-data user-message commands)
      {:user-message user-message
       :expansion nil}))

(defn- augmentation-record-in
  [session-data turn-id]
  (get-in session-data [:turn-augmentations turn-id]))

(defn- live-augmentation-record!
  [session-id session-data turn-id]
  (let [record (augmentation-record-in session-data turn-id)]
    (cond
      (and (nil? record) (contains? session-data :turn-augmentations))
      (throw (ex-info "Missing turn augmentation record"
                      {:reason :missing-turn-augmentation-record
                       :session-id session-id
                       :turn-id turn-id}))

      (nil? record)
      nil

      (:accepting? record)
      (throw (ex-info "Turn augmentation is still open"
                      {:reason :turn-augmentation-still-open
                       :session-id session-id
                       :turn-id turn-id}))

      (not (turn-augmentation/well-formed-record? session-id turn-id record))
      (throw (ex-info "Invalid turn augmentation record"
                      {:reason :invalid-turn-augmentation-record
                       :session-id session-id
                       :turn-id turn-id}))

      :else
      record)))

(defn- prepared-turn-messages
  [ctx session-id session-data turn-id user-message]
  (let [augmentation-record (when turn-id
                              (live-augmentation-record! session-id session-data turn-id))
        base-messages       (-> (session->provider-messages ctx session-id)
                                (replace-current-user-message user-message))
        with-augmentation   (turn-augmentation/insert-augmentation-message base-messages augmentation-record)
        steering-messages   (queued-steering-messages session-data user-message)
        messages            (cond-> with-augmentation
                              (seq steering-messages) (into steering-messages))]
    {:messages (if (and user-message (empty? messages)) [user-message] messages)
     :queued-steering-messages steering-messages
     :augmentation-record augmentation-record}))

(defn- normalized-turn-input
  [ctx session-id session-data {:keys [turn-id user-message expansion runtime-opts runtime-model messages queued-steering-messages augmentation-record]}]
  (let [cache-bps (set (or (:cache-breakpoints session-data) #{}))]
    {:turn/id                           turn-id
     :turn/session-id                   session-id
     :turn/user-message                 user-message
     :turn/input-expansion              expansion
     :turn/queued-steering-messages     queued-steering-messages
     :turn/messages                     messages
     :turn/augmentation-record          augmentation-record
     :turn/runtime-model                (or runtime-model
                                            (resolve-runtime-model ctx (:model session-data)))
     :turn/ai-options                   (session->request-options ctx session-data (or runtime-opts {}))
     :turn/cache-breakpoints            cache-bps
     :turn/session-model                (:model session-data)
     :turn/thinking-level               (:thinking-level session-data)
     :turn/prompt-mode                  (:prompt-mode session-data)
     :turn/response-mode                (:response-mode session-data)
     :turn/active-tools                 (set (:tool-ids session-data))
     :turn/developer-prompt             (:developer-prompt session-data)
     :turn/developer-prompt-source      (:developer-prompt-source session-data)
     :turn/base-system-prompt           (:base-system-prompt session-data)
     :turn/sorted-prompt-contributions  (sorted-contributions ctx session-id session-data)
     :turn/filtered-tool-defs           (let [tool-source (ss/agent-tool-source-in ctx session-id)
                                              resolved    (tool-defs/resolve-tool-defs tool-source (:tool-ids session-data))]
                                          (system-prompt/filter-tool-defs resolved
                                                                          (:prompt-component-selection session-data)))}))

(defn build-prepared-request
  "Build a prepared-request artifact from canonical session state.

   Input opts:
   - :turn-id
   - :user-message
   - :runtime-opts
   - :runtime-model"
  [ctx session-id {:keys [turn-id user-message runtime-opts runtime-model commands]}]
  (let [root-state @(:state* ctx)
        session-data (ss/get-session-data-in ctx session-id)
        {:keys [user-message expansion]} (expanded-turn-input root-state session-data user-message commands)
        {:keys [messages queued-steering-messages augmentation-record]} (prepared-turn-messages ctx session-id session-data turn-id user-message)
        normalized-turn (normalized-turn-input ctx session-id session-data
                                               {:turn-id turn-id
                                                :user-message user-message
                                                :expansion expansion
                                                :runtime-opts runtime-opts
                                                :runtime-model runtime-model
                                                :messages messages
                                                :queued-steering-messages queued-steering-messages
                                                :augmentation-record augmentation-record})]
    (turn-request/build-prepared-request normalized-turn)))
