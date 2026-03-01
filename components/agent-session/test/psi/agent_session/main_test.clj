(ns psi.agent-session.main-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.main :as main]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.agent-session.executor :as executor]
   [psi.tui.app :as tui-app]))

(deftest select-login-provider-test
  (let [providers [{:id :anthropic :name "Anthropic"}
                   {:id :openai :name "OpenAI"}]]

    (testing "defaults to active model provider when no explicit provider arg"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai nil)]
        (is (nil? error))
        (is (= :openai (:id provider)))))

    (testing "explicit provider arg overrides active provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "anthropic")]
        (is (nil? error))
        (is (= :anthropic (:id provider)))))

    (testing "returns clear error for unknown explicit provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "not-a-provider")]
        (is (nil? provider))
        (is (str/includes? error "Unknown OAuth provider"))
        (is (str/includes? error "anthropic"))
        (is (str/includes? error "openai"))))))

(deftest select-login-provider-missing-active-provider-test
  (testing "does not silently fall back to another provider"
    (let [providers [{:id :anthropic :name "Anthropic"}]
          {:keys [provider error]}
          (commands/select-login-provider providers :openai nil)]
      (is (nil? provider))
      (is (str/includes? error "not available for model provider openai"))
      (is (str/includes? error "OPENAI_API_KEY"))
      (is (str/includes? error "anthropic")))))

(deftest run-session-initializes-session-file-test
  (let [orig-state @main/session-state]
    (try
      (with-redefs [psi.agent-session.main/resolve-model
                    (fn [_]
                      {:provider           :anthropic
                       :id                 "test-model"
                       :name               "Test Model"
                       :supports-reasoning false
                       :context-window     200000})
                    oauth/create-context (fn [] nil)
                    pt/discover-templates (fn [] [])
                    skills/discover-skills (fn [] {:skills [] :diagnostics []})
                    sys-prompt/discover-context-files (fn [_] [])
                    sys-prompt/build-system-prompt (fn [_] "")
                    ext/discover-extension-paths (fn [& _] [])
                    session/bootstrap-session-in!
                    (fn [ctx _]
                      (session/new-session-in! ctx)
                      {:extension-errors [] :extension-loaded-count 0})
                    clojure.core/read-line (let [calls (atom 0)]
                                             (fn []
                                               (if (= 1 (swap! calls inc))
                                                 "/quit"
                                                 nil)))]
        (main/run-session :ignored)
        (let [ctx (:ctx @main/session-state)]
          (is (some? ctx))
          (is (string? (:session-file (session/get-session-data-in ctx))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-tui-session-passes-current-session-file-test
  (let [orig-state @main/session-state
        captured   (atom nil)]
    (try
      (with-redefs [psi.agent-session.main/resolve-model
                    (fn [_]
                      {:provider           :anthropic
                       :id                 "test-model"
                       :name               "Test Model"
                       :supports-reasoning false
                       :context-window     200000})
                    oauth/create-context (fn [] nil)
                    pt/discover-templates (fn [] [])
                    skills/discover-skills (fn [] {:skills [] :diagnostics []})
                    sys-prompt/discover-context-files (fn [_] [])
                    sys-prompt/build-system-prompt (fn [_] "")
                    ext/discover-extension-paths (fn [& _] [])
                    session/bootstrap-session-in!
                    (fn [ctx _]
                      (session/new-session-in! ctx)
                      {:extension-errors [] :extension-loaded-count 0})
                    tui-app/start! (fn [_model-name _run-agent-fn opts]
                                     (reset! captured opts)
                                     :ok)]
        (is (= :ok (main/run-tui-session :ignored)))
        (is (string? (:current-session-file @captured)))
        (is (fn? (:dispatch-fn @captured)))
        (is (fn? (:on-interrupt-fn! @captured))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest agent-messages->tui-resume-state-rehydrates-tool-rows-test
  (let [messages [{:role "user"
                   :content [{:type :text :text "read file"}]}
                  {:role "assistant"
                   :content [{:type :text :text "Sure"}
                             {:type :tool-call :id "call-1" :name "read"
                              :arguments "{\"path\":\"a.txt\"}"}]}
                  {:role "toolResult"
                   :tool-call-id "call-1"
                   :tool-name "read"
                   :content [{:type :text :text "hello"}
                             {:type :image :mime-type "image/png" :data "<base64>"}]
                   :details {:full-output-path "/tmp/all.log"}
                   :is-error false}
                  {:role "assistant"
                   :content [{:type :text :text "done"}]}]
        {:keys [messages tool-calls tool-order]}
        (#'main/agent-messages->tui-resume-state messages)]
    (is (= [{:role :user :text "read file"}
            {:role :assistant :text "Sure"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-1"] tool-order))
    (is (= "read" (get-in tool-calls ["call-1" :name])))
    (is (= :success (get-in tool-calls ["call-1" :status])))
    (is (= "hello" (get-in tool-calls ["call-1" :result])))
    (is (= {:full-output-path "/tmp/all.log"}
           (get-in tool-calls ["call-1" :details])))))
