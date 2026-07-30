(ns psi.rpc-prompt-codex-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.models :as ai-models]
   [psi.agent-session.core :as session]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.tools :as tools]
   [psi.rpc-test-support :as support]))

(deftest rpc-openai-codex-prompt-emits-tool-events-with-final-args-test
  (testing "openai codex tool args from response.output_item.done flow through RPC tool events"
    (let [[ctx session-id]   (support/create-session-context)
          _                  (session/dispatch-in! ctx :session/set-active-tools {:session-id session-id :tool-maps [tools/bash-tool]} {:origin :core})
          state              (atom {:transport {:ready? true :pending {}}
                                    :sync-on-git-head-change? false
                                    :rpc-ai-model (ai-models/get-model :gpt-5.3-codex)})
          handler            (support/make-handler ctx state)
          requests           (atom [])
          call-n             (atom 0)
          marker             (str "rpc-codex-tool-args-" (random-uuid))
          first-sse          (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments ""}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_item.done"
                                         :output_index 0
                                         :item {:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "bash"
                                                :arguments "{\"command\":\"pwd\"}"}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          second-sse         (str
                              "data: " (json/generate-string
                                        {:type "response.output_item.added"
                                         :item {:type "message"
                                                :id "msg_2"
                                                :role "assistant"
                                                :status "in_progress"
                                                :content []}}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.output_text.delta"
                                         :delta "Final response"}) "\n\n"
                              "data: " (json/generate-string
                                        {:type "response.completed"
                                         :response {:status "completed"}}) "\n\n")
          input              (str
                              "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                              "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"tool/start\" \"tool/executing\" \"tool/result\" \"assistant/message\"]}}\n"
                              "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"run pwd " marker "\"}}\n")
          {:keys [out-lines]}
          (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] support/openai-chatgpt-test-token)
                        http/post (fn [url req]
                                    (swap! requests conj {:url url :req req})
                                    (let [n (swap! call-n inc)]
                                      {:body (support/stream-body (if (= 1 n) first-sse second-sse))}))]
            (support/run-loop input handler state 900))
          relevant-requests (->> @requests
                                 (filter (fn [{:keys [url req]}]
                                           (and (= "https://chatgpt.com/backend-api/codex/responses" url)
                                                (str/includes? (str (:body req)) marker))))
                                 vec)
          frames         (support/parse-frames out-lines)
          events         (filter #(= :event (:kind %)) frames)
          prompt-frame   (some #(when (and (= :response (:kind %))
                                           (= "prompt" (:op %))) %) frames)
          tool-start-evt  (some #(when (and (= "tool/start" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-exec-evt   (some #(when (and (= "tool/executing" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          tool-result-evt (some #(when (and (= "tool/result" (:event %))
                                            (= "call_1|fc_1" (get-in % [:data :tool-id]))
                                            (= "bash" (get-in % [:data :tool-name]))) %) events)
          assistant-evt   (some #(when (= "assistant/message" (:event %)) %) events)]
      (is (some? prompt-frame))
      (is (true? (get-in prompt-frame [:data :accepted])))
      (is (= 2 (count relevant-requests)))
      (is (= (str "Bearer " support/openai-chatgpt-test-token)
             (get-in (first relevant-requests) [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in (first relevant-requests) [:req :headers "chatgpt-account-id"])))
      (let [body (json/parse-string (get-in (first relevant-requests) [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= true (:stream body)))
        (is (= "bash" (get-in body [:tools 0 :name]))))
      (is (= "call_1|fc_1" (get-in tool-start-evt [:data :tool-id])))
      (is (= "bash" (get-in tool-start-evt [:data :tool-name])))
      (is (= {"command" "pwd"}
             (get-in tool-exec-evt [:data :parsed-args])))
      (is (false? (get-in tool-result-evt [:data :is-error])))
      (is (string? (get-in tool-result-evt [:data :result-text])))
      (is (not (str/blank? (get-in tool-result-evt [:data :result-text]))))
      (is (= "assistant" (get-in assistant-evt [:data :role])))
      (is (some #(= "Final response" (:text %))
                (get-in assistant-evt [:data :content]))))))
