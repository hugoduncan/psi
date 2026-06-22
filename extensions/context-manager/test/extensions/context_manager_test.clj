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
        (is (some #(re-find #"session_turn_finished" %)
                  (:log-lines @state)))
        (is (some #(re-find #"session-id=s1" %)
                  (:log-lines @state)))
        (is (some #(re-find #"turn-id=t1" %)
                  (:log-lines @state)))))))

(deftest init-reload-safety-test
  (testing "calling init twice registers duplicate handlers — reload safety concern"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (sut/init api)
      (is (= 2 (count (get-in @state [:handlers "session_turn_finished"])))
          "init is not idempotent: calling twice registers two handlers"))))

(deftest init-registers-no-commands-tools-or-prompts-test
  (testing "init registers no commands, tools, operations, or prompt contributions"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (is (empty? (:commands @state)) "no commands registered")
      (is (empty? (:tools @state)) "no tools registered")
      (is (empty? (:prompt-contributions @state)) "no prompt contributions registered"))))

(deftest handler-handles-missing-payload-keys-test
  (testing "handler logs gracefully when :session-id or :turn-id are missing"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        ;; Missing both keys
        (handler {})
        (is (some #(re-find #"session-id=nil" %)
                  (:log-lines @state))
            "logs session-id=nil when key is missing")
        (is (some #(re-find #"turn-id=nil" %)
                  (:log-lines @state))
            "logs turn-id=nil when key is missing")))))
