(ns psi.tool-runtime.core
  "Lower-level tool execution mechanics: content normalization, canonical tool
   event shaping, generic execute/record phases, and callback-based lifecycle
   delivery."
  (:require
   [clojure.string :as str]
   [psi.tool-runtime.args :as tool-args]
   [psi.tool-runtime.call-summary :as call-summary]))

(defn- utf8-bytes [s]
  (count (.getBytes (str (or s "")) "UTF-8")))

(defn tool-content->text
  "Extract plain text from tool content (string, block-vec, or other)."
  [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
         (keep (fn [block] (when (= :text (:type block)) (:text block))))
         (str/join "\n"))

    :else
    (str (or content ""))))

(defn normalize-tool-content
  "Coerce tool content into a vec of {:type :text :text ...} blocks."
  [content]
  (cond
    (sequential? content) (vec content)
    (string? content)     [{:type :text :text content}]
    (nil? content)        [{:type :text :text ""}]
    :else                 [{:type :text :text (str content)}]))

(defn normalize-tool-result
  "Coerce raw tool execution output into the canonical result map shape.

   Registered tools may return a plain string as successful textual output.
   Normalize before post-tool processing so downstream enrichment, lifecycle,
   and provider-facing toolResult shaping all see the same map contract."
  [result]
  (cond
    (map? result) result
    (nil? result) {:content "Error: tool execution returned no result"
                   :is-error true}
    :else {:content result
           :is-error false}))

(defn tool-lifecycle-event
  "Build one canonical tool lifecycle event shape."
  [event-kind tool-call-id tool-name & {:as extra}]
  (merge {:event-kind   event-kind
          :tool-call-id tool-call-id
          :tool-id      tool-call-id
          :tool-name    tool-name}
         extra))

(defn emit-tool-event!
  "Deliver a canonical tool event to the optional `on-event` callback."
  [on-event event]
  (when on-event
    (on-event event))
  event)

(defn start-tool-call!
  "Emit the canonical tool-start event."
  [{:keys [on-event tool-def-fn]} tool-call]
  (let [tool-def (when tool-def-fn
                   (tool-def-fn (:name tool-call)))
        summary  (call-summary/format-call-summary {:tool tool-def
                                                    :tool-name (:name tool-call)
                                                    :parsed-args (:parsed-args tool-call)
                                                    :arguments (:arguments tool-call)})]
    (emit-tool-event! on-event
                      (tool-lifecycle-event :tool-start
                                            (:id tool-call)
                                            (:name tool-call)
                                            :arguments (:arguments tool-call)
                                            :parsed-args (:parsed-args tool-call)
                                            :call-summary summary))))

(defn execute-tool-call!
  "Execute one tool call and return a shaped result map before recording.

   Services map keys:
   - :execute-tool       fn [tool-name args opts] -> raw result
   - :post-process       fn [tool-call args raw-result] -> shaped result
   - :effective-policy   fn [tool-name] -> output policy
   - :telemetry-args-fn  fn [tool-name args] -> args projected into events
   - :on-event           fn [event]
   - :execute-opts       map passed to execute-tool

   Output: {:tool-call tc :tool-result raw :result-message msg :effective-policy policy}"
  [{:keys [execute-tool post-process effective-policy telemetry-args-fn on-event execute-opts tool-def-fn]}
   tool-call
   parsed-args]
  (let [call-id        (:id tool-call)
        name           (:name tool-call)
        args           (or parsed-args
                           (:parsed-args tool-call)
                           (tool-args/parse-args (:arguments tool-call)))
        telemetry-args (if telemetry-args-fn
                         (telemetry-args-fn name args)
                         args)
        tool-def       (when tool-def-fn
                         (tool-def-fn name))
        summary        (call-summary/format-call-summary {:tool tool-def
                                                          :tool-name name
                                                          :parsed-args args
                                                          :arguments (:arguments tool-call)})]
    (emit-tool-event! on-event
                      (tool-lifecycle-event :tool-executing call-id name
                                            :arguments (:arguments tool-call)
                                            :parsed-args telemetry-args
                                            :call-summary summary))
    (let [raw-tool-result (normalize-tool-result
                           (execute-tool
                            name
                            args
                            (assoc execute-opts
                                   :on-update
                                   (fn [{:keys [content details is-error]}]
                                     (let [content-blocks (normalize-tool-content content)
                                           text-fallback  (tool-content->text content)]
                                       (emit-tool-event! on-event
                                                         (tool-lifecycle-event :tool-execution-update
                                                                               call-id
                                                                               name
                                                                               :content content-blocks
                                                                               :result-text text-fallback
                                                                               :details details
                                                                               :is-error (boolean is-error)
                                                                               :call-summary summary)))))))
          {:keys [content is-error details] :as tool-result}
          (normalize-tool-result
           (if post-process
             (post-process tool-call args raw-tool-result)
             raw-tool-result))
          content-blocks  (normalize-tool-content content)
          text-fallback   (tool-content->text content)
          policy          (when effective-policy
                            (effective-policy name))
          result-msg      {:role         "toolResult"
                           :tool-call-id call-id
                           :tool-name    name
                           :content      content-blocks
                           :is-error     is-error
                           :details      details
                           :result-text  text-fallback
                           :timestamp    (java.time.Instant/now)}]
      {:tool-call        tool-call
       :tool-result      tool-result
       :result-message   result-msg
       :effective-policy policy})))

(defn record-tool-call-result!
  "Record one shaped execution result using generic callbacks.

   Services map keys:
   - :on-event             fn [event]
   - :record-output-stat!  fn [{...}] ; optional
   - :on-agent-end!        fn [tool-call tool-result is-error?] ; optional
   - :record-result!       fn [result-message] ; optional"
  [{:keys [on-event record-output-stat! on-agent-end! record-result!]}
   {:keys [tool-call tool-result result-message effective-policy]}]
  (let [call-id     (:id tool-call)
        name        (:name tool-call)
        result-text (or (:result-text result-message)
                        (tool-content->text (:content result-message)))
        {:keys [content is-error details]} tool-result]
    (emit-tool-event! on-event
                      (tool-lifecycle-event :tool-result call-id name
                                            :content (:content result-message)
                                            :result-text result-text
                                            :details details
                                            :is-error is-error
                                            :call-summary (:call-summary tool-call)))
    (when record-output-stat!
      (let [truncation   (:truncation details)
            limit-hit?   (boolean (:truncated truncation))
            truncated-by (or (:truncated-by truncation) :none)
            content-text (tool-content->text content)
            output-bytes (utf8-bytes content-text)
            stat         {:tool-call-id        call-id
                          :tool-name           name
                          :timestamp           (java.time.Instant/now)
                          :limit-hit           limit-hit?
                          :truncated-by        truncated-by
                          :effective-max-lines (:max-lines effective-policy)
                          :effective-max-bytes (:max-bytes effective-policy)
                          :output-bytes        output-bytes
                          :context-bytes-added output-bytes}]
        (record-output-stat! {:stat stat
                              :context-bytes-added output-bytes
                              :limit-hit? limit-hit?})))
    (when on-agent-end!
      (on-agent-end! tool-call tool-result is-error))
    (when record-result!
      (record-result! result-message))
    result-message))

(defn- exception-tool-result
  [tool-call err-text details]
  (let [call-id    (:id tool-call)
        tool-name  (:name tool-call)
        content    [{:type :text :text err-text}]
        result-msg {:role         "toolResult"
                    :tool-call-id call-id
                    :tool-name    tool-name
                    :content      content
                    :is-error     true
                    :details      details
                    :result-text  err-text
                    :timestamp    (java.time.Instant/now)}]
    {:tool-call      tool-call
     :tool-result    {:content err-text :is-error true :details details}
     :result-message result-msg}))

(defn- emit-tool-error!
  [on-event tool-call err-text details]
  (emit-tool-event! on-event
                    (tool-lifecycle-event :tool-error
                                          (:id tool-call)
                                          (:name tool-call)
                                          :content [{:type :text :text err-text}]
                                          :result-text err-text
                                          :details details
                                          :is-error true
                                          :call-summary (:call-summary tool-call))))

(defn execute-tool-call-prepared!
  "Generic execute phase for one tool call.

   Emits start/executing/update lifecycle and returns a shaped result without
   recording the final tool result. On exception emits `:tool-error` and returns
   a shaped error result."
  [{:keys [on-event] :as services} tool-call parsed-args]
  (let [prepared-tool-call (assoc tool-call :parsed-args
                                  (or parsed-args
                                      (:parsed-args tool-call)
                                      (tool-args/parse-args (:arguments tool-call))))
        tool-def           (when-let [tool-def-fn (:tool-def-fn services)]
                             (tool-def-fn (:name tool-call)))
        call-summary       (call-summary/format-call-summary {:tool tool-def
                                                              :tool-name (:name tool-call)
                                                              :parsed-args (:parsed-args prepared-tool-call)
                                                              :arguments (:arguments prepared-tool-call)})
        prepared-tool-call (assoc prepared-tool-call :call-summary call-summary)]
    (start-tool-call! services prepared-tool-call)
    (try
      (execute-tool-call! services prepared-tool-call (:parsed-args prepared-tool-call))
      (catch Exception e
        (let [err-text (str "Error: " (ex-message e))
              details  {:exception true}]
          (emit-tool-error! on-event prepared-tool-call err-text details)
          (assoc (exception-tool-result prepared-tool-call err-text details)
                 :effective-policy nil))))))

(defn record-tool-call-prepared-result!
  "Generic record phase for one previously prepared tool result."
  [services shaped-result]
  (record-tool-call-result! services shaped-result))

(defn run-tool-call-through-runtime-effect!
  "Compose the generic execute and record phases for one tool call."
  [services tool-call parsed-args]
  (record-tool-call-prepared-result!
   services
   (execute-tool-call-prepared! services tool-call parsed-args)))
