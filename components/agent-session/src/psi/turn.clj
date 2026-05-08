(ns psi.turn
  "Authoritative turn lifecycle API.

   Owns the canonical submit/start -> prepare -> execute -> record ->
   continue/finish flow while lower-level prompt namespaces remain migration
   seams or shared utilities."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.session-persistence.core :as persist]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.runtime :as runtime]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

(defn build-prepared-request
  [ctx session-id opts]
  (prompt-request/build-prepared-request ctx session-id opts))

(defn execute-prepared-request!
  [ai-ctx ctx session-id prepared-request progress-queue]
  (turn-runtime/execute-prepared-request! ai-ctx ctx session-id prepared-request progress-queue))

(defn execute-prepared-request-and-journal!
  "Execute one prepared request and append the resulting assistant message to
   the canonical session journal. Returns the shaped execution-result map."
  [ai-ctx ctx session-id prepared-request progress-queue]
  (let [execution-result (execute-prepared-request! ai-ctx ctx session-id prepared-request progress-queue)
        assistant-msg    (:execution-result/assistant-message execution-result)]
    (dispatch/dispatch! ctx :session/append-journal-entry
                        {:session-id session-id
                         :entry (persist/message-entry assistant-msg)}
                        {:origin :core})
    execution-result))

(defn build-record-response
  [session-id execution-result progress-queue]
  (prompt-recording/build-record-response session-id execution-result progress-queue))

(defn- extract-text-from-content-blocks
  "Extract :text values from agent-core message content blocks."
  [messages]
  (keep (fn [msg]
          (some (fn [block]
                  (when (= :text (:type block))
                    (:text block)))
                (:content msg)))
        messages))

(defn- merge-text-sources
  "Deduplicate, trim, and join text fragments from multiple sources."
  [& text-colls]
  (->> (apply concat text-colls)
       (keep #(when (string? %) (str/trim %)))
       (remove str/blank?)
       distinct
       (str/join "\n")))

(defn prompt-dispatch!
  [ctx session-id text images opts]
  (when-not (ss/idle-in? ctx session-id)
    (throw (ex-info "Session is not idle" {:phase (ss/sc-phase-in ctx session-id)})))
  (let [user-msg {:role      "user"
                  :content   (cond-> [{:type :text :text text}]
                               images (into images))
                  :timestamp (java.time.Instant/now)}
        turn-id  (:turn-id (dispatch/dispatch! ctx :session/prompt-submit
                                               {:session-id session-id :user-msg user-msg}
                                               {:origin :core}))
        _        (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
        result   (dispatch/dispatch! ctx :session/prompt-prepare-request
                                     (cond-> {:session-id session-id
                                              :turn-id    turn-id
                                              :user-msg   user-msg}
                                       (:progress-queue opts)
                                       (assoc :progress-queue (:progress-queue opts))
                                       (:runtime-opts opts)
                                       (assoc :runtime-opts (:runtime-opts opts))
                                       (:return-execution-result? opts)
                                       (assoc :return-execution-result? true))
                                     {:origin :core})]
    (runtime/safe-maybe-sync-on-git-head-change! ctx session-id)
    result))

(defn prompt-in!
  "Submit `text` (and optional `images`) to the agent for `session-id`.
   Requires the session to be idle."
  ([ctx session-id text]
   (prompt-in! ctx session-id text nil))
  ([ctx session-id text images]
   (prompt-in! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (prompt-dispatch! ctx session-id text images opts)))

(defn prompt-execution-result-in!
  "Submit `text` to the agent for `session-id` and return the shaped
   execution-result for the completed turn instead of the prepared-request map."
  ([ctx session-id text]
   (prompt-execution-result-in! ctx session-id text nil))
  ([ctx session-id text images]
   (prompt-execution-result-in! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (prompt-dispatch! ctx session-id text images (assoc (or opts {}) :return-execution-result? true))))

(defn last-assistant-message-in
  "Return the last assistant message from the session journal, or nil."
  [ctx session-id]
  (some (fn [message]
          (when (= "assistant" (:role message))
            message))
        (rseq (vec (persist/messages-from-entries-in ctx session-id)))))

(defn steer-in!
  "Inject a steering message while the agent is streaming for `session-id`."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-steering-message {:session-id session-id :text text} {:origin :core}))

(defn follow-up-in!
  "Queue a follow-up message for delivery after the current agent run for `session-id`."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-follow-up-message {:session-id session-id :text text} {:origin :core}))

(defn queue-while-streaming-in!
  "Queue prompt text while streaming for `session-id`."
  [ctx session-id text behavior]
  (let [sd                 (ss/get-session-data-in ctx session-id)
        interrupt-pending? (boolean (:interrupt-pending sd))
        mode               (cond
                             interrupt-pending? :coerced-follow-up
                             (= behavior :steer) :steer
                             :else :queue)]
    (case mode
      :steer
      (do (steer-in! ctx session-id text)
          {:accepted? true :behavior :steer})

      (:queue :coerced-follow-up)
      (do (follow-up-in! ctx session-id text)
          {:accepted? true :behavior (if interrupt-pending? :coerced-follow-up :queue)}))))

(defn request-interrupt-in!
  "Request a deferred interrupt at the next turn boundary for `session-id`."
  [ctx session-id]
  (let [phase (ss/sc-phase-in ctx session-id)
        sd    (ss/get-session-data-in ctx session-id)]
    (if (= :streaming phase)
      (let [already-pending? (boolean (:interrupt-pending sd))
            agent-data       (agent/get-data-in (ss/agent-ctx-in ctx session-id))
            dropped-text     (merge-text-sources
                              (extract-text-from-content-blocks (:steering-queue agent-data))
                              (:steering-messages sd))]
        (dispatch/dispatch! ctx
                            :session/request-interrupt
                            {:session-id       session-id
                             :already-pending? already-pending?
                             :requested-at     (java.time.Instant/now)}
                            {:origin :core})
        {:accepted? (not already-pending?)
         :pending? true
         :dropped-steering-text dropped-text})
      {:accepted? false
       :pending? (boolean (:interrupt-pending sd))
       :dropped-steering-text ""})))

(defn abort-in!
  "Abort the current agent run immediately for `session-id`. Prefer
   `request-interrupt-in!` for deferred semantics."
  [ctx session-id]
  (turn-runtime/abort-active-turn-in! ctx session-id)
  (dispatch/dispatch! ctx :session/abort {:session-id session-id} {:origin :core}))

(defn consume-queued-input-text-in!
  "Return queued steering/follow-up text (joined by newlines) and clear queues
   for `session-id`."
  [ctx session-id]
  (let [agent-data (agent/get-data-in (ss/agent-ctx-in ctx session-id))
        sd         (ss/get-session-data-in ctx session-id)
        merged     (merge-text-sources
                    (extract-text-from-content-blocks
                     (concat (:steering-queue agent-data)
                             (:follow-up-queue agent-data)))
                    (:steering-messages sd)
                    (:follow-up-messages sd))]
    (dispatch/dispatch! ctx :session/clear-queued-messages {:session-id session-id} {:origin :core})
    merged))
