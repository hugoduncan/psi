(ns psi.ai.providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.proxy :as proxy]
   [psi.ai.providers.openai :as openai]
   [psi.ai.providers.openai.codex-responses :as codex])
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
(deftest codex-provider-scoped-api-key-resolution-test
  ;; Review 13: the :openai-codex-responses transport is the third custom
  ;; ModelDef ApiProtocol — it never received the provider-scoped key
  ;; resolution reviews 3/10 gave :anthropic-messages/:openai-completions, so
  ;; a custom codex provider with no configured key silently sent the global
  ;; OPENAI_API_KEY to the third-party :base-url (or hard-failed confusingly
  ;; on a regular sk- env key). Mirrors
  ;; openai-provider-scoped-api-key-resolution-test: custom codex providers
  ;; fail fast (or go keyless via :no-auth-header / recognized auth header
  ;; among custom :headers); only built-in OpenAI models fall back to the
  ;; env var.
  (testing "custom codex provider never falls back to OPENAI_API_KEY env var (no cross-provider leak)"
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :api :openai-codex-responses
                 :base-url "https://example.com/v1"
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
      (with-redefs [codex/getenv (fn [_] (jwt-with-account-id "should-never-leak"))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-codex"
             (openai/build-codex-request convo model {}))
            "OPENAI_API_KEY must not be used to satisfy a custom codex provider's request"))))

  (testing "custom codex missing-auth error points at models.edn :auth, never hints at /login, normalizes kebab-case env suggestion"
    (let [model {:id "my-proxy-codex"
                 :name "My Proxy Codex"
                 :provider :my-codex-proxy
                 :api :openai-codex-responses
                 :base-url "https://my-proxy.example.com/v1"
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
      (try
        (openai/build-codex-request convo model {})
        (is false "expected build-codex-request to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"models.edn" (ex-message e))
              "error must name the models.edn :auth remedy")
          (is (re-find #"env:MY_CODEX_PROXY_API_KEY" (ex-message e))
              "env var suggestion must normalize kebab-case provider keys to underscores")
          (is (nil? (re-find #"/login" (ex-message e)))
              "custom-provider error must not hint at /login — OAuth login only exists for built-in providers")))))

  (testing "built-in codex model falls back to OPENAI_API_KEY env var"
    (let [model (models/get-model :gpt-5.3-codex)
          convo (conv/create "sys")
          token (jwt-with-account-id "acc_env")]
      (with-redefs [codex/getenv (fn [_] token)]
        (let [req (openai/build-codex-request convo model {})]
          (is (= (str "Bearer " token)
                 (get-in req [:headers "Authorization"]))
              "built-in codex requests without an explicit key use OPENAI_API_KEY")
          (is (= "acc_env" (get-in req [:headers "chatgpt-account-id"]))
              "chatgpt-account-id is derived from the env fallback key")))))

  (testing "keyless custom codex provider with :no-auth-header true builds a request without Authorization or chatgpt-account-id"
    (let [model {:id "local-codex"
                 :name "Local Codex"
                 :provider :local-codex
                 :api :openai-codex-responses
                 :base-url "http://localhost:8080/v1"
                 :supports-reasoning true
                 :supports-images false
                 :supports-text true
                 :context-window 128000
                 :max-tokens 16384
                 :input-cost 0.0
                 :output-cost 0.0
                 :cache-read-cost 0.0
                 :cache-write-cost 0.0}
          convo (conv/create "sys")
          req   (openai/build-codex-request convo model {:no-auth-header true})]
      (with-redefs [codex/getenv (fn [_] (jwt-with-account-id "should-never-leak"))]
        (is (nil? (get-in req [:headers "Authorization"]))
            "no Authorization when :no-auth-header is set — even with OPENAI_API_KEY present")
        (is (nil? (get-in req [:headers "chatgpt-account-id"]))
            "no chatgpt-account-id for a keyless request")
        (is (= "application/json" (get-in req [:headers "Content-Type"]))))))

  (testing "recognized auth header among custom headers (case-insensitive) implies keyless codex auth"
    (let [model {:id "local-codex"
                 :name "Local Codex"
                 :provider :local-codex
                 :api :openai-codex-responses
                 :base-url "http://localhost:8080/v1"
                 :supports-reasoning true
                 :supports-images false
                 :supports-text true
                 :context-window 128000
                 :max-tokens 16384
                 :input-cost 0.0
                 :output-cost 0.0
                 :cache-read-cost 0.0
                 :cache-write-cost 0.0}
          convo (conv/create "sys")
          req   (openai/build-codex-request convo model {:headers {"X-API-Key" "local-key"}})
          headers (:headers req)]
      (is (= "local-key" (get headers "X-API-Key"))
          "custom auth header is preserved")
      (with-redefs [codex/getenv (fn [_] (jwt-with-account-id "should-never-leak"))]
        (is (nil? (get headers "Authorization"))
            "env key must not replace/add a Bearer for a headers-auth keyless request")
        (is (nil? (get headers "chatgpt-account-id"))
            "no chatgpt-account-id for a headers-auth keyless request"))))

  (testing "incidental custom headers with a blank key fast-fail (no env fallback)"
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :api :openai-codex-responses
                 :base-url "https://example.com/v1"
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
      (with-redefs [codex/getenv (fn [_] (jwt-with-account-id "should-never-leak"))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-codex"
             (openai/build-codex-request convo model {:headers {"X-Client" "psi"}}))
            "incidental headers must not imply keyless — a blank key still fast-fails instead of leaking the env key")))))
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

(deftest unresolved-model-fails-fast-test
  ;; An unknown/unresolved model key yields nil from model lookup; the
  ;; provider boundary must reject it rather than sending `:model null`
  ;; and hanging the request.
  (testing "nil model throws on stream dispatch"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"resolved model"
         ((:stream openai/provider) (conv/create "sys") nil {} identity))))
  (testing "nil model throws on execute dispatch"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"resolved model"
         ((:execute openai/provider) (conv/create "sys") nil {}))))
  (testing "model without :id throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"resolved model"
         ((:execute openai/provider) (conv/create "sys") {:api :openai-completions} {}))))
  (testing "resolved model with :id passes validation"
    (with-redefs [http/post (fn [_ _] {:body (stream-body "data: [DONE]\n\n")})]
      (is (nil? ((:stream openai/provider)
                 (conv/create "sys")
                 (models/get-model :gpt-5.1)
                 {:api-key "t"}
                 identity))))))
