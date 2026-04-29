(ns psi.ai.providers.anthropic-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.ai.providers.anthropic.request-schema :as request-schema])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

;; ── build-request ───────────────────────────────────────────────────────────

(deftest build-request-no-thinking-test
  (testing "no thinking param when thinking-level is :off"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :off
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (nil? (:thinking body)))
      (is (string? (:system body))
          "plain system prompts are sent as string when cache controls are absent")
      (is (some? (:temperature body)) "temperature present when thinking off")))

  (testing "no thinking param when model does not support reasoning"
    (let [model   (assoc (models/get-model :claude-3-5-haiku) :supports-reasoning false)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (nil? (:thinking body)))))

  (testing "missing api-key fails early with a clear message"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "sys")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing Anthropic API key"
           (#'anthropic/build-request convo model {:api-key ""})))))

  (testing "custom anthropic-compatible provider without auth keeps existing missing-auth failure"
    (let [model {:id "MiniMax-M2.7"
                 :name "MiniMax M2.7"
                 :provider :minimax
                 :api :anthropic-messages
                 :base-url "https://api.minimax.io/anthropic"
                 :supports-reasoning true
                 :supports-images false
                 :supports-text true
                 :context-window 128000
                 :max-tokens 16384
                 :input-cost 0.0
                 :output-cost 0.0
                 :cache-read-cost 0.0
                 :cache-write-cost 0.0}
          convo (conv/create "sys")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing Anthropic API key"
           (#'anthropic/build-request convo model {}))))))

(deftest anthropic-request-schema-validation-fails-fast-test
  (testing "invalid provider request body is rejected with shape diagnostics"
    (let [invalid-body {:model "claude-sonnet-4-6"
                        :max_tokens 1024
                        :messages [{:role "user"
                                    :content [{:type "text" :text "hello"}]}]
                        :stream true
                        :tools [{:name "bad_tool"
                                 :description "Bad schema"
                                 :input_schema "not-a-map"}]}]
      (try
        (request-schema/validate-request-body! invalid-body)
        (is false "expected validate-request-body! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= "provider/anthropic-invalid-request-shape"
                 (:error-code (ex-data e))))
          (is (re-find #"Anthropic request shape invalid"
                       (ex-message e)))
          (is (re-find #"input_schema"
                       (ex-message e))))))))

(deftest build-request-with-thinking-test
  (testing "thinking param present when level is non-:off and model supports reasoning"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "enabled" (get-in body [:thinking :type])))
      (is (pos? (get-in body [:thinking :budget_tokens])))
      (is (nil? (:temperature body)) "temperature must be absent with extended thinking")
      (is (some? (re-find #"interleaved-thinking" (get headers "anthropic-beta")))
          "interleaved-thinking beta header required")))

  (testing "oauth requests with thinking also include interleaved-thinking beta"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "sk-ant-oat-test-token"})
          headers (:headers req)]
      (is (some? (re-find #"oauth-2025-04-20" (get headers "anthropic-beta")))
          "oauth beta header required for oauth auth")
      (is (some? (re-find #"claude-code-20250219" (get headers "anthropic-beta")))
          "claude-code beta header required for oauth auth")
      (is (some? (re-find #"context-management-2025-06-27" (get headers "anthropic-beta")))
          "context-management beta header required for oauth auth")
      (is (some? (re-find #"prompt-caching-scope-2026-01-05" (get headers "anthropic-beta")))
          "prompt-caching-scope beta retained for oauth compatibility")
      (is (some? (re-find #"interleaved-thinking" (get headers "anthropic-beta")))
          "oauth requests with thinking must include interleaved-thinking beta"))))

;; ── Adaptive thinking (Opus 4.7+) ───────────────────────────────────────────

(deftest build-request-adaptive-thinking-test
  (testing "adaptive thinking model emits type=adaptive + output_config, no budget_tokens"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :high
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "adaptive" (get-in body [:thinking :type])))
      (is (= "summarized" (get-in body [:thinking :display])))
      (is (nil? (get-in body [:thinking :budget_tokens]))
          "budget_tokens must be absent for adaptive thinking")
      (is (= "high" (get-in body [:output_config :effort])))
      (is (nil? (:temperature body))
          "temperature must be absent for adaptive thinking models")
      (is (nil? (re-find #"interleaved-thinking" (or (get headers "anthropic-beta") "")))
          "interleaved-thinking beta must NOT be sent for adaptive thinking")))

  (testing "adaptive thinking off — no thinking param, no output_config, no temperature"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :off
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (nil? (:thinking body)))
      (is (nil? (:output_config body)))
      (is (nil? (:temperature body))
          "temperature must be absent even with thinking off on adaptive models")))

  (testing "xhigh effort maps to high for adaptive thinking"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :xhigh
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (= "high" (get-in body [:output_config :effort])))))

  (testing "medium effort level passes through"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (= "medium" (get-in body [:output_config :effort])))))

  (testing "Opus 4.7 defaults max_tokens to Anthropic's 128000 cap"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :off
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (= 128000 (:max_tokens body))))))

(deftest build-request-normalizes-legacy-string-tool-parameters-test
  (testing "legacy string tool parameters are normalized before Anthropic input_schema validation"
    (let [model        (models/get-model :opus-4.7)
          convo        (-> (conv/create "sys")
                           (conv/add-tool {:name "read"
                                           :description "Read a file"
                                           :parameters "{:type \"object\" :properties {\"path\" {:type \"string\"}} :required [\"path\"]}"}))
          req          (#'anthropic/build-request convo model {:thinking-level :high
                                                               :api-key "test-key"})
          body         (json/parse-string (:body req) true)
          input-schema (get-in body [:tools 0 :input_schema])]
      (is (map? input-schema))
      (is (= "object" (:type input-schema)))
      (is (= ["path"] (:required input-schema)))
      (is (= "string"
             (or (get-in input-schema [:properties "path" :type])
                 (get-in input-schema [:properties :path :type])))))))

(deftest build-request-with-cache-breakpoints-test
  (testing "system prompt blocks and tools emit Anthropic cache_control when marked ephemeral"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (-> (conv/create {:system-prompt "sys"
                                    :system-prompt-blocks [{:kind :text
                                                            :text "sys"
                                                            :cache-control {:type :ephemeral}}]})
                      (conv/add-tool {:name "read"
                                      :description "Read a file"
                                      :parameters {:type "object"}
                                      :cache-control {:type :ephemeral}}))
          req     (#'anthropic/build-request convo model {:api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= [{:type "text"
               :text "sys"
               :cache_control {:type "ephemeral"}}]
             (:system body)))
      (is (= {:type "ephemeral"}
             (get-in body [:tools 0 :cache_control])))
      (is (some? (re-find #"prompt-caching" (get headers "anthropic-beta")))
          "prompt-caching beta header required when cache_control is present"))))

(deftest build-request-with-tool-results-thinking-and-cache-test
  (testing "tool result history, thinking, and cache breakpoints produce a coherent Anthropic request"
    (let [model    (models/get-model :sonnet-4.6)
          convo    (-> (conv/create {:system-prompt "joined"
                                     :system-prompt-blocks [{:kind :text
                                                             :text "sys"
                                                             :cache-control {:type :ephemeral}}]})
                       (conv/add-user-message "boot")
                       (conv/add-assistant-message
                        {:content {:kind :structured
                                   :blocks [{:kind :tool-call
                                             :id "call_abc|fc_123"
                                             :name "read"
                                             :input {:path "a"}}]}})
                       (conv/add-tool-result "call_abc|fc_123" "read" {:kind :text :text "ok"} false)
                       (conv/add-assistant-message {:content {:kind :text :text "ready"}})
                       (conv/add-user-message "who?")
                       (conv/add-tool {:name "read"
                                       :description "Read a file"
                                       :parameters {:type "object"}
                                       :cache-control {:type :ephemeral}}))
          req      (#'anthropic/build-request convo model {:thinking-level :high
                                                           :api-key "test-key"})
          body     (json/parse-string (:body req) true)
          headers  (:headers req)
          messages (:messages body)
          asst     (second messages)
          tool-res (nth messages 2)
          use-id   (get-in asst [:content 0 :id])
          res-id   (get-in tool-res [:content 0 :tool_use_id])]
      (is (= [{:type "text"
               :text "sys"
               :cache_control {:type "ephemeral"}}]
             (:system body)))
      (is (= {:type "ephemeral"}
             (get-in body [:tools 0 :cache_control])))
      (is (= "enabled" (get-in body [:thinking :type])))
      (is (= 16000 (get-in body [:thinking :budget_tokens])))
      (is (nil? (:temperature body)) "temperature must be absent with extended thinking")
      (is (some? (re-find #"interleaved-thinking" (get headers "anthropic-beta")))
          "interleaved-thinking beta header required")
      (is (some? (re-find #"prompt-caching" (get headers "anthropic-beta")))
          "prompt-caching beta header required when cache_control is present")
      (is (= ["user" "assistant" "user" "assistant" "user"]
             (mapv :role messages)))
      (is (= "tool_use" (get-in asst [:content 0 :type])))
      (is (= "tool_result" (get-in tool-res [:content 0 :type])))
      (is (= use-id res-id) "tool_result must reference normalized tool_use id")
      (is (re-matches #"^[a-zA-Z0-9_-]+$" use-id)
          "normalized id must satisfy Anthropic regex"))))

(deftest stream-anthropic-captures-provider-request-and-response-test
  (testing "Anthropic streaming emits provider request/response captures"
    (let [model           (models/get-model :sonnet-4.6)
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
          reply-captures  (atom [])
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic
         convo model {:api-key "test-key"
                      :on-provider-request  #(reset! request-capture %)
                      :on-provider-response #(swap! reply-captures conj %)}
         (fn [_] nil)))

      (is (= :anthropic (:provider @request-capture)))
      (is (= :anthropic-messages (:api @request-capture)))
      (is (= "claude-sonnet-4-6"
             (get-in @request-capture [:request :body :model])))
      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "x-api-key"])))
      (is (pos? (count @reply-captures)))
      (is (some #(= "message_start"
                    (get-in % [:event :type]))
                @reply-captures))))

  (testing "Anthropic-compatible custom providers preserve provider identity and base-url"
    (let [model           {:id "MiniMax-M2.7"
                           :name "MiniMax M2.7"
                           :provider :minimax
                           :api :anthropic-messages
                           :base-url "https://api.minimax.io/anthropic"
                           :supports-reasoning true
                           :supports-images false
                           :supports-text true
                           :context-window 128000
                           :max-tokens 16384
                           :input-cost 0.0
                           :output-cost 0.0
                           :cache-read-cost 0.0
                           :cache-write-cost 0.0}
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
          posted-url      (atom nil)
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [url _req]
                                (reset! posted-url url)
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic
         convo model {:api-key "minimax-inline-key"
                      :on-provider-request #(reset! request-capture %)}
         (fn [_] nil)))

      (is (= "https://api.minimax.io/anthropic/v1/messages" @posted-url))
      (is (= :minimax (:provider @request-capture)))
      (is (= :anthropic-messages (:api @request-capture)))
      (is (= "MiniMax-M2.7"
             (get-in @request-capture [:request :body :model])))
      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "x-api-key"])))))

  (testing "Anthropic error replies capture raw body and headers"
    (let [model           (models/get-model :sonnet-4.6)
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          reply-captures  (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                (throw (ex-info "Error"
                                                {:status 400
                                                 :headers {"request-id" "req_ant_456"}
                                                 :body (stream-body
                                                        (json/generate-string
                                                         {:error {:message "prompt is too long"}}))})))]
        (anthropic/stream-anthropic
         convo model {:api-key "test-key"
                      :on-provider-response #(swap! reply-captures conj %)}
         (fn [_] nil)))
      (is (= :anthropic (-> @reply-captures last :provider)))
      (is (= :anthropic-messages (-> @reply-captures last :api)))
      (is (= 400 (get-in (last @reply-captures) [:event :http-status])))
      (is (= "req_ant_456"
             (get-in (last @reply-captures) [:event :headers "request-id"])))
      (is (= {:error {:message "prompt is too long"}}
             (get-in (last @reply-captures) [:event :body])))
      (is (string? (get-in (last @reply-captures) [:event :body-text]))))))

(deftest stream-anthropic-error-includes-status-and-request-id-test
  (testing "Anthropic HTTP errors preserve provider message, status, request id, and body"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                (throw (ex-info "Error"
                                                {:status 400
                                                 :headers {"request-id" "req_ant_123"}
                                                 :body (stream-body
                                                        (json/generate-string
                                                         {:error {:message "cache_control requires prompt-caching beta"}}))})))]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 1 (count @events)))
      (is (= :error (:type (first @events))))
      (is (= "cache_control requires prompt-caching beta (status 400) [request-id req_ant_123]"
             (:error-message (first @events))))
      (is (= 400 (:http-status (first @events))))
      (is (= "req_ant_123" (get-in (first @events) [:headers "request-id"])))
      (is (= {:error {:message "cache_control requires prompt-caching beta"}}
             (:body (first @events))))
      (is (string? (:body-text (first @events)))))))

(deftest stream-anthropic-non-2xx-response-map-surfaces-body-message-test
  (testing "non-2xx response map emits parsed provider error message"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                {:status 400
                                 :headers {"request-id" "req_ant_400"}
                                 :body (stream-body
                                        (json/generate-string
                                         {:error {:message "invalid messages payload"}}))})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 1 (count @events)))
      (is (= :error (:type (first @events))))
      (is (= "invalid messages payload (status 400) [request-id req_ant_400]"
             (:error-message (first @events))))
      (is (= 400 (:http-status (first @events))))))

  (testing "missing 400 body uses actionable fallback text"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                {:status 400
                                 :headers {"request-id" "req_ant_nobody"}
                                 :body nil})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 1 (count @events)))
      (is (re-find #"Anthropic rejected the request"
                   (:error-message (first @events))))
      (is (re-find #"no error body returned"
                   (:error-message (first @events))))
      (is (re-find #"possible causes"
                   (:error-message (first @events))))
      (is (re-find #"request\{model=claude-sonnet-4-6"
                   (:error-message (first @events))))
      (is (re-find #"request-id req_ant_nobody"
                   (:error-message (first @events)))))))

(deftest stream-anthropic-retries-without-prompt-caching-on-400-test
  (testing "400 with prompt-caching enabled retries once without cache directives"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create {:system-prompt "sys"
                                   :system-prompt-blocks [{:kind :text
                                                           :text "sys"
                                                           :cache-control {:type :ephemeral}}]})
                     (conv/add-user-message "hello"))
          calls  (atom [])
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body nil}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls)))
      (is (re-find #"prompt-caching"
                   (or (get-in (first @calls) [:headers "anthropic-beta"]) "")))
      (is (not (re-find #"prompt-caching"
                        (or (get-in (second @calls) [:headers "anthropic-beta"]) ""))))
      (is (not (re-find #"cache_control"
                        (or (:body (second @calls)) ""))))
      (is (= "sys"
             (:system (json/parse-string (:body (second @calls)) true)))
          "after prompt-caching fallback, system is collapsed to plain string")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)))))

(deftest stream-anthropic-retries-without-thinking-on-400-test
  (testing "oauth + thinking request retries once with compatibility fallbacks on 400"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          calls  (atom [])
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic convo model {:api-key "sk-ant-oat-test-token"
                                                 :thinking-level :medium}
                                    (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls)))
      (let [first-betas  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
            second-betas (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
            second-body  (json/parse-string (:body (second @calls)) true)]
        (is (re-find #"claude-code" first-betas))
        (is (re-find #"interleaved-thinking" first-betas))
        (is (re-find #"context-management" first-betas))
        (is (re-find #"prompt-caching-scope-2026-01-05" first-betas)
            "scope beta should be present for oauth")
        (is (re-find #"oauth-2025-04-20" second-betas)
            "oauth beta must be preserved")
        (is (re-find #"claude-code" second-betas)
            "claude-code beta should remain for oauth compatibility")
        (is (re-find #"context-management" second-betas)
            "context-management beta should remain for oauth compatibility")
        (is (re-find #"prompt-caching-scope-2026-01-05" second-betas)
            "scope beta should remain for oauth compatibility")
        (is (not (re-find #"interleaved-thinking" second-betas)))
        (is (nil? (:thinking second-body))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)))))

;; ── SSE parser — thinking block routing ─────────────────────────────────────

(defn- run-stream [sse-str model options]
  (let [events (atom [])
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse-str)})]
      (anthropic/stream-anthropic convo model options
                                  (fn [e] (swap! events conj e))))
    @events))

(deftest thinking-block-emits-thinking-delta-test
  (testing "thinking content block deltas are routed as :thinking-delta events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "thinking" :thinking "" :signature ""}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking "I think"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking " therefore"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "signature_delta" :signature "sig-1"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 1
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 1
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 1})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (some #(= :thinking-start (:type %)) events)
          "should emit a thinking-start event")
      (is (some #(= :thinking-delta (:type %)) events)
          "should emit at least one :thinking-delta")
      (is (= ["I think" " therefore"]
             (->> events
                  (filter #(= :thinking-delta (:type %)))
                  (mapv :delta)))
          "thinking deltas carry incremental text")
      (is (= "sig-1"
             (some #(when (= :thinking-signature-delta (:type %))
                      (:signature %))
                   events))
          "signature deltas are surfaced separately")
      (is (some #(= :text-delta (:type %)) events)
          "text block after thinking block still emits :text-delta")
      (is (not-any? #(and (= :text-delta (:type %))
                          (some-> (:delta %) (.contains "I think")))
                    events)
          "thinking text must not bleed into :text-delta events"))))

(deftest text-block-not-misrouted-as-thinking-test
  (testing "plain text blocks are not emitted as :thinking-delta"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (not-any? #(= :thinking-delta (:type %)) events))
      (is (some #(= :text-delta (:type %)) events)))))

(deftest usage-captured-from-sse-events-test
  (testing "usage tokens are read from message_start and message_delta SSE events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start"
                               {:type    "message_start"
                                :message {:usage {:input_tokens                  100
                                                  :cache_read_input_tokens        20
                                                  :cache_creation_input_tokens    10}}})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_delta"
                               {:type  "message_delta"
                                :delta {:stop_reason "end_turn"}
                                :usage {:output_tokens 50}})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})
          done   (first (filter #(= :done (:type %)) events))
          usage  (:usage done)]
      (is (some? done) "should emit a :done event")
      (is (= 100 (:input-tokens usage))  "input-tokens from message_start")
      (is (= 50  (:output-tokens usage)) "output-tokens from message_delta")
      (is (= 20  (:cache-read-tokens usage))  "cache-read-tokens from message_start")
      (is (= 10  (:cache-write-tokens usage)) "cache-write-tokens from message_start")
      (is (= 180 (:total-tokens usage)) "total = input + output + cache-read + cache-write")
      (is (map? (:cost usage)) "cost map present"))))

(deftest transform-messages-normalizes-invalid-tool-ids-test
  (testing "assistant tool_use ids and tool_result tool_use_id are normalized to Anthropic-safe ids"
    (let [convo  {:messages [{:role :assistant
                              :content {:kind :structured
                                        :blocks [{:kind :tool-call
                                                  :id "call_abc|fc_123"
                                                  :name "read"
                                                  :input {"path" "README.md"}}]}}
                             {:role :tool-result
                              :tool-call-id "call_abc|fc_123"
                              :tool-name "read"
                              :content {:kind :text :text "ok"}
                              :is-error false}]}
          out    (anthropic/transform-messages convo)
          asst   (first out)
          user   (second out)
          use-id (get-in asst [:content 0 :id])
          res-id (get-in user [:content 0 :tool_use_id])]
      (is (= "assistant" (:role asst)))
      (is (= "tool_use" (get-in asst [:content 0 :type])))
      (is (= "user" (:role user)))
      (is (= "tool_result" (get-in user [:content 0 :type])))
      (is (= use-id res-id) "tool_result must reference normalized tool_use id")
      (is (re-matches #"^[a-zA-Z0-9_-]+$" use-id)
          "normalized id must satisfy Anthropic regex"))))

(deftest transform-messages-preserves-user-text-shape-test
  (testing "vector text blocks from agent messages become Anthropic text content, not stringified EDN"
    (let [convo {:messages [{:role :user
                             :content [{:type :text :text "who are you?"}]}]}
          out   (anthropic/transform-messages convo)]
      (is (= [{:role "user"
               :content [{:type "text" :text "who are you?"}]}]
             out))))

  (testing "user text blocks preserve cache_control metadata"
    (let [convo {:messages [{:role :user
                             :content [{:type :text
                                        :text "stable"
                                        :cache-control {:type :ephemeral}}
                                       {:type :text
                                        :text "tail"}]}]}
          out   (anthropic/transform-messages convo)]
      (is (= [{:type "text"
               :text "stable"
               :cache_control {:type "ephemeral"}}
              {:type "text"
               :text "tail"}]
             (get-in out [0 :content]))))))
