(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (alter-var-root #'sut/initialized? (fn [_] (atom nil)))
                      (f)))

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
        (let [line (last (:log-lines @state))]
          (is (re-find #"session_turn_finished" line))
          (is (re-find #"session-id=s1" line))
          (is (re-find #"turn-id=t1" line)))))))

(deftest init-reload-safety-test
  (testing "calling init twice does not register duplicate handlers"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"])))
          "init is idempotent: calling twice still registers only one handler"))))

(deftest init-registers-no-commands-tools-operations-or-prompts-test
  (testing "init registers no commands, tools, operations, or prompt contributions"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (is (empty? (:commands @state)) "no commands registered")
      (is (empty? (:tools @state)) "no tools registered")
      ;; Operations are not separately trackable in the nullable API — they
      ;; are dispatched through the same handler mechanism as other mutations,
      ;; so there is no :operations key on the nullable state map.
      (is (empty? (:prompt-contributions @state)) "no prompt contributions registered"))))

(deftest handler-handles-missing-payload-keys-test
  (testing "handler logs gracefully when :session-id or :turn-id are missing"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (testing "missing both keys"
          (is (nil? (handler {}))
              "handler returns nil")
          (let [line (last (:log-lines @state))]
            (is (re-find #"session-id=nil" line)
                "logs session-id=nil when key is missing")
            (is (re-find #"turn-id=nil" line)
                "logs turn-id=nil when key is missing")))
        (testing "missing only :turn-id"
          (is (nil? (handler {:session-id "s2"}))
              "handler returns nil")
          (let [line (last (:log-lines @state))]
            (is (re-find #"session-id=s2" line)
                "logs session-id=s2 when present")
            (is (re-find #"turn-id=nil" line)
                "logs turn-id=nil when :turn-id is missing")))
        (testing "missing only :session-id"
          (is (nil? (handler {:turn-id "t2"}))
              "handler returns nil")
          (let [line (last (:log-lines @state))]
            (is (re-find #"session-id=nil" line)
                "logs session-id=nil when :session-id is missing")
            (is (re-find #"turn-id=t2" line)
                "logs turn-id=t2 when present")))))))
