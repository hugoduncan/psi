(ns psi.turn-runtime.request
  "Lower prepared-turn request assembly from normalized turn inputs."
  (:require
   [clojure.string :as str]
   [psi.prompt-assets.system-prompt :as system-prompt]
   [psi.turn-runtime.conversation :as conv]
   [psi.turn-runtime.augmentation :as turn-augmentation]))

(defn effective-system-prompt
  "Assemble the provider-visible effective system prompt from a fully
   normalized lower-boundary `normalized-turn` map.

   This function must consume only normalized `:turn/*` data and must not do
   session lookup, journal lookup, auth resolution, template/skill expansion,
   or prompt-selection policy."
  [normalized-turn]
  (let [base     (:turn/base-system-prompt normalized-turn)
        dev      (:turn/developer-prompt normalized-turn)
        base+dev (cond-> (or base "")
                   (not (str/blank? dev)) (str "\n\n" dev))]
    (system-prompt/apply-prompt-contributions
     base+dev
     (:turn/sorted-prompt-contributions normalized-turn))))

(defn build-prompt-layers
  "Return introspectable prompt layers from a fully normalized lower-boundary
   `normalized-turn` map. Consumes normalized `:turn/*` values only."
  [normalized-turn]
  (let [base               (:turn/base-system-prompt normalized-turn)
        dev                (:turn/developer-prompt normalized-turn)
        augmentation-layer (some-> (:turn/augmentation-record normalized-turn)
                                   turn-augmentation/augmentation-prompt-layer)
        contrib            (->> (:turn/sorted-prompt-contributions normalized-turn)
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
             :source  (:turn/developer-prompt-source normalized-turn)
             :content dev})

      (not (str/blank? contrib))
      (conj {:id      :system/contributions
             :kind    :contributions
             :stable? true
             :content contrib})

      augmentation-layer
      (conj augmentation-layer))))

(defn build-provider-conversation
  "Build the provider-facing conversation from a fully normalized lower-boundary
   `normalized-turn` map. Consumes normalized `:turn/*` values only."
  [normalized-turn]
  (let [cache-bps (set (or (:turn/cache-breakpoints normalized-turn) #{}))
        tool-defs (:turn/filtered-tool-defs normalized-turn)]
    (conv/agent-messages->ai-conversation
     (effective-system-prompt normalized-turn)
     (:turn/messages normalized-turn)
     tool-defs
     {:cache-breakpoints cache-bps})))

(defn prepared-request-query-text
  [prepared-request]
  (or (get-in prepared-request [:prepared-request/user-message :content 0 :text])
      (some->> (:prepared-request/queued-steering-messages prepared-request)
               first
               :content
               first
               :text)))

(defn build-prepared-request
  "Assemble the final prepared-request map from a fully normalized lower-boundary
   `normalized-turn` map. Consumes normalized `:turn/*` values only."
  [normalized-turn]
  (let [cache-bps     (set (or (:turn/cache-breakpoints normalized-turn) #{}))
        prompt-layers (build-prompt-layers normalized-turn)
        provider-conv (build-provider-conversation normalized-turn)]
    {:prepared-request/id                       (:turn/id normalized-turn)
     :prepared-request/session-id               (:turn/session-id normalized-turn)
     :prepared-request/user-message             (:turn/user-message normalized-turn)
     :prepared-request/input-expansion          (:turn/input-expansion normalized-turn)
     :prepared-request/queued-steering-messages (:turn/queued-steering-messages normalized-turn)
     :prepared-request/session-snapshot         {:model                   (:turn/session-model normalized-turn)
                                                 :thinking-level          (:turn/thinking-level normalized-turn)
                                                 :prompt-mode             (:turn/prompt-mode normalized-turn)
                                                 :response-mode           (:turn/response-mode normalized-turn)
                                                 :cache-breakpoints       cache-bps
                                                 :active-tools            (:turn/active-tools normalized-turn)
                                                 :developer-prompt        (:turn/developer-prompt normalized-turn)
                                                 :developer-prompt-source (:turn/developer-prompt-source normalized-turn)}
     :prepared-request/prompt-layers            prompt-layers
     :prepared-request/augmentation              (when-let [record (:turn/augmentation-record normalized-turn)]
                                                   (turn-augmentation/prepared-request-summary
                                                    record
                                                    (boolean (turn-augmentation/augmentation-context-message
                                                              (:turn/id normalized-turn)
                                                              (:operations record)))))
     :prepared-request/system-prompt            (:system-prompt provider-conv)
     :prepared-request/system-prompt-blocks     (:system-prompt-blocks provider-conv)
     :prepared-request/messages                 (:messages provider-conv)
     :prepared-request/tools                    (:tools provider-conv)
     :prepared-request/model                    (:turn/runtime-model normalized-turn)
     :prepared-request/ai-options               (:turn/ai-options normalized-turn)
     :prepared-request/cache-projection         {:cache-breakpoints cache-bps
                                                 :system-cached?    (contains? cache-bps :system)
                                                 :tools-cached?     (contains? cache-bps :tools)
                                                 :message-breakpoint-count
                                                 (count (filter #(= :user (:role %))
                                                                (:messages provider-conv)))}
     :prepared-request/provider-conversation    provider-conv}))
