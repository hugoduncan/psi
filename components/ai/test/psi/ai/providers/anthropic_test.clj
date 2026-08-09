(ns psi.ai.providers.anthropic-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.ai.providers.anthropic.request-schema :as request-schema]
   [psi.ai.providers.request-support :as request-support]))

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
          convo (conv/create "sys")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider minimax"
           (#'anthropic/build-request convo model {})))))

  (testing "custom-provider missing-auth error points at models.edn :auth and never hints at /login"
    (let [model {:id "MiniMax-M2.7"
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
          convo (conv/create "sys")]
      (try
        (#'anthropic/build-request convo model {})
        (is false "expected build-request to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"models.edn" (ex-message e))
              "error must name the models.edn :auth remedy")
          (is (nil? (re-find #"/login" (ex-message e)))
              "custom-provider error must not hint at /login — OAuth login only exists for built-in providers")))))

  (testing "custom-provider missing-auth error suggests an env var name with hyphens normalized to underscores"
    (let [model {:id "my-proxy-model"
                 :name "My Proxy Model"
                 :provider :my-anthropic-proxy
                 :custom? true
                 :api :anthropic-messages
                 :base-url "https://my-proxy.example.com"
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
        (#'anthropic/build-request convo model {})
        (is false "expected build-request to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"env:MY_ANTHROPIC_PROXY_API_KEY" (ex-message e))
              "env var suggestion must normalize kebab-case provider keys to underscores — bash identifiers cannot contain hyphens")
          (is (nil? (re-find #"MY-ANTHROPIC-PROXY_API_KEY" (ex-message e)))
              "suggestion must not preserve hyphens from a kebab-case provider key")))))

  (testing "custom provider never falls back to ANTHROPIC_API_KEY env var (no cross-provider leak)"
    (let [model {:id "deepseek-v4-flash"
                 :name "DeepSeek V4 Flash"
                 :provider :deepseek
                 :custom? true
                 :api :anthropic-messages
                 :base-url "https://api.deepseek.com/anthropic"
                 :supports-reasoning true
                 :supports-text true
                 :context-window 1000000
                 :max-tokens 384000}
          convo (conv/create "sys")]
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-ant-should-never-leak")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider deepseek"
             (#'anthropic/build-request convo model {}))
            "ANTHROPIC_API_KEY must not be used to satisfy a custom provider's request"))))

  (testing "built-in anthropic model falls back to ANTHROPIC_API_KEY env var"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "sys")]
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-ant-env-fallback-key")]
        (let [req (#'anthropic/build-request convo model {})]
          (is (= "sk-ant-env-fallback-key" (get-in req [:headers "x-api-key"]))
              "built-in Anthropic requests without an explicit key use ANTHROPIC_API_KEY"))))))

(deftest anthropic-temperature-explicit-override-test
  (testing "explicit temperature override flows through to request body"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "sys")
          req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                        :temperature 1.0})
          body  (json/parse-string (:body req) true)]
      (is (= 1.0 (:temperature body))
          "explicit temperature 1.0 must appear in request body")))

  (testing "absent temperature uses provider default (0.7)"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "sys")
          req   (#'anthropic/build-request convo model {:api-key "test-key"})
          body  (json/parse-string (:body req) true)]
      (is (= 0.7 (:temperature body))
          "absent temperature must fall back to provider default 0.7"))))

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
          "oauth requests with thinking must include interleaved-thinking beta")
      (is (some? (re-find #"^claude-cli/" (get headers "user-agent")))
          "oauth requests present as the claude-cli user-agent")
      (is (= "cli" (get headers "x-app"))
          "oauth requests carry the x-app: cli header")))

  (testing "api-key requests do not carry the Claude Code CLI headers"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          headers (:headers (#'anthropic/build-request convo model {:api-key "sk-ant-api-key"}))]
      (is (nil? (get headers "user-agent")) "api-key requests must not spoof the claude-cli user-agent")
      (is (nil? (get headers "x-app")) "api-key requests must not carry x-app"))))

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

  (testing "xhigh effort maps to highest for adaptive thinking"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :xhigh
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (= "highest" (get-in body [:output_config :effort])))))

  (testing "effort override wins for adaptive thinking"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :high
                                                          :effort-override :xhigh
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)]
      (is (= "highest" (get-in body [:output_config :effort])))))

  (testing "non-xhigh effort override wins over a different thinking level"
    (let [model   (models/get-model :opus-4.7)
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :effort-override :high
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

(def ^:private deepseek-custom-provider-model
  "A custom-provider (non-catalog) Anthropic-compatible model map, shaped the
   way `psi.ai.user-models/expand-model` produces one from a `models.edn`
   DeepSeek entry with `:adaptive-thinking true`."
  {:id "deepseek-v4-flash"
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
   :cache-write-cost 0.14
   :locality :cloud
   :latency-tier :low
   :cost-tier :low})

(deftest build-request-adaptive-thinking-custom-provider-test
  (testing "a non-catalog custom-provider model map with :adaptive-thinking true emits the adaptive shape"
    (let [convo   (conv/create "sys")
          req     (#'anthropic/build-request convo deepseek-custom-provider-model
                                             {:thinking-level :high
                                              :api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "adaptive" (get-in body [:thinking :type])))
      (is (nil? (get-in body [:thinking :budget_tokens]))
          "budget_tokens must be absent for adaptive thinking")
      (is (= "high" (get-in body [:output_config :effort])))
      (is (nil? (:temperature body))
          "temperature must be absent for adaptive thinking models")
      (is (= "test-key" (get headers "x-api-key"))
          "api-key auth must use x-api-key from the configured key")
      (is (nil? (get headers "Authorization"))
          "no OAuth Authorization header for api-key auth")
      (is (some? (get headers "anthropic-version"))
          "anthropic-version header must be present")
      (is (nil? (get headers "anthropic-beta"))
          "no anthropic-beta header — adaptive thinking must not force interleaved-thinking beta")))

  (testing "thinking off — no thinking param, no output_config, no temperature"
    (let [convo (conv/create "sys")
          req   (#'anthropic/build-request convo deepseek-custom-provider-model
                                           {:thinking-level :off
                                            :api-key "test-key"})
          body  (json/parse-string (:body req) true)]
      (is (nil? (:thinking body)))
      (is (nil? (:output_config body)))
      (is (nil? (:temperature body))
          "temperature must be absent even with thinking off on adaptive models")))

  (testing "effort override alone (no active thinking level) emits no output_config"
    ;; request-body's effort is gated on (and thinking adaptive?), and
    ;; thinking-param requires an active :thinking-level (session default
    ;; :off), so /effort on an adaptive model with /thinking unset/off emits
    ;; neither :thinking nor :output_config — a silent no-op, documented in
    ;; doc/custom-providers.md (review 11).
    (let [convo (conv/create "sys")
          req   (#'anthropic/build-request convo deepseek-custom-provider-model
                                           {:effort-override :xhigh
                                            :api-key "test-key"})
          body  (json/parse-string (:body req) true)]
      (is (nil? (:thinking body))
          "no thinking param when no thinking level is active")
      (is (nil? (:output_config body))
          "no output_config when no thinking level is active — effort applies only with /thinking on"))))

(deftest build-request-classic-thinking-custom-provider-test
  ;; Docs advise DeepSeek users who need temperature to fall back to
  ;; :adaptive-thinking false / omitted, "relying on the classic
  ;; extended-thinking shape DeepSeek accepts". Lock that path for a
  ;; NON-catalog custom-provider model map (all existing budget_tokens shape
  ;; tests use built-in catalog models).
  (testing "non-catalog custom-provider model without :adaptive-thinking emits the classic extended-thinking shape"
    (let [convo   (conv/create "sys")
          model   (dissoc deepseek-custom-provider-model :adaptive-thinking)
          req     (#'anthropic/build-request convo model {:thinking-level :medium
                                                          :api-key "test-key"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= {:type "enabled" :budget_tokens 8000} (:thinking body))
          "classic extended-thinking shape for medium: type enabled + budget_tokens 8000")
      (is (nil? (:output_config body))
          "no output_config for the classic extended-thinking shape")
      (is (nil? (:temperature body))
          "temperature must be absent with extended thinking")
      (is (some? (re-find #"interleaved-thinking" (get headers "anthropic-beta")))
          "interleaved-thinking beta header required for the classic shape"))))

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

(deftest transform-messages-handles-inline-system-placement-test
  ;; Anthropic inline system messages are allowed only immediately after user messages.
  (testing "valid user-system tail is emitted"
    (let [convo {:messages [{:role :user :content {:kind :text :text "q"}}
                            {:role :system :content {:kind :text :text "Use short answers."}}]}
          out   (anthropic/transform-messages convo)]
      (is (= [{:role "user" :content [{:type "text" :text "q"}]}
              {:role "system" :content [{:type "text" :text "Use short answers."}]}]
             out))))

  (testing "invalid beginning, consecutive, and after-assistant systems are dropped"
    (let [convo {:messages [{:role :system :content {:kind :text :text "drop first"}}
                            {:role :user :content {:kind :text :text "q"}}
                            {:role :system :content {:kind :text :text "keep"}}
                            {:role :system :content {:kind :text :text "drop consecutive"}}
                            {:role :assistant :content {:kind :text :text "a"}}
                            {:role :system :content {:kind :text :text "drop after assistant"}}]}
          out   (anthropic/transform-messages convo)]
      (is (= ["user" "system" "assistant"] (mapv :role out)))
      (is (= "keep" (get-in out [1 :content 0 :text]))))))

(deftest anthropic-request-schema-accepts-inline-system-message-test
  ;; Local validation accepts valid inline system messages; placement is enforced separately.
  (testing "anthropic-request-schema-accepts-inline-system-message"
    (let [body {:model "claude-opus-4-8"
                :max_tokens 1024
                :messages [{:role "user"
                            :content [{:type "text" :text "q"}]}
                           {:role "system"
                            :content [{:type "text" :text "Use short answers."}]}]}]
      (is (= body (request-schema/validate-request-body! body))))))

(deftest anthropic-speed-mode-request-shaping-test
  ;; Fast speed mode is both a body parameter and a beta header; default modes omit both.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (testing "fast speed mode adds speed body key and beta header"
      (let [req  (#'anthropic/build-request convo model {:api-key "test-key"
                                                         :speed-mode :fast})
            body (json/parse-string (:body req) true)
            beta (get-in req [:headers "anthropic-beta"])]
        (is (= "fast" (:speed body)))
        (is (re-find #"fast-mode-2026-02-01" beta))))

    (testing "normal and nil speed modes omit speed body key and beta header"
      (doseq [opts [{:api-key "test-key"}
                    {:api-key "test-key" :speed-mode :normal}]]
        (let [req  (#'anthropic/build-request convo model opts)
              body (json/parse-string (:body req) true)
              beta (get-in req [:headers "anthropic-beta"])]
          (is (not (contains? body :speed)))
          (is (not (re-find #"fast-mode-2026-02-01" (or beta "")))))))))

(deftest build-request-custom-anthropic-beta-header-replaces-transport-betas-test
  ;; Review 22: build-request merges custom :headers over the base headers, so
  ;; a custom "anthropic-beta" header REPLACES the transport-generated beta
  ;; header on the first request — the transport's own betas (prompt-caching,
  ;; interleaved-thinking, fast-mode) are silently dropped from the wire. The
  ;; "don't mix" guidance covers auth headers only; this locks the beta-side
  ;; merge for :anthropic-messages custom providers (documented in
  ;; doc/custom-providers.md "Local servers and custom headers").
  (testing "custom anthropic-beta replaces the transport-generated beta header"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (-> (conv/create "sys") (conv/add-user-message "hi"))
          req     (#'anthropic/build-request convo model {:api-key "test-key"
                                                          :speed-mode :fast
                                                          :thinking-level :medium
                                                          :headers {"anthropic-beta" "custom-beta-1"}})
          beta    (get-in req [:headers "anthropic-beta"])]
      (is (= "custom-beta-1" beta)
          "custom anthropic-beta header must replace the transport betas on the wire")
      (is (not (re-find #"fast-mode-2026-02-01" (or beta "")))
          "fast-mode beta must be dropped — the custom header wins the merge")
      (is (not (re-find #"interleaved-thinking" (or beta "")))
          "interleaved-thinking beta must be dropped — the custom header wins the merge")))

  (testing "without a custom anthropic-beta header the transport betas are sent"
    (let [model (models/get-model :sonnet-4.6)
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                        :speed-mode :fast
                                                        :thinking-level :medium})
          beta  (get-in req [:headers "anthropic-beta"])]
      (is (re-find #"fast-mode-2026-02-01" beta))
      (is (re-find #"interleaved-thinking" beta)))))

;; ── non-streaming execute response mapping ──────────────────────────────────

(deftest execute-anthropic-preserves-tool-use-blocks-test
  ;; Review 57: the non-streaming execute response mapping previously kept
  ;; only "text" blocks (text-content-blocks) — a response containing a
  ;; tool_use block silently dropped the tool call while :stop-reason
  ;; :tool_use was preserved, so classify-assistant-message /
  ;; extract-tool-calls recorded :turn.outcome/stop and the tool call never
  ;; executed (reachable on the newly shipped DeepSeek provider via
  ;; response-mode :non-streaming sessions with tools; inconsistent with
  ;; BOTH the :openai-completions sibling and the anthropic transport's own
  ;; streaming accumulator). The mapping now mirrors those paths: tool_use →
  ;; :tool-call (id/name/arguments, :input JSON-encoded so downstream
  ;; tool-args/parse-args parses it), thinking → :thinking, text → :text,
  ;; wire order preserved.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys") (conv/add-user-message "What is the weather in Paris?"))
        body  {:content [{:type "tool_use"
                          :id "toolu_01"
                          :name "get_weather"
                          :input {:location "Paris"}}
                         {:type "text" :text "Let me check"}]
               :stop_reason "tool_use"
               :usage {:input_tokens 12 :output_tokens 8}}
        result (with-redefs [http/post (fn [_url _req]
                                         {:status 200
                                          :body (json/generate-string body)})]
                 ((:execute anthropic/provider)
                  convo model {:api-key "test-key"}))
        content (:content (:assistant-message result))]
    (is (= :tool_use (:stop-reason (:assistant-message result)))
        "stop-reason :tool_use is preserved")
    (is (= [{:type :tool-call
             :id "toolu_01"
             :name "get_weather"
             :arguments "{\"location\":\"Paris\"}"}
            {:type :text :text "Let me check"}]
           content)
        "tool_use block maps to :tool-call with id/name/JSON-string arguments, wire order preserved")))

(deftest execute-anthropic-preserves-thinking-blocks-test
  ;; Review 57 informational note: the old text-content-blocks mapping also
  ;; dropped thinking blocks on the non-streaming execute path while the
  ;; streaming accumulator keeps them in the final content — the same
  ;; mapping now preserves them (:thinking with text/signature), matching
  ;; thinking-blocks-in-order.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys") (conv/add-user-message "Think then answer"))
        body  {:content [{:type "thinking"
                          :thinking "Let me reason about this step by step."
                          :signature "sig_01"}
                         {:type "text" :text "The answer is 42."}]
               :stop_reason "end_turn"
               :usage {:input_tokens 12 :output_tokens 8}}
        result (with-redefs [http/post (fn [_url _req]
                                         {:status 200
                                          :body (json/generate-string body)})]
                 ((:execute anthropic/provider)
                  convo model {:api-key "test-key"}))
        content (:content (:assistant-message result))]
    (is (= [{:type :thinking
             :text "Let me reason about this step by step."
             :signature "sig_01"}
            {:type :text :text "The answer is 42."}]
           content)
        "thinking block maps to :thinking with text/signature, wire order preserved")))
