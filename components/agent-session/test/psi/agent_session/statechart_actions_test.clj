(ns psi.agent-session.statechart-actions-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-handlers.statechart-actions :as statechart-actions]
   [psi.session-state.state :as session-state]
   [psi.agent-session.test-support :as test-support]))

(defn- invoke-handler
  [ctx event-type data]
  (let [handler-fn (get-in (kernel/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(defn- apply-root-state-update!
  [ctx result]
  (when-let [f (:root-state-update result)]
    (swap! (:state* ctx) f))
  result)

(defn- with-registered-handlers
  [ctx f]
  (kernel/clear-handlers!)
  (try
    (statechart-actions/register! ctx)
    (f)
    (finally
      (kernel/clear-handlers!))))

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
                                                                        :interrupt-reason :deferred-interrupt
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
                     :interrupt-requested-at nil
                     :interrupt-reason nil}
                    (select-keys (session-state/get-session-data-in ctx session-id)
                                 [:is-streaming :retry-attempt :interrupt-pending :interrupt-requested-at :interrupt-reason])))
             (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                     {:effect/type :runtime/emit-background-job-terminal-messages}
                     {:effect/type :scheduler/drain-queue}
                     {:effect/type :runtime/record-pending-tool-call-interrupts
                      :session-id session-id
                      :reason :deferred-interrupt}]
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

(deftest retry-classified-transport-failure-does-not-activate-legacy-statechart-retry-test
  (testing "classified transport failure is handled by provider-boundary retry, not statechart replay"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:auto-retry-enabled true
                                                                          :retry-attempt 0}})
          should-retry?    (resolve 'psi.agent-session.statechart/should-retry?)
          guard-data       {:ctx ctx
                            :session-id session-id
                            :config {:auto-retry-max-retries 3}
                            :pending-agent-event {:type :agent-end
                                                  :messages [{:role "assistant"
                                                              :stop-reason :error
                                                              :error-message "Premature end of chunk coded message body: closing chunk expected"}]}}]
      (is (false? (should-retry? guard-data)))
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx
                                      :on-retry-triggered
                                      {:session-id session-id
                                       :pending-agent-event {:provider-error/headers {"Retry-After" "8"}}})]
           (apply-root-state-update! ctx result)
           (is (= 0 (:retry-attempt (session-state/get-session-data-in ctx session-id))))
           (is (nil? (:retry (session-state/get-session-data-in ctx session-id))))
           (is (= [] (:effects result))))))))

(deftest retry-handlers-test
  ;; Tests compatibility retry handlers cannot replay the old whole-agent loop.
  (testing "on-retry-triggered is a compatibility no-op because provider-boundary retry is authoritative"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:retry-attempt 2
                                                                          :retry {:active? true
                                                                                  :attempt 2
                                                                                  :delay-ms 250
                                                                                  :delay-source :exponential-backoff
                                                                                  :resume-at 1250
                                                                                  :rate-limit nil}
                                                                          :model {:provider "openai" :id "gpt-5.4"}
                                                                          :last-execution-result-summary {:turn-id "turn-2"}}})
          reg              (:extension-registry ctx)
          seen             (atom [])]
      (ext/register-extension-in! reg "/ext/provider-telemetry")
      (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_finished" #(swap! seen conj %))
      (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_retry_scheduled" #(swap! seen conj %))
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx :on-retry-triggered {:session-id session-id
                                                               :pending-agent-event {:type :agent-end
                                                                                     :messages [{:role "assistant"
                                                                                                 :stop-reason :error
                                                                                                 :error-message "Premature end of chunk coded message body: closing chunk expected"
                                                                                                 :http-status nil}]}})]
           (apply-root-state-update! ctx result)
           (is (= 2 (:retry-attempt (session-state/get-session-data-in ctx session-id))))
           (is (nil? (:retry (session-state/get-session-data-in ctx session-id))))
           (is (= [] (:effects result)))
           (is (= [] @seen))))))

  (testing "on-retrying-entered returns an explicit no-op pure result because retry state was already updated"
    (let [[ctx _session-id] (test-support/make-session-ctx {})]
      (with-registered-handlers
        ctx
        #(is (= {:effects []}
                (invoke-handler ctx :on-retrying-entered {}))))))

  (testing "on-retry-resume clears retry metadata without replaying the agent loop"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:retry {:active? true
                                                                                  :attempt 1
                                                                                  :delay-ms 1000
                                                                                  :delay-source :retry-after
                                                                                  :resume-at 2000}}})]
      (with-registered-handlers
        ctx
        #(let [result (invoke-handler ctx :on-retry-resume {:session-id session-id})]
           (apply-root-state-update! ctx result)
           (is (nil? (:retry (session-state/get-session-data-in ctx session-id))))
           (is (= {:effects []}
                  (dissoc result :root-state-update))))))))

(deftest on-agent-done-emits-terminal-provider-failure-telemetry-test
  (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:model {:provider "openai" :id "gpt-5.4"}
                                                                        :retry-attempt 1
                                                                        :last-execution-result-summary {:turn-id "turn-9"}}})
        reg (:extension-registry ctx)
        seen (atom [])]
    (ext/register-extension-in! reg "/ext/provider-telemetry")
    (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_finished" #(swap! seen conj %))
    (with-registered-handlers
      ctx
      #(do
         (invoke-handler ctx :on-agent-done {:session-id session-id
                                             :pending-agent-event {:type :agent-end
                                                                   :provider-error/headers {"retry-after" "2"}
                                                                   :messages [{:role "assistant"
                                                                               :stop-reason :error
                                                                               :error-message "rate limit exceeded"
                                                                               :http-status 429}]}})
         (is (= 1 (count @seen)))
         (is (= :failed (:status (first @seen))))
         (is (true? (:final? (first @seen))))
         (is (= :rate-limit (:error-kind (first @seen))))))))

(deftest legacy-retry-flow-does-not-mutate-prepared-request-turn-id-test
  (testing "legacy retry handlers no longer own prepared-request replay"
    (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:retry-attempt 0
                                                                          :model {:provider "openai" :id "gpt-5.4"}}})
          first-turn-id "turn-initial"
          second-turn-id "turn-retry"
          first-prepared (prompt-request/build-prepared-request
                          ctx session-id
                          {:turn-id first-turn-id
                           :user-message {:role "user"
                                          :content [{:type :text :text "hello"}]}
                           :commands []})
          _ (session-state/update-state-value-in!
             ctx
             (session-state/state-path :session-data session-id)
             assoc
             :last-prepared-request-summary {:turn-id (:prepared-request/id first-prepared)}
             :last-execution-result-summary {:turn-id (:prepared-request/id first-prepared)})
          _ (with-registered-handlers
              ctx
              #(->> (invoke-handler ctx :on-retry-triggered {:session-id session-id
                                                             :pending-agent-event {:type :agent-end
                                                                                   :messages [{:role "assistant"
                                                                                               :stop-reason :error
                                                                                               :error-message "Premature end of chunk coded message body: closing chunk expected"}]}})
                    (apply-root-state-update! ctx)))
          _ (is (= 0 (:retry-attempt (session-state/get-session-data-in ctx session-id))))
          _ (with-registered-handlers
              ctx
              #(->> (invoke-handler ctx :on-retry-resume {:session-id session-id})
                    (apply-root-state-update! ctx)))
          second-prepared (prompt-request/build-prepared-request
                           ctx session-id
                           {:turn-id second-turn-id
                            :user-message {:role "user"
                                           :content [{:type :text :text "hello"}]}
                            :commands []})]
      (is (= first-turn-id (:prepared-request/id first-prepared)))
      (is (= second-turn-id (:prepared-request/id second-prepared)))
      (is (not= (:prepared-request/id first-prepared)
                (:prepared-request/id second-prepared)))
      (is (= session-id (:prepared-request/session-id first-prepared)))
      (is (= session-id (:prepared-request/session-id second-prepared)))
      (is (nil? (:retry (session-state/get-session-data-in ctx session-id)))))))
