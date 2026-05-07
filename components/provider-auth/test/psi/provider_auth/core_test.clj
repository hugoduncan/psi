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
  (testing "oauth auth wins over model-registry auth"
    (with-redefs [oauth/get-api-key (fn [_ctx provider]
                                      (when (= :anthropic provider) "oauth-k"))
                  model-registry/get-auth (fn [_]
                                            {:auth-header? true :api-key "registry-k"})]
      (is (= "oauth-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic)))))

  (testing "model-registry auth is used when auth headers are enabled"
    (with-redefs [oauth/get-api-key (fn [_ _] nil)
                  model-registry/get-auth (fn [_]
                                            {:auth-header? true :api-key "registry-k"})]
      (is (= "registry-k"
             (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic)))))

  (testing "model-registry auth is ignored when auth headers are disabled"
    (with-redefs [oauth/get-api-key (fn [_ _] nil)
                  model-registry/get-auth (fn [_]
                                            {:auth-header? false :api-key "registry-k"})]
      (is (nil? (provider-auth/provider-api-key {:oauth-ctx {}} :anthropic))))))

(deftest provider-request-options-test
  (testing "includes no-auth-header and headers from model-registry auth"
    (with-redefs [model-registry/get-auth (fn [_]
                                            {:auth-header? false
                                             :headers {"x-test" "1"}})]
      (is (= {:no-auth-header true
              :headers {"x-test" "1"}}
             (provider-auth/provider-request-options :anthropic)))))

  (testing "returns nil when no auth config exists"
    (with-redefs [model-registry/get-auth (fn [_] nil)]
      (is (nil? (provider-auth/provider-request-options :anthropic))))))
