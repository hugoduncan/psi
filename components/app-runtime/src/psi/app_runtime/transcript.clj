(ns psi.app-runtime.transcript
  (:require
   [clojure.string :as str]
   [psi.agent-session.message-text :as message-text]))

(defn message->display-text
  [msg]
  (or (message-text/content-display-text (:content msg))
      ""))

(defn- ->kw
  [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        nil))

(defn- block-attr
  [block k]
  (or (get block k)
      (get block (name k))))

(defn- block-kind
  [block]
  (->kw (or (block-attr block :type)
            (block-attr block :kind))))

(defn- tool-call-block?
  [block]
  (= :tool-call (block-kind block)))

(defn- thinking-block?
  [block]
  (= :thinking (block-kind block)))

(defn- normalize-tool-call-block
  [block]
  {:id        (block-attr block :id)
   :name      (block-attr block :name)
   :arguments (or (block-attr block :arguments)
                  (some-> (block-attr block :input) pr-str)
                  "")})

(defn- content-blocks
  "Normalize assistant message content to a flat sequence of block maps.
   Accepts either a plain vector of blocks or a {:kind :structured :blocks [...]} map."
  [content]
  (cond
    (sequential? content)
    (filter map? content)

    (and (map? content)
         (= :structured (->kw (block-attr content :kind))))
    (content-blocks (block-attr content :blocks))

    :else
    []))

(defn- tool-result->display-text
  [msg]
  (let [text (message->display-text msg)]
    (when-not (str/blank? text)
      text)))

(defn agent-messages->tui-resume-state
  [messages]
  (reduce
   (fn [acc msg]
     (case (:role msg)
       "user"
       (let [text (message->display-text msg)]
         (update acc :messages conj {:role :user
                                     :text (if (str/blank? text) "[user]" text)}))

       "assistant"
       (let [text   (message->display-text msg)
             blocks (content-blocks (:content msg))
             ;; Single pass over content blocks in order:
             ;; - :thinking → {:role :thinking :text t}
             ;; - :tool-call → register in tool-calls/tool-order + {:role :tool :tool-id id}
             ;; - other (text) blocks → skip (text is appended after all blocks)
             ;; Assistant text is appended last so it follows all inline blocks.
             ;; This preserves the relative order of thinking and tool rows.
             acc'   (reduce
                     (fn [a block]
                       (cond
                         (thinking-block? block)
                         (let [t (:text block)]
                           (if (str/blank? t)
                             a
                             (update a :messages conj {:role :thinking :text t})))

                         (tool-call-block? block)
                         (let [nb (normalize-tool-call-block block)
                               id (:id nb)
                               tc {:name      (:name nb)
                                   :args      (or (:arguments nb) "")
                                   :status    :pending
                                   :result    nil
                                   :is-error  false
                                   :expanded? false}]
                           (-> a
                               (update :tool-calls #(if (contains? % id) % (assoc % id tc)))
                               (update :tool-order #(if (some #{id} %) % (conj % id)))
                               (update :messages conj {:role :tool :tool-id id})))

                         :else a))
                     acc
                     blocks)]
         (if (str/blank? text)
           acc'
           (update acc' :messages conj {:role :assistant :text text})))

       "toolResult"
       (let [id       (:tool-call-id msg)
             text     (tool-result->display-text msg)
             content  (:content msg)
             details  (:details msg)
             err?     (boolean (:is-error msg))
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

       acc))
   {:messages [] :tool-calls {} :tool-order []}
   messages))
