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

    ;; Review 48: "redacted_thinking" blocks (Anthropic's first thinking
    ;; block in extended-thinking streams, carrying opaque base64 :data) are
    ;; SKIPPED — no phantom text block, no mislabeled :text-start. The block
    ;; is redacted (no usable content), so mapping it to a thinking block
    ;; would only add an empty/meaningless transcript entry; the stop event
    ;; is skipped symmetrically (content-block-stop-event) so no unbalanced
    ;; block event can create a phantom closed block downstream.
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
    ;; Review 48: skipped symmetrically with content-block-start-event —
    ;; a redacted_thinking block's stop must not emit :text-end (mislabeled
    ;; marker) nor an unbalanced :thinking-end for a block whose start was
    ;; skipped (a phantom closed block in the accumulator).
    "redacted_thinking" nil
    {:type          :text-end
     :content-index idx}))

(defn consume-event!
  [consume-fn event]
  (when event
    (consume-fn event)))
