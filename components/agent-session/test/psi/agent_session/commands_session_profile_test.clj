(ns psi.agent-session.commands-session-profile-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.commands.session-profile :as session-profile-command]
   [psi.agent-session.context :as session-context]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-journal.store :as journal-store]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]))

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
  [home]
  (java.io.File. (str home "/.psi/agent/config.edn")))

(defn- write-user-config!
  [home config]
  (let [f (user-config-file-in home)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str config))
    f))

(defmacro ^:private with-user-home
  [home & body]
  `(let [old-home# (System/getProperty "user.home")]
     (try
       (System/setProperty "user.home" ~home)
       ~@body
       (finally
         (System/setProperty "user.home" old-home#)))))

(defn- session-state
  [ctx session-id]
  (select-keys (ss/get-session-data-in ctx session-id)
               [:model :thinking-level :speed-mode :effort-override :selected-session-profile]))

(defn- model-identity
  [session-data]
  (select-keys (:model session-data) [:provider :id]))

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
  (testing "compound, namespaced keyword, unselectable character, and EDN collection tokens fail"
    (is (= :error (:action (session-profile-command/parse-profile-argument "planning extra"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument ":team/coding"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument "fast+coding"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument ":fast+coding"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument "{:a 1}"))))
    (is (= :error (:action (session-profile-command/parse-profile-argument "[:planning]"))))))

(deftest dispatch-session-profiles-list-and-query-test
  ;; Tests profile listing and current-profile formatting through backend commands.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:coding {:model-provider "openai"
                                                       :model-id "gpt-5.5"
                                                       :speed-mode :fast}
                                              :fast+coding {:speed-mode :normal}
                                              :team/coding {:speed-mode :normal}
                                              "oops" {:speed-mode :normal}
                                              :empty {}
                                              :clear {:speed-mode :fast}}}})
    (with-user-home cwd
      (let [[ctx session-id] (create-session-context {:cwd cwd :persist? false})]
        (testing "/session-profiles lists valid and invalid effective profiles"
          (let [message (:message (commands/dispatch-in ctx session-id "/session-profiles" cmd-opts))]
            (is (str/includes? message "coding — model openai/gpt-5.5, speed fast"))
            (is (str/includes? message "fast+coding — unavailable: profile name must match /session-profile token characters"))
            (is (str/includes? message "team/coding — unavailable: profile name must be a selectable unqualified keyword"))
            (is (not (str/includes? message "  coding — speed normal"))
                "a namespaced :team/coding profile must not display as a selectable coding profile")
            (is (not (str/includes? message "  fast+coding — speed normal"))
                "a command-unparseable profile must not display as selectable")
            (is (str/includes? message "empty — unavailable: profile has no supported concrete settings"))
            (is (str/includes? message "clear — unavailable: profile name is reserved"))
            (is (str/includes? message "oops — unavailable: profile name must be a selectable unqualified keyword"))))
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
    (with-user-home cwd
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
        (testing "malformed profile tokens leave concrete settings and selected metadata unchanged"
          (let [before (session-state ctx session-id)
                cases  [["/session-profile planning extra" "Usage: /session-profile"]
                        ["/session-profile {}" "Invalid session profile token: {}"]
                        ["/session-profile [:coding]" "Invalid session profile token: [:coding]"]
                        ["/session-profile (:coding)" "Invalid session profile token: (:coding)"]
                        ["/session-profile fast+coding" "Invalid session profile token: fast+coding"]]]
            (doseq [[command expected-message] cases]
              (let [result (commands/dispatch-in ctx session-id command cmd-opts)]
                (is (str/includes? (:message result) expected-message)
                    command)
                (is (= before (session-state ctx session-id))
                    command)))))
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
            (let [message (:message (commands/dispatch-in ctx session-id "/session-profile :clear" cmd-opts))]
              (is (str/includes? message "Invalid session profile: clear"))
              (is (str/includes? message "profile name is reserved"))
              (is (not (str/includes? message "Unknown session profile"))))
            (is (= before (session-state ctx session-id)))))))))

(deftest reserved-clear-profile-selection-without-config-test
  ;; Tests :clear is globally reserved even when config does not define it.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:coding {:speed-mode :fast}}}})
    (with-user-home cwd
      (let [[ctx session-id] (create-session-context
                              {:session-defaults {:model no-reasoning-model
                                                  :thinking-level :off
                                                  :speed-mode :normal
                                                  :effort-override :high
                                                  :system-prompt "test prompt"}
                               :cwd cwd
                               :persist? false})
            before           (session-state ctx session-id)
            result           (commands/dispatch-in ctx session-id "/session-profile :clear" cmd-opts)
            message          (:message result)]
        (is (str/includes? message "Invalid session profile: clear"))
        (is (str/includes? message "profile name is reserved"))
        (is (not (str/includes? message "Unknown session profile")))
        (is (= before (session-state ctx session-id)))))))

(deftest selected-session-profile-metadata-is-not-inherited-or-resumed-test
  ;; Task 217 TT4: selected-profile metadata is session-local observability only.
  ;; Concrete settings may follow their existing lifecycle rules, but descendants
  ;; and cold resume must never claim the parent profile is selected.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:coding {:model-provider "anthropic"
                                                       :model-id "claude-opus-4-8"
                                                       :thinking-level :high
                                                       :speed-mode :fast
                                                       :effort-override :xhigh}}}})
    (with-user-home cwd
      (let [[ctx session-id] (create-session-context
                              {:session-defaults {:model no-reasoning-model
                                                  :thinking-level :off
                                                  :speed-mode :normal
                                                  :effort-override :low
                                                  :system-prompt "test prompt"}
                               :cwd cwd
                               :persist? false})]
        (commands/dispatch-in ctx session-id "/session-profile coding" cmd-opts)
        (let [parent-sd (ss/get-session-data-in ctx session-id)]
          (is (= :coding (get-in parent-sd [:selected-session-profile :name])))

          (testing "new sessions may inherit concrete settings but not selected metadata"
            (let [child-sd (session/new-session-in! ctx session-id {})]
              (is (= {:provider "anthropic" :id "claude-opus-4-8"}
                     (model-identity child-sd)))
              (is (= :high (:thinking-level child-sd)))
              (is (= :fast (:speed-mode child-sd)))
              (is (= :xhigh (:effort-override child-sd)))
              (is (nil? (:selected-session-profile child-sd)))))

          (testing "forked sessions may inherit concrete settings but not selected metadata"
            (let [entry-id (:id (ss/append-journal-entry-in!
                                 ctx session-id
                                 (persist/message-entry {:role "user"
                                                         :content [{:type :text :text "branch here"}]
                                                         :timestamp (java.time.Instant/now)})))
                  fork-sd  (session/fork-session-in! ctx session-id entry-id)]
              (is (= {:provider "anthropic" :id "claude-opus-4-8"}
                     (model-identity fork-sd)))
              (is (= :high (:thinking-level fork-sd)))
              (is (= :fast (:speed-mode fork-sd)))
              (is (= :xhigh (:effort-override fork-sd)))
              (is (nil? (:selected-session-profile fork-sd)))))

          (testing "workflow child sessions may inherit concrete request settings but not selected metadata"
            (let [child-id "workflow-profile-child"
                  result   ((var-get #'session-context/create-workflow-child-session!)
                            ctx session-id
                            {:child-session-id child-id
                             :session-name "workflow child"
                             :system-prompt "workflow system"
                             :tool-ids []
                             :skills []
                             :model (:model parent-sd)
                             :thinking-level (:thinking-level parent-sd)
                             :speed-mode (:speed-mode parent-sd)
                             :effort-override (:effort-override parent-sd)
                             :workflow-run-id "run-tt4"
                             :workflow-step-id "step-tt4"
                             :workflow-attempt-id "attempt-tt4"
                             :workflow-owned? true
                             :inherited-snapshot? true})
                  child-sd (ss/get-session-data-in ctx child-id)]
              (is (= {:psi.agent-session/session-id child-id} result))
              (is (= {:provider "anthropic" :id "claude-opus-4-8"}
                     (model-identity child-sd)))
              (is (= :high (:thinking-level child-sd)))
              (is (= :fast (:speed-mode child-sd)))
              (is (= :xhigh (:effort-override child-sd)))
              (is (nil? (:selected-session-profile child-sd)))))

          (testing "cold journal resume restores journaled model/thinking only and never selected metadata"
            (let [f (java.io.File/createTempFile "psi-session-profile-resume" ".ndedn")]
              (.deleteOnExit f)
              (journal-store/flush-journal! f
                                            "resumed-profile-session"
                                            cwd
                                            nil
                                            (persist/all-entries-in ctx session-id))
              (let [resumed-sd (session/resume-session-in! ctx session-id (.getAbsolutePath f))]
                (is (= {:provider "anthropic" :id "claude-opus-4-8"}
                       (model-identity resumed-sd)))
                (is (= :high (:thinking-level resumed-sd)))
                (is (nil? (:speed-mode resumed-sd)))
                (is (nil? (:effort-override resumed-sd)))
                (is (nil? (:selected-session-profile resumed-sd)))))))))))

(deftest session-profile-thinking-clamps-after-model-test
  ;; Tests model-before-thinking semantics: profile thinking is applied against
  ;; the resulting model and therefore clamps to :off for non-reasoning models.
  (let [cwd (test-support/temp-cwd)]
    (write-user-config! cwd {:agent-session {:session-profiles
                                             {:haiku-high {:model-provider "anthropic"
                                                           :model-id "claude-3-5-haiku-20241022"
                                                           :thinking-level :high}}}})
    (with-user-home cwd
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
    (with-user-home cwd
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
