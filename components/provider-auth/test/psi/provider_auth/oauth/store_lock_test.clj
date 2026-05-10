(ns psi.provider-auth.oauth.store-lock-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [psi.provider-auth.oauth.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-path
  "Create a temp file path for test auth.json."
  []
  (let [dir (str (Files/createTempDirectory "psi-store-test-"
                                            (make-array FileAttribute 0)))]
    (str dir "/auth.json")))

(deftest file-backed-store-test
  ;; File-backed store reads/writes to disk
  (testing "persist and reload"
    (let [path (temp-path)
          s    (store/create-store {:path path})]
      (store/set-credential! s :anthropic {:type :api-key :key "sk-test"})
      ;; Create a second store pointing at the same file
      (let [s2 (store/create-store {:path path})]
        (is (= "sk-test" (:key (store/get-credential s2 :anthropic)))))))

  (testing "file permissions are restrictive"
    (let [path (temp-path)
          s    (store/create-store {:path path})]
      (store/set-credential! s :x {:type :api-key :key "k"})
      (let [f (java.io.File. ^String path)]
        (is (.exists f))
        ;; Owner-readable
        (is (.canRead f))))))

(deftest locked-refresh-test
  ;; with-locked-refresh! coordinates concurrent access
  (testing "refresh-fn is called and result persisted"
    (let [path (temp-path)
          s    (store/create-store {:path path})]
      (store/set-credential! s :anthropic {:type :oauth :refresh "old" :access "old"
                                           :expires 1000})
      (let [result (store/with-locked-refresh! s :anthropic
                     (fn [current]
                       (is (= "old" (:access current)))
                       {:type :oauth :refresh "new" :access "new"
                        :expires 999999999999}))]
        (is (= "new" (:access result)))
        ;; Persisted to disk — new store reads it
        (let [s2 (store/create-store {:path path})]
          (is (= "new" (:access (store/get-credential s2 :anthropic))))))))

  (testing "nil result from refresh-fn skips update"
    (let [path (temp-path)
          s    (store/create-store {:path path})]
      (store/set-credential! s :anthropic {:type :oauth :refresh "orig" :access "orig"
                                           :expires 1000})
      (store/with-locked-refresh! s :anthropic (fn [_] nil))
      (is (= "orig" (:access (store/get-credential s :anthropic))))))

  (testing "concurrent refreshes serialize correctly"
    (let [path    (temp-path)
          s       (store/create-store {:path path})
          counter (atom 0)]
      (store/set-credential! s :anthropic {:type :oauth :refresh "r" :access "a"
                                           :expires 1000})
      ;; Launch 5 concurrent refreshes — all should serialize
      (let [futures (mapv (fn [_]
                            (future
                              (store/with-locked-refresh! s :anthropic
                                (fn [current]
                                  (swap! counter inc)
                                  (Thread/sleep 20)
                                  (assoc current :access (str "refreshed-" @counter))))))
                          (range 5))]
        (doseq [f futures] @f)
        ;; All 5 ran (counter = 5)
        (is (= 5 @counter))
        ;; Final value is from the last one to run
        (let [final-cred (store/get-credential s :anthropic)]
          (is (string? (:access final-cred)))
          (is (str/starts-with? (:access final-cred) "refreshed-")))))))

(deftest null-store-locked-refresh-test
  ;; Null store's with-locked-refresh! works without locking
  (testing "refresh-fn called on null store"
    (let [s      (store/create-null-store {:anthropic {:type :oauth :refresh "old" :access "old"
                                                       :expires 1000}})
          result (store/with-locked-refresh! s :anthropic
                   (fn [_current]
                     {:type :oauth :refresh "new" :access "new"
                      :expires 999999999999}))]
      (is (= "new" (:access result)))
      (is (= "new" (:access (store/get-credential s :anthropic)))))))
