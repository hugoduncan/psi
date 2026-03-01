(ns psi.agent-session.commands-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-core.core :as agent]
   [psi.memory.core :as memory]
   [psi.recursion.core :as recursion]))

;; ── Test helper ─────────────────────────────────────────────

(defn- make-test-ctx
  "Create a minimal session context for testing commands."
  ([] (make-test-ctx {}))
  ([opts]
   (session/create-context
    {:initial-session (merge {:model {:provider "anthropic"
                                      :id       "test-model"
                                      :reasoning false}
                              :system-prompt "test prompt"}
                             opts)
     :persist? false})))

(def ^:private test-ai-model
  {:provider :anthropic :id "test-model" :name "Test"})

(def ^:private cmd-opts
  {:oauth-ctx nil :ai-model test-ai-model})

(defn- with-recursion-ctx
  [ctx]
  (let [rctx (recursion/create-context)]
    (recursion/register-hooks-in! rctx)
    (assoc ctx :recursion-ctx rctx)))

(defn- with-ready-memory-ctx
  [ctx]
  (assoc ctx :memory-ctx
         (memory/create-context {:state-overrides {:status :ready}})))

;; ── dispatch tests ──────────────────────────────────────────

(deftest dispatch-quit-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :quit} (commands/dispatch ctx "/quit" cmd-opts)))
    (is (= {:type :quit} (commands/dispatch ctx "/exit" cmd-opts)))))

(deftest dispatch-new-session-test
  (let [ctx (make-test-ctx)
        _   (session/new-session-in! ctx)
        old-id (:session-id (session/get-session-data-in ctx))
        result (commands/dispatch ctx "/new" cmd-opts)]
    (is (= :new-session (:type result)))
    (is (string? (:message result)))
    ;; Session ID should have changed
    (is (not= old-id (:session-id (session/get-session-data-in ctx))))))

(deftest dispatch-resume-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :resume} (commands/dispatch ctx "/resume" cmd-opts)))))

(deftest dispatch-status-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/status" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Session status"))
    (is (str/includes? (:message result) "Phase"))))

(deftest dispatch-history-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/history" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Message history"))
    (is (str/includes? (:message result) "(empty)"))))

(deftest dispatch-help-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/help" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "/quit"))
    (is (str/includes? (:message result) "/new"))
    (is (str/includes? (:message result) "/login")))
  (testing "/? is an alias for /help"
    (let [ctx (make-test-ctx)]
      (is (= :text (:type (commands/dispatch ctx "/?" cmd-opts)))))))

(deftest dispatch-prompts-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/prompts" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Prompt Templates"))))

(deftest dispatch-skills-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/skills" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Skills"))))

(deftest dispatch-feed-forward-without-recursion-ctx-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/feed-forward" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "not configured"))))

(deftest dispatch-feed-forward-accepted-test
  (let [ctx    (-> (make-test-ctx)
                   with-recursion-ctx
                   with-ready-memory-ctx)
        result (commands/dispatch ctx "/feed-forward verify runtime" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "trigger accepted"))
    (is (str/includes? (:message result) "feed-forward-manual-trigger"))
    (is (str/includes? (:message result) "cycle-id:"))
    (is (str/includes? (:message result) "awaiting approval"))))

(deftest dispatch-feed-forward-busy-test
  (let [ctx  (-> (make-test-ctx)
                 with-recursion-ctx
                 with-ready-memory-ctx)
        _    (commands/dispatch ctx "/feed-forward first" cmd-opts)
        busy (commands/dispatch ctx "/feed-forward second" cmd-opts)]
    (is (= :text (:type busy)))
    (is (str/includes? (:message busy) "trigger rejected"))
    (is (str/includes? (:message busy) "controller-busy"))))

(deftest dispatch-feed-forward-blocked-when-live-readiness-fails-test
  (let [ctx    (with-recursion-ctx (make-test-ctx))
        result (commands/dispatch ctx "/feed-forward check live readiness" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "trigger blocked"))
    (is (str/includes? (:message result) "recursion_prerequisites_not_ready"))))

(deftest dispatch-not-a-command-test
  (testing "plain text returns nil"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "hello world" cmd-opts)))))
  (testing "skill invocation returns nil (handled by agent)"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "/skill:foo" cmd-opts)))))
  (testing "prompt template returns nil (handled by agent)"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "/my-template" cmd-opts))))))

(deftest dispatch-login-no-oauth-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/login" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :login-error (:type result)))))

(deftest dispatch-logout-no-oauth-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/logout" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "not available"))))

(deftest dispatch-extension-cmd-test
  (let [ctx       (make-test-ctx)
        reg       (:extension-registry ctx)
        called    (atom nil)
        handler   (fn [args] (reset! called args))
        _         (ext/register-extension-in! reg "test-ext")
        _         (ext/register-command-in! reg
                                            "test-ext"
                                            {:name "test-cmd"
                                             :description "A test command"
                                             :handler handler})
        result    (commands/dispatch ctx "/test-cmd some args" cmd-opts)]
    (is (= :extension-cmd (:type result)))
    (is (= "test-cmd" (:name result)))
    (is (= "some args" (:args result)))
    (is (fn? (:handler result)))))

;; ── format-* tests ──────────────────────────────────────────

(deftest format-status-test
  (let [ctx (make-test-ctx)
        s   (commands/format-status ctx)]
    (is (str/includes? s "Phase"))
    (is (str/includes? s "idle"))))

(deftest format-history-empty-test
  (let [ctx (make-test-ctx)
        s   (commands/format-history ctx)]
    (is (str/includes? s "(empty)"))))

(deftest format-history-with-messages-test
  (let [ctx (make-test-ctx)]
    (agent/append-message-in! (:agent-ctx ctx)
                              {:role "user" :content [{:type :text :text "hello"}]})
    (let [s (commands/format-history ctx)]
      (is (str/includes? s "[user]"))
      (is (str/includes? s "hello")))))

(deftest format-help-includes-all-commands-test
  (let [ctx (make-test-ctx)
        s   (commands/format-help ctx)]
    (doseq [cmd ["/quit" "/status" "/history" "/new" "/resume"
                 "/login" "/logout" "/feed-forward" "/help" "/prompts" "/skills"]]
      (is (str/includes? s cmd) (str "help should mention " cmd)))))

(deftest format-prompts-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-prompts ctx)]
    (is (str/includes? s "(none discovered)"))))

(deftest format-skills-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-skills ctx)]
    (is (str/includes? s "(none discovered)"))))

