(ns psi.agent-session.statechart-actions-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers.statechart-actions :as statechart-actions]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.test-support :as test-support]))

(defn- invoke-handler
  [ctx event-type data]
  (let [handler-fn (get-in (dispatch/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(defn- apply-root-state-update!
  [ctx result]
  (when-let [f (:root-state-update result)]
    (swap! (:state* ctx) f))
  result)

(defn- with-registered-handlers
  [ctx f]
  (dispatch/clear-handlers!)
  (try
    (statechart-actions/register! ctx)
    (f)
    (finally
      (dispatch/clear-handlers!))))

(deftest daemon-thread-test
  ;; Tests daemon thread creation and execution.
  (testing "starts a daemon thread that runs the supplied function"
    (let [done-p (promise)
          t      (statechart-actions/daemon-thread #(deliver done-p :done))]
      (is (.isDaemon t))
      (is (= :done (deref done-p 1000 ::timeout))))))

(deftest drop-trailing-overflow-error!-test
  ;; Tests trailing overflow-error cleanup in the agent message list.
  (testing "drops a trailing assistant overflow error message"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          agent-ctx        (session-state/agent-ctx-in ctx session-id)]
      (agent/create-agent-in! agent-ctx {:system-prompt "prompt"
                                         :model {:provider "anthropic" :id "claude"}
                                         :messages [{:role "user" :content [{:type :text :text "hi"}]}
                                                    {:role "assistant"
                                                     :stop-reason :error
                                                     :error-message "context window exceeded"
                                                     :content [{:type :text :text "too long"}]}]})
      (dispatch-effects/drop-trailing-overflow-error! ctx session-id)
      (is (= [{:role "user" :content [{:type :text :text "hi"}]}]
             (:messages (agent/get-data-in agent-ctx))))))

  (testing "leaves messages unchanged when the trailing message is not an overflow error"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          agent-ctx        (session-state/agent-ctx-in ctx session-id)
          messages         [{:role "user" :content [{:type :text :text "hi"}]}
                            {:role "assistant"
                             :stop-reason :stop
                             :content [{:type :text :text "done"}]}]]
      (agent/create-agent-in! agent-ctx {:system-prompt "prompt"
                                         :model {:provider "anthropic" :id "claude"}
                                         :messages messages})
      (dispatch-effects/drop-trailing-overflow-error! ctx session-id)
      (is (= messages
             (:messages (agent/get-data-in agent-ctx)))))))

(deftest streaming-and-terminal-state-handlers-test
  ;; Tests state transitions and effect payloads for streaming lifecycle handlers.
  (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:is-streaming false
                                                                        :retry-attempt 3
                                                                        :interrupt-pending true
                                                                        :interrupt-requested-at #inst "2026-01-01T00:00:00.000Z"}})]
    (with-registered-handlers
      ctx
      #(do
         (testing "on-streaming-entered marks the session as streaming"
           (->> (invoke-handler ctx :on-streaming-entered {:session-id session-id})
                (apply-root-state-update! ctx))
           (is (true? (:is-streaming (session-state/get-session-data-in ctx session-id)))))

         (testing "on-agent-done clears transient flags and emits terminal effects"
           (let [result (invoke-handler ctx :on-agent-done {:session-id session-id})]
             (apply-root-state-update! ctx result)
             (is (= {:is-streaming false
                     :retry-attempt 0
                     :interrupt-pending false
                     :interrupt-requested-at nil}
                    (select-keys (session-state/get-session-data-in ctx session-id)
                                 [:is-streaming :retry-attempt :interrupt-pending :interrupt-requested-at])))
             (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                     {:effect/type :runtime/emit-background-job-terminal-messages}
                     {:effect/type :scheduler/drain-queue}]
                    (:effects result)))))

         (testing "on-abort clears interrupt state and emits agent-abort effect"
           (let [result (invoke-handler ctx :on-abort {:session-id session-id})]
             (apply-root-state-update! ctx result)
             (is (= {:is-streaming false
                     :interrupt-pending false
                     :interrupt-requested-at nil}
                    (select-keys (session-state/get-session-data-in ctx session-id)
                                 [:is-streaming :interrupt-pending :interrupt-requested-at])))
             (is (= [{:effect/type :runtime/agent-abort}
                     {:effect/type :scheduler/drain-queue}]
                    (:effects result)))))))))

(deftest auto-compaction-handlers-test
  ;; Tests auto-compaction reason derivation and compacting flag updates.
  (testing "uses overflow reason and enables retry when the pending assistant message is a context overflow error"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:auto-compaction-enabled true}})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx
                                      :on-auto-compact-triggered
                                      {:session-id session-id
                                       :ctx ctx
                                       :config {}
                                       :pending-agent-event {:messages [{:role "assistant"
                                                                         :stop-reason :error
                                                                         :error-message "context length exceeded"}]}})]
           (apply-root-state-update! ctx result)
           (is (true? (:is-compacting (session-state/get-session-data-in ctx session-id))))
           (is (= [{:effect/type :runtime/auto-compact-workflow
                    :reason :overflow
                    :will-retry? true}]
                  (:effects result)))))))

  (testing "uses threshold reason when token usage exceeds the configured reserve cutoff"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:auto-compaction-enabled true
                                                                          :context-tokens 90000
                                                                          :context-window 100000}})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx
                                      :on-auto-compact-triggered
                                      {:session-id session-id
                                       :ctx ctx
                                       :config {:auto-compaction-reserve-tokens 5000}
                                       :pending-agent-event {:messages [{:role "assistant"
                                                                         :stop-reason :stop}]}})]
           (apply-root-state-update! ctx result)
           (is (true? (:is-compacting (session-state/get-session-data-in ctx session-id))))
           (is (= [{:effect/type :runtime/auto-compact-workflow
                    :reason :threshold
                    :will-retry? false}]
                  (:effects result)))))))

  (testing "does not classify threshold auto-compaction when the last assistant stop reason is an error"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:auto-compaction-enabled true
                                                                          :context-tokens 90000
                                                                          :context-window 100000}})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx
                                      :on-auto-compact-triggered
                                      {:session-id session-id
                                       :ctx ctx
                                       :config {:auto-compaction-reserve-tokens 5000}
                                       :pending-agent-event {:messages [{:role "assistant"
                                                                         :stop-reason "error"
                                                                         :error-message "provider overloaded"}]}})]
           (is (= [{:effect/type :runtime/auto-compact-workflow
                    :reason :threshold
                    :will-retry? false}]
                  (:effects result)))))))

  (testing "falls back to threshold when no auto-compaction reason is derivable"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:auto-compaction-enabled false}})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx
                                      :on-auto-compact-triggered
                                      {:session-id session-id
                                       :ctx ctx
                                       :config {}
                                       :pending-agent-event {:messages [{:role "user"
                                                                         :content [{:type :text :text "hi"}]}]}})]
           (is (= [{:effect/type :runtime/auto-compact-workflow
                    :reason :threshold
                    :will-retry? false}]
                  (:effects result)))))))

  (testing "on-compacting-entered and on-compact-done toggle the compacting flag"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:is-compacting false}})]
      (with-registered-handlers
        ctx
        #(do
           (->> (invoke-handler ctx :on-compacting-entered {:session-id session-id})
                (apply-root-state-update! ctx))
           (is (true? (:is-compacting (session-state/get-session-data-in ctx session-id))))
           (->> (invoke-handler ctx :on-compact-done {:session-id session-id})
                (apply-root-state-update! ctx))
           (is (false? (:is-compacting (session-state/get-session-data-in ctx session-id)))))))))

(deftest retry-handlers-test
  ;; Tests retry backoff scheduling and retry continuation effects.
  (testing "on-retry-triggered increments retry-attempt and schedules backoff with configured limits"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:retry-attempt 2}})
          ctx             (assoc ctx :config {:auto-retry-base-delay-ms 100
                                              :auto-retry-max-delay-ms 250})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx :on-retry-triggered {:session-id session-id})]
           (apply-root-state-update! ctx result)
           (is (= 3 (:retry-attempt (session-state/get-session-data-in ctx session-id))))
           (is (= [{:effect/type :runtime/schedule-thread-sleep-send-event
                    :delay-ms 250
                    :event :session/retry-done}]
                  (:effects result)))))))

  (testing "on-retrying-entered returns an explicit no-op pure result because retry state was already updated"
    (let [[ctx _session-id] (test-support/make-session-ctx {})]
      (with-registered-handlers
        ctx
        #(is (= {:effects []}
                (invoke-handler ctx :on-retrying-entered {}))))))

  (testing "on-retry-resume emits agent-start-loop"
    (let [[ctx _session-id] (test-support/make-session-ctx {})]
      (with-registered-handlers
        ctx
        #(is (= {:effects [{:effect/type :runtime/agent-start-loop}]}
                (invoke-handler ctx :on-retry-resume {})))))))
