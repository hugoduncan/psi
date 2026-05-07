(ns psi.provider-auth.oauth.callback-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.provider-auth.oauth.callback-server :as cb]))

(deftest null-server-test
  ;; Null server delivers results without network
  (testing "deliver result is received by wait-for-code"
    (let [srv (cb/create-null-server)]
      (future (Thread/sleep 20) ((:deliver srv) {:code "abc" :state "xyz"}))
      (let [result ((:wait-for-code srv) 3000)]
        (is (= {:code "abc" :state "xyz"} result))
        ((:close srv)))))

  (testing "cancel causes wait-for-code to return nil"
    (let [srv (cb/create-null-server)]
      (future (Thread/sleep 20) ((:cancel srv)))
      (is (nil? ((:wait-for-code srv) 1000)))
      ((:close srv))))

  (testing "timeout returns nil"
    (let [srv (cb/create-null-server)]
      (is (nil? ((:wait-for-code srv) 200)))
      ((:close srv))))

  (testing "port is 0"
    (is (zero? (:port (cb/create-null-server))))))

(deftest real-server-test
  ;; Real server receives HTTP callback
  (testing "receives code via HTTP GET"
    (let [srv  (cb/start-server {:port 0})
          port (:port srv)]
      (is (pos? port))
      (future
        (Thread/sleep 30)
        (try
          (slurp (str "http://127.0.0.1:" port "/oauth/callback?code=real&state=s1"))
          (catch Exception _)))
      (let [result ((:wait-for-code srv) 5000)]
        (is (= {:code "real" :state "s1"} result))
        ((:close srv)))))

  (testing "wrong path returns 404, no result"
    (let [srv  (cb/start-server {:port 0})
          port (:port srv)]
      (future
        (Thread/sleep 30)
        (try (slurp (str "http://127.0.0.1:" port "/wrong?code=x")) (catch Exception _)))
      (is (nil? ((:wait-for-code srv) 500)))
      ((:close srv)))))

(deftest null-and-real-isolation-test
  ;; Two servers are independent
  (testing "null servers are independent"
    (let [a (cb/create-null-server)
          b (cb/create-null-server)]
      ((:deliver a) {:code "a-code" :state "a-state"})
      (is (= {:code "a-code" :state "a-state"} ((:wait-for-code a) 1000)))
      (is (nil? ((:wait-for-code b) 200)))
      ((:close a))
      ((:close b)))))
