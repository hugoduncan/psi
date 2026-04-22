(ns psi.agent-session.dispatch-handlers.prompt-lifecycle
  "Handlers for prompt lifecycle registration:
   manual-compaction-execute,
   prompt-submit, prompt-prepare-request, prompt-record-response,
   prompt-continue, prompt-finish, prompt-execute."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.session-state :as session]))

(defn- now-inst []
  (java.time.Instant/now))

(defn- register-core-handler! [event handler]
  (dispatch/register-handler! event handler))

(defn- follow-up-text->message
  [text]
  {:role "user"
   :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- queued-follow-up-batch
  [ctx session-id]
  (let [sd           (session/get-session-data-in ctx session-id)
        agent-ctx    (session/agent-ctx-in ctx session-id)
        follow-mode  (or (some-> agent-ctx agent/get-data-in :follow-up-mode)
                         :one-at-a-time)
        texts        (->> (:follow-up-messages sd)
                          (filter string?)
                          (map str/trim)
                          (remove str/blank?)
                          vec)
        batch-texts  (case follow-mode
                       :all texts
                       :one-at-a-time (vec (take 1 texts))
                       (vec (take 1 texts)))
        messages     (mapv follow-up-text->message batch-texts)]
    (when (seq messages)
      {:texts         batch-texts
       :messages      messages
       :consume-count (count messages)
       :follow-mode   follow-mode})))

(defn- synthetic-user-prompt-effects
  [session-id user-msg]
  [{:effect/type :runtime/dispatch-event-with-effect-result
    :event-type :session/prompt-submit
    :event-data {:session-id session-id
                 :user-msg user-msg}
    :origin :core}
   {:effect/type :runtime/dispatch-event
    :event-type :session/prompt
    :event-data {:session-id session-id}
    :origin :core}
   {:effect/type :runtime/dispatch-event-with-effect-result
    :event-type :session/prompt-prepare-request
    :event-data {:session-id session-id
                 :turn-id (str (java.util.UUID/randomUUID))
                 :user-msg user-msg}
    :origin :core}])

(defn- prepared-request-state-summary
  [turn-id prepared-request]
  {:turn-id             turn-id
   :system-prompt-chars (count (or (:prepared-request/system-prompt prepared-request) ""))
   :message-count       (count (:prepared-request/messages prepared-request))
   :tool-count          (count (:prepared-request/tools prepared-request))
   :cache-breakpoints   (get-in prepared-request [:prepared-request/session-snapshot :cache-breakpoints])
   :input-expansion     (:prepared-request/input-expansion prepared-request)
   :prepared-at         (now-inst)})

(defn- prepared-request-query-text
  [prepared-request]
  (or (get-in prepared-request [:prepared-request/user-message :content 0 :text])
      (some->> (:prepared-request/queued-steering-messages prepared-request)
               first
               :content
               first
               :text)))

(defn- prompt-prepare-request-effects
  [prepared-request progress-queue steering-consumed?]
  (cond-> [{:effect/type :memory/recover-query
            :query-text (prepared-request-query-text prepared-request)}
           {:effect/type      :runtime/prompt-execute-and-record
            :prepared-request prepared-request
            :progress-queue   progress-queue}]
    steering-consumed?
    (conj {:effect/type :runtime/agent-clear-steering-queue})))

(defn- prompt-prepare-request-handler
  [ctx {:keys [session-id turn-id user-msg runtime-opts progress-queue]}]
  (let [prepared-request   ((:build-prepared-request-fn ctx)
                            ctx session-id {:turn-id turn-id
                                            :user-message user-msg
                                            :runtime-opts runtime-opts
                                            :commands (ext/command-names-in (:extension-registry ctx))})
        api-key            (get-in prepared-request [:prepared-request/ai-options :api-key])
        steering-consumed? (seq (:prepared-request/queued-steering-messages prepared-request))]
    {:root-state-update
     (session/session-update
      session-id
      #(cond-> (assoc % :last-prepared-request-summary
                      (prepared-request-state-summary turn-id prepared-request))
         api-key            (assoc :runtime-api-key api-key)
         steering-consumed? (assoc :steering-messages [])))
     :effects (prompt-prepare-request-effects prepared-request progress-queue steering-consumed?)
     :return-effect-result? true
     :return {:prepared-request prepared-request}}))

(defn- execution-usage-tokens
  [execution-result]
  (let [usage (:execution-result/usage execution-result)]
    (when (map? usage)
      (let [total (or (:total-tokens usage)
                      (+ (or (:input-tokens usage) 0)
                         (or (:output-tokens usage) 0)
                         (or (:cache-read-tokens usage) 0)
                         (or (:cache-write-tokens usage) 0)))]
        (when (and (number? total) (pos? total))
          total)))))

(defn- prompt-record-next-payload
  [session-id execution-result progress-queue next-event]
  (cond-> {:session-id session-id
           :execution-result execution-result
           :progress-queue progress-queue}
    (= next-event :session/prompt-finish)
    (assoc :turn-id (:execution-result/turn-id execution-result)
           :terminal-result execution-result)))

(defn- prompt-record-next-event-effect
  [next-event next-payload]
  {:effect/type :runtime/dispatch-event
   :event-type next-event
   :event-data next-payload
   :origin :core})

(defn- prompt-record-context-usage-effect
  [session-id tokens window]
  {:effect/type :runtime/dispatch-event
   :event-type :session/update-context-usage
   :event-data {:session-id session-id
                :tokens tokens
                :window window}
   :origin :core})

(defn- prompt-record-response-handler
  [ctx {:keys [session-id execution-result progress-queue]}]
  (let [result       ((:build-record-response-fn ctx) session-id execution-result progress-queue)
        next-event   (get-in result [:return :next-event])
        next-payload (prompt-record-next-payload session-id execution-result progress-queue next-event)
        tokens       (execution-usage-tokens execution-result)
        sd           (when tokens (session/get-session-data-in ctx session-id))
        window       (or (some-> execution-result :execution-result/model :context-window)
                         (when sd (:context-window sd)))]
    (cond-> result
      next-event
      (update :effects (fnil conj []) (prompt-record-next-event-effect next-event next-payload))
      (and tokens (number? window) (pos? window))
      (update :effects (fnil conj []) (prompt-record-context-usage-effect session-id tokens window)))))

(defn- prompt-continue-handler
  [_ctx {:keys [session-id execution-result progress-queue]}]
  (let [turn-id (str (java.util.UUID/randomUUID))]
    {:effects [{:effect/type :runtime/prompt-continue-chain
                :execution-result execution-result
                :progress-queue progress-queue}
               {:effect/type :runtime/dispatch-event-with-effect-result
                :event-type :session/prompt-prepare-request
                :event-data {:session-id session-id
                             :turn-id turn-id
                             :user-msg nil
                             :progress-queue progress-queue}
                :origin :core}
               {:effect/type :runtime/reconcile-and-emit-background-job-terminals}]
     :return-effect-result? true
     :return {:continued? true
              :next-turn-id turn-id
              :turn-outcome (:execution-result/turn-outcome execution-result)}}))

(defn- prompt-finish-base-result
  [session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch]
  {:effects [{:effect/type :runtime/dispatch-event
              :event-type :on-agent-done
              :event-data {:session-id session-id}
              :origin :core}
             {:effect/type :notify/extension-dispatch
              :event-name "session_turn_finished"
              :payload {:session-id session-id
                        :turn-id turn-id}}
             {:effect/type :runtime/reconcile-and-emit-background-job-terminals}
             {:effect/type :statechart/send-event
              :event :session/reset}]
   :return {:finished? true
            :turn-id turn-id
            :next-turn-id next-turn-id
            :turn-outcome (:execution-result/turn-outcome terminal-result)
            :follow-up-triggered? (boolean follow-up-msg)
            :follow-up-count (or (:consume-count follow-up-batch) 0)}})

(defn- consume-follow-up-state-update
  [session-id follow-up-batch]
  (session/session-update
   session-id
   #(update % :follow-up-messages
            (fn [xs]
              (vec (drop (:consume-count follow-up-batch) (or xs [])))))))

(defn- prompt-finish-follow-up-effects
  [session-id follow-up-batch follow-up-msg]
  [{:effect/type :runtime/agent-drain-follow-up-queue
    :messages (:messages follow-up-batch)}
   {:effect/type :runtime/dispatch-event-with-effect-result
    :event-type :session/submit-synthetic-user-prompt
    :event-data {:session-id session-id
                 :user-msg follow-up-msg}
    :origin :core}])

(defn- prompt-finish-handler
  [ctx {:keys [session-id turn-id terminal-result]}]
  (let [follow-up-batch (queued-follow-up-batch ctx session-id)
        follow-up-msg   (first (:messages follow-up-batch))
        next-turn-id    (when follow-up-msg (str (java.util.UUID/randomUUID)))]
    (cond-> (prompt-finish-base-result session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch)
      follow-up-batch
      (assoc :root-state-update (consume-follow-up-state-update session-id follow-up-batch))
      follow-up-msg
      (update :effects into (prompt-finish-follow-up-effects session-id follow-up-batch follow-up-msg)))))

(defn- prompt-execute-handler
  [_ctx {:keys [user-msg]}]
  {:effects [{:effect/type :runtime/agent-start-loop-with-messages
              :messages [user-msg]}
             {:effect/type :runtime/reconcile-and-emit-background-job-terminals}]})

(defn register!
  "Register prompt lifecycle handlers. Called once during context creation."
  [_ctx]
  ;; Intentional narrow synchronous boundary — callers require a direct return
  ;; value and the surrounding statechart transitions already own the state
  ;; transition. execute-compaction-fn is injected by core.clj to avoid a
  ;; circular dependency. Keep the boundary explicit via :return; do not
  ;; generalise this pattern.
  (register-core-handler!
   :session/manual-compaction-execute
   (fn [ctx {:keys [session-id custom-instructions]}]
     {:return ((:execute-compaction-fn ctx) ctx session-id custom-instructions)}))

  (register-core-handler!
   :session/prompt-submit
   (fn [_ctx {:keys [user-msg]}]
     {:effects [{:effect/type :persist/journal-append-message-entry
                 :message user-msg}]
      :return {:submitted? true
               :turn-id (str (java.util.UUID/randomUUID))
               :user-msg user-msg}}))

  (register-core-handler!
   :session/submit-synthetic-user-prompt
   (fn [_ctx {:keys [session-id user-msg]}]
     {:effects (synthetic-user-prompt-effects session-id user-msg)
      :return {:submitted? true
               :user-msg user-msg}}))

  (register-core-handler! :session/prompt-prepare-request prompt-prepare-request-handler)
  (register-core-handler! :session/prompt-record-response prompt-record-response-handler)
  (register-core-handler! :session/prompt-continue prompt-continue-handler)
  (register-core-handler! :session/prompt-finish prompt-finish-handler)
  (register-core-handler! :session/prompt-execute prompt-execute-handler))
