(ns psi.agent-session.config-resolution-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.config-resolution :as config-resolution]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.user-config :as user-config]))

(deftest resolve-config-test
  ;; Tests system/user/project precedence and nil-safe :agent-session extraction.
  (testing "returns system defaults when user and project config are empty"
    (with-redefs [user-config/read-config              (fn [] {})
                  project-prefs/read-preferences       (fn [_cwd] {})]
      (is (= {:model-provider nil
              :model-id nil
              :thinking-level :off
              :prompt-mode :lambda
              :nucleus-prelude-override nil}
             (config-resolution/resolve-config "/tmp/project")))))

  (testing "merges user config over system defaults"
    (with-redefs [user-config/read-config        (fn [] {:agent-session {:model-provider "anthropic"
                                                                         :model-id "claude-sonnet-4"
                                                                         :thinking-level :medium}})
                  project-prefs/read-preferences (fn [_cwd] {})]
      (is (= {:model-provider "anthropic"
              :model-id "claude-sonnet-4"
              :thinking-level :medium
              :prompt-mode :lambda
              :nucleus-prelude-override nil}
             (config-resolution/resolve-config "/tmp/project")))))

  (testing "merges project config over user config"
    (with-redefs [user-config/read-config        (fn [] {:agent-session {:model-provider "anthropic"
                                                                         :model-id "claude-sonnet-4"
                                                                         :thinking-level :medium
                                                                         :prompt-mode :prose
                                                                         :nucleus-prelude-override "user prelude"}})
                  project-prefs/read-preferences (fn [cwd]
                                                   (is (= "/tmp/project" cwd))
                                                   {:agent-session {:model-provider "openai"
                                                                    :model-id "gpt-5"
                                                                    :prompt-mode :lambda}})]
      (is (= {:model-provider "openai"
              :model-id "gpt-5"
              :thinking-level :medium
              :prompt-mode :lambda
              :nucleus-prelude-override "user prelude"}
             (config-resolution/resolve-config "/tmp/project")))))

  (testing "ignores non-agent-session keys from user and project config"
    (with-redefs [user-config/read-config        (fn [] {:version 1
                                                         :unrelated true
                                                         :agent-session {:thinking-level :high}})
                  project-prefs/read-preferences (fn [_cwd] {:version 1
                                                             :other :value})]
      (is (= {:model-provider nil
              :model-id nil
              :thinking-level :high
              :prompt-mode :lambda
              :nucleus-prelude-override nil}
             (config-resolution/resolve-config "/tmp/project"))))))

(deftest resolved-model-test
  ;; Tests typed model extraction and provider keyword coercion.
  (testing "returns keywordized provider and id when both fields are valid strings"
    (is (= {:provider :anthropic :id "claude-sonnet-4"}
           (config-resolution/resolved-model {:model-provider "anthropic"
                                              :model-id "claude-sonnet-4"}))))

  (testing "returns nil when provider or id is missing or invalid"
    (is (nil? (config-resolution/resolved-model {:model-provider "anthropic"})))
    (is (nil? (config-resolution/resolved-model {:model-id "claude-sonnet-4"})))
    (is (nil? (config-resolution/resolved-model {:model-provider :anthropic
                                                 :model-id "claude-sonnet-4"})))
    (is (nil? (config-resolution/resolved-model {:model-provider "anthropic"
                                                 :model-id :claude-sonnet-4})))))

(deftest resolved-thinking-level-test
  ;; Tests thinking-level fallback to :off.
  (testing "returns configured keyword thinking level"
    (is (= :high
           (config-resolution/resolved-thinking-level {:thinking-level :high}))))

  (testing "falls back to :off for non-keyword values"
    (is (= :off
           (config-resolution/resolved-thinking-level {:thinking-level "high"})))
    (is (= :off
           (config-resolution/resolved-thinking-level {})))))

(deftest resolved-prompt-mode-test
  ;; Tests prompt-mode validation and fallback behavior.
  (testing "returns supported prompt modes"
    (is (= :lambda
           (config-resolution/resolved-prompt-mode {:prompt-mode :lambda})))
    (is (= :prose
           (config-resolution/resolved-prompt-mode {:prompt-mode :prose}))))

  (testing "falls back to :lambda for unsupported values"
    (is (= :lambda
           (config-resolution/resolved-prompt-mode {:prompt-mode :xml})))
    (is (= :lambda
           (config-resolution/resolved-prompt-mode {:prompt-mode "prose"})))
    (is (= :lambda
           (config-resolution/resolved-prompt-mode {})))))

(deftest resolved-nucleus-prelude-override-test
  ;; Tests string-only nucleus prelude override extraction.
  (testing "returns string override"
    (is (= "custom prelude"
           (config-resolution/resolved-nucleus-prelude-override
            {:nucleus-prelude-override "custom prelude"}))))

  (testing "returns nil for non-string or missing override"
    (is (nil? (config-resolution/resolved-nucleus-prelude-override
               {:nucleus-prelude-override :custom})))
    (is (nil? (config-resolution/resolved-nucleus-prelude-override {})))))
