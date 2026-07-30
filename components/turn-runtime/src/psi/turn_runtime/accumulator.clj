(ns psi.turn-runtime.accumulator
  "Streaming turn accumulation — content blocks, text merging, tool-call assembly,
   and the make-turn-actions factory that drives the per-turn statechart.

   Owns all mutable state updates to the turn-data atom during a streaming turn."
  (:require
   [clojure.string :as str]
   [psi.ai.textual-tool-calls :as textual-tool-calls]
   [psi.turn-runtime.state :as trs]
   [psi.tool-runtime.args :as tool-args]
   [psi.turn-statechart.core :as turn-sc]))

(defn emit-progress!
  "Emit a progress event to the progress queue (if provided).
   Events are maps with :type :agent-event and :event-kind."
  [progress-queue event]
  (when progress-queue
    (.offer ^java.util.concurrent.LinkedBlockingQueue progress-queue
            (assoc event :type :agent-event))))

(defn- note-last-provider-event! [turn-data event-type data]
  (swap! turn-data assoc
         :last-provider-event
         (cond-> {:type      event-type
                  :timestamp (java.time.Instant/now)}
           (contains? data :content-index) (assoc :content-index (:content-index data))
           (contains? data :reason)        (assoc :reason (:reason data))
           (contains? data :http-status)   (assoc :http-status (:http-status data))
           (contains? data :error-message) (assoc :error-message (:error-message data))
           (contains? data :headers)       (assoc :headers (:headers data)))))

(defn- update-content-block! [turn-data idx f]
  (swap! turn-data update :content-blocks
         (fn [blocks]
           (let [blocks* (or blocks (sorted-map))
                 current (get blocks* idx {:content-index idx
                                           :kind          :unknown
                                           :status        :open
                                           :delta-count   0})]
             (assoc blocks* idx (f current))))))

(defn- begin-content-block! [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (assoc current :content-index idx :status :open :ended-at nil)]
         (cond-> block (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- note-content-delta! [turn-data idx kind]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (let [block (-> current
                       (assoc :content-index idx :kind kind :status :open :last-delta-at ts)
                       (update :delta-count (fnil inc 0)))]
         (cond-> block (nil? (:started-at block)) (assoc :started-at ts)))))))

(defn- end-content-block! [turn-data idx]
  (let [ts (java.time.Instant/now)]
    (update-content-block!
     turn-data idx
     (fn [current]
       (assoc current :content-index idx :status :closed :ended-at ts)))))

(defn- common-prefix-length [a b]
  (let [a*    (or a "")
        b*    (or b "")
        limit (min (count a*) (count b*))]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (.charAt ^String a* idx) (.charAt ^String b* idx)))
        (recur (inc idx))
        idx))))

(defn merge-stream-text
  "Merge current streamed text with incoming provider chunk.
   Handles both incremental deltas (append) and cumulative snapshots (replace)."
  [current incoming]
  (let [current*  (or current "")
        incoming* (or incoming "")]
    (cond
      (empty? incoming*) current*
      (empty? current*)  incoming*
      (str/starts-with? incoming* current*) incoming*
      :else
      (let [cur-len    (count current*)
            in-len     (count incoming*)
            cp         (common-prefix-length current* incoming*)
            tail-close (and (> in-len cur-len) (>= cp (max 1 (dec cur-len))))]
        (if tail-close incoming* (str current* incoming*))))))

(defn- canonical-tool-call-id [turn-id content-index provider-tool-call-id]
  (or provider-tool-call-id (str turn-id "/toolcall/" content-index)))

(defn complete-tool-calls [turn-id tool-calls]
  (->> tool-calls
       (sort-by key)
       (mapv (fn [[idx tc]]
               (let [content-index (or (:content-index tc) idx)]
                 (-> tc
                     (assoc :content-index content-index)
                     (update :id #(canonical-tool-call-id turn-id content-index %))))))))

(defn invalid-tool-call [tc]
  (let [{:keys [ok?]} (tool-args/parse-args-strict (:arguments tc))]
    (cond
      (or (nil? (:id tc)) (str/blank? (str (:id tc))))
      {:reason :missing-call-id
       :message "Tool call missing call_id; cannot execute reliably"
       :tool-call tc}

      (or (nil? (:name tc)) (str/blank? (str (:name tc))))
      {:reason :missing-tool-name
       :message "Tool call missing name; cannot execute reliably"
       :tool-call tc}

      (not ok?)
      {:reason :invalid-arguments
       :message "Tool call arguments invalid; expected JSON object"
       :tool-call tc}

      :else nil)))

(defn- thinking-blocks-in-order [thinking-blocks]
  (->> thinking-blocks
       vals
       (sort-by :content-index)
       (mapv (fn [{:keys [text provider signature]}]
               (cond-> {:type :thinking :text (or text "")}
                 provider  (assoc :provider provider)
                 signature (assoc :signature signature))))))

(defn- text-content-blocks
  ([text-buffer text-content-index]
   (cond-> []
     (seq text-buffer)
     (conj {:type :text :content-index (or text-content-index 0) :text text-buffer})))
  ([text-buffer text-content-index text-blocks]
   (let [blocks (->> (or text-blocks {})
                     (sort-by key)
                     (keep (fn [[idx {:keys [text]}]]
                             (when (seq text)
                               {:type :text :content-index idx :text text})))
                     vec)]
     (if (seq blocks)
       blocks
       (text-content-blocks text-buffer text-content-index)))))

(defn- error-content-blocks
  [invalids]
  (mapv (fn [inv]
          {:type :error
           :content-index (get-in inv [:tool-call :content-index])
           :text (:message inv)})
        invalids))

(defn- tool-content-blocks
  [tool-calls]
  (mapv (fn [tc]
          {:type :tool-call
           :content-index (:content-index tc)
           :id (:id tc)
           :name (:name tc)
           :arguments (:arguments tc)
           :call-summary (:call-summary tc)})
        tool-calls))

(defn- strip-content-index
  [block]
  (dissoc block :content-index))

(defn- build-final-content-indexed
  [thinking-blocks text-buffer text-content-index text-blocks tool-calls]
  (let [invalids       (keep invalid-tool-call tool-calls)
        valid-calls    (remove invalid-tool-call tool-calls)
        thinking-parts (thinking-blocks-in-order thinking-blocks)
        content-parts  (->> (concat (text-content-blocks text-buffer text-content-index text-blocks)
                                    (error-content-blocks invalids)
                                    (tool-content-blocks valid-calls))
                            (sort-by #(or (:content-index %) 0)))]
    (into thinking-parts content-parts)))

(defn build-final-content
  ([thinking-blocks text-buffer tool-calls]
   (build-final-content thinking-blocks text-buffer nil nil tool-calls))
  ([thinking-blocks text-buffer text-content-index tool-calls]
   (build-final-content thinking-blocks text-buffer text-content-index nil tool-calls))
  ([thinking-blocks text-buffer text-content-index text-blocks tool-calls]
   (mapv strip-content-index
         (build-final-content-indexed thinking-blocks
                                      text-buffer
                                      text-content-index
                                      text-blocks
                                      tool-calls))))

(defn- emit-tool-assembly-errors! [progress-queue tool-calls]
  (doseq [invalid (keep invalid-tool-call tool-calls)]
    (emit-progress! progress-queue
                    {:event-kind :error
                     :error      (:message invalid)
                     :detail     (:reason invalid)
                     :error-code "tool-call/assembly-failed"})))

(defn- provider-id [provider]
  (cond
    (keyword? provider) (name provider)
    (symbol? provider)  (name provider)
    (string? provider)  provider
    :else               (str (or provider ""))))

(defn anthropic-provider? [ai-model]
  (= "anthropic" (provider-id (:provider ai-model))))

(defn reset-thinking-buffers-on-toolcall-start!
  "Clear thinking buffers at a tool-call boundary.
   Anthropic: clear only the matching index. OpenAI: clear all."
  [thinking-buffers ai-model content-index]
  (if (anthropic-provider? ai-model)
    (swap! thinking-buffers dissoc content-index)
    (reset! thinking-buffers {})))

(defn- content-index [data]
  (or (:content-index data) 0))

(defn- anthropic-thinking-block [idx data]
  {:content-index idx
   :provider      :anthropic
   :text          (or (:thinking data) "")
   :signature     (:signature data)})

(defn- update-thinking-block-text [blocks idx merged]
  (let [blocks* (or blocks (sorted-map))
        current (get blocks* idx {:content-index idx :provider :anthropic :signature nil})]
    (assoc blocks* idx (assoc current :text merged))))

(defn- update-thinking-block-signature [blocks idx signature]
  (let [blocks* (or blocks (sorted-map))
        current (get blocks* idx {:content-index idx :provider :anthropic :text ""})]
    (assoc blocks* idx (assoc current :signature signature))))

(defn- canonical-provider-tool-call-id [turn-id tool-id]
  (when-not (str/starts-with? (str tool-id) (str turn-id "/toolcall/"))
    tool-id))

(defn- update-tool-call-start [td idx tc-id tc-name]
  (get-in (swap! td update-in [:tool-calls idx]
                 (fn [cur]
                   (let [merged (merge {:id nil :name nil :arguments "" :content-index idx}
                                       cur
                                       {:id (or tc-id (:id cur))
                                        :name (or tc-name (:name cur))
                                        :content-index idx})]
                     (assoc merged :id (canonical-tool-call-id (:turn-id @td) idx (:id merged))))))
          [:tool-calls idx]))

(defn- update-tool-call-delta [td idx delta]
  (get-in (swap! td update-in [:tool-calls idx]
                 (fn [cur]
                   (let [current-args (or (:arguments cur) "")
                         merged       (merge {:id nil :name nil :arguments "" :content-index idx}
                                             cur
                                             {:arguments     (str current-args (or delta ""))
                                              :content-index idx})]
                     (assoc merged :id (canonical-tool-call-id (:turn-id @td) idx (:id merged))))))
          [:tool-calls idx]))

(defn- emit-tool-assembly-progress! [progress-queue td phase idx updated provider-tool-id]
  (emit-progress! progress-queue
                  {:event-kind            :tool-call-assembly
                   :phase                 phase
                   :turn-id               (:turn-id @td)
                   :content-index         idx
                   :tool-id               (:id updated)
                   :provider-tool-call-id provider-tool-id
                   :tool-name             (:name updated)
                   :arguments             (:arguments updated)}))

(defn- handle-text-start! [td data]
  (let [idx (content-index data)]
    (note-last-provider-event! td :text-start data)
    (begin-content-block! td idx)
    (update-content-block! td idx #(assoc % :kind :text))))

(defn- handle-text-delta! [td progress-queue data]
  (let [idx    (content-index data)
        merged (get-in (swap! td (fn [td*]
                                   (-> td*
                                       (update-in [:text-blocks idx :text]
                                                  merge-stream-text (:delta data))
                                       (assoc-in [:text-blocks idx :content-index] idx)
                                       (update :text-buffer merge-stream-text (:delta data))
                                       (update :text-content-index #(or % idx)))))
                       [:text-blocks idx :text])]
    (note-last-provider-event! td :text-delta data)
    (note-content-delta! td idx :text)
    (emit-progress! progress-queue {:event-kind :text-delta :content-index idx :text merged})))

(defn- handle-thinking-start! [td ai-model data]
  (let [idx (content-index data)]
    (note-last-provider-event! td :thinking-start data)
    (begin-content-block! td idx)
    (update-content-block! td idx #(assoc % :kind :thinking))
    (when (anthropic-provider? ai-model)
      (swap! td update :thinking-blocks
             (fnil assoc (sorted-map)) idx
             (anthropic-thinking-block idx data)))))

(defn- handle-thinking-delta! [td progress-queue ai-model thinking-buffers data]
  (let [idx    (content-index data)
        raw    (let [d (:delta data)] (if (string? d) d (str (or d ""))))
        merged (get (swap! thinking-buffers update idx merge-stream-text raw) idx)]
    (note-last-provider-event! td :thinking-delta data)
    (note-content-delta! td idx :thinking)
    (when (anthropic-provider? ai-model)
      (swap! td update :thinking-blocks update-thinking-block-text idx merged))
    (emit-progress! progress-queue {:event-kind :thinking-delta :content-index idx :text merged})))

(defn- handle-thinking-signature-delta! [td ai-model data]
  (let [idx (content-index data)]
    (when (anthropic-provider? ai-model)
      (swap! td update :thinking-blocks update-thinking-block-signature idx (:signature data)))))

(defn- handle-toolcall-start! [ctx session-id td progress-queue ai-model thinking-buffers data]
  (let [idx     (:content-index data)
        tc-id   (:tool-id data)
        tc-name (:tool-name data)
        updated (update-tool-call-start td idx tc-id tc-name)]
    (reset-thinking-buffers-on-toolcall-start! thinking-buffers ai-model idx)
    (note-last-provider-event! td :toolcall-start data)
    (begin-content-block! td idx)
    (update-content-block! td idx #(assoc % :kind :tool-call))
    (trs/append-tool-call-attempt-in! ctx session-id
                                      {:turn-id       (:turn-id @td)
                                       :event-kind    :toolcall-start
                                       :content-index idx
                                       :id            tc-id
                                       :name          tc-name})
    (emit-tool-assembly-progress! progress-queue td :start idx updated
                                  (when (= tc-id (:id updated)) tc-id))))

(defn- handle-toolcall-delta! [ctx session-id td progress-queue data]
  (let [idx     (:content-index data)
        delta   (:delta data)
        updated (update-tool-call-delta td idx delta)]
    (note-last-provider-event! td :toolcall-delta data)
    (note-content-delta! td idx :tool-call)
    (trs/append-tool-call-attempt-in! ctx session-id
                                      {:turn-id       (:turn-id @td)
                                       :event-kind    :toolcall-delta
                                       :content-index idx
                                       :delta         delta})
    (emit-tool-assembly-progress! progress-queue td :delta idx updated
                                  (canonical-provider-tool-call-id (:turn-id @td) (:id updated)))))

(defn- handle-toolcall-end! [ctx session-id td progress-queue data]
  (let [idx     (:content-index data)
        updated (get-in @td [:tool-calls idx])]
    (note-last-provider-event! td :toolcall-end data)
    (end-content-block! td idx)
    (trs/append-tool-call-attempt-in! ctx session-id
                                      {:turn-id       (:turn-id @td)
                                       :event-kind    :toolcall-end
                                       :content-index idx})
    (emit-tool-assembly-progress! progress-queue td :end idx updated
                                  (canonical-provider-tool-call-id (:turn-id @td) (:id updated)))))

(defn- handle-logprob-delta! [td data]
  (swap! td update :logprob-buffer (fnil conj []) (:tokens data)))

(defn- handle-structured-output-strategy! [td data]
  (swap! td assoc :structured-output-strategy (:structured-output data)))

(defn- handle-structured-output-result! [td data]
  (swap! td assoc :structured-output-result (:structured-output data)))

(defn- handle-done! [td done-p progress-queue data]
  (let [{:keys [thinking-blocks text-buffer text-content-index text-blocks tool-calls logprob-buffer]} @td
        completed (complete-tool-calls (:turn-id @td) tool-calls)
        content   (build-final-content-indexed thinking-blocks text-buffer text-content-index text-blocks completed)
        usage     (:usage data)
        stop-reason (or (:reason data) :stop)
        logprobs  (when (seq logprob-buffer) (into [] cat logprob-buffer))
        final     (cond-> (textual-tool-calls/normalize-assistant-message
                           (:turn-id @td)
                           (:ai-model @td)
                           {:role        "assistant"
                            :content     content
                            :stop-reason stop-reason
                            :timestamp   (java.time.Instant/now)})
                    true (update :content #(mapv strip-content-index %))
                    (map? usage) (assoc :usage usage))]
    (note-last-provider-event! td :done data)
    (emit-tool-assembly-errors! progress-queue completed)
    (swap! td assoc :final-message final :stop-reason stop-reason :logprobs logprobs)
    (deliver done-p final)))

(defn- handle-error! [td done-p data]
  (let [{:keys [text-buffer]} @td
        stop-reason (or (:stop-reason data) :error)
        err-msg     (or (:error-message data)
                        (when (= :aborted stop-reason) "Aborted")
                        "Unknown error")
        content (cond-> []
                  (seq text-buffer) (conj {:type :text :text text-buffer})
                  :always           (conj {:type :error :text err-msg}))
        headers (or (:provider-error/headers data)
                    (:headers data))
        final   (cond-> {:role          "assistant"
                         :content       content
                         :stop-reason   stop-reason
                         :error-message err-msg
                         :timestamp     (java.time.Instant/now)}
                  (:http-status data) (assoc :http-status (:http-status data))
                  headers (assoc :provider-error/headers headers))]
    (note-last-provider-event! td :error data)
    (swap! td assoc :final-message final :error-message err-msg :stop-reason stop-reason)
    (deliver done-p final)))

(defn make-turn-actions
  "Create the actions-fn for the per-turn statechart.
   Handles data accumulation (in turn-data atom) and session-data writes.
   When progress-queue is non-nil, emits :agent-event messages for TUI.

   Tool-call identity semantics:
   - content-index is the provisional per-turn identity during assembly
   - every tool call is upgraded to a canonical tool-call-id by execution time
   - lifecycle/result events must target rows by canonical tool-call-id

   `ai-model`         — for provider-specific thinking accumulation (Anthropic vs OpenAI)
   `thinking-buffers` — atom({content-index → merged-text}) for per-block accumulation"
  [ctx session-id done-p progress-queue ai-model thinking-buffers]
  (fn [action-key data]
    (let [td (:turn-data data)]
      (case action-key
        :on-stream-start (note-last-provider-event! td :start data)
        :on-text-start (handle-text-start! td data)
        :on-text-delta (handle-text-delta! td progress-queue data)
        :on-text-end (do (note-last-provider-event! td :text-end data)
                         (end-content-block! td (content-index data)))
        :on-thinking-start (handle-thinking-start! td ai-model data)
        :on-thinking-delta (handle-thinking-delta! td progress-queue ai-model thinking-buffers data)
        :on-thinking-signature-delta (handle-thinking-signature-delta! td ai-model data)
        :on-thinking-end (do (note-last-provider-event! td :thinking-end data)
                             (end-content-block! td (content-index data)))
        :on-toolcall-start (handle-toolcall-start! ctx session-id td progress-queue ai-model thinking-buffers data)
        :on-toolcall-delta (handle-toolcall-delta! ctx session-id td progress-queue data)
        :on-toolcall-end (handle-toolcall-end! ctx session-id td progress-queue data)
        :on-logprob-delta (handle-logprob-delta! td data)
        :on-structured-output-strategy (handle-structured-output-strategy! td data)
        :on-structured-output-result (handle-structured-output-result! td data)
        :on-done (handle-done! td done-p progress-queue data)
        :on-error (handle-error! td done-p data)
        :on-reset (reset! td (turn-sc/create-turn-data))
        nil))))
