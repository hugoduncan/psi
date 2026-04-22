(ns psi.agent-session.tool-execution
  "Tool call execution — content normalization, output stats, lifecycle events,
   execute/record pipeline, and dispatch-owned tool-call phases.

   Public entry points:
   - start-tool-call!                       — emit tool start lifecycle/agent events
   - execute-tool-call!                     — perform execution and shape the result
   - execute-tool-call-prepared!            — dispatch-owned execute phase
   - record-tool-call-result!               — record one shaped execution result
   - record-tool-call-prepared-result!      — dispatch-owned record phase
   - run-tool-call-through-runtime-effect!  — full start→execute→record transaction"
  (:require
   [clojure.string :as str]
   [psi.agent-session.conversation :as conv-translate]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.turn-accumulator :as accum]))

;; ============================================================
;; Content helpers
;; ============================================================

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

;; ============================================================
;; Output stats
;; ============================================================

(defn- record-tool-output-stat!
  [ctx session-id {:keys [tool-call-id tool-name content details effective-policy]}]
  (let [truncation          (:truncation details)
        limit-hit?          (boolean (:truncated truncation))
        truncated-by        (or (:truncated-by truncation) :none)
        content-text        (tool-content->text content)
        output-bytes        (utf8-bytes content-text)
        stat                {:tool-call-id        tool-call-id
                             :tool-name           tool-name
                             :timestamp           (java.time.Instant/now)
                             :limit-hit           limit-hit?
                             :truncated-by        truncated-by
                             :effective-max-lines (:max-lines effective-policy)
                             :effective-max-bytes (:max-bytes effective-policy)
                             :output-bytes        output-bytes
                             :context-bytes-added output-bytes}]
    (sa/record-tool-output-stat-in! ctx session-id stat output-bytes limit-hit?)))

;; ============================================================
;; Lifecycle events
;; ============================================================

(defn tool-lifecycle-event
  "Build one canonical tool lifecycle event shape."
  [event-kind tool-id tool-name & {:as extra}]
  (merge {:event-kind event-kind :tool-id tool-id :tool-name tool-name} extra))

(defn- emit-tool-lifecycle!
  [ctx session-id lifecycle-event]
  (dispatch/dispatch! ctx
                      :session/tool-lifecycle-event
                      {:session-id session-id :entry lifecycle-event}
                      {:origin :core})
  lifecycle-event)

;; ============================================================
;; Effective policy
;; ============================================================

(defn- effective-tool-output-policy [ctx session-id tool-name]
  (tool-output/effective-policy
   (or (:tool-output-overrides (session/get-session-data-in ctx session-id)) {})
   tool-name))

;; ============================================================
;; Execute one tool call
;; ============================================================

(defn start-tool-call!
  "Emit the canonical tool-start lifecycle + agent-start side effects."
  [ctx session-id tool-call progress-queue]
  (accum/emit-progress!
   progress-queue
   (emit-tool-lifecycle!
    ctx session-id
    (tool-lifecycle-event :tool-start (:id tool-call) (:name tool-call))))
  (dispatch/dispatch! ctx :session/tool-agent-start
                      {:session-id session-id :tool-call tool-call}
                      {:origin :core}))

(defn- apply-post-tool-processing
  [ctx session-id tool-call args tool-result]
  (post-tool/run-post-tool-processing-in!
   ctx
   {:session-id    session-id
    :tool-name     (:name tool-call)
    :tool-call-id  (:id tool-call)
    :tool-args     args
    :tool-result   tool-result
    :worktree-path (session/session-worktree-path-in ctx session-id)}))

(defn execute-tool-call!
  "Execute one tool call and return a shaped result map before recording.

   Output: {:tool-call tc :tool-result raw :result-message msg :effective-policy policy}"
  [ctx session-id tool-call progress-queue]
  (let [call-id (:id tool-call)
        name    (:name tool-call)
        args    (or (:parsed-args tool-call)
                    (conv-translate/parse-args (:arguments tool-call)))]
    (accum/emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event :tool-executing call-id name
                            :arguments (:arguments tool-call)
                            :parsed-args (if (= "psi-tool" name)
                                           (psi-tool/telemetry-args args)
                                           args))))
    (let [sd              (session/get-session-data-in ctx session-id)
          opts            {:cwd          (session/session-worktree-path-in ctx session-id)
                           :overrides    (:tool-output-overrides sd)
                           :tool-call-id call-id
                           :on-update    (fn [{:keys [content details is-error]}]
                                           (let [content-blocks (normalize-tool-content content)
                                                 text-fallback  (tool-content->text content)]
                                             (accum/emit-progress!
                                              progress-queue
                                              (emit-tool-lifecycle!
                                               ctx session-id
                                               (tool-lifecycle-event :tool-execution-update call-id name
                                                                     :content content-blocks
                                                                     :result-text text-fallback
                                                                     :details details
                                                                     :is-error (boolean is-error))))))}
          raw-tool-result (or (dispatch/dispatch! ctx
                                                  :session/tool-execute
                                                  {:session-id session-id :tool-name name :args args :opts opts}
                                                  {:origin :core})
                              {:content "Error: tool execution returned no result" :is-error true})
          {:keys [content is-error details] :as tool-result}
          (apply-post-tool-processing ctx session-id tool-call args raw-tool-result)
          content-blocks  (normalize-tool-content content)
          text-fallback   (tool-content->text content)
          policy          (effective-tool-output-policy ctx session-id name)
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

;; ============================================================
;; Record result
;; ============================================================

(defn record-tool-call-result!
  "Record one shaped execution result into progress, telemetry, and agent-core."
  [ctx session-id {:keys [tool-call tool-result result-message effective-policy]} progress-queue]
  (let [call-id     (:id tool-call)
        name        (:name tool-call)
        result-text (or (:result-text result-message)
                        (tool-content->text (:content result-message)))
        {:keys [content is-error details]} tool-result]
    (accum/emit-progress!
     progress-queue
     (emit-tool-lifecycle!
      ctx session-id
      (tool-lifecycle-event :tool-result call-id name
                            :content (:content result-message)
                            :result-text result-text
                            :details details
                            :is-error is-error)))
    (record-tool-output-stat!
     ctx session-id
     {:tool-call-id     call-id
      :tool-name        name
      :content          content
      :details          details
      :effective-policy effective-policy})
    (dispatch/dispatch! ctx :session/tool-agent-end
                        {:session-id session-id :tool-call tool-call :result tool-result :is-error? is-error}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/tool-agent-record-result
                        {:session-id session-id :tool-result-msg result-message}
                        {:origin :core})
    result-message))

(defn execute-tool-call-prepared!
  "Dispatch-owned execute phase for one tool call.

   Emits start/executing/update lifecycle and returns a shaped result without
   recording the final tool result."
  [ctx session-id tool-call parsed-args progress-queue]
  (let [prepared-tool-call (assoc tool-call :parsed-args
                                  (or parsed-args
                                      (:parsed-args tool-call)
                                      (conv-translate/parse-args (:arguments tool-call))))]
    (start-tool-call! ctx session-id prepared-tool-call progress-queue)
    (try
      (execute-tool-call! ctx session-id prepared-tool-call progress-queue)
      (catch Exception e
        (let [err-text   (str "Error: " (ex-message e))
              result-msg {:role         "toolResult"
                          :tool-call-id (:id prepared-tool-call)
                          :tool-name    (:name prepared-tool-call)
                          :content      [{:type :text :text err-text}]
                          :is-error     true
                          :details      {:exception true}
                          :result-text  err-text
                          :timestamp    (java.time.Instant/now)}]
          {:tool-call        prepared-tool-call
           :tool-result      {:content err-text :is-error true}
           :result-message   result-msg
           :effective-policy nil})))))

(defn record-tool-call-prepared-result!
  "Dispatch-owned record phase for one previously prepared tool result."
  [ctx session-id shaped-result progress-queue]
  (record-tool-call-result! ctx session-id shaped-result progress-queue))

;; ============================================================
;; Public runtime-effect boundary
;; ============================================================

(defn run-tool-call-through-runtime-effect!
  "Compatibility helper that composes the dispatch-owned execute and record phases."
  [ctx session-id tool-call parsed-args progress-queue]
  (record-tool-call-prepared-result!
   ctx session-id
   (execute-tool-call-prepared! ctx session-id tool-call parsed-args progress-queue)
   progress-queue))
