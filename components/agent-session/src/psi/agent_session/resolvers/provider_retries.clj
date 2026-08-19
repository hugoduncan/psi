(ns psi.agent-session.resolvers.provider-retries
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.session-state.state :as session]))

(defn- provider-events
  [agent-session-ctx session-id]
  (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :provider-events session-id))
           [])))

(defn- event-provider-request-id
  [event]
  (or (:provider-request-id event) (:turn-id event)))

(defn- retry-schedule->eql
  [event final?]
  {:psi.provider-retry/attempt       (:retry-attempt event)
   :psi.provider-retry/failed-attempt (:failed-attempt event)
   :psi.provider-retry/error-kind    (:error-kind event)
   :psi.provider-retry/error-message (:error-message event)
   :psi.provider-retry/http-status   (:http-status event)
   :psi.provider-retry/delay-ms      (:delay-ms event)
   :psi.provider-retry/delay-source  (:delay-source event)
   :psi.provider-retry/resume-at     (:resume-at event)
   :psi.provider-retry/rate-limit    (:rate-limit event)
   :psi.provider-retry/final?        final?})

(defn- provider-retry-summary->eql
  [[provider-request-id events]]
  (let [events*       (sort-by (juxt #(or (:retry-attempt %) -1) :timestamp) events)
        schedules     (filter #(= "provider_retry_scheduled" (:type %)) events*)
        finals        (filter :final? events*)
        final         (last finals)
        ;; A schedule is the final retry of the provider request when it is the
        ;; LAST schedule (max retry-attempt), not when its attempt number
        ;; matches the terminal event's. The terminal event does not always
        ;; report the superseded schedule's attempt: the truncated-final
        ;; deadline give-up reports the pre-sleep FAILED attempt N (the actual
        ;; executed attempt, matching its :attempt-id) while the superseded
        ;; truncated schedule — the retry that runs out the window to the
        ;; deadline — carries N+1. Keying on the last schedule keeps the marker
        ;; correct for every terminal path (success, count-cap, deadline,
        ;; cancel: the cancel event reports the scheduled attempt N+1).
        final-attempt (some-> (last schedules) :retry-attempt)]
    {:psi.provider-request/id             provider-request-id
     :psi.provider-request/turn-id        (:turn-id (first events*))
     :psi.provider-request/retry-count    (count schedules)
     :psi.provider-request/retry-attempts (mapv #(retry-schedule->eql % (= (:retry-attempt %) final-attempt)) schedules)
     :psi.provider-request/final-status   (or (:failure-reason final)
                                              (:status final))
     :psi.provider-request/error-kind     (:error-kind final)}))

(defn- provider-retry-summaries
  [agent-session-ctx session-id]
  (->> (provider-events agent-session-ctx session-id)
       (filter event-provider-request-id)
       (group-by event-provider-request-id)
       (mapv provider-retry-summary->eql)))

(defn- matching-provider-retry-summary
  [agent-session-ctx session-id predicate]
  (some #(when (predicate %) %)
        (provider-retry-summaries agent-session-ctx session-id)))

(pco/defresolver provider-retry-by-request-id
  "Resolve one provider retry summary from an explicit provider request id."
  [{:keys [psi/agent-session-ctx
           psi.agent-session/session-id
           psi.provider-request/id]}]
  {::pco/input  [:psi/agent-session-ctx
                 :psi.agent-session/session-id
                 :psi.provider-request/id]
   ::pco/output [:psi.provider-request/turn-id
                 :psi.provider-request/retry-count
                 :psi.provider-request/final-status
                 :psi.provider-request/error-kind
                 {:psi.provider-request/retry-attempts
                  [:psi.provider-retry/attempt
                   :psi.provider-retry/failed-attempt
                   :psi.provider-retry/error-kind
                   :psi.provider-retry/error-message
                   :psi.provider-retry/http-status
                   :psi.provider-retry/delay-ms
                   :psi.provider-retry/delay-source
                   :psi.provider-retry/resume-at
                   :psi.provider-retry/rate-limit
                   :psi.provider-retry/final?]}]}
  (or (matching-provider-retry-summary agent-session-ctx session-id
                                       #(= id (:psi.provider-request/id %)))
      {}))

(pco/defresolver provider-retry-by-turn-id
  "Resolve one provider retry summary from an explicit turn id."
  [{:keys [psi/agent-session-ctx
           psi.agent-session/session-id
           psi.provider-request/turn-id]}]
  {::pco/input  [:psi/agent-session-ctx
                 :psi.agent-session/session-id
                 :psi.provider-request/turn-id]
   ::pco/output [:psi.provider-request/id
                 :psi.provider-request/retry-count
                 :psi.provider-request/final-status
                 :psi.provider-request/error-kind
                 {:psi.provider-request/retry-attempts
                  [:psi.provider-retry/attempt
                   :psi.provider-retry/failed-attempt
                   :psi.provider-retry/error-kind
                   :psi.provider-retry/error-message
                   :psi.provider-retry/http-status
                   :psi.provider-retry/delay-ms
                   :psi.provider-retry/delay-source
                   :psi.provider-retry/resume-at
                   :psi.provider-retry/rate-limit
                   :psi.provider-retry/final?]}]}
  (or (matching-provider-retry-summary agent-session-ctx session-id
                                       #(= turn-id (:psi.provider-request/turn-id %)))
      {}))

(pco/defresolver agent-session-provider-retries
  "Resolve provider retry summaries from retained provider lifecycle events."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.agent-session/provider-retry-count
                 :psi.agent-session/provider-retried-request-count
                 {:psi.agent-session/provider-retries
                  [:psi.provider-request/id
                   :psi.provider-request/turn-id
                   :psi.provider-request/retry-count
                   :psi.provider-request/final-status
                   :psi.provider-request/error-kind
                   {:psi.provider-request/retry-attempts
                    [:psi.provider-retry/attempt
                     :psi.provider-retry/failed-attempt
                     :psi.provider-retry/error-kind
                     :psi.provider-retry/error-message
                     :psi.provider-retry/http-status
                     :psi.provider-retry/delay-ms
                     :psi.provider-retry/delay-source
                     :psi.provider-retry/resume-at
                     :psi.provider-retry/rate-limit
                     :psi.provider-retry/final?]}]}]}
  (let [summaries (provider-retry-summaries agent-session-ctx session-id)
        retried   (filter #(pos? (:psi.provider-request/retry-count %)) summaries)]
    {:psi.agent-session/provider-retry-count           (reduce + (map :psi.provider-request/retry-count summaries))
     :psi.agent-session/provider-retried-request-count (count retried)
     :psi.agent-session/provider-retries               (vec retried)}))

(def resolvers
  [agent-session-provider-retries
   provider-retry-by-request-id
   provider-retry-by-turn-id])
