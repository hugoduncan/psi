(ns psi.turn-runtime.response-mode-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.ai.core]
   [psi.ai.models :as models]
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
  [ctx session-id]
  (prompt-request/build-prepared-request
   ctx session-id {:turn-id "turn-1"
                   :user-message {:role "user"
                                  :content [{:type :text :text "hello"}]}}))

(deftest execute-prepared-request-non-streaming-uses-execute-path-test
  (testing "workflow-owned child session with :response-mode :non-streaming uses ai/execute-response-in"
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
