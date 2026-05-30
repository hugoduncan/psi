(ns psi.agent-session.commands-speed-effort-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.user :as user-config]))

(defn- create-session-context
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- make-test-ctx
  ([] (make-test-ctx {}))
  ([opts]
   (create-session-context
    {:session-defaults (merge {:model {:provider "anthropic"
                                       :id       "test-model"
                                       :reasoning false}
                               :system-prompt "test prompt"}
                              opts)
     :cwd (test-support/temp-cwd)
     :persist? false})))

(def ^:private cmd-opts
  {:oauth-ctx nil
   :ai-model {:provider :anthropic :id "test-model" :name "Test"}
   :supports-session-tree? true})

(deftest dispatch-speed-command-test
  ;; The /speed command exposes nil session state as normal and supports scoped updates.
  (testing "no args shows normal by default"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/speed" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "Current speed mode: normal" (:message result)))
      (is (nil? (:speed-mode (ss/get-session-data-in ctx session-id))))))

  (testing "fast sets in-memory speed mode"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/speed fast" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Speed mode set to fast" (:message result)))
      (is (= :fast (:speed-mode (ss/get-session-data-in ctx session-id))))))

  (testing "normal session clears in-memory speed mode to nil"
    (let [[ctx session-id] (make-test-ctx {:session-defaults {:speed-mode :fast}})
          result (commands/dispatch-in ctx session-id "/speed normal session" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Speed mode set to normal [session]" (:message result)))
      (is (nil? (:speed-mode (ss/get-session-data-in ctx session-id))))))

  (testing "project-scoped normal stores explicit normal in current session"
    (let [cwd (test-support/temp-cwd)
          [ctx session-id] (make-test-ctx {:cwd cwd})
          result (commands/dispatch-in ctx session-id "/speed normal project" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Speed mode set to normal [project]" (:message result)))
      (is (= :normal (:speed-mode (ss/get-session-data-in ctx session-id))))))

  (testing "project/user scoped commands persist speed config, including explicit normal masks"
    (let [cwd      (test-support/temp-cwd)
          local-f  (project-prefs/project-local-preferences-file cwd)
          user-f   (java.io.File. (str cwd "/user-home/.psi/agent/config.edn"))
          _        (.mkdirs (.getParentFile local-f))
          _        (.mkdirs (.getParentFile user-f))
          [ctx session-id] (create-session-context {:cwd cwd :persist? false})]
      (with-redefs [user-config/user-config-file (fn [] user-f)]
        (commands/dispatch-in ctx session-id "/speed fast project" cmd-opts)
        (is (= :fast (get-in (edn/read-string (slurp local-f))
                             [:agent-session :speed-mode])))
        (commands/dispatch-in ctx session-id "/speed normal project" cmd-opts)
        (is (= :normal (get-in (edn/read-string (slurp local-f))
                               [:agent-session :speed-mode])))
        (commands/dispatch-in ctx session-id "/speed fast user" cmd-opts)
        (is (= :fast (get-in (edn/read-string (slurp user-f))
                             [:agent-session :speed-mode])))
        (commands/dispatch-in ctx session-id "/speed normal user" cmd-opts)
        (is (= :normal (get-in (edn/read-string (slurp user-f))
                               [:agent-session :speed-mode]))))))

  (testing "unknown mode and scope report allowed values"
    (let [[ctx session-id] (make-test-ctx)]
      (is (= "Unknown speed mode: turbo. Allowed: normal, fast"
             (:message (commands/dispatch-in ctx session-id "/speed turbo" cmd-opts))))
      (is (= "Unknown speed scope: bogus. Allowed: session, project, user"
             (:message (commands/dispatch-in ctx session-id "/speed fast bogus" cmd-opts))))
      (is (= "Usage: /speed OR /speed <normal|fast> [session|project|user]"
             (:message (commands/dispatch-in ctx session-id "/speed fast project extra" cmd-opts)))))))

(deftest dispatch-effort-command-test
  ;; The /effort command exposes nil session state as none and supports scoped updates.
  (testing "no args shows none by default"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/effort" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "Current effort override: none" (:message result)))
      (is (nil? (:effort-override (ss/get-session-data-in ctx session-id))))))

  (testing "xhigh sets in-memory effort override"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/effort xhigh" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Effort override set to xhigh" (:message result)))
      (is (= :xhigh (:effort-override (ss/get-session-data-in ctx session-id))))))

  (testing "none clears in-memory effort override to nil"
    (let [[ctx session-id] (make-test-ctx {:session-defaults {:effort-override :high}})
          result (commands/dispatch-in ctx session-id "/effort none session" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Effort override set to none [session]" (:message result)))
      (is (nil? (:effort-override (ss/get-session-data-in ctx session-id))))))

  (testing "project-scoped xhigh stores override in current session"
    (let [cwd (test-support/temp-cwd)
          [ctx session-id] (make-test-ctx {:cwd cwd})
          result (commands/dispatch-in ctx session-id "/effort xhigh project" cmd-opts)]
      (is (= :text (:type result)))
      (is (= "✓ Effort override set to xhigh [project]" (:message result)))
      (is (= :xhigh (:effort-override (ss/get-session-data-in ctx session-id))))))

  (testing "project/user scoped commands persist effort config, including explicit nil masks"
    (let [cwd      (test-support/temp-cwd)
          local-f  (project-prefs/project-local-preferences-file cwd)
          user-f   (java.io.File. (str cwd "/user-home/.psi/agent/config.edn"))
          _        (.mkdirs (.getParentFile local-f))
          _        (.mkdirs (.getParentFile user-f))
          [ctx session-id] (create-session-context {:cwd cwd :persist? false})]
      (with-redefs [user-config/user-config-file (fn [] user-f)]
        (commands/dispatch-in ctx session-id "/effort xhigh project" cmd-opts)
        (is (= :xhigh (get-in (edn/read-string (slurp local-f))
                              [:agent-session :effort-override])))
        (commands/dispatch-in ctx session-id "/effort none project" cmd-opts)
        (is (contains? (:agent-session (edn/read-string (slurp local-f)))
                       :effort-override))
        (is (nil? (get-in (edn/read-string (slurp local-f))
                          [:agent-session :effort-override])))
        (commands/dispatch-in ctx session-id "/effort high user" cmd-opts)
        (is (= :high (get-in (edn/read-string (slurp user-f))
                             [:agent-session :effort-override])))
        (commands/dispatch-in ctx session-id "/effort none user" cmd-opts)
        (is (contains? (:agent-session (edn/read-string (slurp user-f)))
                       :effort-override))
        (is (nil? (get-in (edn/read-string (slurp user-f))
                          [:agent-session :effort-override]))))))

  (testing "unknown effort and scope report allowed values"
    (let [[ctx session-id] (make-test-ctx)]
      (is (= "Unknown effort override: turbo. Allowed: low, medium, high, xhigh, none"
             (:message (commands/dispatch-in ctx session-id "/effort turbo" cmd-opts))))
      (is (= "Unknown effort scope: bogus. Allowed: session, project, user"
             (:message (commands/dispatch-in ctx session-id "/effort xhigh bogus" cmd-opts))))
      (is (= "Usage: /effort OR /effort <low|medium|high|xhigh|none> [session|project|user]"
             (:message (commands/dispatch-in ctx session-id "/effort high project extra" cmd-opts)))))))
