(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-turn-finished-handler-test
  (testing "init registers a session_turn_finished handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"])))))))

(deftest turn-finished-handler-fires-and-logs-test
  (testing "handler fires on synthetic session_turn_finished event and logs session-id and turn-id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"})))
        (is (some #(re-find #"(?i)session_turn_finished" %)
                  (:log-lines @state)))
        (is (some #(re-find #"(?i)s1" %)
                  (:log-lines @state)))
        (is (some #(re-find #"(?i)t1" %)
                  (:log-lines @state)))))))
