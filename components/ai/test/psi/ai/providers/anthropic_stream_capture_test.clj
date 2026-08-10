(ns psi.ai.providers.anthropic-stream-capture-test
  "Anthropic stream request/response capture tests (redaction, provider
  identity, endpoint derivation, error capture)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest stream-anthropic-post-terminal-events-are-not-captured-test
  (testing "parsed SSE events after a terminal event produce no events or captures"
    (let [model    (models/get-model :sonnet-4.6)
          convo    (-> (conv/create "sys") (conv/add-user-message "hello"))
          events   (atom [])
          captures (atom [])
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"})
                        (sse-line "content_block_start"
                                  {:type "content_block_start"
                                   :index 0
                                   :content_block {:type "text"}}))
          http     (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :api-key "test-key"
                    :on-provider-response #(swap! captures conj %)}
       #(swap! events conj %))
      (is (= [:start :done] (mapv :type @events))
          "the trailing content event is suppressed")
      (is (= ["message_start" "message_stop"]
             (mapv (comp :type :event) @captures))
          "the trailing content event is not captured"))))

(deftest stream-anthropic-captures-provider-request-and-response-test
  (testing "Anthropic streaming emits provider request/response captures"
    (let [model           (models/get-model :sonnet-4.6)
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
          reply-captures  (atom [])
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :api-key "test-key"
                    :on-provider-request  #(reset! request-capture %)
                    :on-provider-response #(swap! reply-captures conj %)}
       (fn [_] nil))

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
                           :custom? true
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
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :api-key "minimax-inline-key"
                    :on-provider-request #(reset! request-capture %)}
       (fn [_] nil))

      (is (= "https://api.minimax.io/anthropic/v1/messages"
             (:url (first (http-boundary/requests http)))))
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
                           :custom? true
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
          sse             (str (sse-line "message_start" {:type "message_start"})
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :api-key "deepseek-inline-key"
                    :on-provider-request #(reset! request-capture %)}
       (fn [_] nil))

      (is (= "https://api.deepseek.com/anthropic/v1/messages"
             (:url (first (http-boundary/requests http))))
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
                           :custom? true
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
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :headers {"X-API-Key" "local-key"}
                    :on-provider-request #(reset! request-capture %)}
       (fn [_] nil))

      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "X-API-Key"]))
          "mixed-case X-API-Key must be redacted in the :on-provider-request payload")))

  (testing "mixed-case Authorization header is redacted case-insensitively in captures"
    ;; The case-insensitive find-headers redaction covers lowercase
    ;; x-api-key and mixed-case X-API-Key, but not the redact-authorization
    ;; path for a non-exact-case Authorization header (existing tests use
    ;; exact-case "Authorization" only). A keyless custom provider carrying
    ;; :headers {"authorization" "local-token"} must capture
    ;; "Bearer ***REDACTED***", not the token verbatim.
    (let [model           {:id "local-proxy"
                           :name "Local Proxy"
                           :provider :local-proxy
                           :custom? true
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
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :headers {"authorization" "local-token"}
                    :on-provider-request #(reset! request-capture %)}
       (fn [_] nil))

      (is (= "Bearer ***REDACTED***"
             (get-in @request-capture [:request :headers "authorization"]))
          "mixed-case authorization header must be redacted via redact-authorization")))

  (testing "differently-cased duplicate auth headers are ALL redacted in captures"
    ;; redact-headers redacted only the FIRST case-insensitive
    ;; match per auth header name, so a wire request carrying both casings of
    ;; the same auth header — base "x-api-key" (configured key) + custom
    ;; "X-API-Key" (a supported but discouraged mixed-auth scenario) — leaked the second
    ;; one VERBATIM into the :on-provider-request capture. Every
    ;; case-insensitive match must be redacted so the CHANGELOG claim
    ;; "secrets carried in custom :headers never persist verbatim in
    ;; :on-provider-request session captures" holds for dual-casing requests.
    (let [model           {:id "local-proxy"
                           :name "Local Proxy"
                           :provider :local-proxy
                           :custom? true
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
                               (sse-line "message_stop" {:type "message_stop"}))
          http            (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic
       convo model {:http-boundary http
                    :api-key "configured-key"
                    :headers {"X-API-Key" "secret-custom-key"}
                    :on-provider-request #(reset! request-capture %)}
       (fn [_] nil))

      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "x-api-key"]))
          "configured lowercase x-api-key must be redacted")
      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "X-API-Key"]))
          "differently-cased X-API-Key duplicate must ALSO be redacted — no verbatim secret in the capture")))

  (testing "Anthropic error replies capture raw body and headers"
    (let [model           (models/get-model :sonnet-4.6)
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          reply-captures  (atom [])]
      (let [http (http-boundary/nullable
                  [(ex-info "Error"
                            {:status 400
                             :headers {"request-id" "req_ant_456"}
                             :body (stream-body
                                    (json/generate-string
                                     {:error {:message "prompt is too long"}}))})])]
        (anthropic/stream-anthropic
         convo model {:http-boundary http
                      :api-key "test-key"
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
