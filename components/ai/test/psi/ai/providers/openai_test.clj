(ns psi.ai.providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.proxy :as proxy]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream]
           [java.util Base64]))
(defn- jwt-with-account-id
  [account-id]
  (let [payload-json (json/generate-string
                      {"https://api.openai.com/auth"
                       {"chatgpt_account_id" account-id}})
        payload      (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                      (.getBytes payload-json "UTF-8"))]
    (str "aaa." payload ".bbb")))
(defn- stream-body
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))
(deftest structured-user-content-renders-as-plain-text-for-chat-and-codex-test
  (let [convo (-> (conv/create "sys")
                  (conv/add-user-message [{:type :text :text "line one"}
                                          {:type :text :text "line two"}]))
        chat-messages (#'openai/transform-messages convo)
        codex-items   (#'openai/codex-input-messages convo)]
    (is (= "line one\nline two"
           (get-in chat-messages [0 :content])))
    (is (= "line one\nline two"
           (get-in codex-items [0 "content" 0 "text"])))))
(deftest chat-completions-system-message-transform-test
  ;; OpenAI chat completions receives inline system messages as wire role "system".
  (testing "chat-completions-system-message-transform"
    (let [convo (-> (conv/create "sys")
                    (conv/add-user-message "q")
                    (conv/add-system-message "Use short answers."))
          messages (#'openai/transform-messages convo)]
      (is (= [{:role "user" :content "q"}
              {:role "system" :content "Use short answers."}]
             messages)))))
(deftest codex-streaming-test
  (testing "codex model streams via chatgpt backend and emits normalized events"
    (let [model      (models/get-model :gpt-5.3-codex)
          token      (jwt-with-account-id "acc_test")
          convo      (-> (conv/create "You are a helpful assistant")
                         (conv/add-user-message "Say hello"))
          events     (atom [])
          captured   (atom nil)
          sse        (str
                      "data: " (json/generate-string
                                {:type "response.output_item.added"
                                 :item {:type "message"
                                        :id "msg_1"
                                        :role "assistant"
                                        :status "in_progress"
                                        :content []}}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.content_part.added"
                                 :part {:type "output_text" :text ""}}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.output_text.delta"
                                 :delta "Hello"}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.output_item.done"
                                 :item {:type "message"
                                        :id "msg_1"
                                        :role "assistant"
                                        :status "completed"
                                        :content [{:type "output_text" :text "Hello"}]}}) "\n\n"
                      "data: " (json/generate-string
                                {:type "response.completed"
                                 :response {:status "completed"
                                            :usage {:input_tokens 5
                                                    :output_tokens 3
                                                    :total_tokens 8
                                                    :input_tokens_details {:cached_tokens 0}}}}) "\n\n")]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (= "https://chatgpt.com/backend-api/codex/responses"
             (:url @captured)))
      (is (= (str "Bearer " token)
             (get-in @captured [:req :headers "Authorization"])))
      (is (= "acc_test"
             (get-in @captured [:req :headers "chatgpt-account-id"])))
      (let [body (json/parse-string (get-in @captured [:req :body]) true)]
        (is (= "gpt-5.3-codex" (:model body)))
        (is (= "You are a helpful assistant" (:instructions body)))
        (is (= true (:stream body)))
        (is (= {:effort "medium" :summary "auto"}
               (:reasoning body))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :text-delta (:type %)) (= "Hello" (:delta %))) @events))
      (is (some #(= :done (:type %)) @events)))))
(deftest codex-request-and-reply-capture-callbacks-test
  (testing "built-in OpenAI codex captures preserve provider and api identity"
    (let [model            (models/get-model :gpt-5.3-codex)
          token            (jwt-with-account-id "acc_test")
          convo            (-> (conv/create "sys")
                               (conv/add-user-message "hello"))
          request-capture  (atom nil)
          reply-captures   (atom [])
          sse              (str
                            "data: " (json/generate-string
                                      {:type "response.output_item.added"
                                       :item {:type "message"
                                              :id "msg_1"
                                              :role "assistant"
                                              :status "in_progress"
                                              :content []}}) "\n\n"
                            "data: " (json/generate-string
                                      {:type "response.completed"
                                       :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token
                      :on-provider-request  #(reset! request-capture %)
                      :on-provider-response #(swap! reply-captures conj %)}
         (fn [_ev] nil)))
      (is (= :openai (:provider @request-capture)))
      (is (= :openai-codex-responses (:api @request-capture)))
      (is (= "gpt-5.3-codex"
             (get-in @request-capture [:request :body :model])))
      (is (re-find #"\*\*\*REDACTED\*\*\*"
                   (or (get-in @request-capture [:request :headers "Authorization"])
                       "")))
      (is (pos? (count @reply-captures)))
      (is (every? #(= :openai (:provider %)) @reply-captures))
      (is (every? #(= :openai-codex-responses (:api %)) @reply-captures))
      (is (some #(= "response.completed"
                    (get-in % [:event :type]))
                @reply-captures)))
    (testing "custom OpenAI-compatible codex captures preserve selected provider identity"
      (let [model           {:id                 "local-codex"
                             :name               "Local Codex"
                             :provider           :local
                             :api                :openai-codex-responses
                             :base-url           "http://localhost:8080/v1"
                             :supports-reasoning true
                             :supports-images    false
                             :supports-text      true
                             :context-window     128000
                             :max-tokens         16384
                             :input-cost         0.0
                             :output-cost        0.0
                             :cache-read-cost    0.0
                             :cache-write-cost   0.0}
            token           (jwt-with-account-id "acc_test")
            convo           (-> (conv/create "sys")
                                (conv/add-user-message "hello"))
            request-capture (atom nil)
            reply-captures  (atom [])
            sse             (str
                             "data: " (json/generate-string
                                       {:type "response.output_item.added"
                                        :item {:type "message"
                                               :id "msg_1"
                                               :role "assistant"
                                               :status "in_progress"
                                               :content []}}) "\n\n"
                             "data: " (json/generate-string
                                       {:type "response.completed"
                                        :response {:status "completed"}}) "\n\n")]
        (with-redefs [http/post (fn [_url _req]
                                  {:body (stream-body sse)})]
          ((:stream openai/provider)
           convo model {:api-key token
                        :on-provider-request  #(reset! request-capture %)
                        :on-provider-response #(swap! reply-captures conj %)}
           (fn [_ev] nil)))
        (is (= :local (:provider @request-capture)))
        (is (= :openai-codex-responses (:api @request-capture)))
        (is (= "local-codex"
               (get-in @request-capture [:request :body :model])))
        (is (pos? (count @reply-captures)))
        (is (every? #(= :local (:provider %)) @reply-captures))
        (is (every? #(= :openai-codex-responses (:api %)) @reply-captures))
        (is (some #(= "response.completed"
                      (get-in % [:event :type]))
                  @reply-captures))))))
(deftest openai-stream-applies-proxy-request-options-test
  (testing "OpenAI codex stream request merges shared proxy options"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          captured (atom nil)
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "message"
                                      :id "msg_1"
                                      :role "assistant"
                                      :status "in_progress"
                                      :content []}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"}}) "\n\n")]
      (with-redefs [proxy/request-proxy-options (fn [url]
                                                  (is (= "https://chatgpt.com/backend-api/codex/responses" url))
                                                  {:proxy-host "proxy.example"
                                                   :proxy-port 8080
                                                   :proxy-scheme :http})
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [_] nil)))
      (is (= "proxy.example" (:proxy-host @captured)))
      (is (= 8080 (:proxy-port @captured)))
      (is (= :http (:proxy-scheme @captured)))))
  (testing "OpenAI completions stream leaves request unchanged when no proxy applies"
    (let [model    (models/get-model :gpt-4o)
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          captured (atom nil)
          sse      (str "data: " (json/generate-string {:choices [{:delta {:content "hi"}}]}) "\n\n"
                        "data: [DONE]\n\n")]
      (with-redefs [proxy/request-proxy-options (constantly nil)
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "test-key"}
         (fn [_] nil)))
      (is (nil? (:proxy-host @captured)))
      (is (nil? (:proxy-port @captured)))
      (is (nil? (:proxy-scheme @captured))))))
(deftest codex-requires-chatgpt-token-test
  (testing "non-ChatGPT token emits an error event (missing chatgpt_account_id)"
    (let [model  (models/get-model :gpt-5.3-codex)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      ((:stream openai/provider)
       convo model {:api-key "not-a-jwt-token"}
       (fn [ev] (swap! events conj ev)))
      (is (= :error (:type (first @events))))
      (is (re-find #"chatgpt_account_id"
                   (:error-message (first @events)))))))
(deftest codex-reasoning-text-delta-maps-to-thinking-delta-test
  (testing "response.reasoning_text.delta is bridged as :thinking-delta"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "You are a helpful assistant")
                       (conv/add-user-message "Think then answer"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.reasoning_text.delta"
                               :delta "Plan step"}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan step" (:delta %)))
                @events))
      (is (some #(= :done (:type %)) @events)))))
(deftest codex-reasoning-map-delta-normalized-to-string-test
  (testing "non-string reasoning delta payloads are normalized to text"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "sys") (conv/add-user-message "think"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.reasoning_summary.delta"
                               :delta {:text "Plan chunk"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan chunk" (:delta %)))
                @events)))))
(deftest codex-reasoning-output-item-done-emits-thinking-boundary-test
  (testing "response.output_item.done reasoning emits thinking start/end even without reasoning delta events"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "think"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 0
                             :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_item.done"
                             :output_index 0
                             :item {:type "reasoning"
                                    :id "rs_1"
                                    :encrypted_content "enc"}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.completed"
                             :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (let [types (mapv :type @events)]
        (is (some #{:thinking-start} types))
        (is (some #{:thinking-end} types))))))
(deftest codex-thinking-level-maps-to-reasoning-effort-test
  (let [model (models/get-model :gpt-5.3-codex)]
    (is (= {"effort" "high" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :high})))
    (is (= {"effort" "minimal" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :minimal})))
    (is (= {"effort" "high" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :medium
                                            :effort-override :xhigh})))
    (is (= {"effort" "medium" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :high
                                            :effort-override :medium})))
    (is (nil? (#'openai/codex-reasoning model {:thinking-level :off
                                               :effort-override :xhigh})))
    (is (= {"effort" "medium" "summary" "auto"}
           (#'openai/codex-reasoning model {})))))
(deftest codex-tool-call-id-roundtrip-test
  (testing "tool call ids split into call_id + item id (not single-char prefixes)"
    (let [call-id "call_abc123"
          item-id "fc_456def"
          full-id (str call-id "|" item-id)
          convo   (-> (conv/create "sys")
                      (conv/add-user-message "ls")
                      (conv/add-assistant-message
                       {:content
                        {:kind :structured
                         :blocks [{:kind  :tool-call
                                   :id    full-id
                                   :name  "bash"
                                   :input {"command" "ls"}}]}})
                      (conv/add-tool-result full-id "bash" {:kind :text :text "ok"} false))
          input   ((deref #'openai/codex-input-messages) convo)
          call    (second input)
          result  (nth input 2)]
      (is (= "function_call" (get call "type")))
      (is (= call-id (get call "call_id")))
      (is (= item-id (get call "id")))
      (is (= "function_call_output" (get result "type")))
      (is (= call-id (get result "call_id"))))))
(deftest codex-function-call-done-includes-final-arguments-test
  (testing "response.output_item.done can carry final function arguments"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run pwd"))
          events (atom [])
          sse    (str
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
                             :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(and (= :toolcall-start (:type %))
                      (= "call_1|fc_1" (:id %))
                      (= "bash" (:name %)))
                @events))
      (is (some #(and (= :toolcall-delta (:type %))
                      (= "{\"command\":\"pwd\"}" (:delta %)))
                @events))
      (is (some #(= :toolcall-end (:type %)) @events))
      (is (some #(= :done (:type %)) @events)))))
(deftest completions-tool-call-starts-when-id-arrives-late-test
  (testing "chat completions buffers tool args until call id is available"
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run pwd"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:tool_calls [{:index 0
                                                              :function {:name "bash"
                                                                         :arguments "{\"command\":\"pwd\"}"}}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:tool_calls [{:index 0
                                                              :id "call_late"
                                                              :function {:name "bash"}}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:finish_reason "tool_calls"}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :toolcall-start (:type %))
                      (= 0 (:content-index %))
                      (= "call_late" (:id %))
                      (= "bash" (:name %)))
                @events))
      (is (some #(and (= :toolcall-delta (:type %))
                      (= 0 (:content-index %))
                      (= "{\"command\":\"pwd\"}" (:delta %)))
                @events))
      (is (some #(and (= :toolcall-end (:type %))
                      (= 0 (:content-index %)))
                @events))
      (is (some #(and (= :done (:type %))
                      (= :tool_calls (:reason %)))
                @events)))))
(deftest completions-tool-call-cumulative-arguments-emit-only-unseen-suffix-test
  (testing "chat completions cumulative tool args do not duplicate emitted deltas"
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run pwd"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:tool_calls [{:index 0
                                                              :id "call_1"
                                                              :function {:name "bash"
                                                                         :arguments "{\"command\""}}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:tool_calls [{:index 0
                                                              :function {:arguments "{\"command\":\"pwd\"}"}}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:finish_reason "tool_calls"}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (let [deltas (->> @events
                        (filter #(= :toolcall-delta (:type %)))
                        (map :delta)
                        (apply str))]
        (is (= "{\"command\":\"pwd\"}" deltas)))
      (is (= 1 (count (filter #(= :toolcall-start (:type %)) @events))))
      (is (= 1 (count (filter #(= :toolcall-end (:type %)) @events)))))))
(deftest completions-tool-call-from-message-fallback-test
  (testing "chat completions message.tool_calls fallback is processed"
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run read"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:tool_calls [{:index 0 :id "call_1"}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {}
                                        :message {:tool_calls [{:index 0
                                                                :id "call_1"
                                                                :type "function"
                                                                :function {:name "read"
                                                                           :arguments "{\"path\":\"README.md\"}"}}]}
                                        :finish_reason "tool_calls"}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))

      (is (some #(and (= :toolcall-start (:type %))
                      (= "call_1" (:id %))
                      (= "read" (:name %)))
                @events))
      (is (some #(and (= :toolcall-delta (:type %))
                      (= "{\"path\":\"README.md\"}" (:delta %)))
                @events))
      (is (some #(= :toolcall-end (:type %)) @events))
      (is (some #(and (= :done (:type %))
                      (= :tool_calls (:reason %))) @events)))))

(deftest completions-legacy-function-call-stream-shape-test
  (testing "chat completions legacy delta.function_call shape is bridged"
    (let [model  (models/get-model :gpt-4o)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run read"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:function_call {:name "read"}}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:function_call {:arguments "{\"path\":\"README.md\"}"}}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:finish_reason "function_call"}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))

      (is (some #(and (= :toolcall-start (:type %))
                      (= "read" (:name %)))
                @events))
      (is (some #(and (= :toolcall-delta (:type %))
                      (= "{\"path\":\"README.md\"}" (:delta %)))
                @events))
      (is (some #(= :toolcall-end (:type %)) @events))
      (is (some #(and (= :done (:type %))
                      (= :function_call (:reason %))) @events)))))

(deftest completions-thinking-level-maps-to-reasoning-effort-test
  (let [model (models/get-model :gpt-5)
        convo (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (testing "default reasoning effort is medium for reasoning-capable models"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"})
            body (json/parse-string (:body req) true)]
        (is (= "medium" (:reasoning_effort body)))
        (is (= 0 (:temperature body)))))

    (testing "explicit thinking level maps to expected reasoning effort"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :high})
            body (json/parse-string (:body req) true)]
        (is (= "high" (:reasoning_effort body)))))

    (testing "effort override maps xhigh to provider ceiling"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :medium
                                                      :effort-override :xhigh})
            body (json/parse-string (:body req) true)]
        (is (= "high" (:reasoning_effort body)))))

    (testing "non-xhigh effort override wins over a different thinking level"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :high
                                                      :effort-override :medium})
            body (json/parse-string (:body req) true)]
        (is (= "medium" (:reasoning_effort body)))))

    (testing "thinking off omits reasoning effort for cloud models"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test"
                                                      :thinking-level :off})
            body (json/parse-string (:body req) true)]
        (is (nil? (:reasoning_effort body)))
        (is (nil? (:chat_template_kwargs body)))))))

(deftest openai-completions-parallel-tool-calls-uses-model-setting-test
  (let [convo (-> (conv/create "sys")
                  (conv/add-user-message "hi")
                  (conv/add-tool {:name "read"
                                  :description "Read"
                                  :parameters {:type "object"
                                               :properties {:path {:type "string"}}
                                               :required ["path"]}}))
        model (assoc (models/get-model :gpt-5)
                     :parallel-tool-calls false)
        req   (#'openai/build-request convo model {:api-key "sk-test"})
        body  (json/parse-string (:body req) true)]
    (is (= false (:parallel_tool_calls body))))

  (testing "omits provider option when model does not declare it"
    (let [convo (-> (conv/create "sys")
                    (conv/add-user-message "hi")
                    (conv/add-tool {:name "read"
                                    :description "Read"
                                    :parameters {:type "object"
                                                 :properties {:path {:type "string"}}
                                                 :required ["path"]}}))
          req   (#'openai/build-request convo (models/get-model :gpt-5) {:api-key "sk-test"})
          body  (json/parse-string (:body req) true)]
      (is (not (contains? body :parallel_tool_calls))))))

(deftest local-openai-completions-thinking-off-disables-chat-template-thinking-test
  (let [model {:id                 "local-completions"
               :name               "Local Completions"
               :provider           :local
               :api                :openai-completions
               :base-url           "http://localhost:8080/v1"
               :locality           :local
               :supports-reasoning true
               :supports-images    false
               :supports-text      true
               :context-window     128000
               :max-tokens         16384
               :input-cost         0.0
               :output-cost        0.0
               :cache-read-cost    0.0
               :cache-write-cost   0.0}
        convo (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (testing "thinking off adds chat_template_kwargs enable_thinking false for local models"
      (let [req  (#'openai/build-request convo model {:thinking-level :off})
            body (json/parse-string (:body req) true)]
        (is (nil? (:reasoning_effort body)))
        (is (= {:enable_thinking false}
               (:chat_template_kwargs body)))))

    (testing "thinking on leaves chat_template_kwargs unset for local models"
      (let [req  (#'openai/build-request convo model {:thinking-level :medium})
            body (json/parse-string (:body req) true)]
        (is (= "medium" (:reasoning_effort body)))
        (is (nil? (:chat_template_kwargs body)))))))

(deftest openai-temperature-defaults-to-zero-test
  (testing "chat completions defaults temperature to zero when absent"
    (let [model (models/get-model :gpt-5)
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          req  (#'openai/build-request convo model {:api-key "sk-test"})
          body (json/parse-string (:body req) true)]
      (is (= 0 (:temperature body)))))

  (testing "chat completions respects explicit temperature override"
    (let [model (models/get-model :gpt-5)
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          req  (#'openai/build-request convo model {:api-key "sk-test"
                                                    :temperature 0.2})
          body (json/parse-string (:body req) true)]
      (is (= 0.2 (:temperature body)))))

  (testing "codex omits temperature by default"
    (let [model (models/get-model :gpt-5.3-codex)
          token (jwt-with-account-id "acc_test")
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          req   (#'openai/build-codex-request convo model {:api-key token})
          body  (json/parse-string (:body req) true)]
      (is (not (contains? body :temperature)))))

  (testing "codex ignores explicit temperature override"
    (let [model (models/get-model :gpt-5.3-codex)
          token (jwt-with-account-id "acc_test")
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          req   (#'openai/build-codex-request convo model {:api-key token
                                                           :temperature 0.3})
          body  (json/parse-string (:body req) true)]
      (is (not (contains? body :temperature))))))

(deftest local-openai-non-streaming-response-preserves-usage-test
  (testing "non-streaming local OpenAI-compatible responses keep usage totals"
    (let [model {:id "qwen-3.6-27b"
                 :provider :local3
                 :api :openai-completions
                 :base-url "http://localhost:8082/v1"
                 :supports-text true}
          convo (-> (conv/create "sys")
                    (conv/add-user-message "Reply with exactly: hi"))
          body {:choices [{:finish_reason "stop"
                           :index 0
                           :message {:role "assistant"
                                     :content "hi"
                                     :reasoning_content "internal reasoning"}}]
                :usage {:prompt_tokens 15
                        :completion_tokens 152
                        :total_tokens 167}}]
      (with-redefs [http/post (fn [_url _req]
                                {:status 200
                                 :body (json/generate-string body)})]
        (let [result ((:execute openai/provider) convo model {:no-auth-header true})]
          (is (= "hi" (get-in result [:assistant-message :content 0 :text])))
          (is (= :stop (get-in result [:assistant-message :stop-reason])))
          (is (= {:input-tokens 15
                  :output-tokens 152
                  :cache-read-tokens 0
                  :cache-write-tokens 0
                  :total-tokens 167
                  :cost {:input 0.0
                         :output 0.0
                         :cache-read 0.0
                         :cache-write 0.0
                         :total 0.0}}
                 (get-in result [:assistant-message :usage]))))))))

(deftest completions-reasoning-delta-shapes-map-to-thinking-delta-test
  (testing "chat completions reasoning delta variants are emitted as :thinking-delta"
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "think"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:reasoning_content "A"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:reasoning [{:type "reasoning_text" :text "B"}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:content [{:type "reasoning" :text "C"}]}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:reasoning {:content [{:type "reasoning_text"
                                                                       :text "D"}]}}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:reasoning {:summary [{:type "summary_text"
                                                                       :text "E"}]}}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:finish_reason "stop"}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))

      (is (some #(= :start (:type %)) @events))
      (is (= ["A" "B" "C" "D" "E"]
             (->> @events
                  (filter #(= :thinking-delta (:type %)))
                  (mapv :delta))))
      (is (some #(= :done (:type %)) @events)))))

(deftest completions-trailing-usage-after-finish-reason-is-preserved-test
  (testing "chat completions keep trailing usage when finish_reason arrives before usage"
    (let [model {:id "qwen-3.6-27b"
                 :provider :local3
                 :api :openai-completions
                 :base-url "http://localhost:1234"
                 :supports-text true}
          convo (-> (conv/create "sys")
                    (conv/add-user-message "hello"))
          events (atom [])
          sse (str
               "data: " (json/generate-string
                         {:choices [{:delta {:role "assistant"}}]}) "\n\n"
               "data: " (json/generate-string
                         {:choices [{:delta {:content "Hello"}}]}) "\n\n"
               "data: " (json/generate-string
                         {:choices [{:finish_reason "stop"}]}) "\n\n"
               "data: " (json/generate-string
                         {:usage {:prompt_tokens 42
                                  :completion_tokens 128
                                  :total_tokens 170}}) "\n\n"
               "data: [DONE]\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (let [done-events (filter #(= :done (:type %)) @events)]
        (is (= 1 (count done-events)))
        (is (= :stop (:reason (first done-events))))
        (is (= {:input-tokens 42
                :output-tokens 128
                :cache-read-tokens 0
                :cache-write-tokens 0
                :total-tokens 170
                :cost {:input 0.0 :output 0.0 :cache-read 0.0 :cache-write 0.0 :total 0.0}}
               (:usage (first done-events))))))))

(deftest completions-non-2xx-response-map-surfaces-body-message-test
  (let [model  (models/get-model :gpt-5)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "hello"))
        events (atom [])]
    (with-redefs [http/post (fn [_url _req]
                              {:status 400
                               :headers {"x-request-id" "req_oai_400"}
                               :body (stream-body
                                      (json/generate-string
                                       {:error {:message "invalid request payload"}}))})]
      ((:stream openai/provider)
       convo model {:api-key "sk-test"}
       (fn [ev] (swap! events conj ev))))
    (is (= 1 (count @events)))
    (is (= :error (:type (first @events))))
    (is (= "invalid request payload (status 400) [request-id req_oai_400]"
           (:error-message (first @events))))
    (is (= 400 (:http-status (first @events))))
    (is (= "req_oai_400" (get-in (first @events) [:headers "x-request-id"])))
    (is (= {:error {:message "invalid request payload"}}
           (:body (first @events))))
    (is (string? (:body-text (first @events))))))

(deftest codex-non-2xx-response-map-surfaces-body-message-test
  (let [model  (models/get-model :gpt-5.3-codex)
        token  (jwt-with-account-id "acc_test")
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "hello"))
        events (atom [])]
    (with-redefs [http/post (fn [_url _req]
                              {:status 429
                               :headers {"x-request-id" "req_oai_429"}
                               :body (stream-body
                                      (json/generate-string
                                       {:error {:message "rate limit exceeded"}}))})]
      ((:stream openai/provider)
       convo model {:api-key token}
       (fn [ev] (swap! events conj ev))))
    (is (= 1 (count @events)))
    (is (= :error (:type (first @events))))
    (is (= "rate limit exceeded (status 429) [request-id req_oai_429]"
           (:error-message (first @events))))
    (is (= 429 (:http-status (first @events))))))

