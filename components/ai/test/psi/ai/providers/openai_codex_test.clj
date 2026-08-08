(ns psi.ai.providers.openai-codex-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
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

(deftest codex-chatgpt-account-id-capture-masked-test
  ;; Review 21: mask-chatgpt-account-id (first 6 chars + "...",
  ;; request_support.clj) is wired into openai/transport.clj
  ;; redact-request-headers, but no capture-path test asserts the masked
  ;; output — codex-request-and-reply-capture-callbacks-test asserts only
  ;; Authorization redaction, and custom-header-auth-redacted-in-captures-test
  ;; covers X-API-Key/authorization only. Locks the mask on the
  ;; :on-provider-request payload for a wire chatgpt-account-id header
  ;; (keyless codex request, custom header passes through per review 18) and a
  ;; mixed-case duplicate (review 19 dual-casing semantics: EVERY
  ;; case-insensitive match is masked).
  (testing "wire chatgpt-account-id headers are masked to first-6-chars in :on-provider-request captures"
    (let [model           {:id                 "local-codex"
                           :name               "Local Codex"
                           :provider           :local
                           :custom?            true
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
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
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
         convo model {:no-auth-header true
                      :headers {"chatgpt-account-id" "acc_1234567890"
                                "ChatGPT-Account-Id" "acc_0987654321"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= "acc_12..." (get-in @request-capture [:request :headers "chatgpt-account-id"]))
          "lowercase chatgpt-account-id must be masked to first 6 chars + '...'")
      (is (= "acc_09..." (get-in @request-capture [:request :headers "ChatGPT-Account-Id"]))
          "mixed-case duplicate chatgpt-account-id must also be masked (review 19 dual-casing)")
      (is (nil? (get-in @request-capture [:request :headers "Authorization"]))
          "keyless request sends no Authorization header"))))
