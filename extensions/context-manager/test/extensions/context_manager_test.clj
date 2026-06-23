(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (f)))

(defn- setup-api
  "Helper to create a nullable API and initialize the extension."
  []
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/context_manager.clj"})]
    (context-manager/init api)
    {:api api :state state}))

(deftest init-registers-turn-finished-handler-test
  (testing "init registers a session_turn_finished handler"
    (let [{:keys [state]} (setup-api)]
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (is (contains? (get-in @state [:handlers]) "session_turn_finished")
          "handler map must explicitly contain the session_turn_finished key"))))

(deftest turn-finished-handler-fires-and-logs-test
  (testing "handler fires on synthetic session_turn_finished event and logs session-id and turn-id"
    (let [{:keys [state]} (setup-api)
          handler (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "nominal case"
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil as per design requirement")
        (let [line (last (:log-lines @state))]
          (is (re-find #"session_turn_finished" line))
          (is (re-find #"session-id=s1" line))
          (is (re-find #"turn-id=t1" line))))
      (testing "payload is an empty map"
        (is (nil? (handler {}))
            "handler returns nil")
        (let [line (last (:log-lines @state))]
          (is (re-find #"session-id=nil" line))
          (is (re-find #"turn-id=nil" line))))
      (testing "payload is nil"
        (is (nil? (handler nil))
            "handler must return nil and not throw when payload is nil")))))

(deftest init-reload-safety-test
  (testing "calling init twice does not register duplicate handlers"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})]
      (context-manager/init api)
      (context-manager/init api)
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
      (is (nil? (context-manager/init {:log (fn [_] nil)}))
          "should return nil and not throw NPE when :on is missing")
      (reset! context-manager/initialized? nil))
    (testing "recovery after missing :on key"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (is (nil? (context-manager/init {:log (fn [_] nil)})) "first call fails")
        (is (true? (context-manager/init api)) "subsequent call with valid API succeeds")
        (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
        (reset! context-manager/initialized? nil)))
    (testing "missing :log key"
      (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (reset! context-manager/initialized? nil)
        (context-manager/init (dissoc api :log))
        (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
          (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
              "handler should return nil and not throw NPE when log-fn is missing")
          (is (empty? (:log-lines @state))
              "handler should not log anything when :log is missing from API"))))
    (testing "api is nil"
      (is (nil? (context-manager/init nil))
          "should return nil and not throw NPE when api is nil"))
    (testing "api is not a map"
      (is (nil? (context-manager/init "not-a-map"))
          "should return nil and not throw NPE when api is not a map"))))

(deftest init-return-value-test
  (testing "init returns true on successful first-time initialization"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (true? (context-manager/init api))
          "init should return true on successful first-time initialization"))))

(deftest init-registration-call-test
  (testing "init calls (:on api) with correct event name"
    (let [on-spy (atom nil)
          api {:on (fn [event handler] (reset! on-spy [event handler]))
               :log (fn [_] nil)}]
      (reset! context-manager/initialized? nil)
      (context-manager/init api)
      (let [[event handler] @on-spy]
        (is (= "session_turn_finished" event)
            "must call registration with event name 'session_turn_finished'")
        (is (fn? handler)
            "must register a function as the handler")))))

(deftest init-registration-contract-test
  (testing "init registration contract"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (reset! context-manager/initialized? nil)
      (context-manager/init api)
      (let [handlers (get-in @state [:handlers])]
        (testing "registered with correct event name"
          (is (contains? handlers "session_turn_finished")
              "must register handler for session_turn_finished"))
        (testing "handler is a function"
          (let [handler (first (get handlers "session_turn_finished"))]
            (is (fn? handler)
                "registered handler must be a function to be compatible with dispatch pipeline")))))))

(deftest handler-purity-test
  (testing "handler does not mutate external state"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))
          before-state    @state]
      (handler {:session-id "s1" :turn-id "t1"})
      (is (= (dissoc before-state :log-lines) (dissoc @state :log-lines))
          "handler must not mutate the API state map; it should only use the provided log-fn")
      (is (= (count (:log-lines before-state)) (dec (count (@state :log-lines))))
          "the provided log-fn is used"))))
