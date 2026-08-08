(ns psi.ai.providers.request-support-test
  "Direct-call tests for the shared provider request-support helpers
   (psi.ai.providers.request-support), used by all three transports
   (:anthropic-messages, :openai-completions, :openai-codex-responses)."
  (:require
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
      (with-redefs [psi.ai.providers.request-support/getenv
                    (fn [_] "sk-ant-env-fallback-key")]
        (is (= "sk-ant-env-fallback-key"
               (request-support/resolve-api-key model {} anthropic-config))
            "built-in model with no configured key uses the env fallback"))))

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
