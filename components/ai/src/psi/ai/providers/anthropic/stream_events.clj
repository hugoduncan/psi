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

    ;; Review 50: explicit branch — the redacted_thinking_delta skip must not
    ;; depend on the delta's current shape. Before this branch the type fell
    ;; through to the default text branch and returned nil only because
    ;; redacted_thinking_delta currently carries no :text key (it carries
    ;; :data) — if Anthropic ever sends a redacted_thinking_delta with a
    ;; :text key (or renames the payload field), the block would emit a
    ;; :text-delta with no :text-start: a phantom text delta for a block
    ;; whose start/stop are skipped (content-block-start-event /
    ;; content-block-stop-event), leaving unbalanced block events (the
    ;; accumulator's note-content-delta! would open a block at an unbegun
    ;; index while start/stop stay nil). Explicit nil makes the skip
    ;; symmetric with start/stop and shape-independent.
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
