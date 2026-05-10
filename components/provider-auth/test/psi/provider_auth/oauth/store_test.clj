(ns psi.provider-auth.oauth.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.provider-auth.oauth.store :as store]))

(deftest null-store-crud-test
  ;; Basic CRUD on a null store
  (testing "set and get credential"
    (let [s (store/create-null-store)]
      (store/set-credential! s :anthropic {:type :api-key :key "sk-test"})
      (is (= {:type :api-key :key "sk-test"}
             (store/get-credential s :anthropic)))))

  (testing "remove credential"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "sk-x"}})]
      (store/remove-credential! s :anthropic)
      (is (nil? (store/get-credential s :anthropic)))))

  (testing "list providers"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "k1"}
                                      :openai    {:type :api-key :key "k2"}})]
      (is (= #{:anthropic :openai} (set (store/list-providers s))))))

  (testing "has-credential?"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "k"}})]
      (is (store/has-credential? s :anthropic))
      (is (not (store/has-credential? s :openai)))))

  (testing "persist! is called on set and remove"
    (let [s (store/create-null-store)]
      (store/set-credential! s :anthropic {:type :api-key :key "k"})
      (is (= {:anthropic {:type :api-key :key "k"}} @(:persisted s)))
      (store/remove-credential! s :anthropic)
      (is (= {} @(:persisted s))))))

(deftest api-key-priority-test
  ;; API key resolution priority chain
  (testing "runtime override wins over stored credential"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "stored"}})]
      (store/set-runtime-override! s :anthropic "runtime")
      (is (= "runtime" (store/get-api-key s :anthropic)))))

  (testing "stored api-key credential"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "stored"}})]
      (is (= "stored" (store/get-api-key s :anthropic)))))

  (testing "stored oauth access token"
    (let [s (store/create-null-store {:anthropic {:type :oauth :access "tok" :refresh "r"
                                                  :expires 999999999999999}})]
      (is (= "tok" (store/get-api-key s :anthropic)))))

  (testing "runtime override removed falls back to stored"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "stored"}})]
      (store/set-runtime-override! s :anthropic "runtime")
      (store/remove-runtime-override! s :anthropic)
      (is (= "stored" (store/get-api-key s :anthropic)))))

  (testing "no auth returns nil"
    (let [s (store/create-null-store)]
      (is (nil? (store/get-api-key s :nonexistent))))))

(deftest oauth-expiry-test
  ;; OAuth token expiration checks
  (testing "non-expired token"
    (let [s (store/create-null-store
             {:anthropic {:type :oauth :access "a" :refresh "r"
                          :expires (+ (System/currentTimeMillis) 60000)}})]
      (is (not (store/oauth-expired? s :anthropic)))))

  (testing "expired token"
    (let [s (store/create-null-store
             {:anthropic {:type :oauth :access "a" :refresh "r"
                          :expires 1000}})]
      (is (store/oauth-expired? s :anthropic))))

  (testing "non-oauth credential is not expired"
    (let [s (store/create-null-store
             {:anthropic {:type :api-key :key "k"}})]
      (is (not (store/oauth-expired? s :anthropic)))))

  (testing "missing credential is not expired"
    (let [s (store/create-null-store)]
      (is (not (store/oauth-expired? s :anthropic))))))

(deftest has-auth-test
  (testing "has-auth via stored credential"
    (let [s (store/create-null-store {:anthropic {:type :api-key :key "k"}})]
      (is (store/has-auth? s :anthropic))))

  (testing "has-auth via runtime override"
    (let [s (store/create-null-store)]
      (store/set-runtime-override! s :anthropic "rt")
      (is (store/has-auth? s :anthropic))))

  (testing "no auth"
    (let [s (store/create-null-store)]
      (is (not (store/has-auth? s :nonexistent))))))

(deftest null-store-isolation-test
  ;; Two null stores are independent
  (testing "stores do not share state"
    (let [a (store/create-null-store)
          b (store/create-null-store)]
      (store/set-credential! a :anthropic {:type :api-key :key "a-key"})
      (is (store/has-credential? a :anthropic))
      (is (not (store/has-credential? b :anthropic))))))
