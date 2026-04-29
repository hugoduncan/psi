(ns psi.ai.conversation
  "Conversation entity and lifecycle management"
  (:require [psi.ai.schemas :as schemas]
            [psi.agent-session.tool-defs :as tool-defs])
  (:import [java.time Instant]
           [java.util UUID]))

;; Conversation state management

(defn- now []
  (Instant/now))

(defn- new-id []
  (str (UUID/randomUUID)))

(defn- validate-conversation [conversation]
  (schemas/validate! schemas/Conversation conversation))

(defn- stamp-and-validate [conversation timestamp]
  (-> conversation
      (assoc :updated-at timestamp)
      validate-conversation))

(defn- update-conversation [conversation f]
  (let [timestamp (now)]
    (stamp-and-validate (f conversation) timestamp)))

(defn- append-message [conversation message]
  (let [timestamp (now)]
    (stamp-and-validate
     (update conversation :messages conj (merge {:id        (new-id)
                                                 :timestamp timestamp}
                                                message))
     timestamp)))

(defn- normalize-system-prompt-blocks
  [system-prompt system-prompt-blocks]
  (cond
    (seq system-prompt-blocks)
    (vec system-prompt-blocks)

    (some? system-prompt)
    [{:kind :text :text system-prompt}]

    :else
    nil))

(defn create
  "Create new conversation with optional system prompt.

   Accepts either a system prompt string/nil or an options map with:
   - :system-prompt string
   - :system-prompt-blocks vector of text blocks"
  [system-prompt-or-options]
  (let [{:keys [system-prompt system-prompt-blocks]}
        (if (map? system-prompt-or-options)
          system-prompt-or-options
          {:system-prompt system-prompt-or-options})
        timestamp         (now)
        normalized-blocks (normalize-system-prompt-blocks system-prompt system-prompt-blocks)]
    (validate-conversation
     (cond-> {:id            (new-id)
              :system-prompt system-prompt
              :status        :active
              :created-at    timestamp
              :updated-at    timestamp
              :messages      []
              :tools         #{}}
       (some? normalized-blocks) (assoc :system-prompt-blocks normalized-blocks)))))

(defn- legacy-block->text-content
  "Convert a legacy canonical block {:type :text ...} to internal {:kind :text ...} form."
  [block]
  (cond-> {:kind :text
           :text (or (:text block) "")}
    (:cache-control block) (assoc :cache-control (:cache-control block))))

(defn- normalize-user-content
  [content]
  (cond
    (string? content)
    {:kind :text
     :text content}

    ;; Legacy canonical blocks: [{:type :text :text "..." ...}]
    (and (vector? content)
         (every? #(and (map? %)
                       (= :text (:type %))
                       (string? (:text %)))
                 content))
    (let [blocks (mapv legacy-block->text-content content)]
      (if (= 1 (count blocks))
        (first blocks)
        {:kind :structured
         :blocks blocks}))

    ;; Already-normalized content map {:kind :text ...} or {:kind :structured ...}
    (map? content)
    content

    ;; Unknown — coerce to text; schema validation will catch anything invalid
    :else
    {:kind :text
     :text (str content)}))

(defn add-user-message
  "Add user message to conversation.

   CONTENT may be either:
   - string text
   - vector of legacy canonical text blocks {:type :text :text ... [:cache-control ...]}
   - normalized map content {:kind :text ...} or {:kind :structured :blocks [...]}"
  [conversation content]
  (append-message conversation
                  {:role    :user
                   :content (normalize-user-content content)}))

(defn add-assistant-message
  "Add assistant message to conversation"
  [conversation message-data]
  (append-message conversation
                  (merge {:role :assistant}
                         message-data)))

(defn add-tool-result
  "Add tool result message to conversation"
  [conversation tool-call-id tool-name content is-error]
  (append-message conversation
                  {:role         :tool-result
                   :tool-call-id tool-call-id
                   :tool-name    tool-name
                   :content      content
                   :is-error     is-error}))

(defn add-tool
  "Add tool to conversation"
  [conversation tool]
  (update-conversation conversation (fn [conversation]
                                      (update conversation :tools conj (tool-defs/provider-tool tool)))))

;; Derived data

(defn- assistant-messages [conversation]
  (filter #(= :assistant (:role %)) (:messages conversation)))

(defn- sum-fields [maps init ks]
  (reduce (fn [acc m]
            (reduce (fn [acc k]
                      (update acc k + (get m k 0)))
                    acc
                    ks))
          init
          maps))

(defn total-usage
  "Calculate total token usage across all messages"
  [conversation]
  (sum-fields (keep :usage (assistant-messages conversation))
              {:input-tokens       0
               :output-tokens      0
               :cache-read-tokens  0
               :cache-write-tokens 0
               :total-tokens       0}
              [:input-tokens
               :output-tokens
               :cache-read-tokens
               :cache-write-tokens
               :total-tokens]))

(defn total-cost
  "Calculate total cost across all messages"
  [conversation]
  (sum-fields (keep (comp :cost :usage) (assistant-messages conversation))
              {:input       0.0
               :output      0.0
               :cache-read  0.0
               :cache-write 0.0
               :total       0.0}
              [:input :output :cache-read :cache-write :total]))
