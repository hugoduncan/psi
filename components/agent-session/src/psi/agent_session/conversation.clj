(ns psi.agent-session.conversation
  "Translates between agent-core message history and ai/conversation format.

   agent-core messages use plain maps with {:role string :content [{:type ...}]}.
   ai/conversation uses the ai.schemas shapes (role keyword, :kind-dispatched content).

   Entry point: agent-messages->ai-conversation"
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [psi.ai.conversation :as conv]
   [psi.agent-session.system-prompt :as system-prompt]
   [psi.agent-session.tool-defs :as tool-defs]))

;; ============================================================
;; JSON argument parsing
;; ============================================================

(defn parse-args-strict
  "Parse tool args strictly, preserving parse validity.
   Returns {:ok? true :value <map>} when JSON parses to a map,
   otherwise {:ok? false :value nil}."
  [arguments]
  (try
    (let [parsed (json/parse-string arguments)]
      (if (map? parsed)
        {:ok? true :value parsed}
        {:ok? false :value nil}))
    (catch Exception _
      {:ok? false :value nil})))

(defn parse-args
  "Parse JSON tool arguments string into a map.
   Always returns a map — returns {} on non-map or parse failure."
  [arguments]
  (let [{:keys [ok? value]} (parse-args-strict arguments)]
    (if ok? value {})))

;; ============================================================
;; Cache-control helpers
;; ============================================================

(def ^:private ephemeral-cache-control {:type :ephemeral})
(def ^:private max-provider-cache-breakpoints 4)

(defn- maybe-cache-control [enabled?]
  (when enabled? ephemeral-cache-control))

(defn- add-cache-control-to-content
  "Add ephemeral cache-control to a message's normalized content."
  [content]
  (case (:kind content)
    :text       (assoc content :cache-control ephemeral-cache-control)
    :structured (update content :blocks
                        (fn [blocks]
                          (if (seq blocks)
                            (update blocks (dec (count blocks))
                                    assoc :cache-control ephemeral-cache-control)
                            blocks)))
    content))

(defn- apply-message-cache-breakpoints
  "Mark the last N user messages with ephemeral cache-control.
   N = available breakpoint slots after system and tools."
  [conv system-cache? tools-cache?]
  (let [available (- max-provider-cache-breakpoints
                     (if system-cache? 1 0)
                     (if tools-cache? 1 0))]
    (if (pos? available)
      (let [messages  (:messages conv)
            user-idxs (->> (range (count messages))
                           (filterv #(= :user (:role (nth messages %)))))]
        (if (empty? user-idxs)
          conv
          (let [target-idxs (set (take-last available user-idxs))]
            (assoc conv :messages
                   (into []
                         (map-indexed
                          (fn [i msg]
                            (if (contains? target-idxs i)
                              (update msg :content add-cache-control-to-content)
                              msg)))
                         messages)))))
      conv)))

;; ============================================================
;; Per-role message translators
;; ============================================================

(defn- append-user-msg [conv msg]
  (let [text-blocks (->> (:content msg)
                         (keep (fn [block]
                                 (when (= :text (:type block))
                                   (cond-> {:type :text
                                            :text (or (:text block) "")}
                                     (:cache-control block)
                                     (assoc :cache-control (:cache-control block))))))
                         vec)
        user-content (if (seq text-blocks)
                       text-blocks
                       (or (some #(when (= :text (:type %)) (:text %)) (:content msg))
                           (str (:content msg))))]
    (conv/add-user-message conv user-content)))

(defn- append-assistant-msg [conv msg]
  (let [thinking-blocks (->> (:content msg)
                             (keep (fn [block]
                                     (when (= :thinking (:type block))
                                       (cond-> {:kind :thinking
                                                :text (or (:text block) "")}
                                         (:provider block)  (assoc :provider (:provider block))
                                         (:signature block) (assoc :signature (:signature block)))))))
        text-parts      (keep #(when (= :text (:type %)) (:text %)) (:content msg))
        tool-calls      (filter #(= :tool-call (:type %)) (:content msg))
        text            (str/join "\n" text-parts)
        structured-blocks (vec
                           (concat
                            thinking-blocks
                            (when (seq text) [{:kind :text :text text}])
                            (map (fn [tc]
                                   {:kind  :tool-call
                                    :id    (:id tc)
                                    :name  (:name tc)
                                    :input (parse-args (:arguments tc))})
                                 tool-calls)))]
    (if (seq structured-blocks)
      (conv/add-assistant-message conv {:content {:kind :structured :blocks structured-blocks}})
      conv)))

(defn- append-tool-result-msg [conv msg]
  (let [text (or (some #(when (= :text (:type %)) (:text %)) (:content msg)) "")]
    (conv/add-tool-result conv
                          (:tool-call-id msg)
                          (:tool-name msg)
                          {:kind :text :text text}
                          (boolean (:is-error msg)))))

(defn- append-msg
  "Append one agent-core message to an ai/conversation.
   Skips extension transcript markers (:custom-type) and unknown roles."
  [conv msg]
  (if (:custom-type msg)
    conv
    (case (:role msg)
      "user"       (append-user-msg conv msg)
      "assistant"  (append-assistant-msg conv msg)
      "toolResult" (append-tool-result-msg conv msg)
      conv)))

(defn- add-tools-to-conv [conv agent-tools tools-cache?]
  (reduce (fn [c tool]
            (conv/add-tool c
                           (cond-> (tool-defs/provider-tool tool)
                             tools-cache?
                             (assoc :cache-control (maybe-cache-control tools-cache?)))))
          conv
          agent-tools))

;; ============================================================
;; Public entry point
;; ============================================================

(defn agent-messages->ai-conversation
  "Rebuild an ai/conversation from agent-core message history.
   Used to reconstruct the conversation context before each LLM call.
   Includes tool definitions so the provider can offer tool_use.

   Messages with :custom-type are extension transcript markers and are excluded —
   consecutive same-role messages cause provider 400 errors."
  [system-prompt-str messages agent-tools {:keys [cache-breakpoints]}]
  (let [cache-set     (set (or cache-breakpoints #{}))
        system-cache? (contains? cache-set :system)
        tools-cache?  (contains? cache-set :tools)
        conv          (reduce append-msg
                              (conv/create {:system-prompt        system-prompt-str
                                            :system-prompt-blocks (system-prompt/system-prompt-blocks
                                                                   system-prompt-str system-cache?)})
                              messages)]
    (-> conv
        (apply-message-cache-breakpoints system-cache? tools-cache?)
        (add-tools-to-conv agent-tools tools-cache?))))
