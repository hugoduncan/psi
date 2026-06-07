(ns psi.agent-session.commands-session-profile-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.commands.session-profile :as session-profile-command]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]
   [psi.shared-config.user :as user-config]))

(def ^:private reasoning-model
  {:provider "anthropic" :id "claude-opus-4-8" :reasoning true :supports-reasoning true})

(def ^:private no-reasoning-model
  {:provider "anthropic" :id "claude-3-5-haiku-20241022" :reasoning false})

(def ^:private cmd-opts
  {:oauth-ctx nil
   :ai-model {:provider :anthropic :id "test-model" :name "Test"}
   :supports-session-tree? true})

(defn- create-session-context
  [opts]
  (let [ctx (session/create-context (test-support/safe-context-opts opts))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- user-config-file-in
  [cwd]
  (java.io.File. (str cwd "/user-home/.psi/agent/config.edn")))

(defn- write-user-config!
  [cwd config]
  (let [f (user-config-file-in cwd)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str config))
    f))

(defn- session-state
  [ctx session-id]
  (select-keys (ss/get-session-data-in ctx session-id)
               [:model :thinking-level :speed-mode :effort-override :selected-session-profile]))

(deftest session-profile-token-parser-test
  ;; Tests command-token normalization without any session mutation.
  (testing "bare and EDN-style names normalize to the same unqualified keyword"
    (is (= {:action :select :profile-name :planning}
           (session-profile-command/parse-profile-argument "planning")))
    (is (= {:action :select :profile-name :planning}
           (session-profile-command/parse-profile-argument ":planning"))))
  (testing "bare clear is the clear action and :clear remains a selectable-token parse"
    (is (= {:action :clear}
           (session-profile-command/parse-profile-argument "clear")))
    (is (= {:action :select :profile-name :clear}
           (session-profile-command/parse-profile-argument ":clear"))))
  (testing "compound, namespaced keyword, and EDN collection tokens fail"
    (is (= :error (:action (session-profile-command/parse-profile-argument "planning extra"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument ":team/coding"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument "{:a 1}"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument "[:planning]"))))))

(deftest dispatch-session-profiles-list-and-query-test
  ;; Tests profile listing and current-profile formatting through backend commands.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:coding {:model-provider "openai"
                                                       :model-id "gpt-5.5"
                                                       :speed-mode :fast}
                                              :team/coding {:speed-mode :normal}
                                              "oops" {:speed-mode :normal}
                                              :empty {}
                                              :clear {:speed-mode :fast}}}})
    (with-redefs [user-config/user-config-file (fn [] (user-config-file-in cwd))]
      (let [[ctx session-id] (create-session-context {:cwd cwd :persist? false})]
        (testing "/session-profiles lists valid and invalid effective profiles"
          (let [message (:message (commands/dispatch-in ctx session-id "/session-profiles" cmd-opts))]
            (is (str/includes? message "coding — model openai/gpt-5.5, speed fast"))
            (is (str/includes? message "team/coding — unavailable: profile name must be an unqualified keyword"))
            (is (not (str/includes? message "  coding — speed normal"))
                "a namespaced :team/coding profile must not display as a selectable coding profile")
            (is (str/includes? message "empty — unavailable: profile has no supported concrete settings"))
            (is (str/includes? message "clear — unavailable: profile name is reserved"))
            (is (str/includes? message "oops — unavailable: profile name must be an unqualified keyword"))))
        (testing "/session-profile shows current concrete settings with no selected metadata"
          (let [message (:message (commands/dispatch-in ctx session-id "/session-profile" cmd-opts))]
            (is (str/includes? message "Selected: (none)"))
            (is (str/includes? message "Speed   : normal"))
            (is (str/includes? message "Effort  : none"))))))))

(deftest dispatch-session-profile-application-test
  ;; Tests atomic live application through the backend command/dispatch path.
  (let [cwd (test-support/temp-cwd)
        user-config {:agent-session {:session-profiles
                                     {:coding {:model-provider "anthropic"
                                               :model-id "claude-opus-4-8"
                                               :thinking-level :high
                                               :speed-mode :normal
                                               :effort-override nil}
                                      :thinking-only {:thinking-level :high}
                                      :empty {}
                                      :clear {:speed-mode :fast}}}}]
    (write-user-config! cwd user-config)
    (with-redefs [user-config/user-config-file (fn [] (user-config-file-in cwd))]
      (let [[ctx session-id] (create-session-context
                              {:session-defaults {:model no-reasoning-model
                                                  :thinking-level :off
                                                  :speed-mode :fast
                                                  :effort-override :high
                                                  :system-prompt "test prompt"}
                               :cwd cwd
                               :persist? false})]
        (testing "valid profile applies model, thinking, transient speed/effort, and metadata"
          (let [result (commands/dispatch-in ctx session-id "/session-profile coding" cmd-opts)
                sd     (ss/get-session-data-in ctx session-id)]
            (is (str/includes? (:message result) "✓ Session profile set to coding"))
            (is (= {:provider "anthropic" :id "claude-opus-4-8"}
                   (select-keys (:model sd) [:provider :id])))
            (is (= :high (:thinking-level sd)))
            (is (nil? (:speed-mode sd)) "session-scope normal stores as nil")
            (is (nil? (:effort-override sd)) "explicit nil effort is applied as a clear")
            (is (= :coding (get-in sd [:selected-session-profile :name])))
            (is (contains? (get-in sd [:selected-session-profile :settings]) :effort-override))
            (is (= [:thinking-level :model :model :thinking-level]
                   (mapv :kind (persist/all-entries-in ctx session-id)))
                "only model/thinking journal entries are appended; speed/effort/profile metadata are not")))
        (testing "clear removes only selected-profile metadata"
          (let [before (select-keys (ss/get-session-data-in ctx session-id)
                                    [:model :thinking-level :speed-mode :effort-override])]
            (is (= "✓ Session profile metadata cleared"
                   (:message (commands/dispatch-in ctx session-id "/session-profile clear" cmd-opts))))
            (is (= before
                   (select-keys (ss/get-session-data-in ctx session-id)
                                [:model :thinking-level :speed-mode :effort-override])))
            (is (nil? (:selected-session-profile (ss/get-session-data-in ctx session-id))))))
        (testing "invalid or unknown profile selection leaves state unchanged"
          (let [before (session-state ctx session-id)]
            (is (str/includes? (:message (commands/dispatch-in ctx session-id "/session-profile missing" cmd-opts))
                               "Unknown session profile: missing"))
            (is (= before (session-state ctx session-id)))
            (is (str/includes? (:message (commands/dispatch-in ctx session-id "/session-profile empty" cmd-opts))
                               "Invalid session profile: empty"))
            (is (= before (session-state ctx session-id)))
            (is (str/includes? (:message (commands/dispatch-in ctx session-id "/session-profile :clear" cmd-opts))
                               "Invalid session profile: clear"))
            (is (= before (session-state ctx session-id)))))))))

(deftest session-profile-thinking-clamps-after-model-test
  ;; Tests model-before-thinking semantics: profile thinking is applied against
  ;; the resulting model and therefore clamps to :off for non-reasoning models.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:haiku-high {:model-provider "anthropic"
                                                           :model-id "claude-3-5-haiku-20241022"
                                                           :thinking-level :high}}}})
    (with-redefs [user-config/user-config-file (fn [] (user-config-file-in cwd))]
      (let [[ctx session-id] (create-session-context
                              {:session-defaults {:model reasoning-model
                                                  :thinking-level :high
                                                  :system-prompt "test prompt"}
                               :cwd cwd
                               :persist? false})]
        (commands/dispatch-in ctx session-id "/session-profile haiku-high" cmd-opts)
        (let [sd (ss/get-session-data-in ctx session-id)]
          (is (= "claude-3-5-haiku-20241022" (get-in sd [:model :id])))
          (is (= :off (:thinking-level sd)))
          (is (= [:thinking-level :model :model :thinking-level]
                 (mapv :kind (persist/all-entries-in ctx session-id)))))))))

(deftest session-profile-resolver-test
  ;; Tests profile and selected-profile observability via Pathom EQL.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:coding {:speed-mode :fast}}}})
    (with-redefs [user-config/user-config-file (fn [] (user-config-file-in cwd))]
      (let [[ctx session-id] (create-session-context {:cwd cwd :persist? false})]
        (commands/dispatch-in ctx session-id "/session-profile coding" cmd-opts)
        (let [result (session/query-in ctx session-id
                                       [:psi.agent-session/session-profiles
                                        :psi.agent-session/session-profile-snapshot
                                        :psi.agent-session/selected-session-profile])]
          (is (= [:coding]
                 (keys (:psi.agent-session/session-profiles result))))
          (is (= [:coding]
                 (get-in result [:psi.agent-session/session-profile-snapshot :valid-profile-names])))
          (is (= :coding
                 (get-in result [:psi.agent-session/selected-session-profile :name]))))))))
