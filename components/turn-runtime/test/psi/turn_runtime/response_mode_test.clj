(ns psi.turn-runtime.response-mode-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.ai.core]
   [psi.agent-session.extensions :as ext]
   [psi.ai.models :as models]
   [psi.ai.structured-output :as structured-output]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.turn-runtime.core :as turn-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- prepared-request
  ([ctx session-id]
   (prepared-request ctx session-id "turn-1"))
  ([ctx session-id turn-id]
   (prepared-request ctx session-id turn-id {}))
  ([ctx session-id turn-id {:keys [resolve-runtime-model?]}]
   (let [augmentation-record {:session-id session-id
                              :turn-id turn-id
                              :workflow-run-id nil
                              :status :no-op
                              :replay? false
                              :accepted-operation-count 0
                              :operations []
                              :providers []}]
     (swap! (:state* ctx) assoc-in
            [:agent-session :sessions session-id :data :turn-augmentations turn-id]
            augmentation-record)
     (prompt-request/build-prepared-request
      ctx session-id (cond-> {:turn-id turn-id
                              :user-message {:role "user"
                                             :content [{:type :text :text "hello"}]}}
                       (not resolve-runtime-model?)
                       (assoc :runtime-model (:model (ss/get-session-data-in ctx session-id))))))))

(defn- provider-events
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :telemetry :provider-events]))

(defn- error-turn
  [message]
  {:turn-id "turn-1"
   :model {:provider "openai" :id "gpt-test"}
   :ai-options {}
   :turn-ctx nil
   :assistant-message {:role "assistant"
                       :content [{:type :error :text message}]
                       :stop-reason :error
                       :error-message message
                       :timestamp (java.time.Instant/now)}})

(deftest execute-prepared-request-non-streaming-uses-execute-path-test
  (testing "workflow-owned child session with :response-mode :non-streaming uses psi.ai.core/execute-response-in"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                   (merge (ss/get-session-data-in ctx session-id)
                          {:model {:provider "anthropic" :id "claude-test"}
                           :response-mode :non-streaming}))
          prepared (prepared-request ctx session-id)
          execute-calls* (atom [])
          stream-calls*  (atom [])]
      (with-redefs [psi.ai.core/execute-response-in
                    (fn [_ai-ctx _conv _model opts]
                      ((:on-provider-request opts)
                       {:provider :openai
                        :api :chat-completions
                        :url "https://example.test/v1/chat/completions"
                        :headers {"content-type" "application/json"}
                        :body {:stream false}})
                      ((:on-provider-response opts)
                       {:provider :openai
                        :api :chat-completions
                        :url "https://example.test/v1/chat/completions"
                        :event {:type :done :reason :stop}})
                      (swap! execute-calls* conj :called)
                      {:assistant-message {:role "assistant"
                                           :content [{:type :text :text "done"}]
                                           :stop-reason :stop
                                           :usage {:input-tokens 1 :output-tokens 1 :total-tokens 2}
                                           :timestamp (java.time.Instant/now)}
                       :logprobs [{:token "done" :logprob -0.1 :top []}]})
                    psi.turn-runtime.core/execute-live-turn!
                    (fn [& _]
                      (swap! stream-calls* conj :called)
                      (throw (ex-info "stream path should not be used" {})))]
        (let [result (turn-runtime/execute-prepared-request!
                      {:provider-registry (atom {})}
                      ctx session-id prepared nil)]
          (is (= [:called] @execute-calls*))
          (is (empty? @stream-calls*))
          (is (= :stop (:execution-result/stop-reason result)))
          (is (= [{:type :text :text "done"}]
                 (get-in result [:execution-result/assistant-message :content])))
          (is (= [{:token "done" :logprob -0.1 :top []}]
                 (:execution-result/logprobs result)))
          (is (= {:request-captures [{:provider :openai
                                      :api :chat-completions
                                      :url "https://example.test/v1/chat/completions"
                                      :headers {"content-type" "application/json"}
                                      :body {:stream false}
                                      :turn-id "turn-1"
                                      :timestamp (-> result :execution-result/provider-captures :request-captures first :timestamp)}]
                  :response-captures [{:provider :openai
                                       :api :chat-completions
                                       :url "https://example.test/v1/chat/completions"
                                       :event {:type :done :reason :stop}
                                       :turn-id "turn-1"
                                       :timestamp (-> result :execution-result/provider-captures :response-captures first :timestamp)}]}
                 (:execution-result/provider-captures result)))
          (is (instance? java.time.Instant
                         (-> result :execution-result/provider-captures :request-captures first :timestamp)))
          (is (instance? java.time.Instant
                         (-> result :execution-result/provider-captures :response-captures first :timestamp))))))))

(deftest execute-prepared-request-non-streaming-recovers-textual-tool-call-test
  ;; Tests non-streaming responses use the same textual tool-call normalizer via
  ;; a nullable provider seam rather than redefining the AI execution function.
  (let [[ctx session-id] (create-session-context {:persist? false})
        model (assoc (models/get-model :claude-3-5-sonnet)
                     :provider :local
                     :id "local-tool-model"
                     :capabilities {:textual-tool-calls #{:xml}})
        provider {:execute (fn [_conversation _model _options]
                             {:assistant-message {:role "assistant"
                                                  :content [{:type :text
                                                             :text "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"}]
                                                  :stop-reason :stop
                                                  :timestamp (java.time.Instant/now)}})}
        _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                 (merge (ss/get-session-data-in ctx session-id)
                        {:model model
                         :response-mode :non-streaming}))
        prepared (prepared-request ctx session-id)
        result (turn-runtime/execute-prepared-request!
                {:provider-registry (atom {:local provider})}
                ctx session-id prepared nil)]
    (is (= [{:type :tool-call
             :id "turn-1/toolcall/0"
             :name "bash"
             :arguments "{\"command\":\"pwd\"}"}]
           (get-in result [:execution-result/assistant-message :content])))
    (is (= :turn.outcome/tool-use (:execution-result/turn-outcome result)))))

(deftest execute-prepared-request-defaults-to-streaming-test
  (testing "absent :response-mode preserves streaming execution path"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                   (merge (ss/get-session-data-in ctx session-id)
                          {:model {:provider "anthropic" :id "claude-test"}}))
          prepared (prepared-request ctx session-id)
          execute-calls* (atom [])
          stream-calls*  (atom [])]
      (with-redefs [psi.ai.core/execute-response-in
                    (fn [& _]
                      (swap! execute-calls* conj :called)
                      (throw (ex-info "non-stream path should not be used" {})))
                    psi.turn-runtime.core/execute-live-turn!
                    (fn [_ai-ctx _ctx _session-id {:keys [turn-id ai-model]}]
                      (swap! stream-calls* conj :called)
                      {:turn-id turn-id
                       :model (or ai-model (models/get-model :sonnet-4.6))
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "streamed"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}
                       :logprobs nil})]
        (let [result (turn-runtime/execute-prepared-request!
                      {:provider-registry (atom {})}
                      ctx session-id prepared nil)]
          (is (empty? @execute-calls*))
          (is (= [:called] @stream-calls*))
          (is (= [{:type :text :text "streamed"}]
                 (get-in result [:execution-result/assistant-message :content]))))))))

(deftest execute-prepared-request-dispatches-provider-telemetry-test
  ;; First-attempt success preserves existing success behavior and records one
  ;; start/finish lifecycle pair without retry metadata.
  (let [[ctx session-id] (create-session-context {:persist? false})
        _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                 (merge (ss/get-session-data-in ctx session-id)
                        {:model {:provider "openai" :id "gpt-5.4"}}))
        prepared (prepared-request ctx session-id)
        reg (:extension-registry ctx)
        seen (atom [])
        attempts* (atom 0)]
    (ext/register-extension-in! reg "/ext/provider-telemetry")
    (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_started" #(swap! seen conj %))
    (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_finished" #(swap! seen conj %))
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [_ai-ctx _ctx _session-id {:keys [turn-id ai-model]}]
                    (swap! attempts* inc)
                    {:turn-id turn-id
                     :model ai-model
                     :ai-options {}
                     :turn-ctx nil
                     :assistant-message {:role "assistant"
                                         :content [{:type :text :text "streamed"}]
                                         :stop-reason :stop
                                         :timestamp (java.time.Instant/now)}
                     :logprobs nil})]
      (let [result (turn-runtime/execute-prepared-request! {:provider-registry (atom {})} ctx session-id prepared nil)]
        (is (= 1 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= [{:type :text :text "streamed"}]
               (get-in result [:execution-result/assistant-message :content])))
        (is (nil? (:execution-result/retry-outcome result)))))
    (is (= ["provider_request_started" "provider_request_finished"]
           (mapv :type @seen)))
    (is (= {:session-id session-id
            :turn-id "turn-1"
            :provider-request-id "turn-1"
            :attempt-id "turn-1#attempt-0"
            :provider "openai"
            :model-id "gpt-5.4"
            :retry-attempt 0
            :type "provider_request_started"}
           (first @seen)))
    (is (= :succeeded (:status (second @seen))))
    (is (true? (:final? (second @seen))))
    (is (empty? (filter #(= "provider_retry_scheduled" (:type %))
                        (provider-events ctx session-id))))
    (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))

(deftest execute-prepared-request-unsupported-runtime-model-preflights-before-provider-test
  ;; Tests persisted/startup-selected OAuth-backed gpt-5.6 reaches the turn
  ;; preflight boundary through normal prompt-request runtime resolution, and
  ;; fails as a shaped assistant error before any provider request is attempted.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :oauth-ctx (test-support/oauth-openai-ctx)})
        _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                 (merge (ss/get-session-data-in ctx session-id)
                        {:model {:provider "openai" :id "gpt-5.6"}}))
        prepared (prepared-request ctx session-id "turn-unsupported-runtime-model"
                                   {:resolve-runtime-model? true})
        result (turn-runtime/execute-prepared-request!
                {:provider-registry (atom {})}
                ctx session-id prepared nil)]
    (is (= :openai (:provider (:prepared-request/model prepared))))
    (is (= "gpt-5.6" (:id (:prepared-request/model prepared))))
    (is (= true (:runtime/unsupported? (:prepared-request/model prepared))))
    (is (= :turn.outcome/error (:execution-result/turn-outcome result)))
    (is (= :openai-oauth-model-unsupported
           (:execution-result/runtime-unsupported-reason result)))
    (is (= (test-support/unsupported-runtime-model-message)
           (:execution-result/error-message result)))
    (is (= :error (:execution-result/stop-reason result)))
    (is (= {:request-captures [] :response-captures []}
           (:execution-result/provider-captures result)))
    (is (empty? (provider-events ctx session-id)))))

(deftest execute-prepared-request-gpt-5-6-variants-pass-preflight-and-reach-provider-test
  ;; Positive counterpart to
  ;; execute-prepared-request-unsupported-runtime-model-preflights-before-provider-test:
  ;; the OAuth/Codex-supported gpt-5.6 variants (sol/terra/luna) must NOT be
  ;; preflight-rejected. Each persisted/startup-selected variant, resolved
  ;; through the same runtime path (:resolve-runtime-model? true) under OpenAI
  ;; OAuth, is codex-resolved (verbatim id, codex :api/:base-url), carries no
  ;; :runtime/unsupported? marker, is not error-shaped at preflight, and reaches
  ;; provider dispatch (execute-live-turn! called with the codex-resolved model).
  (doseq [id ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :oauth-ctx (test-support/oauth-openai-ctx)})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                   (merge (ss/get-session-data-in ctx session-id)
                          {:model {:provider "openai" :id id}}))
          prepared (prepared-request ctx session-id "turn-variant-preflight"
                                     {:resolve-runtime-model? true})
          seen-model* (atom nil)
          result (with-redefs [psi.turn-runtime.core/execute-live-turn!
                               (fn [_ai-ctx _ctx _session-id {:keys [turn-id ai-model]}]
                                 (reset! seen-model* ai-model)
                                 {:turn-id turn-id
                                  :model ai-model
                                  :ai-options {}
                                  :turn-ctx nil
                                  :assistant-message {:role "assistant"
                                                      :content [{:type :text :text "streamed"}]
                                                      :stop-reason :stop
                                                      :timestamp (java.time.Instant/now)}
                                  :logprobs nil})]
                   (turn-runtime/execute-prepared-request!
                    {:provider-registry (atom {})}
                    ctx session-id prepared nil))]
      ;; Preflight resolved the variant to the codex transport, verbatim id, and
      ;; did NOT mark it runtime-unsupported (contrast bare gpt-5.6).
      (is (= :openai (:provider (:prepared-request/model prepared))) (str id " provider"))
      (is (= id (:id (:prepared-request/model prepared))) (str id " verbatim id"))
      (is (= :openai-codex-responses (:api (:prepared-request/model prepared)))
          (str id " codex api"))
      (is (= "https://chatgpt.com/backend-api" (:base-url (:prepared-request/model prepared)))
          (str id " codex base-url"))
      (is (not (:runtime/unsupported? (:prepared-request/model prepared)))
          (str id " not runtime-unsupported"))
      ;; Not error-shaped at preflight; reached provider dispatch.
      (is (not= :turn.outcome/error (:execution-result/turn-outcome result))
          (str id " not error-shaped"))
      (is (nil? (:execution-result/runtime-unsupported-reason result))
          (str id " no unsupported reason"))
      (is (= :stop (:execution-result/stop-reason result)) (str id " reached provider"))
      (is (= id (:id @seen-model*)) (str id " provider dispatch received codex-resolved variant")))))

(deftest execute-prepared-request-unsupported-structured-output-preflights-before-provider-test
  (testing "fallback-forbidden unsupported strategy fails before streaming provider request"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                   (merge (ss/get-session-data-in ctx session-id)
                          {:model {:provider "openai"
                                   :id "fallback-only"
                                   :capabilities {:structured-output structured-output/openai-codex-fallback-capability}}}))
          prepared (assoc (prepared-request ctx session-id)
                          :prepared-request/ai-options
                          {:structured-output {:schema-id :psi.workflow/test
                                               :schema-version 1
                                               :json-schema {:type "object"}
                                               :strategy-preference :provider-native
                                               :fallback-allowed? false
                                               :strict? true}})
          stream-calls* (atom [])]
      (with-redefs [psi.turn-runtime.core/execute-live-turn!
                    (fn [& _]
                      (swap! stream-calls* conj :called)
                      (throw (ex-info "provider stream should not be called" {})))]
        (let [result (turn-runtime/execute-prepared-request!
                      {:provider-registry (atom {})}
                      ctx session-id prepared nil)]
          (is (empty? @stream-calls*))
          (is (= :turn.outcome/error (:execution-result/turn-outcome result)))
          (is (= :unsupported-structured-output
                 (get-in result [:execution-result/structured-output :reason])))
          (is (= :fallback-not-allowed
                 (get-in result [:execution-result/structured-output :ai-reason])))
          (is (empty? (get-in result [:execution-result/provider-captures :request-captures]))))))))

(deftest execute-prepared-request-unsupported-structured-output-non-streaming-preflights-before-provider-test
  (testing "fallback-forbidden unsupported strategy fails before non-streaming provider request"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data]
                   (merge (ss/get-session-data-in ctx session-id)
                          {:model {:provider "anthropic"
                                   :id "unsupported"
                                   :response-mode :non-streaming
                                   :capabilities {:structured-output structured-output/unsupported-structured-output-capability}}}))
          prepared (assoc (prepared-request ctx session-id)
                          :prepared-request/ai-options
                          {:structured-output {:schema-id :psi.workflow/test
                                               :schema-version 1
                                               :json-schema {:type "object"}
                                               :strategy-preference :provider-native
                                               :fallback-allowed? false
                                               :strict? true}})
          execute-calls* (atom [])]
      (with-redefs [psi.ai.core/execute-response-in
                    (fn [& _]
                      (swap! execute-calls* conj :called)
                      (throw (ex-info "provider execute should not be called" {})))]
        (let [result (turn-runtime/execute-prepared-request!
                      {:provider-registry (atom {})}
                      ctx session-id prepared nil)]
          (is (empty? @execute-calls*))
          (is (= :unsupported-structured-output
                 (get-in result [:execution-result/structured-output :reason])))
          (is (= :structured-output-capability-omitted
                 (get-in result [:execution-result/structured-output :ai-reason])))
          (is (empty? (get-in result [:execution-result/provider-captures :request-captures]))))))))

(deftest execute-prepared-request-zero-max-retries-exhausts-without-scheduling-test
  ;; Enabled retry with zero allowed retry executions returns retry-exhausted, not retry-disabled.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 0}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)]
        (is (= 1 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (true? (:retryable? outcome)))
        (is (true? (:exhausted? outcome)))
        (is (= 0 (:max-retries outcome)))
        (is (= ["provider_request_started" "provider_request_finished"]
               (mapv :type (provider-events ctx session-id))))))))

(deftest execute-prepared-request-retry-exhaustion-preserves-last-cause-test
  ;; Retryable failures run through the configured retry count then return structured exhaustion data.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false
                                                  :config {:auto-retry-max-retries 2}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (let [attempt (swap! attempts* inc)]
                      (assoc (error-turn (str "Connection reset by peer " attempt))
                             :assistant-message {:role "assistant"
                                                 :content [{:type :error :text (str "Connection reset by peer " attempt)}]
                                                 :stop-reason :error
                                                 :error-message (str "Connection reset by peer " attempt)
                                                 :timestamp (java.time.Instant/now)})))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            events  (provider-events ctx session-id)]
        (is (= 3 @attempts*))
        (is (= :retry-exhausted (:failure-reason outcome)))
        (is (= :transport (:error-kind outcome)))
        (is (= "Connection reset by peer 3" (:last-error-message outcome)))
        (is (= 3 (:attempt-count outcome)))
        (is (= 2 (:retry-attempt outcome)))
        (is (true? (:exhausted? outcome)))
        (is (= ["provider_request_started" "provider_request_finished"
                "provider_retry_scheduled" "provider_request_started"
                "provider_request_finished" "provider_retry_scheduled"
                "provider_request_started" "provider_request_finished"]
               (mapv :type events)))
        (is (= [0 1 2]
               (mapv :retry-attempt (filter #(= "provider_request_started" (:type %)) events))))
        (is (= [1 2]
               (mapv :retry-attempt (filter #(= "provider_retry_scheduled" (:type %)) events))))
        (is (= :retry-exhausted
               (:failure-reason (last events))))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-success-without-retry-does-not-emit-retry-updated-test
  (testing "ordinary successful completions do not publish retry footer refreshes"
    (let [[ctx session-id] (create-session-context {:persist? false})
          progress-q       (java.util.concurrent.LinkedBlockingQueue.)
          prepared         (prepared-request ctx session-id)]
      (with-redefs [psi.turn-runtime.core/execute-live-turn!
                    (fn [& _]
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "done"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}})]
        (turn-runtime/execute-prepared-request!
         {:provider-registry (atom {})} ctx session-id prepared progress-q)
        (let [events (loop [acc []]
                       (if-let [event (.poll progress-q)]
                         (recur (conj acc event))
                         acc))]
          (is (not-any? #(= :retry-updated (:event-kind %)) events)
              "non-retry success must not emit retry-updated/footer churn"))))))

(deftest execute-prepared-request-clears-active-retry-state-before-retry-attempt-test
  ;; Active retry fields are visible through the existing retrying phase during
  ;; backoff and cleared before the next provider attempt starts.
  (let [during-retry*     (atom nil)
        phase-during*     (atom nil)
        attempt-retries*  (atom [])
        [ctx0 session-id] (create-session-context {:persist? false})
        ctx               (assoc ctx0 :provider-retry-sleep-fn
                                 (fn [_delay-ms]
                                   (reset! during-retry*
                                           (:retry (ss/get-session-data-in ctx0 session-id)))
                                   (reset! phase-during*
                                           (ss/sc-phase-in ctx0 session-id))))
        prepared          (prepared-request ctx session-id)
        attempts*         (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempt-retries* conj (:retry (ss/get-session-data-in ctx session-id)))
                    (if (= 1 (swap! attempts* inc))
                      (error-turn "Connection reset by peer")
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "recovered"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}}))]
      (let [result (turn-runtime/execute-prepared-request!
                    {:provider-registry (atom {})} ctx session-id prepared nil)]
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= 2 @attempts*))
        (is (= :retrying @phase-during*))
        (is (= :transport (:error-kind @during-retry*)))
        (is (= 1 (:retry-attempt @during-retry*)))
        (is (= [nil nil] @attempt-retries*))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))))))

(deftest execute-prepared-request-retry-after-header-drives-delay-test
  ;; Provider Retry-After headers are authoritative for retry delay metadata.
  (let [[ctx0 session-id] (create-session-context {:persist? false
                                                   :provider-retry-sleep? false
                                                   :config {:auto-retry-base-delay-ms 10
                                                            :auto-retry-max-retries 1}})
        ctx              (assoc ctx0 :now-fn #(java.time.Instant/ofEpochMilli 1000))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (if (= 1 (swap! attempts* inc))
                      (assoc (error-turn "rate limit exceeded")
                             :assistant-message {:role "assistant"
                                                 :content [{:type :error :text "rate limit exceeded"}]
                                                 :stop-reason :error
                                                 :error-message "rate limit exceeded"
                                                 :http-status 429
                                                 :provider-error/headers {"Retry-After" "5"
                                                                          "RateLimit-Limit" "100"
                                                                          "RateLimit-Remaining" "0"
                                                                          "RateLimit-Reset" "3"}
                                                 :timestamp (java.time.Instant/now)})
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "recovered"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= 2 @attempts*))
        (is (= 5000 (:delay-ms scheduled)))
        (is (= :retry-after (:delay-source scheduled)))
        (is (= 6000 (:resume-at scheduled)))
        (is (= {:limit 100 :remaining 0 :reset-after-ms 3000 :reset-at 4000}
               (:rate-limit scheduled)))))))

(deftest execute-prepared-request-invalid-retry-after-falls-back-test
  ;; Invalid Retry-After headers preserve retryability and fall back to exponential backoff.
  (let [[ctx0 session-id] (create-session-context {:persist? false
                                                   :provider-retry-sleep? false
                                                   :config {:auto-retry-base-delay-ms 25
                                                            :auto-retry-max-delay-ms 1000
                                                            :auto-retry-max-retries 1}})
        ctx              (assoc ctx0 :now-fn #(java.time.Instant/ofEpochMilli 2000))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (if (= 1 (swap! attempts* inc))
                      (assoc (error-turn "provider overloaded")
                             :assistant-message {:role "assistant"
                                                 :content [{:type :error :text "provider overloaded"}]
                                                 :stop-reason :error
                                                 :error-message "provider overloaded"
                                                 :http-status 503
                                                 :provider-error/headers {"Retry-After" "not-a-date"}
                                                 :timestamp (java.time.Instant/now)})
                      {:turn-id "turn-1"
                       :model {:provider "openai" :id "gpt-test"}
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text "recovered"}]
                                           :stop-reason :stop
                                           :timestamp (java.time.Instant/now)}}))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= 2 @attempts*))
        (is (= 25 (:delay-ms scheduled)))
        (is (= :exponential-backoff (:delay-source scheduled)))
        (is (= 2025 (:resume-at scheduled)))))))

(deftest execute-prepared-request-cancels-pending-retry-backoff-test
  ;; Cancellation during provider-boundary backoff suppresses the next attempt
  ;; and records a request-level cancellation event.
  (let [cancelled?       (atom false)
        active-in-sleep* (atom nil)
        [ctx0 session-id] (create-session-context {:persist? false
                                                   :config {:auto-retry-max-retries 2}})
        ctx              (assoc ctx0
                                :provider-retry-cancelled? (fn [_session-id] @cancelled?)
                                :provider-retry-sleep-fn
                                (fn [_delay-ms]
                                  (reset! active-in-sleep*
                                          (:retry (ss/get-session-data-in ctx0 session-id)))
                                  (reset! cancelled? true)))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (let [result  (turn-runtime/execute-prepared-request!
                     {:provider-registry (atom {})} ctx session-id prepared nil)
            outcome (:execution-result/retry-outcome result)
            events  (provider-events ctx session-id)]
        (is (= 1 @attempts*))
        (is (= :retry-cancelled (:failure-reason outcome)))
        (is (true? (:cancelled? outcome)))
        (is (= 0 (:failed-attempt outcome)))
        (is (= 1 (:retry-attempt outcome)))
        (is (= 1 (:attempt-count outcome)))
        (is (= :transport (:error-kind outcome)))
        (is (= 1 (:retry-attempt @active-in-sleep*)))
        (is (nil? (:retry (ss/get-session-data-in ctx session-id))))
        (is (= ["provider_request_started" "provider_request_finished"
                "provider_retry_scheduled" "provider_request_cancelled"]
               (mapv :type events)))
        (is (= [0]
               (mapv :retry-attempt (filter #(= "provider_request_started" (:type %)) events))))
        (is (empty?
             (filter #(and (= "provider_request_finished" (:type %))
                           (= 1 (:retry-attempt %)))
                     events)))
        (is (= :retry-cancelled (:failure-reason (last events))))))))

(deftest execute-prepared-request-streaming-exception-preserves-retry-headers-test
  ;; Streaming exceptions expose ex-data retry headers through the generic
  ;; streaming error path so retry scheduling can honor provider delay metadata.
  (let [[ctx0 session-id] (create-session-context {:persist? false
                                                   :provider-retry-sleep? false
                                                   :config {:auto-retry-base-delay-ms 10
                                                            :auto-retry-max-retries 1}})
        ctx              (assoc ctx0 :now-fn #(java.time.Instant/ofEpochMilli 1000))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    (if (= 1 (swap! attempts* inc))
                      (do
                        (consume-fn {:type :start})
                        (throw (ex-info "rate limit exceeded"
                                        {:http-status 429
                                         :headers {"Retry-After" "2"
                                                   "RateLimit-Limit" "100"
                                                   "RateLimit-Remaining" "0"
                                                   "RateLimit-Reset" "3"}})))
                      (do
                        (consume-fn {:type :start})
                        (consume-fn {:type :text-delta :content-index 0 :delta "recovered"})
                        (consume-fn {:type :done :reason :stop}))))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= 2 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= [{:type :text :text "recovered"}]
               (get-in result [:execution-result/assistant-message :content])))
        (is (= 2000 (:delay-ms scheduled)))
        (is (= :retry-after (:delay-source scheduled)))
        (is (= 3000 (:resume-at scheduled)))
        (is (= {:limit 100 :remaining 0 :reset-after-ms 3000 :reset-at 4000}
               (:rate-limit scheduled)))))))

(deftest execute-prepared-request-streaming-error-event-provider-headers-drive-retry-test
  ;; Background streaming :error events may already carry normalized provider
  ;; headers rather than raw :headers; the turn error path must preserve them so
  ;; retry metadata can honor provider Retry-After / rate-limit details.
  (let [[ctx0 session-id] (create-session-context {:persist? false
                                                   :provider-retry-sleep? false
                                                   :config {:auto-retry-base-delay-ms 10
                                                            :auto-retry-max-retries 1}})
        ctx              (assoc ctx0 :now-fn #(java.time.Instant/ofEpochMilli 1000))
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)]
    (with-redefs [psi.turn-runtime.core/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    (if (= 1 (swap! attempts* inc))
                      (do
                        (consume-fn {:type :start})
                        (consume-fn {:type :error
                                     :error-message "rate limited"
                                     :http-status 429
                                     :provider-error/headers {"Retry-After" "4"
                                                              "RateLimit-Limit" "50"
                                                              "RateLimit-Remaining" "0"
                                                              "RateLimit-Reset" "5"}}))
                      (do
                        (consume-fn {:type :start})
                        (consume-fn {:type :text-delta :content-index 0 :delta "recovered"})
                        (consume-fn {:type :done :reason :stop}))))]
      (let [result    (turn-runtime/execute-prepared-request!
                       {:provider-registry (atom {})} ctx session-id prepared nil)
            scheduled (first (filter #(= "provider_retry_scheduled" (:type %))
                                     (provider-events ctx session-id)))]
        (is (= 2 @attempts*))
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= [{:type :text :text "recovered"}]
               (get-in result [:execution-result/assistant-message :content])))
        (is (= 4000 (:delay-ms scheduled)))
        (is (= :retry-after (:delay-source scheduled)))
        (is (= {:limit 50 :remaining 0 :reset-after-ms 5000 :reset-at 6000}
               (:rate-limit scheduled)))))))

(deftest execute-prepared-request-production-backoff-observes-active-turn-abort-test
  ;; Retry sleep polls active turn abort state.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :config {:auto-retry-max-retries 2
                                                           :auto-retry-base-delay-ms 1000
                                                           :provider-retry-sleep-poll-ms 1}})
        prepared         (prepared-request ctx session-id)
        attempts*        (atom 0)
        abort-thread*    (atom nil)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [& _]
                    (swap! attempts* inc)
                    (error-turn "Connection reset by peer"))]
      (try
        (let [abort-thread (Thread.
                            (fn []
                              (loop []
                                (when (nil? (:retry (ss/get-session-data-in ctx session-id)))
                                  (Thread/sleep 1)
                                  (recur)))
                              (turn-runtime/abort-active-turn-in! ctx session-id)))
              _            (reset! abort-thread* abort-thread)
              _            (.start abort-thread)
              result       (turn-runtime/execute-prepared-request!
                            {:provider-registry (atom {})} ctx session-id prepared nil)
              outcome (:execution-result/retry-outcome result)
              events  (provider-events ctx session-id)]
          (is (= 1 @attempts*))
          (is (= :retry-cancelled (:failure-reason outcome)))
          (is (true? (:cancelled? outcome)))
          (is (= ["provider_request_started" "provider_request_finished"
                  "provider_retry_scheduled" "provider_request_cancelled"]
                 (mapv :type events))))
        (finally
          (when-let [t @abort-thread*]
            (.join t 1000)))))))
