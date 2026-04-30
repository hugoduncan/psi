(ns psi.agent-session.prompt-control
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]))

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

(defn- prompt-dispatch!
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
  Requires the session to be idle.

   Current convergence scaffold routes prompt entry through the existing
   statechart transition plus a chained prepare/execute-and-record dispatch slice:
   session/prompt-submit -> session/prompt -> session/prompt-prepare-request
   -> runtime/prompt-execute-and-record -> session/prompt-record-response.

   The slice is now shared by initial prompt submission and tool-result
   continuation. The active path uses a runtime execute-and-record bridge
   between prepared request projection and recorded turn outcome.

   opts (optional map):
     :progress-queue  — LinkedBlockingQueue for streaming progress events
     :runtime-opts    — map passed through to request preparation (e.g. {:api-key k})"
  ([ctx session-id text]
   (prompt-in! ctx session-id text nil))
  ([ctx session-id text images]
   (prompt-in! ctx session-id text images nil))
  ([ctx session-id text images opts]
   (prompt-dispatch! ctx session-id text images opts)))

(defn prompt-execution-result-in!
  "Submit `text` to the agent for `session-id` and return the shaped
   execution-result for the completed turn instead of the prepared-request map.

   This keeps prompt submission on the canonical dispatch/runtime path while
   giving bounded workflow/judge callers the exact assistant message produced by
   that turn, avoiding any dependence on later journal rereads."
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
  "Inject a steering message while the agent is streaming for `session-id`.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-steering-message {:session-id session-id :text text} {:origin :core}))

(defn follow-up-in!
  "Queue a follow-up message for delivery after the current agent run for `session-id`.
   State recording and agent-core queue mutation are both dispatch-owned."
  [ctx session-id text]
  (dispatch/dispatch! ctx :session/enqueue-follow-up-message {:session-id session-id :text text} {:origin :core}))

(defn queue-while-streaming-in!
  "Queue prompt text while streaming for `session-id`.

   Behavior:
   - when interrupt is pending, both steer/queue inputs are coerced to follow-up
   - otherwise steer remains steering and queue remains follow-up

   Returns {:accepted? bool :behavior keyword} where behavior is
   :steer | :queue | :coerced-follow-up."
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
  "Request a deferred interrupt at the next turn boundary for `session-id`.

   Behavior:
   - while streaming, not yet pending: mark :interrupt-pending, drop steering
   - while streaming, already pending: idempotent — preserve original timestamp,
     still drop any newly queued steering
   - while idle: silent no-op

   Returns {:accepted? bool :pending? bool :dropped-steering-text string}."
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
  "Abort the current agent run immediately for `session-id`. Prefer `request-interrupt-in!` for deferred semantics."
  [ctx session-id]
  (prompt-runtime/abort-active-turn-in! ctx session-id)
  (dispatch/dispatch! ctx :session/abort {:session-id session-id} {:origin :core}))

(defn consume-queued-input-text-in!
  "Return queued steering/follow-up text (joined by newlines) and clear queues for `session-id`.
   State clearing and agent-core queue clearing are both dispatch-owned.
   This is used by legacy immediate-abort TUI interrupt flows.

   For deferred interrupt semantics, prefer `request-interrupt-in!` which only
   drops steering and preserves follow-ups."
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
