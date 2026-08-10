(ns psi.ai.providers.anthropic.stream-events
  "Anthropic stream content-block -> provider event shaping.

  Maps raw Anthropic SSE content_block_start/delta/stop payloads to the
  provider event stream consumed by the accumulator, and guards nil events
  (skipped blocks, e.g. Anthropic's redacted_thinking) so no phantom or
  unbalanced block event reaches the consumer.")

(defn content-block-start-event
  [idx block]
  (case (:type block)
    "tool_use"
    {:type          :toolcall-start
     :content-index idx
     :id            (:id block)
     :name          (:name block)}

    "thinking"
    {:type          :thinking-start
     :content-index idx
     :thinking      (:thinking block)
     :signature     (:signature block)}

    "redacted_thinking"
    nil

    {:type          :text-start
     :content-index idx}))

(defn content-block-delta-event
  [btype idx delta]
  (case btype
    "tool_use"
    (when-let [json-delta (:partial_json delta)]
      {:type          :toolcall-delta
       :content-index idx
       :delta         json-delta})

    "thinking"
    (cond
      (some? (:signature delta))
      {:type          :thinking-signature-delta
       :content-index idx
       :signature     (:signature delta)}

      :else
      (when-let [text (or (:thinking delta) (:text delta))]
        {:type          :thinking-delta
         :content-index idx
         :delta         text}))

    "redacted_thinking"
    nil

    (when-let [text (:text delta)]
      {:type          :text-delta
       :content-index idx
       :delta         text})))

(defn content-block-stop-event
  [btype idx]
  (case btype
    "tool_use" {:type          :toolcall-end
                :content-index idx}
    "thinking" {:type          :thinking-end
                :content-index idx}
    "redacted_thinking" nil
    {:type          :text-end
     :content-index idx}))

(defn consume-event!
  [consume-fn event]
  (when event
    (consume-fn event)))
