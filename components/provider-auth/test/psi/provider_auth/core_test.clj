(ns psi.provider-auth.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.model-registry :as model-registry]
            [psi.provider-auth.core :as provider-auth]
            [psi.provider-auth.oauth.core :as oauth]))

(deftest provider-auth-config-test
  (testing "returns model-registry auth for provider"
    (with-redefs [model-registry/get-auth (fn [provider]
                                            (when (= :anthropic provider)
                                              {:auth-header? true
                                               :api-key "k"}))]
      (is (= {:auth-header? true :api-key "k"}
             (provider-auth/provider-auth-config :anthropic)))
      (is (nil? (provider-auth/provider-auth-config :openai)))))

  (testing "nil provider returns nil"
    (is (nil? (provider-auth/provider-auth-config nil)))))

(deftest provider-api-key-test
  (testing "built-in model (custom? false) resolves OAuth, never registry auth"
    (with-redefs [oauth/get-api-key (fn [_ctx provider] (when (= :anthropic provider) "oauth-k"))
                  model-registry/get-auth (fn [_] {:auth-header? true :api-key "registry-k"})]
      ;; OAuth is the built-in auth path
      (is (= "oauth-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic false)))
      ;; review 42: a built-in same-named model must never inherit a custom
      ;; provider's registry auth even when one exists for the provider name
      (is (= "oauth-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic)))))

  (testing "built-in model with no OAuth resolves nil (registry auth not consulted)"
    (with-redefs [oauth/get-api-key (fn [_ _] nil)
                  model-registry/get-auth (fn [_] {:auth-header? true :api-key "registry-k"})]
      ;; review 42: custom-provider registry auth is never applied to a
      ;; built-in same-named model — built-ins resolve only env/OAuth
      (is (nil? (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic false)))))

  (testing "custom model (custom? true) resolves registry auth when auth headers are enabled"
    (with-redefs [oauth/get-api-key (fn [_ _] nil)
                  model-registry/get-auth (fn [_] {:auth-header? true :api-key "registry-k"})]
      (is (= "registry-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic true)))))

  (testing "custom model never receives a same-named OAuth credential (origin gate)"
    ;; review 42: OAuth login is built-in-only — a custom models.edn provider
    ;; literally named "anthropic"/"openai" must never receive the built-in
    ;; same-named OAuth credential; registry auth resolves instead.
    (with-redefs [oauth/get-api-key (fn [_ctx provider] (when (= :anthropic provider) "oauth-k"))
                  model-registry/get-auth (fn [_] {:auth-header? true :api-key "registry-k"})]
      (is (= "registry-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic true)))))

  (testing "custom model registry auth is ignored when auth headers are disabled"
    (with-redefs [oauth/get-api-key (fn [_ _] nil)
                  model-registry/get-auth (fn [_] {:auth-header? false :api-key "registry-k"})]
      (is (nil? (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic true))))))

(deftest provider-request-options-test
  (testing "custom model (custom? true) includes no-auth-header and headers from model-registry auth"
    (with-redefs [model-registry/get-auth (fn [_]
                                            {:auth-header? false
                                             :headers {"x-test" "1"}})]
      (is (= {:no-auth-header true
              :headers {"x-test" "1"}}
             (provider-auth/provider-request-options :anthropic true)))))

  (testing "built-in model (custom? false) never inherits custom-provider registry options"
    ;; review 42: a built-in same-named model must never inherit a custom
    ;; provider's :no-auth-header/headers config
    (with-redefs [model-registry/get-auth (fn [_]
                                            {:auth-header? false
                                             :headers {"x-test" "1"}})]
      (is (nil? (provider-auth/provider-request-options :anthropic false)))
      (is (nil? (provider-auth/provider-request-options :anthropic)))))

  (testing "custom model returns nil when no auth config exists"
    (with-redefs [model-registry/get-auth (fn [_] nil)]
      (is (nil? (provider-auth/provider-request-options :anthropic true))))))

(deftest oauth-backed-test
  (testing "true when provider has stored oauth credential"
    (let [ctx {:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :oauth
                                                                             :access "tok"
                                                                             :refresh "ref"
                                                                             :expires (+ (System/currentTimeMillis) 60000)}}})}]
      (is (true? (provider-auth/oauth-backed? ctx :openai)))))

  (testing "false when provider has api-key credential or no credential"
    (let [api-key-ctx {:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :api-key
                                                                                     :key "sk-1"}}})}
          empty-ctx   {:oauth-ctx (oauth/create-null-context)}]
      (is (false? (provider-auth/oauth-backed? api-key-ctx :openai)))
      (is (false? (provider-auth/oauth-backed? empty-ctx :openai))))))
