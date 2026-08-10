(ns psi.provider-auth.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.model-registry :as model-registry]
            [psi.provider-auth.core :as provider-auth]
            [psi.provider-auth.oauth.core :as oauth]))

(defn- write-temp-models! [auth]
  (let [tmp (java.io.File/createTempFile "psi-provider-auth" ".edn")]
    (spit tmp
          (pr-str {:version 1
                   :providers {"anthropic"
                               {:base-url "https://third-party.example/anthropic"
                                :api :anthropic-messages
                                :auth auth
                                :models [{:id "custom-model"}]}}}))
    tmp))

(defn- with-registry-auth [auth f]
  (let [models-file (write-temp-models! auth)]
    (try
      (model-registry/init! {:user-models-path (.getAbsolutePath models-file)})
      (f)
      (finally
        (model-registry/init! {})
        (.delete models-file)))))

(defn- oauth-ctx [api-key]
  {:oauth-ctx
   (oauth/create-null-context
    {:credentials (cond-> {}
                    api-key (assoc :anthropic {:type :api-key :key api-key}))})})

(deftest provider-auth-config-test
  (testing "returns model-registry auth for provider"
    (with-registry-auth
      {:auth-header? true :api-key "k"}
      #(do
         (is (= {:provider :anthropic
                 :auth-header? true
                 :api-key "k"
                 :headers nil}
                (provider-auth/provider-auth-config :anthropic)))
         (is (nil? (provider-auth/provider-auth-config :openai))))))

  (testing "nil provider returns nil"
    (is (nil? (provider-auth/provider-auth-config nil)))))

(deftest provider-api-key-test
  (testing "built-in model resolves OAuth, never same-named registry auth"
    (with-registry-auth
      {:auth-header? true :api-key "registry-k"}
      #(do
         (is (= "oauth-k"
                (provider-auth/provider-api-key (oauth-ctx "oauth-k") :anthropic false)))
         (is (= "oauth-k"
                (provider-auth/provider-api-key (oauth-ctx "oauth-k") :anthropic))))))

  (testing "built-in model with no OAuth resolves nil, never registry auth"
    (with-registry-auth
      {:auth-header? true :api-key "registry-k"}
      #(is (nil? (provider-auth/provider-api-key (oauth-ctx nil) :anthropic false)))))

  (testing "custom model resolves registry auth when auth headers are enabled"
    (with-registry-auth
      {:auth-header? true :api-key "registry-k"}
      #(is (= "registry-k"
              (provider-auth/provider-api-key (oauth-ctx nil) :anthropic true)))))

  (testing "custom model never receives a same-named OAuth credential"
    (with-registry-auth
      {:auth-header? true :api-key "registry-k"}
      #(is (= "registry-k"
              (provider-auth/provider-api-key (oauth-ctx "oauth-k") :anthropic true)))))

  (testing "custom model registry auth is ignored when auth headers are disabled"
    (with-registry-auth
      {:auth-header? false :api-key "registry-k"}
      #(is (nil? (provider-auth/provider-api-key (oauth-ctx nil) :anthropic true))))))

(deftest provider-request-options-test
  (testing "custom model includes no-auth-header and registry headers"
    (with-registry-auth
      {:auth-header? false :headers {"x-test" "1"}}
      #(is (= {:no-auth-header true
               :headers {"x-test" "1"}}
              (provider-auth/provider-request-options :anthropic true)))))

  (testing "built-in model never inherits same-named custom-provider options"
    (with-registry-auth
      {:auth-header? false :headers {"x-test" "1"}}
      #(do
         (is (nil? (provider-auth/provider-request-options :anthropic false)))
         (is (nil? (provider-auth/provider-request-options :anthropic))))))

  (testing "custom model returns nil when no auth config exists"
    (model-registry/init! {})
    (is (nil? (provider-auth/provider-request-options :anthropic true)))))

(deftest oauth-backed-test
  (testing "true when provider has stored oauth credential"
    (let [ctx {:oauth-ctx (oauth/create-null-context
                           {:credentials {:openai {:type :oauth
                                                   :access "tok"
                                                   :refresh "ref"
                                                   :expires (+ (System/currentTimeMillis) 60000)}}})}]
      (is (true? (provider-auth/oauth-backed? ctx :openai)))))

  (testing "false when provider has api-key credential or no credential"
    (let [api-key-ctx {:oauth-ctx (oauth/create-null-context
                                   {:credentials {:openai {:type :api-key
                                                           :key "sk-1"}}})}
          empty-ctx   {:oauth-ctx (oauth/create-null-context)}]
      (is (false? (provider-auth/oauth-backed? api-key-ctx :openai)))
      (is (false? (provider-auth/oauth-backed? empty-ctx :openai))))))
