(ns psi.ai.providers.request-support-test
  "Direct-call tests for the shared provider request-support helpers
   (psi.ai.providers.request-support), used by all three transports
   (:anthropic-messages, :openai-completions, :openai-codex-responses)."
  (:require
   [psi.ai.providers.environment-boundary :as environment-boundary]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.providers.request-support :as request-support]))

(def ^:private anthropic-config
  {:builtin-provider    :anthropic
   :env-var             "ANTHROPIC_API_KEY"
   :builtin-missing-msg "Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic."})

(deftest resolve-api-key-keyless-contract-test
  ;; Review 22: resolve-api-key's keyless early-return used to test only
  ;; (:no-auth-header options), not the shared no-auth? predicate — the two
  ;; keyless definitions could drift, and the public function threw for a
  ;; headers-auth keyless config when called directly. The keyless contract
  ;; now lives in one predicate (no-auth?), so a direct caller is safe.
  (testing "keyless options resolve to nil without throwing"
    (let [model {:provider :deepseek :custom? true}]
      (is (nil? (request-support/resolve-api-key model
                                                 {:no-auth-header true}
                                                 anthropic-config))
          "explicit :no-auth-header is keyless → nil")
      (is (nil? (request-support/resolve-api-key model
                                                 {:headers {"X-API-Key" "local-key"}}
                                                 anthropic-config))
          "recognized auth header among custom :headers with no configured key is keyless → nil")
      (is (nil? (request-support/resolve-api-key model
                                                 {:headers {"authorization" "Bearer local-token"}}
                                                 anthropic-config))
          "lowercase authorization header is keyless → nil")))

  (testing "non-keyless options with a blank key still fail fast"
    (let [model {:provider :deepseek :custom? true}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider deepseek"
           (request-support/resolve-api-key model {} anthropic-config)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider deepseek"
           (request-support/resolve-api-key model {:api-key ""} anthropic-config))
          "explicit blank key is not keyless → throws")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing API key for provider deepseek"
           (request-support/resolve-api-key model
                                            {:headers {"X-Client" "psi"}}
                                            anthropic-config))
          "incidental custom headers do not imply keyless → throws")))

  (testing "built-in models still fall back to the env var"
    (let [model {:provider :anthropic}]
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [environment (environment-boundary/nullable {"ANTHROPIC_API_KEY" "sk-ant-env-fallback-key"})]
        (is (= "sk-ant-env-fallback-key"
               (request-support/resolve-api-key model
                                                {:environment-boundary environment}
                                                anthropic-config))
            "built-in model with no configured key uses the env fallback")
        (is (= ["ANTHROPIC_API_KEY"] (environment-boundary/reads environment)))
        (is (= :anthropic (:provider model))))))

  (testing "configured key passes through for non-keyless options"
    (let [model {:provider :deepseek :custom? true}]
      (is (= "sk-deepseek-configured"
             (request-support/resolve-api-key model
                                              {:api-key "sk-deepseek-configured"}
                                              anthropic-config))))))

(deftest no-auth?-predicate-test
  (testing "no-auth? matches the request builders' keyless computation"
    (is (true? (request-support/no-auth? {:no-auth-header true})))
    (is (true? (request-support/no-auth? {:headers {"X-API-Key" "k"}})))
    (is (true? (request-support/no-auth? {:headers {"authorization" "Bearer k"}})))
    (is (not (request-support/no-auth? {:headers {"X-Client" "psi"}})))
    (is (not (request-support/no-auth? {})))
    (is (not (request-support/no-auth? {:headers {"X-API-Key" "k"}
                                        :api-key "configured"}))
        "a configured key alongside a recognized auth header is NOT keyless"))
  (testing "auth-header? recognition is case-insensitive"
    (is (true? (request-support/auth-header? "X-API-Key")))
    (is (true? (request-support/auth-header? "authorization")))
    (is (true? (request-support/auth-header? :x-api-key)))
    (is (false? (request-support/auth-header? "X-Client")))))

(deftest builtin?-origin-tag-gate-test
  ;; Review 24: builtin? is the review-14 origin-tag gate — the predicate
  ;; that decides env-var fallback / OAuth treatment, and the most
  ;; security-relevant helper in the namespace. Previously only exercised
  ;; indirectly through transport tests.
  (testing ":custom? absent → built-in classification by provider name/nil"
    (is (true? (request-support/builtin? {:provider :anthropic} :anthropic)))
    (is (true? (request-support/builtin? {:provider nil} :anthropic)))
    (is (true? (request-support/builtin? {} :anthropic))
        "nil provider is built-in"))
  (testing ":custom? false → still built-in"
    (is (true? (request-support/builtin? {:provider :anthropic :custom? false}
                                         :anthropic))))
  (testing ":custom? true → never built-in, even when named like the built-in"
    (is (false? (request-support/builtin? {:provider :anthropic :custom? true}
                                          :anthropic))
        "a custom models.edn provider literally named anthropic is NOT built-in")
    (is (false? (request-support/builtin? {:provider :openai :custom? true}
                                          :anthropic))))
  (testing "provider mismatch → not built-in"
    (is (false? (request-support/builtin? {:provider :openai} :anthropic)))
    (is (false? (request-support/builtin? {:provider :deepseek} :anthropic)))
    (is (false? (request-support/builtin? {:provider :deepseek :custom? true}
                                          :anthropic)))))

(deftest resolve-key-spec-test
  ;; Review 26: the shared env: spec resolution — used by resolve-api-key at
  ;; request time (and directly by user_models_test.clj's resolve-key-spec-
  ;; test since review 28 deleted the production-dead
  ;; user_models/resolve-api-key-spec wrapper). Custom models.edn `env:` keys
  ;; are stored RAW in the registry and re-resolved per request.
  (testing "nil/blank → nil"
    (is (nil? (request-support/resolve-key-spec nil)))
    (is (nil? (request-support/resolve-key-spec "")))
    (is (nil? (request-support/resolve-key-spec "  "))))
  (testing "env: prefix reads the environment at request time"
    (let [environment (environment-boundary/nullable {"DEEPSEEK_API_KEY" "sk-live-env-key"})]
      (is (= "sk-live-env-key"
             (request-support/resolve-key-spec "env:DEEPSEEK_API_KEY" environment))))
    (let [environment (environment-boundary/nullable {})]
      (is (nil? (request-support/resolve-key-spec "env:PSI_TEST_NONEXISTENT_VAR_XYZ" environment)))))
  (testing "env: with a blank variable name is unresolvable, never getenv \"\" (review 30)"
    ;; "env:" / "env: " name an empty variable — a config error, not an env
    ;; lookup of the empty string (which would silently return nil and
    ;; surface as a misleading "environment variable  is unset" downstream).
    (let [environment (environment-boundary/nullable
                       (fn [_] (throw (ex-info "environment must not be read" {}))))]
      (is (nil? (request-support/resolve-key-spec "env:" environment)))
      (is (nil? (request-support/resolve-key-spec "env: " environment)))
      (is (empty? (environment-boundary/reads environment))
          "blank variable names never reach the environment boundary")))
  (testing "literal string returned as-is"
    (is (= "sk-literal" (request-support/resolve-key-spec "sk-literal")))))

(deftest resolve-api-key-request-time-env-resolution-test
  ;; Custom env: specs resolve per request through the explicit nullable
  ;; environment boundary; unset and malformed specs remain actionable.
  (testing "custom provider with env: spec resolves live at request time"
    (let [model {:provider :deepseek :custom? true}
          environment (environment-boundary/nullable
                       {"DEEPSEEK_API_KEY" "sk-deepseek-live"})]
      (is (= "sk-deepseek-live"
             (request-support/resolve-api-key
              model
              {:api-key "env:DEEPSEEK_API_KEY"
               :environment-boundary environment}
              anthropic-config)))
      (is (= ["DEEPSEEK_API_KEY"] (environment-boundary/reads environment)))))

  (testing "custom provider with unset env: spec fails fast naming the variable"
    (let [model {:provider :deepseek :custom? true}
          environment (environment-boundary/nullable {})
          e (try
              (request-support/resolve-api-key
               model
               {:api-key "env:DEEPSEEK_API_KEY"
                :environment-boundary environment}
               anthropic-config)
              nil
              (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #"environment variable DEEPSEEK_API_KEY is unset" (ex-message e)))
      (is (re-find #"re-read per request" (ex-message e)))
      (is (nil? (re-find #"/login" (ex-message e))))))

  (testing "env: with a blank variable name is a config error"
    (let [model {:provider :deepseek :custom? true}
          environment (environment-boundary/nullable {})
          e (try
              (request-support/resolve-api-key
               model
               {:api-key "env:" :environment-boundary environment}
               anthropic-config)
              nil
              (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #"api-key spec \"env:\" names an empty environment variable"
                   (ex-message e)))
      (is (empty? (environment-boundary/reads environment)))))

  (testing "literal configured key passes through unchanged"
    (is (= "sk-deepseek-configured"
           (request-support/resolve-api-key {:provider :deepseek :custom? true}
                                            {:api-key "sk-deepseek-configured"}
                                            anthropic-config))))

  (testing "built-in env fallback still applies when no key is configured"
    (let [environment (environment-boundary/nullable
                       {"ANTHROPIC_API_KEY" "sk-ant-env-fallback"})]
      (is (= "sk-ant-env-fallback"
             (request-support/resolve-api-key
              {:provider :anthropic}
              {:environment-boundary environment}
              anthropic-config))))))

(deftest builtin-openai-chat-completions?-test
  ;; Review 26: the shared built-in-openai-chat-completions predicate used
  ;; by agent-session's mid-conversation system-message inference — origin
  ;; tag + provider built-in classification must live here once (alongside
  ;; builtin?), not as an inline copy in model_capabilities that could drift.
  (testing "built-in OpenAI chat-completions shape → true"
    (is (true? (request-support/builtin-openai-chat-completions?
                {:provider :openai :api :openai-completions})))
    (is (true? (request-support/builtin-openai-chat-completions?
                {:provider nil :api :openai-completions}))
        "nil provider is built-in (matches builtin? semantics)"))
  (testing "custom provider named openai (:custom? true) → false — origin tag wins over name"
    (is (false? (request-support/builtin-openai-chat-completions?
                 {:provider :openai :api :openai-completions :custom? true}))))
  (testing "custom provider named deepseek → false"
    (is (false? (request-support/builtin-openai-chat-completions?
                 {:provider :deepseek :api :openai-completions :custom? true}))))
  (testing "api constraint preserved — codex-routed built-ins never match"
    (is (false? (request-support/builtin-openai-chat-completions?
                 {:provider :openai :api :openai-codex-responses})))
    (is (true? (request-support/builtin-openai-chat-completions?
                {:provider :openai :api :openai-completions :custom? false}))
        "explicit :custom? false is built-in"))
  (testing "non-openai built-in provider → false"
    (is (false? (request-support/builtin-openai-chat-completions?
                 {:provider :anthropic :api :openai-completions})))))

(deftest find-headers-case-insensitive-all-matches-test
  ;; Review 19: redaction must find EVERY case-insensitive match per
  ;; auth-header name — a differently-cased duplicate on the wire would
  ;; otherwise leak verbatim into the :on-provider-request capture.
  (testing "find-headers returns every case-insensitive match under its original casing"
    (let [headers {"x-api-key" "configured" "X-API-Key" "custom" "X-Client" "psi"}]
      (is (= #{["x-api-key" "configured"] ["X-API-Key" "custom"]}
             (set (request-support/find-headers headers "x-api-key"))))
      (is (= #{["x-api-key" "configured"] ["X-API-Key" "custom"]}
             (set (request-support/find-headers headers "X-API-KEY"))))
      (is (empty? (request-support/find-headers headers "authorization")))))
  (testing "keyword keys match case-insensitively too"
    (is (= [[:x-api-key "k"]]
           (request-support/find-headers {:x-api-key "k"} "x-api-key")))))

(deftest find-header-first-match-test
  (testing "find-header returns the first case-insensitive match, or nil"
    (let [[k v] (request-support/find-header {"x-api-key" "configured"
                                              "X-API-Key" "custom"}
                                             "x-api-key")]
      (is (contains? #{"x-api-key" "X-API-Key"} k))
      (is (contains? #{"configured" "custom"} v)))
    (is (nil? (request-support/find-header {"X-Client" "psi"} "authorization")))))

(deftest redact-secret-test
  (testing "redact-secret emits ***REDACTED***, with a length suffix only for values > 20 chars"
    (is (= "***REDACTED***" (request-support/redact-secret "short")))
    (is (= "***REDACTED***" (request-support/redact-secret (apply str (repeat 20 "x")))))
    (is (= "***REDACTED*** (len=21)"
           (request-support/redact-secret (apply str (repeat 21 "x")))))
    (is (= "***REDACTED*** (len=30)"
           (request-support/redact-secret (apply str (repeat 30 "a"))))))
  (testing "non-string values redact to nil"
    (is (nil? (request-support/redact-secret nil)))
    (is (nil? (request-support/redact-secret 42)))))

(deftest redact-authorization-test
  ;; Review 13: redact-authorization strips a leading "Bearer " prefix before
  ;; counting, so (len=N) measures the secret itself, not the 7-char prefix.
  (testing "Bearer-prefixed values count the secret only"
    (is (= "Bearer ***REDACTED***"
           (request-support/redact-authorization "Bearer short")))
    (is (= "Bearer ***REDACTED*** (len=30)"
           (request-support/redact-authorization
            (str "Bearer " (apply str (repeat 30 "a")))))))
  (testing "a token without the Bearer prefix is counted whole"
    (is (= "Bearer ***REDACTED*** (len=30)"
           (request-support/redact-authorization (apply str (repeat 30 "a"))))))
  (testing "non-string values redact to nil"
    (is (nil? (request-support/redact-authorization nil)))))

(deftest mask-chatgpt-account-id-test
  (testing "mask-chatgpt-account-id keeps the first 6 chars, then ..."
    (is (= "acc_12..." (request-support/mask-chatgpt-account-id "acc_1234567890")))
    (is (= "abc..." (request-support/mask-chatgpt-account-id "abc"))))
  (testing "non-string values mask to nil"
    (is (nil? (request-support/mask-chatgpt-account-id nil)))))

(deftest redact-headers-all-matches-dual-casing-test
  ;; Review 19: redact-headers must redact EVERY case-insensitive match per
  ;; auth-header name (base x-api-key + custom X-API-Key, or Authorization +
  ;; authorization) — redacting only the first would leak the duplicate
  ;; verbatim into the capture. Redacted values are written back under the
  ;; original key casing.
  (testing "dual-casing x-api-key → both redacted, non-auth headers pass through"
    (let [redacted (request-support/redact-headers
                    {"x-api-key" "configured-key"
                     "X-API-Key" "custom-secret"
                     "X-Client"  "psi"}
                    [["x-api-key" request-support/redact-secret]])]
      (is (= "***REDACTED***" (get redacted "x-api-key")))
      (is (= "***REDACTED***" (get redacted "X-API-Key"))
          "the differently-cased duplicate is redacted too — no verbatim secret")
      (is (= "psi" (get redacted "X-Client"))
          "non-auth headers pass through unchanged")
      (is (not (some #{"configured-key" "custom-secret"} (vals redacted))))))
  (testing "dual-casing authorization → both Bearer-redacted with per-value lengths"
    (let [redacted (request-support/redact-headers
                    {"Authorization" (str "Bearer " (apply str (repeat 30 "a")))
                     "authorization" (str "Bearer " (apply str (repeat 25 "b")))}
                    [["Authorization" request-support/redact-authorization]])]
      (is (= "Bearer ***REDACTED*** (len=30)" (get redacted "Authorization")))
      (is (= "Bearer ***REDACTED*** (len=25)" (get redacted "authorization")))))
  (testing "dual-casing chatgpt-account-id → both masked (review 21 mask semantics)"
    (let [redacted (request-support/redact-headers
                    {"chatgpt-account-id" "acc_1234567890"
                     "ChatGPT-Account-Id" "acc_0987654321"}
                    [["chatgpt-account-id" request-support/mask-chatgpt-account-id]])]
      (is (= "acc_12..." (get redacted "chatgpt-account-id")))
      (is (= "acc_09..." (get redacted "ChatGPT-Account-Id")))))
  (testing "redacted values are written back under the original key casing"
    (let [redacted (request-support/redact-headers
                    {"X-API-Key" "secret"}
                    [["x-api-key" request-support/redact-secret]])]
      (is (contains? redacted "X-API-Key"))
      (is (= "***REDACTED***" (get redacted "X-API-Key"))))))

(deftest emit-start-once-test
  ;; Review 54: the shared :start emitter extracted from the three
  ;; byte-identical per-transport copies (anthropic emit-start!, openai
  ;; emit-stream-start!, codex emit-codex-start!) — the once-guard is the
  ;; contract all three transports rely on, so it is locked here directly.
  (testing "the compare-and-set once-guard fires exactly once across call sites"
    (let [events   (atom [])
          consume  (fn [e] (swap! events conj e))
          started? (atom false)]
      (request-support/emit-start! consume started?)
      (request-support/emit-start! consume started?)
      (is (= [{:type :start}] @events)
          "the second call is a no-op — exactly one :start ever")
      (is (true? @started?)
          "the started? atom is set after the first emission")))

  (testing "a pre-set started? atom suppresses the emission entirely"
    (let [events   (atom [])
          consume  (fn [e] (swap! events conj e))
          started? (atom true)]
      (request-support/emit-start! consume started?)
      (is (empty? @events)
          "a stream that already emitted :start never emits a second one"))))
