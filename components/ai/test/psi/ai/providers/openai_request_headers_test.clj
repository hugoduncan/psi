(ns psi.ai.providers.openai-request-headers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream]))

(defn- stream-body
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest completions-request-and-reply-capture-identity-test
  (testing "built-in OpenAI completions captures preserve provider and api identity"
    (let [model           (models/get-model :gpt-5)
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
          reply-captures  (atom [])
          sse             (str
                           "data: " (json/generate-string
                                     {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                           "data: " (json/generate-string
                                     {:choices [{:delta {:content "Hello"}}]}) "\n\n"
                           "data: " (json/generate-string
                                     {:choices [{:finish_reason "stop"}]
                                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"
                      :on-provider-request  #(reset! request-capture %)
                      :on-provider-response #(swap! reply-captures conj %)}
         (fn [_ev] nil)))

      (is (= :openai (:provider @request-capture)))
      (is (= :openai-completions (:api @request-capture)))
      (is (= (:id model)
             (get-in @request-capture [:request :body :model])))
      (is (pos? (count @reply-captures)))
      (is (every? #(= :openai (:provider %)) @reply-captures))
      (is (every? #(= :openai-completions (:api %)) @reply-captures))
      (is (some #(= "Hello"
                    (get-in % [:event :choices 0 :delta :content]))
                @reply-captures)))

    (testing "custom OpenAI-compatible completions captures preserve selected provider identity"
      (let [model           {:id                 "local-completions"
                             :name               "Local Completions"
                             :provider           :local
                             :custom?            true
                             :api                :openai-completions
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
            reply-captures  (atom [])
            sse             (str
                             "data: " (json/generate-string
                                       {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                             "data: " (json/generate-string
                                       {:choices [{:delta {:content "Hello"}}]}) "\n\n"
                             "data: " (json/generate-string
                                       {:choices [{:finish_reason "stop"}]
                                        :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
        (with-redefs [http/post (fn [_url _req]
                                  {:body (stream-body sse)})]
          ((:stream openai/provider)
           convo model {:api-key "sk-test"
                        :on-provider-request  #(reset! request-capture %)
                        :on-provider-response #(swap! reply-captures conj %)}
           (fn [_ev] nil)))

        (is (= :local (:provider @request-capture)))
        (is (= :openai-completions (:api @request-capture)))
        (is (= "local-completions"
               (get-in @request-capture [:request :body :model])))
        (is (pos? (count @reply-captures)))
        (is (every? #(= :local (:provider %)) @reply-captures))
        (is (every? #(= :openai-completions (:api %)) @reply-captures))
        (is (some #(= "Hello"
                      (get-in % [:event :choices 0 :delta :content]))
                  @reply-captures))))))

(deftest custom-header-auth-redacted-in-captures-test
  ;; Review 11: transport/redact-request-headers redacted only exact-case
  ;; "Authorization"/"chatgpt-account-id" — the review-10 keyless custom-header
  ;; auth patterns on :openai-completions (:headers {"X-API-Key" "local-key"}
  ;; or mixed-case authorization) leaked the secret verbatim into the
  ;; :on-provider-request capture payload. Redaction is now case-insensitive
  ;; and covers x-api-key (mirroring the anthropic transport's review-7
  ;; find-header fix).
  (testing "mixed-case X-API-Key custom header is redacted in :on-provider-request captures"
    (let [model           {:id                 "local-completions"
                           :name               "Local Completions"
                           :provider           :local
                           :custom?            true
                           :api                :openai-completions
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
                                     {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                           "data: [DONE]\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:headers {"X-API-Key" "local-key"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= "***REDACTED***"
             (get-in @request-capture [:request :headers "X-API-Key"]))
          "mixed-case X-API-Key must be redacted in the :on-provider-request payload")))

  (testing "lowercase authorization custom header is redacted in :on-provider-request captures"
    (let [model           {:id                 "local-completions"
                           :name               "Local Completions"
                           :provider           :local
                           :custom?            true
                           :api                :openai-completions
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
                                     {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                           "data: [DONE]\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:headers {"authorization" "local-token"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= "Bearer ***REDACTED***"
             (get-in @request-capture [:request :headers "authorization"]))
          "lowercase authorization header must be redacted via redact-authorization")))

  (testing "differently-cased duplicate Authorization headers are ALL redacted in captures"
    ;; Review 19: redact-headers redacted only the FIRST case-insensitive
    ;; match per auth header name, so a wire request carrying both
    ;; "Authorization" (resolved bearer key) and a lowercase custom
    ;; "authorization" header leaked the lowercase one VERBATIM into the
    ;; :on-provider-request capture. Every case-insensitive match must be
    ;; redacted so the CHANGELOG "never persist verbatim" claim holds.
    (let [model           {:id                 "local-completions"
                           :name               "Local Completions"
                           :provider           :local
                           :custom?            true
                           :api                :openai-completions
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
                                     {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                           "data: [DONE]\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key "sk-configured"
                      :headers {"authorization" "local-token"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= "Bearer ***REDACTED***"
             (get-in @request-capture [:request :headers "Authorization"]))
          "configured Authorization must be redacted")
      (is (= "Bearer ***REDACTED***"
             (get-in @request-capture [:request :headers "authorization"]))
          "differently-cased authorization duplicate must ALSO be redacted — no verbatim secret in the capture"))))

(deftest redact-authorization-length-excludes-bearer-prefix-test
  ;; Review 13: openai/transport's redact-authorization counted the WHOLE
  ;; Authorization value including the "Bearer " prefix in the (len=N)
  ;; metadata, unlike the anthropic transport which strips ^Bearer\s+ first —
  ;; a capture of "Bearer abc…" recorded (len=N) with N = 7 + token length.
  ;; The redactor now mirrors the anthropic one: strip the prefix, then count.
  (testing "capture of a Bearer-prefixed authorization header reports length excluding the prefix"
    (let [model           {:id                 "local-completions"
                           :name               "Local Completions"
                           :provider           :local
                           :custom?            true
                           :api                :openai-completions
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
          token           (apply str (repeat 30 "x"))
          sse             (str
                           "data: " (json/generate-string
                                     {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                           "data: [DONE]\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:headers {"authorization" (str "Bearer " token)}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= (str "Bearer ***REDACTED*** (len=" (count token) ")")
             (get-in @request-capture [:request :headers "authorization"]))
          "len metadata must count the token only, excluding the Bearer prefix"))))

(deftest no-auth-header-skips-authorization-test
  (let [convo (-> (conv/create "sys") (conv/add-user-message "hi"))
        model (models/get-model :gpt-4o)]
    (testing "no-auth-header omits Authorization"
      (let [req (#'openai/build-request convo model {:no-auth-header true})]
        (is (not (contains? (:headers req) "Authorization")))
        (is (= "application/json" (get-in req [:headers "Content-Type"]))))))

  (testing "default includes Authorization"
    (let [convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          model (models/get-model :gpt-4o)
          req (#'openai/build-request convo model {:api-key "sk-test"})]
      (is (contains? (:headers req) "Authorization"))
      (is (= "Bearer sk-test" (get-in req [:headers "Authorization"]))))))

(deftest custom-headers-merged-into-request-test
  (let [convo (-> (conv/create "sys") (conv/add-user-message "hi"))
        model (models/get-model :gpt-4o)]
    (testing "custom headers merge into request"
      (let [req (#'openai/build-request convo model
                                        {:api-key "sk-test"
                                         :headers {"X-Custom" "value" "X-Project" "psi"}})]
        (is (= "value" (get-in req [:headers "X-Custom"])))
        (is (= "psi" (get-in req [:headers "X-Project"])))
        (is (= "Bearer sk-test" (get-in req [:headers "Authorization"])))
        (is (= "application/json" (get-in req [:headers "Content-Type"])))))))

(deftest speed-mode-fast-adds-service-tier-flex-test
  ;; OpenAI chat-completions maps psi :fast to the alternate flex service tier.
  (let [convo (-> (conv/create "sys") (conv/add-user-message "hi"))
        model (models/get-model :gpt-4o)]
    (testing "fast speed mode adds service_tier flex"
      (let [req  (#'openai/build-request convo model {:api-key "sk-test" :speed-mode :fast})
            body (json/parse-string (:body req) true)]
        (is (= "flex" (:service_tier body)))))

    (testing "normal and nil speed modes omit service_tier"
      (doseq [opts [{:api-key "sk-test"}
                    {:api-key "sk-test" :speed-mode :normal}]]
        (let [req  (#'openai/build-request convo model opts)
              body (json/parse-string (:body req) true)]
          (is (not (contains? body :service_tier))))))))
