(ns extensions.context-manager-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
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
      (is (contains? (:turn-augmenters @state) "project-context")))))

(deftest turn-finished-handler-fires-and-logs-test
  (testing "handler fires on synthetic session_turn_finished event and logs session-id and turn-id"
    (let [{:keys [state]} (setup-api)
          handler (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "nominal case"
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil as per design requirement")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=s1 turn-id=t1" line)
              "log output must match exact project standard prefix and format")))
      (testing "payload is an empty map"
        (is (nil? (handler {}))
            "handler returns nil")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line))))
      (testing "payload is nil"
        (is (nil? (handler nil))
            "handler must return nil and not throw when payload is nil")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line))))
      (testing "payload is not a map"
        (is (nil? (handler "not-a-map"))
            "handler must return nil and not throw when payload is not a map")
        (let [line (last (:log-lines @state))]
          (is (= "context-manager: session_turn_finished session-id=nil turn-id=nil" line)))))))

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
    (let [{:keys [api state]} (setup-api)]
      (is (empty? (:commands @state)) "no commands registered")
      (is (empty? (:tools @state)) "no tools registered")
      ;; Operations are not separately trackable in the nullable API — they
      ;; are dispatched through the same handler mechanism as other mutations,
      ;; so there is no :operations key on the nullable state map.
      ;; Prompt contributions live in :root-state, not directly on the state map;
      ;; use :list-prompt-contributions on the API to query them.
      (is (empty? ((:list-prompt-contributions api))) "no prompt contributions registered")
      (is (= ["project-context"] (-> @state :turn-augmenters keys sort vec))
          "one turn augmenter is registered"))))

(deftest handler-handles-missing-payload-keys-test
  (testing "handler logs gracefully when :session-id or :turn-id are missing"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))]
      (testing "missing only :turn-id"
        (is (nil? (handler {:session-id "s2"}))
            "handler returns nil")
        (is (= "context-manager: session_turn_finished session-id=s2 turn-id=nil"
               (last (:log-lines @state)))))
      (testing "missing only :session-id"
        (is (nil? (handler {:turn-id "t2"}))
            "handler returns nil")
        (is (= "context-manager: session_turn_finished session-id=nil turn-id=t2"
               (last (:log-lines @state))))))))

(deftest init-robustness-test
  (testing "init handles non-standard api gracefully"
    (testing "missing :on key"
      (is (nil? (context-manager/init {:log (fn [_] nil)}))
          "should return nil and not throw NPE when :on is missing")
      (reset! context-manager/initialized? nil))
    (testing "api is nil"
      (is (nil? (context-manager/init nil))
          "should return nil and not throw NPE when api is nil"))
    (testing "api is not a map"
      (is (nil? (context-manager/init "not-a-map"))
          "should return nil and not throw NPE when api is not a map"))))

(deftest init-recovery-after-missing-on-key-test
  (testing "init recovers after a failed call due to missing :on key"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (nil? (context-manager/init {:log (fn [_] nil)})) "first call fails")
      (is (true? (context-manager/init api)) "subsequent call with valid API succeeds")
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (reset! context-manager/initialized? nil))))

(deftest handler-works-without-log-key-test
  (testing "handler works correctly when :log is missing from API"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (reset! context-manager/initialized? nil)
      (context-manager/init (dissoc api :log))
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler should return nil and not throw NPE when log-fn is missing")
        (is (empty? (:log-lines @state))
            "handler should not log anything when :log is missing from API")))))

(deftest init-return-value-test
  (testing "init returns true on successful first-time initialization"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
      (is (true? (context-manager/init api))
          "init should return true on successful first-time initialization"))
    (testing "init returns nil on second call after successful initialization"
      (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/test/context_manager.clj"})]
        (context-manager/init api)
        (is (nil? (context-manager/init api))
            "init should return nil on subsequent calls after successful initialization")))))

(deftest init-registration-contract-test
  (testing "init registration contract"
    ;; Use a spy on :on to verify call arguments explicitly,
    ;; rather than inspecting the resulting state map.
    (let [call-args (atom nil)
          spy-on    (fn [event-name handler-fn]
                      (reset! call-args {:event-name event-name
                                         :handler-fn handler-fn})
                      handler-fn)
          base-api  (nullable/create-nullable-extension-api
                     {:path "/test/context_manager.clj"})]
      (reset! context-manager/initialized? nil)
      (context-manager/init (assoc (:api base-api) :on spy-on))
      (testing "registered with correct event name"
        (is (= "session_turn_finished" (:event-name @call-args))
            "(:on api) must be called with event name session_turn_finished"))
      (testing "handler is a function"
        (is (fn? (:handler-fn @call-args))
            "registered handler must be a function to be compatible with dispatch pipeline")))))

(deftest handler-purity-test
  (testing "handler does not mutate external state"
    (let [{:keys [state]} (setup-api)
          handler         (first (get-in @state [:handlers "session_turn_finished"]))
          before-state    @state]
      (handler {:session-id "s1" :turn-id "t1"})
      (is (= (dissoc before-state :log-lines) (dissoc @state :log-lines))
          "handler must not mutate the API state map; it should only use the provided log-fn"))))

(deftest handler-log-fn-throws-test
  (testing "handler does not throw when log-fn itself throws an exception"
    (let [throwing-log (fn [_] (throw (ex-info "deliberate" {})))
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          api-with-throwing-log (assoc api :log throwing-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-throwing-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil and not throw when log-fn throws")
        (is (empty? (:log-lines @state))
            "no log output produced when log-fn always throws")))))

(deftest handler-log-fn-throws-logs-error-test
  (testing "handler logs error message when log-fn throws during normal logging"
    (let [call-count (atom 0)
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          real-log (:log api)
          flaky-log  (fn [text]
                       (swap! call-count inc)
                       (if (= @call-count 1)
                         (throw (ex-info "first call fails" {}))
                         (real-log text)))
          api-with-flaky-log (assoc api :log flaky-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-flaky-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil and not throw")
        (is (= "context-manager: handler error: first call fails"
               (last (:log-lines @state)))
            "error message with exact prefix and exception message is logged")))))

(deftest handler-log-fn-returns-non-nil-test
  (testing "handler returns nil even when log-fn returns a non-nil value"
    (let [returning-log (fn [text] (str "not-nil-" text))
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"})
          api-with-returning-log (assoc api :log returning-log)]
      (reset! context-manager/initialized? nil)
      (context-manager/init api-with-returning-log)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil regardless of what log-fn returns")))))

(deftest project-context-augmentation-test
  (testing "returns working-directory append-context-block"
    (is (= {:turn-augmentation/status :success
            :turn-augmentation/operations
            [{:op :append-context-block
              :id "project-context"
              :title "Project context"
              :content "Working directory: /repo"}]
            :turn-augmentation/child-session-ids []}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "s1"
             :turn-augmentation/effective-cwd "/repo"}))))

  (testing "returns no-op when effective cwd is absent"
    (is (= {:turn-augmentation/status :no-op
            :turn-augmentation/operations []
            :turn-augmentation/child-session-ids []
            :turn-augmentation/diagnostic "no effective cwd"}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "s1"
             :turn-augmentation/effective-cwd ""}))))

  (testing "returns no-op for tracked helper sessions to avoid recursion"
    (swap! context-manager/helper-session-ids conj "helper-1")
    (is (= {:turn-augmentation/status :no-op
            :turn-augmentation/operations []
            :turn-augmentation/child-session-ids []}
           (context-manager/project-context-augmentation
            {:turn-augmentation/session-id "helper-1"
             :turn-augmentation/effective-cwd "/repo"})))))

(deftest init-registers-turn-augmenter-test
  (testing "init registers project-context with the dedicated augmentation API"
    (let [{:keys [state]} (setup-api)
          registration (get-in @state [:turn-augmenters "project-context"])]
      (is (= "project-context" (:augmenter-id registration)))
      (is (fn? (:handler registration)))
      (is (= :success
             (:turn-augmentation/status
              ((:handler registration)
               {:turn-augmentation/session-id "s"
                :turn-augmentation/effective-cwd "/repo"})))))))
