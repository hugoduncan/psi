(ns psi.shared-config.resolution-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.resolution :as config-resolution]
   [psi.shared-config.user :as user-config]))

(deftest resolve-config-test
  (testing "returns system defaults when user and project config are empty"
    (with-redefs [user-config/read-config        (fn [] {})
                  project-prefs/read-preferences (fn [_cwd] {})]
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
  (testing "returns configured keyword thinking level"
    (is (= :high
           (config-resolution/resolved-thinking-level {:thinking-level :high}))))

  (testing "falls back to :off for non-keyword values"
    (is (= :off
           (config-resolution/resolved-thinking-level {:thinking-level "high"})))
    (is (= :off
           (config-resolution/resolved-thinking-level {})))))

(deftest resolved-prompt-mode-test
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
  (testing "returns string override"
    (is (= "custom prelude"
           (config-resolution/resolved-nucleus-prelude-override
            {:nucleus-prelude-override "custom prelude"}))))

  (testing "returns nil for non-string or missing override"
    (is (nil? (config-resolution/resolved-nucleus-prelude-override
               {:nucleus-prelude-override :custom})))
    (is (nil? (config-resolution/resolved-nucleus-prelude-override {})))))

(deftest resolved-effort-override-test
  ;; resolved-effort-override preserves explicit nil masks and effort keywords.
  (testing "valid values preserve presence"
    (is (= {:present? true :value nil}
           (config-resolution/resolved-effort-override {:effort-override nil})))
    (is (= {:present? true :value :xhigh}
           (config-resolution/resolved-effort-override {:effort-override :xhigh}))))
  (testing "missing and invalid values are absent"
    (is (nil? (config-resolution/resolved-effort-override {})))
    (is (nil? (config-resolution/resolved-effort-override {:effort-override "xhigh"})))
    (is (nil? (config-resolution/resolved-effort-override {:effort-override :turbo})))))

(deftest resolved-speed-mode-test
  ;; Speed resolution is presence-aware so explicit :normal can mask lower layers.
  (testing "returns presence-aware valid speed modes"
    (is (= {:present? true :value :normal}
           (config-resolution/resolved-speed-mode {:speed-mode :normal})))
    (is (= {:present? true :value :fast}
           (config-resolution/resolved-speed-mode {:speed-mode :fast}))))

  (testing "returns nil for absent or invalid speed modes"
    (is (nil? (config-resolution/resolved-speed-mode {})))
    (is (nil? (config-resolution/resolved-speed-mode {:speed-mode "fast"})))
    (is (nil? (config-resolution/resolved-speed-mode {:speed-mode :turbo})))))
