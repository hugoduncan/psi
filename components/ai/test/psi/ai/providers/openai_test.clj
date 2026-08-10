(ns psi.ai.providers.openai-test
  (:require
   [psi.ai.providers.environment-boundary :as environment-boundary]
   [clj-http.client :as http]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.proxy :as proxy]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream InputStream]
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
(defn- throwing-stream-after
  [s ex-data]
  (let [bytes (.getBytes s "UTF-8")
        index (atom 0)]
    (proxy [InputStream] []
      (read
        ([]
         (if (< @index (alength bytes))
           (let [value (bit-and 0xff (aget bytes @index))]
             (swap! index inc)
             value)
           (throw (ex-info "simulated stream read failure" ex-data))))
        ([buffer offset length]
         (if (< @index (alength bytes))
           (let [remaining (- (alength bytes) @index)
                 count (min length remaining)]
             (System/arraycopy bytes @index buffer offset count)
             (swap! index + count)
             count)
           (throw (ex-info "simulated stream read failure" ex-data))))))))
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
                             :custom? true
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
                 :custom? true
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
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [environment (environment-boundary/nullable {"OPENAI_API_KEY" (jwt-with-account-id "should-never-leak")})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-codex"
             (openai/build-codex-request convo model {:environment-boundary environment}))
            "OPENAI_API_KEY must not be used to satisfy a custom codex provider's request")
        (is (empty? (environment-boundary/reads environment))))))

  (testing "custom codex missing-auth error points at models.edn :auth, never hints at /login, normalizes kebab-case env suggestion"
    (let [model {:id "my-proxy-codex"
                 :name "My Proxy Codex"
                 :provider :my-codex-proxy
                 :custom? true
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
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [environment (environment-boundary/nullable {"OPENAI_API_KEY" token})]
        (let [req (openai/build-codex-request convo model {:environment-boundary environment})]
          (is (= (str "Bearer " token)
                 (get-in req [:headers "Authorization"]))
              "built-in codex requests without an explicit key use OPENAI_API_KEY")
          (is (= "acc_env" (get-in req [:headers "chatgpt-account-id"]))
              "chatgpt-account-id is derived from the env fallback key")
          (is (= ["OPENAI_API_KEY"] (environment-boundary/reads environment)))))))

  (testing "keyless custom codex provider with :no-auth-header true builds a request without Authorization or chatgpt-account-id"
    (let [model {:id "local-codex"
                 :name "Local Codex"
                 :provider :local-codex
                 :custom? true
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
          environment (environment-boundary/nullable
                       {"OPENAI_API_KEY" (jwt-with-account-id "should-never-leak")})
          req   (openai/build-codex-request convo model
                                            {:no-auth-header true
                                             :environment-boundary environment})]
      (is (nil? (get-in req [:headers "Authorization"]))
          "no Authorization when :no-auth-header is set — even with OPENAI_API_KEY present")
      (is (nil? (get-in req [:headers "chatgpt-account-id"]))
          "no chatgpt-account-id for a keyless request")
      (is (= "application/json" (get-in req [:headers "Content-Type"])))))

  (testing "recognized auth header among custom headers (case-insensitive) implies keyless codex auth"
    (let [model {:id "local-codex"
                 :name "Local Codex"
                 :provider :local-codex
                 :custom? true
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
          environment (environment-boundary/nullable
                       {"OPENAI_API_KEY" (jwt-with-account-id "should-never-leak")})
          req   (openai/build-codex-request convo model
                                            {:headers {"X-API-Key" "local-key"}
                                             :environment-boundary environment})
          headers (:headers req)]
      (is (= "local-key" (get headers "X-API-Key"))
          "custom auth header is preserved")
      (is (nil? (get headers "Authorization"))
          "env key must not replace/add a Bearer for a headers-auth keyless request")
      (is (nil? (get headers "chatgpt-account-id"))
          "no chatgpt-account-id for a headers-auth keyless request")))

  (testing "incidental custom headers with a blank key fast-fail (no env fallback)"
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :custom? true
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
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [environment (environment-boundary/nullable {"OPENAI_API_KEY" (jwt-with-account-id "should-never-leak")})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-codex"
             (openai/build-codex-request convo model {:headers {"X-Client" "psi"}
                                                      :environment-boundary environment}))
            "incidental headers must not imply keyless — a blank key still fast-fails instead of leaking the env key"))))

  (testing "custom codex provider named \"openai\" never falls back to OPENAI_API_KEY"
    ;; Review 14: built-in detection must not key off the provider NAME — a
    ;; custom provider literally named "openai" is tagged :custom? true at
    ;; parse time and the shared resolve-api-key treats it as custom on the
    ;; codex transport too.
    (let [model {:id "not-a-builtin"
                 :name "Custom OpenAI-Named Codex Provider"
                 :provider :openai
                 :custom? true
                 :api :openai-codex-responses
                 :base-url "https://third-party.example/v1"
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
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [environment (environment-boundary/nullable {"OPENAI_API_KEY" (jwt-with-account-id "should-never-leak")})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider openai"
             (openai/build-codex-request convo model {:environment-boundary environment}))
            "OPENAI_API_KEY must not be used to satisfy a custom codex provider named \"openai\"")
        (is (empty? (environment-boundary/reads environment)))))))
(deftest codex-configured-key-plus-recognized-auth-header-interplay-test
  ;; Review 14: the review-11 interplay lock covers :anthropic-messages and
  ;; :openai-completions only, but build-codex-request performs the identical
  ;; (merge base-hdrs custom) (codex_responses.clj) — a custom Authorization
  ;; header on a :openai-codex-responses provider silently replaces the
  ;; resolved codex bearer key too (chatgpt-account-id is still derived from
  ;; the configured key), and a custom X-API-Key header coexists with the
  ;; configured bearer key (server picks by case-insensitive header merge).
  ;; Documented in doc/custom-providers.md — don't mix them.
  (testing "custom Authorization header replaces the resolved codex bearer key"
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :custom? true
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
          convo (conv/create "sys")
          req   (openai/build-codex-request
                 convo model
                 {:api-key (jwt-with-account-id "acc_configured")
                  :headers {"Authorization" "Bearer custom"}})]
      (is (= "Bearer custom" (get-in req [:headers "Authorization"]))
          "custom Authorization header replaces the resolved bearer key — the configured key is not sent")
      (is (= "acc_configured" (get-in req [:headers "chatgpt-account-id"]))
          "chatgpt-account-id is still derived from the configured key")))

  (testing "configured codex key + custom X-API-Key header sends both auth headers"
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :custom? true
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
          convo (conv/create "sys")
          req   (openai/build-codex-request
                 convo model
                 {:api-key (jwt-with-account-id "acc_configured")
                  :headers {"X-API-Key" "other-key"}})]
      (is (= (str "Bearer " (jwt-with-account-id "acc_configured"))
             (get-in req [:headers "Authorization"]))
          "configured api-key still sent as the bearer Authorization header")
      (is (= "acc_configured" (get-in req [:headers "chatgpt-account-id"]))
          "chatgpt-account-id derived from the configured key")
      (is (= "other-key" (get-in req [:headers "X-API-Key"]))
          "custom X-API-Key header merged in as-is — duplicate auth header on the wire")))

  (testing "custom chatgpt-account-id header replaces the derived value (configured-key case)"
    ;; Review 18: build-codex-request derives chatgpt-account-id from the
    ;; resolved key, but (merge base-hdrs custom) lets a custom
    ;; chatgpt-account-id header silently replace the derived value — the
    ;; same merge behavior locked for Authorization/X-API-Key in review 14.
    (let [model {:id "custom-codex-model"
                 :name "Custom Codex Model"
                 :provider :custom-codex
                 :custom? true
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
          convo (conv/create "sys")
          req   (openai/build-codex-request
                 convo model
                 {:api-key (jwt-with-account-id "acc_configured")
                  :headers {"chatgpt-account-id" "custom-account"}})]
      (is (= "custom-account" (get-in req [:headers "chatgpt-account-id"]))
          "custom chatgpt-account-id header replaces the derived value — the configured key's account id is not sent")
      (is (= (str "Bearer " (jwt-with-account-id "acc_configured"))
             (get-in req [:headers "Authorization"]))
          "configured key still sent as the bearer Authorization header")))

  (testing "keyless codex request + custom chatgpt-account-id header passes through"
    ;; Review 18: a keyless request derives no account id (no api-key), so a
    ;; custom chatgpt-account-id header legitimately supplies one — it must
    ;; pass through unmodified rather than being stripped.
    (let [model {:id "local-codex"
                 :name "Local Codex"
                 :provider :local-codex
                 :custom? true
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
          req   (openai/build-codex-request
                 convo model
                 {:no-auth-header true
                  :headers {"chatgpt-account-id" "custom-account"}})]
      (is (= "custom-account" (get-in req [:headers "chatgpt-account-id"]))
          "keyless request passes the custom chatgpt-account-id header through")
      (is (nil? (get-in req [:headers "Authorization"]))
          "no Authorization for a keyless request"))))
(deftest codex-adaptive-thinking-ignored-for-custom-providers-test
  ;; Review 15: the doc/custom-providers.md claim that :adaptive-thinking "is
  ;; ignored for OpenAI-compatible custom providers" now names
  ;; :openai-codex-responses too (review 13), but the only no-op lock is the
  ;; completions one (review 10). The codex transport never reads
  ;; :adaptive-thinking — expand-model carries it into every custom model
  ;; map, but openai/reasoning.clj reasoning-effort maps :thinking-level →
  ;; classic reasoning effort — so a custom :openai-codex-responses model
  ;; with :adaptive-thinking true must produce an unchanged codex body (no
  ;; output_config/adaptive leakage), mirroring the completions lock.
  (testing "adaptive-thinking on a custom :openai-codex-responses model does not leak into the request body"
    (let [base-model {:id                 "custom-codex-model"
                      :name               "Custom Codex Model"
                      :provider           :custom-codex
                      :custom?            true
                      :api                :openai-codex-responses
                      :base-url           "https://example.com/v1"
                      :supports-reasoning true
                      :supports-images    false
                      :supports-text      true
                      :context-window     128000
                      :max-tokens         16384
                      :input-cost         0.0
                      :output-cost        0.0
                      :cache-read-cost    0.0
                      :cache-write-cost   0.0}
          convo   (-> (conv/create "sys") (conv/add-user-message "hi"))
          plain   (json/parse-string
                   (:body (openai/build-codex-request convo base-model
                                                      {:api-key (jwt-with-account-id "acc_plain")
                                                       :thinking-level :high}))
                   true)
          adaptive (json/parse-string
                    (:body (openai/build-codex-request convo (assoc base-model :adaptive-thinking true)
                                                       {:api-key (jwt-with-account-id "acc_adaptive")
                                                        :thinking-level :high}))
                    true)]
      (is (= plain adaptive)
          ":adaptive-thinking must not change the codex-compatible request body")
      (is (nil? (get adaptive :output_config))
          "no output_config/adaptive effort leakage into the codex body")
      (is (= "high" (get-in adaptive [:reasoning :effort]))
          "classic codex reasoning shape is unchanged"))))

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

(deftest completions-sse-error-then-read-exception-no-second-error-test
  (testing "a stream-read exception after a mid-stream SSE error chunk does not emit a second :error"
    ;; Review 44: the stream-openai catch block emitted a second :error with
    ;; no done? check after emit-chat-error! had terminated the stream. Drive
    ;; the real parser through a nullable response body that disconnects
    ;; after the error chunk.
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:error {:message "Overloaded"
                                     :type "server_error"}}) "\n\n"
                  "\n")
          http-client (http-boundary/nullable
                       [{:body (throwing-stream-after sse {})}])]
      ((:stream openai/provider)
       convo model {:http-boundary http-client :api-key "sk-test"}
       (fn [ev] (swap! events conj ev)))
      (is (= 1 (count (filter #(= :error (:type %)) @events)))
          "exactly one :error — the post-error stream-read exception must not emit a second one")
      (is (not-any? #(= :done (:type %)) @events)
          "no :done — the :error event is terminal"))))

(deftest completions-sse-error-then-trailing-choices-chunk-no-text-delta-test
  (testing "a trailing :choices chunk after a mid-stream SSE error chunk does not emit :text-delta"
    ;; Review 46: the review-43/44 done? guard covered only the TERMINAL
    ;; emissions. A trailing :choices chunk after the error chunk still
    ;; emitted :text-delta via emit-chat-chunk! (verified: events =
    ;; [:start :error :text-delta]), and a trailing usage/finish chunk could
    ;; drive finish-chat-chunk!'s unguarded force-start-pending-chat-tools! /
    ;; emit-chat-tool-ends! / emit-structured-output-result! (only
    ;; emit-chat-completion-finish! was done?-guarded). process-chat-sse-line!
    ;; is now short-circuited on done? (set by emit-chat-error! and
    ;; emit-chat-completion-finish!), so a post-error trailing chunk is a
    ;; full no-op.
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant" :content "Hello"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:error {:message "Overloaded"
                                     :type "server_error"}}) "\n\n"
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant" :content "trailing"}}]}) "\n\n"
                  "data: [DONE]\n\n")]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :text-delta :error] (mapv :type @events))
          "no :text-delta after the :error — the trailing :choices chunk and [DONE] are full no-ops once done")
      (is (not-any? #(= :done (:type %)) @events)
          "no :done — the :error event is terminal"))))

(deftest codex-error-then-trailing-output-text-delta-no-text-delta-test
  (testing "a trailing response.output_text.delta after response.failed does not emit :text-delta"
    ;; Review 46: handle-codex-event! had no done? check at its top — only
    ;; emit-codex-error!/emit-codex-done! self-guarded, so a trailing
    ;; response.output_text.delta after response.failed/error still emitted
    ;; :text-delta (verified: events = [:start :error :text-delta]).
    ;; handle-codex-event! is now short-circuited on done? (set by
    ;; emit-codex-error! and emit-codex-done!), so a post-error trailing
    ;; event is a full no-op.
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_text.delta"
                             :delta "Hello"}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.failed"
                             :response {:error {:message "Overloaded"}}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_text.delta"
                             :delta "trailing"}) "\n\n")]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :text-delta :error] (mapv :type @events))
          "no :text-delta after the :error — the trailing response.output_text.delta is a full no-op once done")
      (is (not-any? #(= :done (:type %)) @events)
          "no synthetic :done — the :error event is terminal"))))
