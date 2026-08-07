(ns psi.ai.providers.anthropic-auth-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.ai.providers.request-support :as request-support]))

;; ── build-request auth: keyless providers, header interplay, OAuth gating ──
;; Split out of anthropic_test.clj (review 14 finalization — the accumulated
;; review-driven test additions pushed that file past the 800-line
;; commit-checks limit). These deftests form the cohesive "anthropic provider
;; auth" unit: keyless/no-auth-header requests, configured-key + custom-header
;; interplay (case-dependence), OAuth content-sniff gating to built-in
;; models, and the review-14 custom-provider origin-tag (custom? true)
;; built-in detection gap.

(deftest build-request-no-auth-header-custom-provider-test
  (testing "keyless custom provider with :no-auth-header true builds a request without auth headers"
    (let [model {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:no-auth-header true})
          headers (:headers req)]
      (is (nil? (get headers "x-api-key"))
          "no x-api-key when :no-auth-header is set")
      (is (nil? (get headers "Authorization"))
          "no Authorization when :no-auth-header is set")
      (is (some? (get headers "anthropic-version"))
          "anthropic-version header still present")))

  (testing "keyless custom provider with custom-header auth builds a request without a key"
    (let [model {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:no-auth-header true
                                                          :headers {"X-API-Key" "local-key"}})
          headers (:headers req)]
      (is (= "local-key" (get headers "X-API-Key"))
          "custom header auth is preserved")
      (is (nil? (get headers "x-api-key"))
          "no x-api-key when auth comes from custom headers")
      (is (nil? (get headers "Authorization"))
          "no Authorization when auth comes from custom headers")))

  (testing "headers-only auth (no :no-auth-header) builds a request without a key"
    (let [model {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:headers {"X-API-Key" "header-key"}})
          headers (:headers req)]
      (is (= "header-key" (get headers "X-API-Key"))
          "custom header auth is preserved")
      (is (nil? (get headers "x-api-key"))
          "no x-api-key when auth comes entirely from custom headers")
      (is (nil? (get headers "Authorization"))
          "no Authorization when auth comes entirely from custom headers")))

  (testing "configured key plus custom headers still sends both"
    (let [model {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:api-key "test-key"
                                                          :headers {"X-Client" "psi"}})
          headers (:headers req)]
      (is (= "test-key" (get headers "x-api-key"))
          "configured api-key still sent alongside custom headers")
      (is (= "psi" (get headers "X-Client"))
          "custom headers merged in")))

  (testing "no-auth-header is honoured for built-in models too"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "sys")
          headers (:headers (#'anthropic/build-request convo model {:no-auth-header true}))]
      (is (nil? (get headers "x-api-key")))
      (is (nil? (get headers "Authorization")))))

  (testing "incidental custom headers with a blank key fast-fail with the missing-key error"
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
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider deepseek"
           (#'anthropic/build-request convo model {:api-key ""
                                                   :headers {"X-Client" "psi"}}))
          "incidental headers must not imply keyless auth — a blank configured key still fast-fails")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider deepseek"
           (#'anthropic/build-request convo model {:headers {"X-Client" "psi"}}))
          "incidental headers with no configured key still fast-fail")))

  (testing "recognized auth header among custom headers (case-insensitive) implies keyless auth"
    (let [model {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:headers {"Authorization" "Bearer token"}})
          headers (:headers req)]
      (is (= "Bearer token" (get headers "Authorization"))
          "authorization header auth is preserved")
      (is (nil? (get headers "x-api-key"))
          "no x-api-key when auth comes from an authorization header"))))

(deftest configured-key-plus-recognized-auth-header-interplay-test
  ;; Review 11: a custom :headers map carrying a recognized auth header name
  ;; silently replaces/duplicates the configured :api-key — untested for both
  ;; transports. Anthropic build-request merges custom headers OVER the base
  ;; headers, so :headers {"X-API-Key" "other"} with a configured key sends
  ;; BOTH the lowercase x-api-key (configured) and X-API-Key (custom) on the
  ;; wire; the server picks by case-insensitive header merge. Documented in
  ;; doc/custom-providers.md — don't mix them.
  (testing "configured key + custom X-API-Key header sends both (case-insensitive duplicate)"
    (let [model   {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:api-key "configured-key"
                                                          :headers {"X-API-Key" "other-key"}})
          headers (:headers req)]
      (is (= "configured-key" (get headers "x-api-key"))
          "configured api-key still sent as the lowercase x-api-key header")
      (is (= "other-key" (get headers "X-API-Key"))
          "custom X-API-Key header merged in as-is — duplicate auth header on the wire")))

  (testing "configured key + custom Authorization header sends both auth headers"
    (let [model   {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:api-key "configured-key"
                                                          :headers {"Authorization" "Bearer custom"}})
          headers (:headers req)]
      (is (= "configured-key" (get headers "x-api-key"))
          "configured api-key still sent as x-api-key")
      (is (= "Bearer custom" (get headers "Authorization"))
          "custom Authorization header merged in as-is")))

  (testing "configured key + EXACT-case x-api-key custom header REPLACES the configured key"
    ;; Review 14: the merge is on equal string keys — a custom header whose
    ;; name is the exact lowercase "x-api-key" collides with the base header,
    ;; so the configured credential is silently DROPPED (not duplicated, as
    ;; with the mixed-case X-API-Key variant). The doc guidance is
    ;; case-dependent: exact-case replaces, mixed-case duplicates.
    (let [model   {:id "local-proxy"
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
          convo   (conv/create "sys")
          req     (#'anthropic/build-request convo model {:api-key "configured-key"
                                                          :headers {"x-api-key" "other-key"}})
          headers (:headers req)]
      (is (= "other-key" (get headers "x-api-key"))
          "exact-case x-api-key custom header wins the merge — the configured key is silently dropped"))))

(def ^:private claude-code-system
  "You are Claude Code, Anthropic's official CLI for Claude.")

(deftest build-request-oauth-injects-claude-code-system-test
  (testing "oauth requests prepend the Claude Code identity as the first system block"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "Custom Psi system prompt.")
          req   (#'anthropic/build-request convo model {:api-key "sk-ant-oat-test-token"})
          system (:system (json/parse-string (:body req) true))]
      (is (vector? system) "oauth system must be block form to carry the identity first")
      (is (= claude-code-system (:text (first system)))
          "first system block must be the exact Claude Code identity")
      (is (= "Custom Psi system prompt." (:text (second system)))
          "the caller's system prompt follows the injected identity")))

  (testing "api-key requests are unchanged — no Claude Code identity injected"
    (let [model (models/get-model :sonnet-4.6)
          convo (conv/create "Custom Psi system prompt.")
          req   (#'anthropic/build-request convo model {:api-key "sk-ant-api-test-key"})
          system (:system (json/parse-string (:body req) true))]
      (is (= "Custom Psi system prompt." system)
          "api-key system prompt is sent as-is, without the Claude Code identity"))))

(deftest build-request-oauth-gated-on-builtin-models-test
  ;; Review 11: oauth-api-key? content-sniffs the resolved key with no
  ;; provider gate, so a custom :anthropic-messages provider whose key merely
  ;; contains "sk-ant-oat" was treated as an OAuth request — Authorization:
  ;; Bearer + claude-cli user-agent + x-app headers, the claude-code/oauth/
  ;; prompt-caching-scope betas, AND the Claude Code system prompt — all sent
  ;; to the third-party endpoint. OAuth applies only to built-in Anthropic
  ;; models (:provider nil or :anthropic); custom providers always use
  ;; x-api-key auth.
  (testing "custom provider with an sk-ant-oat… key is NOT treated as OAuth"
    (let [model   {:id "deepseek-v4-flash"
                   :name "DeepSeek V4 Flash"
                   :provider :deepseek
                   :custom? true
                   :api :anthropic-messages
                   :base-url "https://api.deepseek.com/anthropic"
                   :supports-reasoning true
                   :supports-text true
                   :context-window 1000000
                   :max-tokens 384000}
          convo   (conv/create "Custom Psi system prompt.")
          req     (#'anthropic/build-request convo model {:api-key "sk-ant-oat-custom-provider-token"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "sk-ant-oat-custom-provider-token" (get headers "x-api-key"))
          "custom providers always use x-api-key auth")
      (is (nil? (get headers "Authorization"))
          "no OAuth Authorization: Bearer header for a custom provider")
      (is (nil? (get headers "user-agent"))
          "no claude-cli user-agent spoofing for a custom provider")
      (is (nil? (get headers "x-app"))
          "no x-app cli header for a custom provider")
      (is (nil? (get headers "anthropic-beta"))
          "no OAuth betas (claude-code/oauth/prompt-caching-scope) for a custom provider")
      (is (= "Custom Psi system prompt." (:system body))
          "custom provider system prompt is sent as-is — no Claude Code identity prepended")))

  (testing "built-in models still get OAuth treatment for sk-ant-oat keys"
    (let [model   (models/get-model :sonnet-4.6)
          convo   (conv/create "Custom Psi system prompt.")
          req     (#'anthropic/build-request convo model {:api-key "sk-ant-oat-test-token"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "Bearer sk-ant-oat-test-token" (get headers "Authorization"))
          "built-in models with an OAuth token still use the OAuth auth path")
      (is (= "claude-cli/2.1.75" (get headers "user-agent"))
          "built-in OAuth requests still present as the Claude Code CLI")
      (is (= claude-code-system (:text (first (:system body))))
          "built-in OAuth requests still prepend the Claude Code identity"))))

(deftest custom-provider-named-anthropic-not-builtin-test
  ;; Review 14: built-in detection is by provider NAME, so a custom models.edn
  ;; provider literally named "anthropic" was classified built-in and defeated
  ;; the provider-scoped guarantees — an unset configured key silently fell
  ;; back to ANTHROPIC_API_KEY (sent to the third-party endpoint) and an
  ;; sk-ant-oat key triggered the Claude Code OAuth headers/system prompt.
  ;; Custom models now carry `:custom? true` (set by expand-model at parse
  ;; time); builtin-anthropic? refuses them, so a custom provider named
  ;; "anthropic" gets the same provider-scoped treatment as any other custom
  ;; name.
  (testing "custom provider named \"anthropic\" never falls back to ANTHROPIC_API_KEY"
    (let [model {:id "not-a-builtin"
                 :name "Custom Anthropic-Named Provider"
                 :provider :anthropic
                 :custom? true
                 :api :anthropic-messages
                 :base-url "https://third-party.example"
                 :supports-reasoning true
                 :supports-text true
                 :context-window 128000
                 :max-tokens 16384}
          convo (conv/create "sys")]
      (with-redefs [request-support/getenv (fn [_] "sk-ant-should-never-leak")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing API key for provider anthropic"
             (#'anthropic/build-request convo model {}))
            "ANTHROPIC_API_KEY must not be used to satisfy a custom provider named \"anthropic\""))))

  (testing "custom provider named \"anthropic\" with an sk-ant-oat key is NOT treated as OAuth"
    (let [model {:id "not-a-builtin"
                 :name "Custom Anthropic-Named Provider"
                 :provider :anthropic
                 :custom? true
                 :api :anthropic-messages
                 :base-url "https://third-party.example"
                 :supports-reasoning true
                 :supports-text true
                 :context-window 128000
                 :max-tokens 16384}
          convo   (conv/create "Custom Psi system prompt.")
          req     (#'anthropic/build-request convo model {:api-key "sk-ant-oat-custom-provider-token"})
          body    (json/parse-string (:body req) true)
          headers (:headers req)]
      (is (= "sk-ant-oat-custom-provider-token" (get headers "x-api-key"))
          "custom providers named \"anthropic\" still use x-api-key auth")
      (is (nil? (get headers "Authorization"))
          "no OAuth Authorization: Bearer header")
      (is (nil? (get headers "user-agent"))
          "no claude-cli user-agent spoofing")
      (is (nil? (get headers "x-app"))
          "no x-app cli header")
      (is (nil? (get headers "anthropic-beta"))
          "no OAuth betas")
      (is (= "Custom Psi system prompt." (:system body))
          "no Claude Code identity prepended for a custom provider named \"anthropic\""))))

;; ── Adaptive thinking (Opus 4.7+) ───────────────────────────────────────────

