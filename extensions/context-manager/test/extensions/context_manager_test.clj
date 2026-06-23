(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! sut/initialized? nil)
                      (f)))

(defn- setup-api
  "Helper to create a nullable API and initialize the extension."
  []
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/context_manager.clj"})]
    (sut/init api)
    {:api api :state state}))

(deftest init-registers-turn-finished-handler-test
  (testing "init registers a session_turn_finished handler"
    (let [{:keys [state]} (setup-api)]
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (is (contains? (get-in @state [:handlers]) "session_turn_finished")
          "handler map must explicitly contain the session_turn_finished key")
      (testing "handler is registered with the correct event name"
        (let [handlers (get-in @state [:handlers])]
          (is (contains? handlers "session_turn_finished")
              "the registration must be under the key 'session_turn_finished'"))))))

(deftest turn-finished-handler-fires-and-logs-test
  (testing "handler fires on synthetic session_turn_finished event and logs session-id and turn-id"
    (let [{:keys [state]} (setup-api)
          handler (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "handler returns nil for the nominal case"
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil as per design requirement"))
      (let [line (last (:log-lines @state))]
        (is (re-find #"session_turn_finished" line))
        (is (re-find #"session-id=s1" line))
        (is (re-find #"turn-id=t1" line))))))

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
    (let [{:keys [state]} (setup-api)]
      (is (empty? (:commands @state)) "no commands registered")
      (is (empty? (:tools @state)) "no tools registered")
      ;; Operations are not separately trackable in the nullable API — they
      ;; are dispatched through the same handler mechanism as other mutations,
      ;; so there is no :operations key on the nullable state map.
      (is (empty? (:prompt-contributions @state)) "no prompt contributions registered"))))

(deftest handler-handles-missing-payload-keys-test
  (testing "handler logs gracefully when :session-id or :turn-id are missing"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))]
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
              "logs turn-id=t2 when present"))))))

(deftest init-robustness-test
  (testing "init handles non-standard api gracefully"
    (testing "missing :on key"
      (is (nil? (sut/init {:log (fn [_] nil)}))
          "should return nil and not throw NPE when :on is missing")
      (reset! sut/initialized? nil))
    (testing "recovery after missing :on key"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (is (nil? (sut/init {:log (fn [_] nil)})) "first call fails")
        (is (true? (sut/init api)) "subsequent call with valid API succeeds")
        (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
        (reset! sut/initialized? nil)))
    (testing "missing :log key"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (reset! sut/initialized? nil)
        (sut/init (dissoc api :log))
        (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
          (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
              "handler should return nil and not throw NPE when log-fn is missing"))))
    (testing "api is nil"
      (is (nil? (sut/init nil))
          "should return nil and not throw NPE when api is nil"))
    (testing "api is not a map"
      (is (nil? (sut/init "not-a-map"))
          "should return nil and not throw NPE when api is not a map"))))

(deftest init-return-value-test
  (testing "init returns true on successful first-time initialization"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (true? (sut/init api))
          "init should return true on successful first-time initialization"))))

