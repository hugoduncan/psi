(ns psi.ai.providers.openai-completions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai]
   [psi.ai.providers.request-support :as request-support])
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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

(deftest openai-completions-adaptive-thinking-ignored-for-custom-providers-test
  ;; Locks the doc/custom-providers.md claim that :adaptive-thinking "is
  ;; ignored for OpenAI-compatible custom providers": expand-model carries the
  ;; field into every custom model map, but the chat-completions transport
  ;; never reads it — so an :openai-completions custom model with
  ;; :adaptive-thinking true must produce an unchanged OpenAI body (no
  ;; output_config/effort/adaptive leakage).
  (testing "adaptive-thinking on a custom :openai-completions model does not leak into the request body"
    (let [base-model {:id                 "custom-chat-model"
                      :name               "Custom Chat Model"
                      :provider           :custom-chat
                      :custom? true
                      :api                :openai-completions
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
                   (:body (#'openai/build-request convo base-model
                                                  {:api-key "sk-test" :thinking-level :high}))
                   true)
          adaptive (json/parse-string
                    (:body (#'openai/build-request convo (assoc base-model :adaptive-thinking true)
                                                   {:api-key "sk-test" :thinking-level :high}))
                    true)]
      (is (= plain adaptive)
          ":adaptive-thinking must not change the OpenAI-compatible request body")
      (is (nil? (:output_config adaptive)))
      (is (nil? (:thinking adaptive)))
      (is (= "high" (:reasoning_effort adaptive))
          "classic chat-completions reasoning shape is unchanged"))))

(deftest openai-provider-scoped-api-key-resolution-test
  ;; Mirrors the anthropic transport's provider-scoped resolve-api-key (review
  ;; 3): a custom :openai-completions provider must never silently receive the
  ;; global OPENAI_API_KEY — the exact cross-provider credential disclosure
  ;; class review 3 eliminated for :anthropic-messages. Custom providers fail
  ;; fast (or go keyless via :no-auth-header / recognized auth header among
  ;; custom :headers); only built-in OpenAI models fall back to the env var.
  (testing "custom provider never falls back to OPENAI_API_KEY env var (no cross-provider leak)"
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-should-never-leak")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-chat"
             (#'openai/build-request convo model {}))
            "OPENAI_API_KEY must not be used to satisfy a custom provider's request"))))

  (testing "custom-provider missing-auth error points at models.edn :auth and never hints at /login"
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
      (try
        (#'openai/build-request convo model {})
        (is false "expected build-request to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"models.edn" (ex-message e))
              "error must name the models.edn :auth remedy")
          (is (nil? (re-find #"/login" (ex-message e)))
              "custom-provider error must not hint at /login — OAuth login only exists for built-in providers")))))

  (testing "custom-provider missing-auth error suggests an env var name with hyphens normalized to underscores"
    (let [model {:id "my-proxy-model"
                 :name "My Proxy Model"
                 :provider :my-openai-proxy
                 :custom? true
                 :api :openai-completions
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
        (#'openai/build-request convo model {})
        (is false "expected build-request to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"env:MY_OPENAI_PROXY_API_KEY" (ex-message e))
              "env var suggestion must normalize kebab-case provider keys to underscores — bash identifiers cannot contain hyphens")
          (is (nil? (re-find #"MY-OPENAI-PROXY_API_KEY" (ex-message e)))
              "suggestion must not preserve hyphens from a kebab-case provider key")))))

  (testing "built-in openai model falls back to OPENAI_API_KEY env var"
    (let [model (models/get-model :gpt-5)
          convo (conv/create "sys")]
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-env-fallback-key")]
        (let [req (#'openai/build-request convo model {})]
          (is (= "Bearer sk-env-fallback-key" (get-in req [:headers "Authorization"]))
              "built-in OpenAI requests without an explicit key use OPENAI_API_KEY")))))

  (testing "keyless custom provider with :no-auth-header true builds a request without Authorization"
    (let [model {:id "local-chat-model"
                 :name "Local Chat Model"
                 :provider :local-chat
                 :custom? true
                 :api :openai-completions
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
          req   (#'openai/build-request convo model {:no-auth-header true})]
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-should-never-leak")]
        (is (nil? (get-in req [:headers "Authorization"]))
            "no Authorization when :no-auth-header is set — even with OPENAI_API_KEY present")
        (is (= "application/json" (get-in req [:headers "Content-Type"]))))))

  (testing "recognized auth header among custom headers (case-insensitive) implies keyless auth"
    (let [model {:id "local-chat-model"
                 :name "Local Chat Model"
                 :provider :local-chat
                 :custom? true
                 :api :openai-completions
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
          req   (#'openai/build-request convo model {:headers {"Authorization" "Bearer local-token"}})
          headers (:headers req)]
      (is (= "Bearer local-token" (get headers "Authorization"))
          "custom authorization header auth is preserved")
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-should-never-leak")]
        (is (= "Bearer local-token" (get headers "Authorization"))
            "env key must not replace the custom auth header"))))

  (testing "incidental custom headers with a blank key fast-fail (no env fallback)"
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-should-never-leak")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider custom-chat"
             (#'openai/build-request convo model {:headers {"X-Client" "psi"}}))
            "incidental headers must not imply keyless — a blank key still fast-fails instead of leaking the env key")))))

(deftest custom-provider-named-openai-not-builtin-test
  ;; Review 14: built-in detection is by provider NAME, so a custom models.edn
  ;; provider literally named "openai" was classified built-in and defeated
  ;; the provider-scoped guarantee — an unset configured key silently fell
  ;; back to OPENAI_API_KEY (sent to the third-party endpoint). Custom models
  ;; now carry `:custom? true` (set by expand-model at parse time); the shared
  ;; resolve-api-key refuses them, so a custom provider named "openai" gets
  ;; the same provider-scoped treatment as any other custom name.
  (testing "custom provider named \"openai\" never falls back to OPENAI_API_KEY"
    (let [model {:id "not-a-builtin"
                 :name "Custom OpenAI-Named Provider"
                 :provider :openai
                 :custom? true
                 :api :openai-completions
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
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-should-never-leak")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider openai"
             (#'openai/build-request convo model {}))
            "OPENAI_API_KEY must not be used to satisfy a custom provider named \"openai\"")))))

(deftest configured-key-plus-recognized-auth-header-interplay-test
  ;; Review 11: a custom :headers map carrying a recognized auth header name
  ;; silently replaces/duplicates the configured :api-key — untested for both
  ;; transports. OpenAI build-request merges custom headers LAST, so a custom
  ;; Authorization header silently REPLACES the resolved bearer key; a custom
  ;; X-API-Key header coexists with the configured bearer key (server picks by
  ;; case-insensitive header merge). Documented in doc/custom-providers.md —
  ;; don't mix them.
  (testing "custom Authorization header replaces the resolved bearer key"
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
          req   (#'openai/build-request convo model {:api-key "configured-key"
                                                     :headers {"Authorization" "Bearer custom"}})]
      (is (= "Bearer custom" (get-in req [:headers "Authorization"]))
          "custom Authorization header replaces the resolved bearer key — the configured key is not sent")))

  (testing "configured key + custom X-API-Key header sends both auth headers"
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
          req   (#'openai/build-request convo model {:api-key "configured-key"
                                                     :headers {"X-API-Key" "other-key"}})]
      (is (= "Bearer configured-key" (get-in req [:headers "Authorization"]))
          "configured api-key still sent as the bearer Authorization header")
      (is (= "other-key" (get-in req [:headers "X-API-Key"]))
          "custom X-API-Key header merged in as-is — duplicate auth header on the wire")))

  (testing "configured key + lowercase authorization custom header sends BOTH authorization headers"
    ;; Review 14: the merge is on equal string keys — a custom header whose
    ;; name is the exact lowercase "authorization" does NOT collide with the
    ;; base "Authorization" (capital A), so it DUPLICATES beside the resolved
    ;; bearer key (the reverse of the anthropic transport's exact-case
    ;; x-api-key replace). The doc guidance is case-dependent: exact-case
    ;; replaces on anthropic, duplicates on openai.
    (let [model {:id "custom-chat-model"
                 :name "Custom Chat Model"
                 :provider :custom-chat
                 :custom? true
                 :api :openai-completions
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
          req   (#'openai/build-request convo model {:api-key "configured-key"
                                                     :headers {"authorization" "Bearer custom"}})]
      (is (= "Bearer configured-key" (get-in req [:headers "Authorization"]))
          "resolved bearer key still sent as the base Authorization header")
      (is (= "Bearer custom" (get-in req [:headers "authorization"]))
          "lowercase authorization custom header merged in as-is — duplicate authorization header on the wire"))))

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
               :custom? true
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
      (let [req  (#'openai/build-request convo model {:thinking-level :off
                                                      :no-auth-header true})
            body (json/parse-string (:body req) true)]
        (is (nil? (:reasoning_effort body)))
        (is (= {:enable_thinking false}
               (:chat_template_kwargs body)))))

    (testing "thinking on leaves chat_template_kwargs unset for local models"
      (let [req  (#'openai/build-request convo model {:thinking-level :medium
                                                      :no-auth-header true})
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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
                 :custom? true
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
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
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

(deftest completions-sse-error-event-emits-error-and-terminates-test
  (testing "a mid-stream OpenAI SSE error chunk emits :error and terminates"
    ;; Review 43: an error chunk ({"error": {...}} — no :choices) previously
    ;; no-oped in process-chat-sse-line!: no :error event, no terminal :done,
    ;; hanging the turn until the idle timeout — the same silent-drop class
    ;; fixed for the anthropic transport's "error" SSE event.
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "hello"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                  "data: " (json/generate-string
                            {:error {:message "The server had an error while processing your request."
                                     :type "server_error"
                                     :code "server_error"}}) "\n\n"
                  "data: [DONE]\n\n")]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (some? err) "SSE error chunk must surface as an :error event")
        (is (= "The server had an error while processing your request."
               (:error-message err))
            "error message extracted from the chunk's error body")
        (is (nil? (:http-status err))
            "no numeric http-status in the chunk → no status suffix")
        (is (= {:error {:message "The server had an error while processing your request."
                        :type "server_error"
                        :code "server_error"}}
               (:body err))
            "raw chunk body preserved")
        (is (not-any? #(= :done (:type %)) @events)
            "no :done after a mid-stream error — the :error event terminates the turn")))))

(deftest completions-non-2xx-response-map-surfaces-body-message-test
  (let [model  (models/get-model :gpt-5)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "hello"))
        events (atom [])]
    (let [response-fn (fn [_]
                        {:status 400
                         :headers {"x-request-id" "req_oai_400"}
                         :body (stream-body
                                (json/generate-string
                                 {:error {:message "invalid request payload"}}))})
          http-client (http-boundary/nullable [response-fn response-fn])]
      ((:stream openai/provider)
       convo model {:http-boundary http-client
                    :api-key "sk-test"}
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
