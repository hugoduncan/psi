(ns psi.ai.providers.anthropic.message-transform
  (:require
   [taoensso.timbre :as timbre]
   [psi.ai.content :as ai-content]
   [psi.ai.providers.anthropic.tool-id :as tool-id]
   [psi.ai.structured-output :as structured-output]))

(defn- anthropic-cache-control
  [cache-control]
  (when (#{:ephemeral "ephemeral"} (:type cache-control))
    {:type "ephemeral"}))

(defn- with-cache-control
  [payload cache-control]
  (if-let [cache-control* (anthropic-cache-control cache-control)]
    (assoc payload :cache_control cache-control*)
    payload))

(defn- text-block
  ([text]
   {:type "text"
    :text (or text "")})
  ([text cache-control]
   (with-cache-control (text-block text)
     cache-control)))

(defn- user-text-blocks
  [content]
  (->> (ai-content/text-blocks content)
       (map (fn [block]
              (text-block (:text block)
                          (:cache-control block))))
       vec))

(defn- provider-text-blocks
  [content]
  (->> content
       (keep (fn [block]
               (when (= :text (:type block))
                 (text-block (:text block)
                             (or (:cache-control block)
                                 (:cache_control block))))))
       vec))

(defn- user-content
  [msg]
  (let [content (:content msg)]
    (cond
      (and (map? content)
           (= :text (:kind content)))
      [(text-block (:text content)
                   (:cache-control content))]

      (and (map? content)
           (= :structured (:kind content)))
      (user-text-blocks (:blocks content))

      (and (sequential? content)
           (seq content))
      (provider-text-blocks content)

      :else
      ;; Last-resort coercion: wrap whatever arrived as a plain text block so
      ;; the message list is never empty and the API call can still proceed.
      [(text-block (ai-content/content-text content))])))

(defn- assistant-thinking-block
  [block]
  (cond-> {:type     "thinking"
           :thinking (or (:text block) "")}
    (some? (:signature block)) (assoc :signature (:signature block))))

(defn- assistant-tool-use-block
  [canonical-id block]
  (with-cache-control {:type  "tool_use"
                       :id    (canonical-id (:id block))
                       :name  (:name block)
                       :input (if (map? (:input block))
                                (:input block)
                                {})}
    (:cache-control block)))

(defn- assistant-block
  [canonical-id block]
  (case (:kind block)
    :thinking
    (assistant-thinking-block block)

    :text
    (text-block (:text block)
                (:cache-control block))

    :tool-call
    (assistant-tool-use-block canonical-id block)

    ;; Intentional fallback: unknown block kinds are stringified as plain text
    ;; rather than dropped, so future block types degrade gracefully.
    (text-block (str block))))

(defn- assistant-content
  [msg canonical-id]
  (if (= :structured (get-in msg [:content :kind]))
    (let [{:keys [blocks]} (ai-content/assistant-content-parts msg)]
      (mapv (partial assistant-block canonical-id)
            blocks))
    [(text-block (or (ai-content/content-text msg) ""))]))

(defn- tool-result-block
  [msg canonical-id]
  (cond-> {:type        "tool_result"
           :tool_use_id (canonical-id (:tool-call-id msg))
           :content     (or (ai-content/content-text (:content msg)) "")}
    (:is-error msg) (assoc :is_error true)))

(defn- append-tool-result
  [acc block]
  (let [last-msg (peek acc)]
    (if (and (= "user" (:role last-msg))
             (every? #(= "tool_result" (:type %))
                     (:content last-msg)))
      (conj (pop acc) (update last-msg :content conj block))
      (conj acc {:role "user" :content [block]}))))

(defn- append-fallback-instructions-to-user-blocks
  [blocks fallback-request]
  (if fallback-request
    (let [last-text-index (last (keep-indexed (fn [idx block]
                                                (when (= "text" (:type block))
                                                  idx))
                                              blocks))]
      (if (some? last-text-index)
        (mapv (fn [idx block]
                (if (= idx last-text-index)
                  (update block
                          :text
                          structured-output/append-fallback-instructions-to-text
                          fallback-request)
                  block))
              (range)
              blocks)
        (conj (vec blocks)
              (text-block (structured-output/json-only-instruction fallback-request)))))
    blocks))

(defn- transform-message
  [canonical-id fallback-request acc msg]
  (case (:role msg)
    :user
    (conj acc {:role "user"
               :content (append-fallback-instructions-to-user-blocks
                         (user-content msg)
                         fallback-request)})

    :assistant
    (conj acc {:role "assistant"
               :content (assistant-content msg canonical-id)})

    :tool-result
    (append-tool-result acc (tool-result-block msg canonical-id))

    :system
    (conj acc {:role "system"
               :content (user-content msg)})

    acc))

(defn- valid-inline-system-placement?
  [acc]
  (= "user" (:role (peek acc))))

(defn- transform-message-with-placement
  [canonical-id fallback-request acc idx msg]
  (if (= :system (:role msg))
    (if (valid-inline-system-placement? acc)
      (transform-message canonical-id fallback-request acc msg)
      (do
        (timbre/warn {:role (:role msg)
                      :index idx
                      :previous-role (:role (peek acc))}
                     "Dropping invalid Anthropic inline system message placement")
        acc))
    (transform-message canonical-id fallback-request acc msg)))

(defn transform-messages
  "Transform conversation messages to Anthropic API format."
  ([conversation]
   (transform-messages conversation nil))
  ([conversation fallback-request]
   (let [canonical-id     (tool-id/canonical-tool-id-fn)
         last-user-index  (when fallback-request
                            (last (keep-indexed (fn [idx msg]
                                                  (when (= :user (:role msg))
                                                    idx))
                                                (:messages conversation))))]
     (->> (:messages conversation)
          (map-indexed vector)
          (reduce (fn [acc [idx msg]]
                    (transform-message-with-placement
                     canonical-id
                     (when (= idx last-user-index)
                       fallback-request)
                     acc
                     idx
                     msg))
                  [])))))
