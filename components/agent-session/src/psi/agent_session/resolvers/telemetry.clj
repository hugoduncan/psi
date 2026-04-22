(ns psi.agent-session.resolvers.telemetry
  (:require
   [clojure.set :as set]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.resolvers.support :as support]
   [psi.agent-session.resolvers.telemetry-basics :as basics]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as accessors]
   [psi.agent-session.turn-statechart :as turn-sc]))
(declare tool-lifecycle-summaries)
(defn- stats-snapshot
  "Build canonical session telemetry stats from current session/journal state."
  [agent-session-ctx session-id]
  (let [sd      (support/session-data agent-session-ctx session-id)
        journal (accessors/journal-state-in agent-session-ctx (:session-id sd))
        msgs    (keep #(when (= :message (:kind %)) (get-in % [:data :message])) journal)]
    {:session-id         (:session-id sd)
     :session-file       (:session-file sd)
     :user-messages      (count (filter #(= "user" (:role %)) msgs))
     :assistant-messages (count (filter #(= "assistant" (:role %)) msgs))
     :ai-calls           (count (filter #(and (= "assistant" (:role %))
                                              (map? (:usage %)))
                                        msgs))
     :tool-calls         (count (filter #(= "toolResult" (:role %)) msgs))
     :total-messages     (count msgs)
     :entry-count        (count journal)
     :context-tokens     (:context-tokens sd)
     :context-window     (:context-window sd)}))
(defn- canonical-start-time
  [agent-session-ctx session-id]
  (let [sd      (support/session-data agent-session-ctx session-id)
        startup (:startup-bootstrap sd)
        journal (accessors/journal-state-in agent-session-ctx (:session-id sd))
        first-ts (:timestamp (first journal))]
    (or (:timestamp startup)
        first-ts
        (java.time.Instant/now))))
(pco/defresolver agent-session-canonical-telemetry
  "Resolve canonical top-level telemetry attrs."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/messages-count
                 :psi.agent-session/ai-call-count
                 :psi.agent-session/tool-call-count
                 :psi.agent-session/executed-tool-count
                 :psi.agent-session/start-time
                 :psi.agent-session/current-time]}
  (let [stats               (stats-snapshot agent-session-ctx session-id)
        executed-tool-count (count (tool-lifecycle-summaries agent-session-ctx session-id))]
    {:psi.agent-session/messages-count      (:total-messages stats)
     :psi.agent-session/ai-call-count       (:ai-calls stats)
     :psi.agent-session/tool-call-count     (:tool-calls stats)
     :psi.agent-session/executed-tool-count executed-tool-count
     :psi.agent-session/start-time          (canonical-start-time agent-session-ctx session-id)
     :psi.agent-session/current-time        (java.time.Instant/now)}))
(pco/defresolver agent-session-stats
  "Resolve a SessionStats snapshot."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/stats]}
  {:psi.agent-session/stats (stats-snapshot agent-session-ctx session-id)})
(defn- utf8-byte-count
  [s]
  (count (.getBytes (str (or s "")) "UTF-8")))
(defn- tool-call-attempt-events
  [agent-session-ctx session-id]
  (let [sid session-id]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :tool-call-attempts sid))
             []))))
(defn- tool-result-ids
  [agent-session-ctx session-id]
  (->> (support/agent-core-messages agent-session-ctx session-id)
       (filter #(= "toolResult" (:role %)))
       (keep :tool-call-id)
       set))
(defn- reduce-attempt-events
  [events]
  (->> events
       (reduce (fn [acc {:keys [event-kind turn-id content-index id name delta timestamp]}]
                 (let [k   [turn-id content-index]
                       cur (get acc k
                                {:psi.tool-call-attempt/id            nil
                                 :psi.tool-call-attempt/name          nil
                                 :psi.tool-call-attempt/content-index content-index
                                 :psi.tool-call-attempt/turn-id       turn-id
                                 :psi.tool-call-attempt/started-at    nil
                                 :psi.tool-call-attempt/ended-at      nil
                                 :psi.tool-call-attempt/delta-count   0
                                 :psi.tool-call-attempt/argument-bytes 0})]
                   (case event-kind
                     :toolcall-start
                     (assoc acc k
                            (-> cur
                                (assoc :psi.tool-call-attempt/id (or id (:psi.tool-call-attempt/id cur)))
                                (assoc :psi.tool-call-attempt/name (or name (:psi.tool-call-attempt/name cur)))
                                (assoc :psi.tool-call-attempt/content-index content-index)
                                (assoc :psi.tool-call-attempt/turn-id turn-id)
                                (assoc :psi.tool-call-attempt/started-at
                                       (or (:psi.tool-call-attempt/started-at cur) timestamp))))
                     :toolcall-delta
                     (assoc acc k
                            (-> cur
                                (assoc :psi.tool-call-attempt/content-index content-index)
                                (assoc :psi.tool-call-attempt/turn-id turn-id)
                                (update :psi.tool-call-attempt/delta-count (fnil inc 0))
                                (update :psi.tool-call-attempt/argument-bytes (fnil + 0)
                                        (utf8-byte-count delta))))
                     :toolcall-end
                     (assoc acc k
                            (-> cur
                                (assoc :psi.tool-call-attempt/id (or id (:psi.tool-call-attempt/id cur)))
                                (assoc :psi.tool-call-attempt/name (or name (:psi.tool-call-attempt/name cur)))
                                (assoc :psi.tool-call-attempt/content-index content-index)
                                (assoc :psi.tool-call-attempt/turn-id turn-id)
                                (assoc :psi.tool-call-attempt/ended-at
                                       (or (:psi.tool-call-attempt/ended-at cur) timestamp))))
                     acc)))
               {})
       vals
       (sort-by (juxt :psi.tool-call-attempt/started-at
                      :psi.tool-call-attempt/turn-id
                      :psi.tool-call-attempt/content-index))
       vec))
(defn- enrich-attempt-status
  [result-ids attempt]
  (let [id        (:psi.tool-call-attempt/id attempt)
        recorded? (and (string? id) (contains? result-ids id))
        status    (cond
                    recorded? :result-recorded
                    (:psi.tool-call-attempt/ended-at attempt) :ended
                    (:psi.tool-call-attempt/started-at attempt) :started
                    :else :partial)]
    (assoc attempt
           :psi.tool-call-attempt/status status
           :psi.tool-call-attempt/executed? recorded?
           :psi.tool-call-attempt/result-recorded? recorded?)))
(defn- build-tool-call-attempts
  [events result-ids]
  (mapv (partial enrich-attempt-status result-ids)
        (reduce-attempt-events events)))
(pco/defresolver agent-session-tool-call-attempts
  "Resolve streamed provider tool-call attempts."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output
   [:psi.agent-session/tool-call-attempt-count
    :psi.agent-session/tool-call-attempt-unmatched-count
    {:psi.agent-session/tool-call-attempts
     [:psi.tool-call-attempt/id
      :psi.tool-call-attempt/name
      :psi.tool-call-attempt/content-index
      :psi.tool-call-attempt/turn-id
      :psi.tool-call-attempt/status
      :psi.tool-call-attempt/started-at
      :psi.tool-call-attempt/ended-at
      :psi.tool-call-attempt/delta-count
      :psi.tool-call-attempt/argument-bytes
      :psi.tool-call-attempt/executed?
      :psi.tool-call-attempt/result-recorded?]}]}
  (let [attempts* (build-tool-call-attempts (tool-call-attempt-events agent-session-ctx session-id)
                                            (tool-result-ids agent-session-ctx session-id))
        unmatched (count (remove :psi.tool-call-attempt/result-recorded? attempts*))]
    {:psi.agent-session/tool-call-attempt-count           (count attempts*)
     :psi.agent-session/tool-call-attempt-unmatched-count unmatched
     :psi.agent-session/tool-call-attempts                attempts*}))
(defn- tool-lifecycle-events
  [agent-session-ctx session-id]
  (let [sid session-id]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :tool-lifecycle-events sid))
             []))))
(defn- tool-lifecycle-event->eql
  [event]
  {:psi.tool-lifecycle/event-kind (:event-kind event)
   :psi.tool-lifecycle/tool-id    (:tool-id event)
   :psi.tool-lifecycle/tool-name  (:tool-name event)
   :psi.tool-lifecycle/timestamp  (:timestamp event)
   :psi.tool-lifecycle/details    (:details event)
   :psi.tool-lifecycle/is-error   (:is-error event)
   :psi.tool-lifecycle/content    (:content event)
   :psi.tool-lifecycle/result-text (:result-text event)
   :psi.tool-lifecycle/arguments  (:arguments event)
   :psi.tool-lifecycle/parsed-args (:parsed-args event)})
(defn- tool-lifecycle-summaries
  [agent-session-ctx session-id]
  (->> (tool-lifecycle-events agent-session-ctx session-id)
       (reduce (fn [acc {:keys [tool-id tool-name event-kind timestamp is-error result-text arguments parsed-args]}]
                 (let [k   tool-id
                       cur (get acc k {:psi.tool-lifecycle.summary/tool-id k
                                       :psi.tool-lifecycle.summary/tool-name tool-name
                                       :psi.tool-lifecycle.summary/event-count 0
                                       :psi.tool-lifecycle.summary/last-event-kind nil
                                       :psi.tool-lifecycle.summary/started-at nil
                                       :psi.tool-lifecycle.summary/last-updated-at nil
                                       :psi.tool-lifecycle.summary/completed? false
                                       :psi.tool-lifecycle.summary/is-error false
                                       :psi.tool-lifecycle.summary/result-text nil
                                       :psi.tool-lifecycle.summary/arguments nil
                                       :psi.tool-lifecycle.summary/parsed-args nil})]
                   (assoc acc k
                          (-> cur
                              (assoc :psi.tool-lifecycle.summary/tool-name
                                     (or tool-name (:psi.tool-lifecycle.summary/tool-name cur)))
                              (update :psi.tool-lifecycle.summary/event-count (fnil inc 0))
                              (assoc :psi.tool-lifecycle.summary/last-event-kind event-kind)
                              (assoc :psi.tool-lifecycle.summary/last-updated-at timestamp)
                              (update :psi.tool-lifecycle.summary/started-at
                                      #(or % (when (= :tool-start event-kind) timestamp) timestamp))
                              (assoc :psi.tool-lifecycle.summary/completed?
                                     (boolean (contains? #{:tool-result} event-kind)))
                              (assoc :psi.tool-lifecycle.summary/is-error (boolean is-error))
                              (assoc :psi.tool-lifecycle.summary/result-text
                                     (or result-text (:psi.tool-lifecycle.summary/result-text cur)))
                              (update :psi.tool-lifecycle.summary/arguments
                                      #(or % arguments))
                              (update :psi.tool-lifecycle.summary/parsed-args
                                      #(or % parsed-args))))))
               {})
       vals
       (sort-by (juxt :psi.tool-lifecycle.summary/started-at
                      :psi.tool-lifecycle.summary/tool-id))
       vec))
(pco/defresolver agent-session-tool-lifecycle-events
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output
   [:psi.agent-session/tool-lifecycle-event-count
    :psi.agent-session/tool-lifecycle-summary-count
    {:psi.agent-session/tool-lifecycle-events
     [:psi.tool-lifecycle/event-kind
      :psi.tool-lifecycle/tool-id
      :psi.tool-lifecycle/tool-name
      :psi.tool-lifecycle/timestamp
      :psi.tool-lifecycle/details
      :psi.tool-lifecycle/is-error
      :psi.tool-lifecycle/content
      :psi.tool-lifecycle/result-text
      :psi.tool-lifecycle/arguments
      :psi.tool-lifecycle/parsed-args]}
    {:psi.agent-session/tool-lifecycle-summaries
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/event-count
      :psi.tool-lifecycle.summary/last-event-kind
      :psi.tool-lifecycle.summary/started-at
      :psi.tool-lifecycle.summary/last-updated-at
      :psi.tool-lifecycle.summary/completed?
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args]}]}
  (let [events    (mapv tool-lifecycle-event->eql
                        (tool-lifecycle-events agent-session-ctx session-id))
        summaries (tool-lifecycle-summaries agent-session-ctx session-id)]
    {:psi.agent-session/tool-lifecycle-event-count   (count events)
     :psi.agent-session/tool-lifecycle-events        events
     :psi.agent-session/tool-lifecycle-summary-count (count summaries)
     :psi.agent-session/tool-lifecycle-summaries     summaries}))
(pco/defresolver tool-lifecycle-summary-by-tool-id
  [{:keys [psi.agent-session/lookup-tool-id psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input
   [:psi.agent-session/lookup-tool-id :psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output
   [{:psi.agent-session/tool-lifecycle-summary-for-tool-id
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/event-count
      :psi.tool-lifecycle.summary/last-event-kind
      :psi.tool-lifecycle.summary/started-at
      :psi.tool-lifecycle.summary/last-updated-at
      :psi.tool-lifecycle.summary/completed?
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args]}]}
  {:psi.agent-session/tool-lifecycle-summary-for-tool-id
   (some (fn [summary]
           (when (= lookup-tool-id (:psi.tool-lifecycle.summary/tool-id summary))
             summary))
         (tool-lifecycle-summaries agent-session-ctx session-id))})
(defn- provider-requests
  [agent-session-ctx session-id]
  (let [sid session-id]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :provider-requests sid))
             []))))
(defn- provider-nonerror-replies
  [agent-session-ctx session-id]
  (let [sid session-id]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :provider-replies sid))
             []))))
(defn- provider-error-replies
  [agent-session-ctx session-id]
  (vec (or (:provider-error-replies (support/session-data agent-session-ctx session-id))
           [])))
(defn- provider-replies
  [agent-session-ctx session-id]
  (let [nonerror (provider-nonerror-replies agent-session-ctx session-id)
        errors   (provider-error-replies agent-session-ctx session-id)]
    (if (seq errors)
      (->> (concat (remove #(= :error (get-in % [:event :type])) nonerror)
                   errors)
           (sort-by :timestamp)
           vec)
      nonerror)))
(defn- provider-request->eql
  [capture]
  {:psi.provider-request/provider  (:provider capture)
   :psi.provider-request/api       (:api capture)
   :psi.provider-request/url       (:url capture)
   :psi.provider-request/turn-id   (:turn-id capture)
   :psi.provider-request/timestamp (:timestamp capture)
   :psi.provider-request/headers   (get-in capture [:request :headers])
   :psi.provider-request/body      (get-in capture [:request :body])})
(defn- provider-reply->eql
  [capture]
  {:psi.provider-reply/provider  (:provider capture)
   :psi.provider-reply/api       (:api capture)
   :psi.provider-reply/url       (:url capture)
   :psi.provider-reply/turn-id   (:turn-id capture)
   :psi.provider-reply/timestamp (:timestamp capture)
   :psi.provider-reply/event     (:event capture)})
(pco/defresolver provider-request-by-turn-id
  "Resolve a single captured provider request by turn-id."
  [{:keys [psi.agent-session/lookup-turn-id psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi.agent-session/lookup-turn-id :psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.agent-session/provider-request-for-turn-id
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}]}
  {:psi.agent-session/provider-request-for-turn-id
   (some->> (provider-requests agent-session-ctx session-id)
            (some (fn [capture]
                    (when (= lookup-turn-id (:turn-id capture))
                      (provider-request->eql capture)))))})
(pco/defresolver provider-reply-by-turn-id
  "Resolve a single captured provider reply by turn-id."
  [{:keys [psi.agent-session/lookup-turn-id psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi.agent-session/lookup-turn-id :psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.agent-session/provider-reply-for-turn-id
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}]}
  {:psi.agent-session/provider-reply-for-turn-id
   (some->> (provider-replies agent-session-ctx session-id)
            reverse
            (some (fn [capture]
                    (when (= lookup-turn-id (:turn-id capture))
                      (provider-reply->eql capture)))))})
(pco/defresolver agent-session-provider-captures
  "Resolve captured outbound provider requests and inbound provider reply events."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/provider-request-count
                 :psi.agent-session/provider-reply-count
                 {:psi.agent-session/provider-last-request
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}
                 {:psi.agent-session/provider-last-reply
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-last-error-reply
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-requests
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}
                 {:psi.agent-session/provider-replies
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-error-replies
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}]}
  (let [requests*         (mapv provider-request->eql (provider-requests agent-session-ctx session-id))
        raw-replies       (provider-replies agent-session-ctx session-id)
        raw-error-replies (provider-error-replies agent-session-ctx session-id)
        replies*          (mapv provider-reply->eql raw-replies)
        error-replies*    (mapv provider-reply->eql raw-error-replies)]
    {:psi.agent-session/provider-request-count   (count requests*)
     :psi.agent-session/provider-reply-count     (count replies*)
     :psi.agent-session/provider-last-request    (last requests*)
     :psi.agent-session/provider-last-reply      (last replies*)
     :psi.agent-session/provider-last-error-reply (last error-replies*)
     :psi.agent-session/provider-requests        requests*
     :psi.agent-session/provider-replies         replies*
     :psi.agent-session/provider-error-replies   error-replies*}))
(defn- error-message-text
  "Extract the first :error block text from an assistant message."
  [msg]
  (some #(when (= :error (:type %)) (:text %)) (:content msg)))
(defn- parse-request-id
  [error-text]
  (when error-text
    (or (second (re-find #"\"request-id\"\s+\"([^\"]+)\"" error-text))
        (second (re-find #"\[request-id\s+([^\]\s]+)\]" error-text)))))
(defn- api-errors-from-messages
  [agent-session-ctx session-id]
  (if-not (ss/agent-ctx-in agent-session-ctx session-id)
    []
    (let [msgs (support/agent-core-messages agent-session-ctx session-id)]
      (->> msgs
           (map-indexed vector)
           (filter (fn [[_ m]]
                     (and (= "assistant" (:role m))
                          (= :error (:stop-reason m)))))
           (mapv (fn [[idx m]]
                   (let [err-text (error-message-text m)
                         brief    (when err-text
                                    (subs err-text
                                          0 (min 120 (count err-text))))]
                     {:psi.api-error/message-index idx
                      :psi.api-error/http-status (:http-status m)
                      :psi.api-error/timestamp (:timestamp m)
                      :psi.api-error/error-message-brief brief
                      :psi.api-error/error-message-full err-text
                      :psi.api-error/request-id (parse-request-id err-text)
                      :psi.agent-session/session-id session-id
                      :psi/agent-session-ctx agent-session-ctx})))))))
(defn- provider-error-reply->api-error
  [agent-session-ctx session-id idx capture]
  (let [event      (:event capture)
        err-text   (:error-message event)
        body       (:body event)
        body-text  (:body-text event)
        request-id (or (get-in event [:headers "request-id"])
                       (:request_id body)
                       (parse-request-id err-text))
        brief-src  (or err-text body-text (some-> body pr-str))
        brief      (when (seq brief-src)
                     (subs brief-src 0 (min 120 (count brief-src))))]
    {:psi.api-error/message-index idx
     :psi.api-error/http-status (:http-status event)
     :psi.api-error/timestamp (:timestamp capture)
     :psi.api-error/error-message-brief brief
     :psi.api-error/error-message-full err-text
     :psi.api-error/request-id request-id
     :psi.api-error/provider (:provider capture)
     :psi.api-error/api (:api capture)
     :psi.api-error/url (:url capture)
     :psi.api-error/turn-id (:turn-id capture)
     :psi.api-error/provider-event event
     :psi.api-error/provider-reply-capture capture
     :psi.agent-session/session-id session-id
     :psi/agent-session-ctx agent-session-ctx}))
(defn- find-provider-reply-by-request-id
  [agent-session-ctx session-id request-id]
  (when (seq request-id)
    (->> (provider-replies agent-session-ctx session-id)
         reverse
         (some (fn [capture]
                 (let [event (:event capture)
                       body  (:body event)]
                   (when (= request-id
                            (or (get-in event [:headers "request-id"])
                                (:request_id body)
                                (parse-request-id (:error-message event))))
                     capture)))))))
(defn- enrich-api-error-from-provider-reply
  [agent-session-ctx session-id error]
  (if (or (:psi.api-error/provider-event error)
          (not (:psi/agent-session-ctx error)))
    error
    (if-let [capture (find-provider-reply-by-request-id agent-session-ctx
                                                        session-id
                                                        (:psi.api-error/request-id error))]
      (merge error
             (dissoc (provider-error-reply->api-error agent-session-ctx
                                                      session-id
                                                      (:psi.api-error/message-index error)
                                                      capture)
                     :psi.api-error/message-index
                     :psi.agent-session/session-id
                     :psi/agent-session-ctx))
      error)))
(defn- api-errors-from-provider-replies
  [agent-session-ctx session-id]
  (->> (provider-replies agent-session-ctx session-id)
       (keep-indexed (fn [idx capture]
                       (when (= :error (get-in capture [:event :type]))
                         (provider-error-reply->api-error agent-session-ctx session-id idx capture))))
       vec))
(defn- dedupe-api-errors
  [errors]
  (->> errors
       (reduce (fn [acc error]
                 (let [k [(or (:psi.api-error/request-id error) ::no-request-id)
                          (or (:psi.api-error/error-message-full error)
                              (:psi.api-error/error-message-brief error)
                              ::no-message)]
                       existing (get acc k)]
                   (assoc acc k
                          (cond
                            (nil? existing)
                            error
                            (and (nil? (:psi.api-error/provider-event existing))
                                 (:psi.api-error/provider-event error))
                            (merge existing error)
                            :else
                            existing))))
               {})
       vals
       vec))
(defn- message-summary
  "Lightweight summary of an agent-core message for context display."
  [msg idx]
  (let [snippet (some (fn [c]
                        (case (:type c)
                          :text      (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          :tool-call (str "[tool:" (:name c) "]")
                          :error     (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          nil))
                      (:content msg))]
    {:psi.context-message/index         idx
     :psi.context-message/role          (:role msg)
     :psi.context-message/content-types (mapv :type (:content msg))
     :psi.context-message/snippet       (or snippet "")}))
(def ^:private request-shape-output
  [:psi.request-shape/message-count
   :psi.request-shape/system-prompt-chars
   :psi.request-shape/message-chars
   :psi.request-shape/tool-schema-chars
   :psi.request-shape/total-chars
   :psi.request-shape/estimated-tokens
   :psi.request-shape/context-window
   :psi.request-shape/max-output-tokens
   :psi.request-shape/headroom-tokens
   :psi.request-shape/role-distribution
   :psi.request-shape/tool-count
   :psi.request-shape/tool-use-count
   :psi.request-shape/tool-result-count
   :psi.request-shape/missing-tool-results
   :psi.request-shape/orphan-tool-results
   :psi.request-shape/alternation-valid?
   :psi.request-shape/alternation-violations
   :psi.request-shape/empty-content-count])
(defn- compute-request-shape
  [system-prompt messages tools context-window max-output-tokens]
  (let [role-counts   (frequencies (map :role messages))
        tool-use-ids  (into #{}
                            (comp (filter #(= "assistant" (:role %)))
                                  (mapcat :content)
                                  (filter #(= :tool-call (:type %)))
                                  (map :id))
                            messages)
        tool-result-ids (into #{}
                              (comp (filter #(= "toolResult" (:role %)))
                                    (map :tool-call-id))
                              messages)
        sys-chars  (count (str system-prompt))
        msg-chars  (transduce (map #(count (pr-str %))) + 0 messages)
        tool-chars (transduce (map #(count (pr-str %))) + 0 tools)
        total      (+ sys-chars msg-chars tool-chars)
        est-tokens (quot total 4)
        headroom   (- context-window est-tokens max-output-tokens)
        api-roles  (->> messages
                        (keep #(case (:role %)
                                 "user"       "user"
                                 "assistant"  "assistant"
                                 "toolResult" "user"
                                 nil)))
        merged     (reduce (fn [acc r]
                             (if (= r (peek acc)) acc (conj acc r)))
                           [] api-roles)
        violations (count (filter (fn [[a b]] (= a b))
                                  (partition 2 1 merged)))
        empty-ct   (count (filter #(empty? (:content %)) messages))]
    {:psi.request-shape/message-count          (count messages)
     :psi.request-shape/system-prompt-chars    sys-chars
     :psi.request-shape/message-chars          msg-chars
     :psi.request-shape/tool-schema-chars      tool-chars
     :psi.request-shape/total-chars            total
     :psi.request-shape/estimated-tokens       est-tokens
     :psi.request-shape/context-window         context-window
     :psi.request-shape/max-output-tokens      max-output-tokens
     :psi.request-shape/headroom-tokens        headroom
     :psi.request-shape/role-distribution      role-counts
     :psi.request-shape/tool-count             (count tools)
     :psi.request-shape/tool-use-count         (count tool-use-ids)
     :psi.request-shape/tool-result-count      (count tool-result-ids)
     :psi.request-shape/missing-tool-results   (count (set/difference tool-use-ids tool-result-ids))
     :psi.request-shape/orphan-tool-results    (count (set/difference tool-result-ids tool-use-ids))
     :psi.request-shape/alternation-valid?     (zero? violations)
     :psi.request-shape/alternation-violations violations
     :psi.request-shape/empty-content-count    empty-ct}))
(defn- resolve-context-window
  [agent-session-ctx session-id]
  (or (:context-window (support/session-data agent-session-ctx session-id))
      (some-> (:model-config-atom agent-session-ctx) deref :context-window)
      200000))
(defn- resolve-max-output-tokens
  [agent-session-ctx]
  (or (some-> (:model-config-atom agent-session-ctx) deref :max-tokens)
      16384))
(pco/defresolver api-error-list
  "Extract API errors from assistant messages and provider reply captures."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/api-error-count
                 {:psi.agent-session/api-errors
                  [:psi.api-error/message-index
                   :psi.api-error/http-status
                   :psi.api-error/timestamp
                   :psi.api-error/error-message-brief
                   :psi.api-error/error-message-full
                   :psi.api-error/request-id
                   :psi.api-error/provider
                   :psi.api-error/api
                   :psi.api-error/url
                   :psi.api-error/turn-id
                   :psi.api-error/provider-event]}]}
  (let [message-errors  (mapv #(enrich-api-error-from-provider-reply agent-session-ctx session-id %)
                              (api-errors-from-messages agent-session-ctx session-id))
        provider-errors (api-errors-from-provider-replies agent-session-ctx session-id)
        errors          (dedupe-api-errors (vec (concat message-errors provider-errors)))]
    {:psi.agent-session/api-error-count (count errors)
     :psi.agent-session/api-errors      errors}))
(pco/defresolver api-error-detail
  "Resolve API error detail."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx psi.agent-session/session-id]
    :as entity}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.api-error/error-message-full
                 :psi.api-error/request-id
                 :psi.api-error/provider
                 :psi.api-error/api
                 :psi.api-error/url
                 :psi.api-error/turn-id
                 :psi.api-error/provider-event
                 {:psi.api-error/surrounding-messages
                  [:psi.context-message/index
                   :psi.context-message/role
                   :psi.context-message/content-types
                   :psi.context-message/snippet]}]}
  (let [msgs              (support/agent-core-messages agent-session-ctx session-id)
        msg               (nth msgs message-index nil)
        err-text          (or (:psi.api-error/error-message-full entity)
                              (when msg (error-message-text msg)))
        provider-event?   (and (nil? msg)
                               (some? (:psi.api-error/provider-event entity)))
        surr              (if provider-event?
                            []
                            (let [start (max 0 (- message-index 5))
                                  end   (min (count msgs) (+ message-index 3))]
                              (mapv #(message-summary (nth msgs %) %) (range start end))))]
    {:psi.api-error/error-message-full   err-text
     :psi.api-error/request-id           (or (:psi.api-error/request-id entity)
                                             (parse-request-id err-text))
     :psi.api-error/provider             (:psi.api-error/provider entity)
     :psi.api-error/api                  (:psi.api-error/api entity)
     :psi.api-error/url                  (:psi.api-error/url entity)
     :psi.api-error/turn-id              (:psi.api-error/turn-id entity)
     :psi.api-error/provider-event       (:psi.api-error/provider-event entity)
     :psi.api-error/surrounding-messages surr}))
(pco/defresolver api-error-request-shape
  "Resolve API error request shape."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.api-error/request-shape request-shape-output}]}
  (let [data      (support/agent-data agent-session-ctx session-id)
        msgs      (:messages data)
        pre-error (subvec (vec msgs) 0 (min message-index (count msgs)))]
    {:psi.api-error/request-shape
     (compute-request-shape (:system-prompt data)
                            pre-error
                            (:tools data)
                            (resolve-context-window agent-session-ctx session-id)
                            (resolve-max-output-tokens agent-session-ctx))}))
(pco/defresolver current-request-shape
  "Resolve request shape for the current conversation state."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [{:psi.agent-session/request-shape request-shape-output}]}
  (let [data (support/agent-data agent-session-ctx session-id)]
    {:psi.agent-session/request-shape
     (compute-request-shape (:system-prompt data)
                            (:messages data)
                            (:tools data)
                            (resolve-context-window agent-session-ctx session-id)
                            (resolve-max-output-tokens agent-session-ctx))}))
(pco/defresolver agent-session-turn
  "Resolve per-turn streaming statechart state."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.turn/phase
                 :psi.turn/is-streaming
                 :psi.turn/text
                 :psi.turn/tool-calls
                 :psi.turn/tool-call-count
                 :psi.turn/final-message
                 :psi.turn/error-message
                 :psi.turn/last-provider-event
                 :psi.turn/content-blocks
                 :psi.turn/is-text-accumulating
                 :psi.turn/is-tool-accumulating
                 :psi.turn/is-done
                 :psi.turn/is-error]}
  (if-let [turn-ctx (accessors/turn-context-in agent-session-ctx session-id)]
    (let [phase (turn-sc/turn-phase turn-ctx)
          td    (turn-sc/get-turn-data turn-ctx)]
      {:psi.turn/phase                phase
       :psi.turn/is-streaming         (boolean (#{:text-accumulating :tool-accumulating} phase))
       :psi.turn/text                 (:text-buffer td)
       :psi.turn/tool-calls           (vec (vals (:tool-calls td)))
       :psi.turn/tool-call-count      (count (:tool-calls td))
       :psi.turn/final-message        (:final-message td)
       :psi.turn/error-message        (:error-message td)
       :psi.turn/last-provider-event  (:last-provider-event td)
       :psi.turn/content-blocks       (vec (vals (:content-blocks td)))
       :psi.turn/is-text-accumulating (= :text-accumulating phase)
       :psi.turn/is-tool-accumulating (= :tool-accumulating phase)
       :psi.turn/is-done              (= :done phase)
       :psi.turn/is-error             (= :error phase)})
    {:psi.turn/phase                nil
     :psi.turn/is-streaming         false
     :psi.turn/text                 nil
     :psi.turn/tool-calls           []
     :psi.turn/tool-call-count      0
     :psi.turn/final-message        nil
     :psi.turn/error-message        nil
     :psi.turn/last-provider-event  nil
     :psi.turn/content-blocks       []
     :psi.turn/is-text-accumulating false
     :psi.turn/is-tool-accumulating false
     :psi.turn/is-done              false
     :psi.turn/is-error             false}))
(def resolvers
  (into basics/resolvers
        [agent-session-canonical-telemetry
         agent-session-stats
         agent-session-tool-call-attempts
         agent-session-tool-lifecycle-events
         tool-lifecycle-summary-by-tool-id
         agent-session-provider-captures
         provider-request-by-turn-id
         provider-reply-by-turn-id
         api-error-list
         api-error-detail
         api-error-request-shape
         current-request-shape
         agent-session-turn]))
