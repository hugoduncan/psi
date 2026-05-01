(ns psi.rpc.session.ops
  "Small op handlers and router delegates for RPC session workflows."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.messages :as app-messages]
   [psi.rpc.events :as events]
   [psi.rpc.session.projections :as projections]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [error-frame protocol-version response-frame supported-rpc-ops]]))

(defn handle-ping
  [request]
  (response-frame (:id request) "ping" true {:pong true :protocol-version protocol-version}))

(defn handle-query-eql
  [{:keys [ctx request params session-id parse-query-edn!]}]
  (let [query-str  (get params :query)
        _          (when-not (and (string? query-str) (not (str/blank? query-str)))
                     (throw (ex-info "invalid request parameter :query: non-empty EDN string"
                                     {:error-code "request/invalid-params"})))
        entity-str (:entity params)
        q          (parse-query-edn! query-str)
        extra      (when (and (string? entity-str) (not (str/blank? entity-str)))
                     (try
                       (binding [*read-eval* false]
                         (let [m (edn/read-string entity-str)]
                           (when (map? m) m)))
                       (catch Throwable _ nil)))
        extra*     (cond-> (or extra {})
                     session-id (assoc :psi.agent-session/session-id session-id))
        result     (try
                     (session/query-in ctx q extra*)
                     (catch Throwable e
                       (throw (ex-info (or (ex-message e) "query execution failed")
                                       {:error-code "runtime/query-failed"}
                                       e))))]
    (response-frame (:id request) (:op request) true {:result result})))

(defn handle-prompt-while-streaming
  [{:keys [ctx request params session-id]}]
  (let [message   (get params :message)
        _         (when-not (and (string? message) (not (str/blank? message)))
                    (throw (ex-info "invalid request parameter :message: non-empty string"
                                    {:error-code "request/invalid-params"})))
        behavior* (let [behavior (:behavior params)]
                    (cond
                      (nil? behavior)      "steer"
                      (keyword? behavior)  (name behavior)
                      (string? behavior)   behavior
                      :else                nil))]
    (case behavior*
      "steer"
      (let [{:keys [accepted? behavior]} (session/queue-while-streaming-in! ctx session-id message :steer)]
        (response-frame (:id request) (:op request) true {:accepted accepted?
                                                          :behavior (name behavior)}))

      "queue"
      (let [{:keys [accepted? behavior]} (session/queue-while-streaming-in! ctx session-id message :queue)]
        (response-frame (:id request) (:op request) true {:accepted accepted?
                                                          :behavior (name behavior)}))

      (throw (ex-info "prompt_while_streaming :behavior must be \"steer\" or \"queue\""
                      {:error-code "request/invalid-params"})))))

(defn handle-steer
  [{:keys [ctx request params session-id]}]
  (let [message (get params :message)
        _       (when-not (and (string? message) (not (str/blank? message)))
                  (throw (ex-info "invalid request parameter :message: non-empty string"
                                  {:error-code "request/invalid-params"})))]
    (session/steer-in! ctx session-id message)
    (response-frame (:id request) (:op request) true {:accepted true})))

(defn handle-follow-up
  [{:keys [ctx request params session-id]}]
  (let [message (get params :message)
        _       (when-not (and (string? message) (not (str/blank? message)))
                  (throw (ex-info "invalid request parameter :message: non-empty string"
                                  {:error-code "request/invalid-params"})))]
    (session/follow-up-in! ctx session-id message)
    (response-frame (:id request) (:op request) true {:accepted true})))

(defn handle-abort
  [{:keys [ctx request session-id]}]
  (session/abort-in! ctx session-id)
  (response-frame (:id request) (:op request) true {:accepted true}))

(defn handle-interrupt
  [{:keys [ctx request session-id]}]
  (let [{:keys [accepted? pending? dropped-steering-text]}
        (session/request-interrupt-in! ctx session-id)]
    (response-frame (:id request) (:op request) true
                    {:accepted accepted?
                     :pending pending?
                     :dropped-steering-text (or dropped-steering-text "")})))

(defn handle-list-sessions
  [{:keys [ctx request state session-id]}]
  (response-frame (:id request) (:op request) true {:active-session-id (or session-id
                                                                           (rpc.state/focus-session-id state))
                                                    :sessions (ss/list-context-sessions-in ctx)}))

(defn handle-set-session-name
  [{:keys [ctx request params session-id]}]
  (let [name   (get params :name)
        _      (when-not (and (string? name) (not (str/blank? name)))
                 (throw (ex-info "invalid request parameter :name: non-empty string"
                                 {:error-code "request/invalid-params"})))
        result (session/set-session-name-in! ctx session-id name)]
    (response-frame (:id request) (:op request) true {:session-name (:session-name result)})))

(defn handle-set-model
  [{:keys [ctx request params session-id resolve-model]}]
  (let [provider (get params :provider)
        _        (when-not (or (keyword? provider) (string? provider))
                   (throw (ex-info "invalid request parameter :provider: string or keyword"
                                   {:error-code "request/invalid-params"})))
        model-id (get params :model-id)
        _        (when-not (and (string? model-id) (not (str/blank? model-id)))
                   (throw (ex-info "invalid request parameter :model-id: non-empty string"
                                   {:error-code "request/invalid-params"})))
        resolved (resolve-model provider model-id)]
    (when-not resolved
      (throw (ex-info "unknown model"
                      {:error-code "request/unknown-model"})))
    (let [provider-str (name (:provider resolved))
          model        {:provider provider-str
                        :id (:id resolved)
                        :reasoning (:supports-reasoning resolved)}
          result       (session/set-model-in! ctx session-id model)]
      (response-frame (:id request) (:op request) true {:model {:provider (:provider (:model result))
                                                                :id (:id (:model result))}}))))

(defn handle-cycle-model
  [{:keys [ctx request params session-id]}]
  (let [direction (case (:direction params)
                    "prev" :backward
                    "next" :forward
                    :backward :backward
                    :forward :forward
                    :forward)
        sd        (session/cycle-model-in! ctx session-id direction)]
    (response-frame (:id request) (:op request) true {:model (some-> (:model sd)
                                                                     (select-keys [:provider :id]))})))

(defn handle-set-thinking-level
  [{:keys [ctx request params session-id]}]
  (let [level   (:level params)
        _       (when-not (some? level)
                  (throw (ex-info "invalid request parameter :level: keyword, string, or integer"
                                  {:error-code "request/invalid-params"})))
        level*  (cond
                  (keyword? level) level
                  (string? level)  (keyword level)
                  :else            level)
        result  (session/set-thinking-level-in! ctx session-id level*)]
    (response-frame (:id request) (:op request) true {:thinking-level (:thinking-level result)})))

(defn handle-cycle-thinking-level
  [{:keys [ctx request session-id]}]
  (let [sd  (session/cycle-thinking-level-in! ctx session-id)]
    (response-frame (:id request) (:op request) true {:thinking-level (:thinking-level sd)})))

(defn handle-compact
  [{:keys [ctx request params session-id]}]
  (let [result (session/manual-compact-in! ctx session-id (:custom-instructions params))]
    (response-frame (:id request) (:op request) true {:compacted (boolean result)
                                                      :summary result})))

(defn handle-set-auto-compaction
  [{:keys [ctx request params session-id]}]
  (let [enabled (:enabled params)
        _       (when-not (boolean? enabled)
                  (throw (ex-info "invalid request parameter :enabled: boolean"
                                  {:error-code "request/invalid-params"})))
        result  (session/set-auto-compaction-in! ctx session-id enabled)]
    (response-frame (:id request) (:op request) true {:enabled (:auto-compaction-enabled result)})))

(defn handle-set-auto-retry
  [{:keys [ctx request params session-id]}]
  (let [enabled (:enabled params)
        _       (when-not (boolean? enabled)
                  (throw (ex-info "invalid request parameter :enabled: boolean"
                                  {:error-code "request/invalid-params"})))
        result  (session/set-auto-retry-in! ctx session-id enabled)]
    (response-frame (:id request) (:op request) true {:enabled (:auto-retry-enabled result)})))

(defn handle-get-state
  [{:keys [ctx request session-id]}]
  (response-frame (:id request) (:op request) true {:state (ss/get-session-data-in ctx session-id)}))

(defn handle-get-messages
  [{:keys [ctx request session-id]}]
  (response-frame (:id request) (:op request) true {:messages (app-messages/session-messages ctx session-id)}))

(defn handle-get-session-stats
  [{:keys [ctx request session-id]}]
  (response-frame (:id request) (:op request) true {:stats (session/diagnostics-in ctx session-id)}))

(defn handle-subscribe
  [{:keys [ctx request params state session-id session-deps maybe-start-external-event-loop! register-rpc-extension-run-fn! ensure-projection-listener!]}]
  (let [topics            (or (:topics params) [])
        _                 (when-not (sequential? topics)
                            (throw (ex-info "subscribe :topics must be sequential"
                                            {:error-code "request/invalid-params"})))
        topics*           (->> topics (filter #(contains? events/event-topics %)) set)
        ui-topic-request? (some events/extension-ui-topic? topics*)
        sid               (or session-id
                              (rpc.state/focus-session-id state)
                              (some-> (ss/list-context-sessions-in ctx) first :session-id))]
    (rpc.state/subscribe-topics! state topics*)
    (when sid
      (rpc.state/set-focus-session-id! state sid))
    (when (or (empty? (rpc.state/subscribed-topics state))
              (contains? (rpc.state/subscribed-topics state) "assistant/message"))
      (maybe-start-external-event-loop! ctx (:emit-frame! request) state sid))
    (register-rpc-extension-run-fn! ctx (:emit-frame! request) state sid session-deps)
    (ensure-projection-listener! ctx (:emit-frame! request) state)
    (when ui-topic-request?
      (events/emit-ui-snapshot-events! (:emit-frame! request)
                                       state
                                       {}
                                       (or (events/ui-snapshot ctx) {})))
    (events/emit-event! (:emit-frame! request) state {:event "session/updated"
                                                      :id (:id request)
                                                      :data (events/session-updated-payload ctx sid)})
    (events/emit-event! (:emit-frame! request) state {:event "footer/updated"
                                                      :id (:id request)
                                                      :data (assoc (events/footer-updated-payload ctx sid)
                                                                   :session-id sid)})
    (events/emit-event! (:emit-frame! request) state {:event "context/updated"
                                                      :id (:id request)
                                                      :data (events/context-updated-payload ctx state sid)})
    (response-frame (:id request) (:op request) true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)})))

(defn handle-unsubscribe
  [{:keys [ctx request params state]}]
  (let [topics  (or (:topics params) [])
        _       (when-not (sequential? topics)
                  (throw (ex-info "unsubscribe :topics must be sequential"
                                  {:error-code "request/invalid-params"})))
        topics* (->> topics (filter string?) set)]
    (if (seq topics*)
      (rpc.state/unsubscribe-topics! state topics*)
      (rpc.state/clear-subscriptions! state))
    (when-not (projections/projection-topic-subscribed? state)
      (projections/unregister-projection-listener! ctx state))
    (response-frame (:id request) (:op request) true {:subscribed (->> (rpc.state/subscribed-topics state) sort vec)})))

(defn handle-op-not-supported
  [request]
  (error-frame {:id            (:id request)
                :op            (:op request)
                :error-code    "request/op-not-supported"
                :error-message (str "unsupported op: " (:op request))
                :data          {:supported-ops supported-rpc-ops}}))
