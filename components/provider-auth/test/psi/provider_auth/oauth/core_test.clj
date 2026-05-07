(ns psi.provider-auth.oauth.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.provider-auth.oauth.core :as oauth]
            [psi.provider-auth.oauth.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-auth-path []
  (let [dir (str (Files/createTempDirectory "psi-oauth-core-test-"
                                            (make-array FileAttribute 0)))]
    (str dir "/auth.json")))

(deftest login-test
  ;; Login stores credentials via the stub provider
  (testing "login stores oauth credential and returns it"
    (let [ctx  (oauth/create-null-context)
          cred (oauth/login! ctx :anthropic
                             {:on-auth     (fn [_])
                              :on-prompt   (fn [_] "fake-code")
                              :on-progress (fn [_])})]
      (is (= :oauth (:type cred)))
      (is (string? (:access cred)))
      (is (string? (:refresh cred)))
      (is (number? (:expires cred)))
      (is (oauth/has-auth? ctx :anthropic))))

  (testing "openai login stores oauth credential and returns it"
    (let [ctx  (oauth/create-null-context)
          cred (oauth/login! ctx :openai
                             {:on-auth     (fn [_])
                              :on-prompt   (fn [_] "fake-code")
                              :on-progress (fn [_])})]
      (is (= :oauth (:type cred)))
      (is (oauth/has-auth? ctx :openai))))

  (testing "login with unknown provider throws"
    (let [ctx (oauth/create-null-context)]
      (is (thrown-with-msg? Exception #"Unknown OAuth provider"
                            (oauth/login! ctx :nonexistent {:on-auth (fn [_]) :on-prompt (fn [_] "")}))))))

(deftest logout-test
  (testing "logout removes credential"
    (let [ctx (oauth/create-null-context)]
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (oauth/has-auth? ctx :anthropic))
      (oauth/logout! ctx :anthropic)
      (is (not (oauth/has-auth? ctx :anthropic))))))

(deftest get-api-key-test
  (testing "returns access token after login"
    (let [ctx (oauth/create-null-context)]
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (= "test-access" (oauth/get-api-key ctx :anthropic)))))

  (testing "auto-refreshes expired token"
    (let [ctx (oauth/create-null-context
               {:credentials {:anthropic {:type :oauth :refresh "old" :access "old"
                                          :expires 1000}}})
          key (oauth/get-api-key ctx :anthropic)]
      (is (= "refreshed-access" key))
      ;; Store should have the refreshed credential
      (is (= "refreshed-access"
             (:access (store/get-credential (:store ctx) :anthropic))))))

  (testing "returns nil when no auth configured"
    (let [ctx (oauth/create-null-context)]
      (is (nil? (oauth/get-api-key ctx :nonexistent)))))

  (testing "reloads file-backed store so cross-process updates are visible"
    (let [auth-path (temp-auth-path)
          writer-store (store/create-store {:path auth-path})
          reader-store (store/create-store {:path auth-path})
          reader-ctx   {:store reader-store}]
      (store/set-credential! writer-store :anthropic {:type :api-key :key "k-1"})
      (is (= "k-1" (oauth/get-api-key reader-ctx :anthropic)))

      (store/set-credential! writer-store :anthropic {:type :api-key :key "k-2"})
      (is (= "k-2" (oauth/get-api-key reader-ctx :anthropic)))
      (is (= "k-2" (:key (store/get-credential reader-store :anthropic)))))))

(deftest refresh-token-test
  (testing "refresh updates expired credential"
    (let [ctx       (oauth/create-null-context
                     {:credentials {:anthropic {:type :oauth :refresh "old" :access "old"
                                                :expires 1000}}})
          refreshed (oauth/refresh-token! ctx :anthropic)]
      (is (= "refreshed-access" (:access refreshed)))
      (is (= "refreshed-access"
             (:access (store/get-credential (:store ctx) :anthropic))))))

  (testing "refresh with no credential throws"
    (let [ctx (oauth/create-null-context)]
      (is (thrown-with-msg? Exception #"No OAuth credential"
                            (oauth/refresh-token! ctx :anthropic))))))

(deftest available-providers-test
  (testing "lists registered providers"
    (let [ctx (oauth/create-null-context)
          ids (set (map :id (oauth/available-providers ctx)))]
      (is (contains? ids :anthropic))
      (is (contains? ids :openai)))))

(deftest logged-in-providers-test
  (testing "returns only providers with oauth credentials"
    (let [ctx (oauth/create-null-context)]
      (is (empty? (oauth/logged-in-providers ctx)))
      (oauth/login! ctx :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (= [:anthropic] (map :id (oauth/logged-in-providers ctx)))))))

(deftest context-isolation-test
  ;; Two null contexts are independent
  (testing "contexts do not share state"
    (let [a (oauth/create-null-context)
          b (oauth/create-null-context)]
      (oauth/login! a :anthropic {:on-auth (fn [_]) :on-prompt (fn [_] "x")})
      (is (oauth/has-auth? a :anthropic))
      (is (not (oauth/has-auth? b :anthropic))))))

(deftest custom-provider-test
  ;; Tests can inject custom providers
  (testing "custom provider login and api-key"
    (let [ctx       (oauth/create-null-context
                     {:providers [{:id          :custom
                                   :name        "Custom Provider"
                                   :login       (fn [callbacks]
                                                  ((:on-auth callbacks) {:url "https://custom.example.com"})
                                                  {:type :oauth :access "custom-tok" :refresh "custom-ref"
                                                   :expires (+ (System/currentTimeMillis) 3600000)})
                                   :refresh-token (fn [_] {:type :oauth :access "new-tok" :refresh "new-ref"
                                                           :expires (+ (System/currentTimeMillis) 3600000)})
                                   :get-api-key :access}]})
          auth-urls (atom [])]
      (oauth/login! ctx :custom
                    {:on-auth   (fn [info] (swap! auth-urls conj (:url info)))
                     :on-prompt (fn [_] "x")})
      (is (= ["https://custom.example.com"] @auth-urls))
      (is (= "custom-tok" (oauth/get-api-key ctx :custom))))))
