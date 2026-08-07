(ns psi.ai.providers.anthropic-stream-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.proxy :as proxy]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest stream-anthropic-applies-proxy-request-options-test
  (testing "Anthropic stream request merges shared proxy options"
    (let [model      (models/get-model :sonnet-4.6)
          convo      (-> (conv/create "sys")
                         (conv/add-user-message "hello"))
          captured   (atom nil)
          sse        (str (sse-line "message_start" {:type "message_start"})
                          (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [proxy/request-proxy-options (fn [url]
                                                  (is (= "https://api.anthropic.com/v1/messages" url))
                                                  {:proxy-host "proxy.example"
                                                   :proxy-port 8443
                                                   :proxy-scheme :http})
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"} (fn [_] nil)))
      (is (= "proxy.example" (:proxy-host @captured)))
      (is (= 8443 (:proxy-port @captured)))
      (is (= :http (:proxy-scheme @captured)))))

  (testing "Anthropic stream leaves request unchanged when no proxy applies"
    (let [model    (models/get-model :sonnet-4.6)
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          captured (atom nil)
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [proxy/request-proxy-options (constantly nil)
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"} (fn [_] nil)))
      (is (nil? (:proxy-host @captured)))
      (is (nil? (:proxy-port @captured)))
      (is (nil? (:proxy-scheme @captured))))))

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

  (testing "DeepSeek custom-provider model derives its Anthropic-compatible endpoint URL"
    (let [model           {:id "deepseek-v4-flash"
                           :name "DeepSeek V4 Flash"
                           :provider :deepseek
                           :api :anthropic-messages
                           :base-url "https://api.deepseek.com/anthropic"
                           :supports-reasoning true
                           :adaptive-thinking true
                           :supports-images false
                           :supports-text true
                           :context-window 1000000
                           :max-tokens 384000
                           :input-cost 0.14
                           :output-cost 0.28
                           :cache-read-cost 0.0028
                           :cache-write-cost 0.14}
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
         convo model {:api-key "deepseek-inline-key"
                      :on-provider-request #(reset! request-capture %)}
         (fn [_] nil)))

      (is (= "https://api.deepseek.com/anthropic/v1/messages" @posted-url)
          "request URL must be derived from the configured base-url")
      (is (= :deepseek (:provider @request-capture)))
      (is (= :anthropic-messages (:api @request-capture)))
      (is (= "deepseek-v4-flash"
             (get-in @request-capture [:request :body :model])))
      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "x-api-key"])))))

  (testing "mixed-case auth header (X-API-Key) is redacted case-insensitively in captures"
    ;; Redaction must match build-request's auth-header? recognition
    ;; (case-insensitive): the keyless custom-headers pattern
    ;; (:headers {"X-API-Key" "local-key"} without :api-key) would otherwise
    ;; leak the secret verbatim into the :on-provider-request capture.
    (let [model           {:id "local-proxy"
                           :name "Local Proxy"
                           :provider :local-proxy
                           :api :anthropic-messages
                           :base-url "http://localhost:8080"
                           :supports-reasoning false
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
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic
         convo model {:headers {"X-API-Key" "local-key"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_] nil)))

      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "X-API-Key"]))
          "mixed-case X-API-Key must be redacted in the :on-provider-request payload")))

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
