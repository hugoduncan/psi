(ns psi.app-runtime-bootstrap-test
  (:require
   [clojure.test :refer [deftest is]]

   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.test-support :as app-test-support]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]))

(deftest bootstrap-runtime-session-applies-project-preferences-test
  (let [cwd (test-support/temp-cwd)]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "openai"
      :model-id "gpt-5.3-codex"
      :thinking-level :high})
    (with-redefs-fn (app-test-support/bootstrap-stub-bindings)
      (fn []
        (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                             {:provider :anthropic
                              :id "claude-sonnet-4-6"
                              :name "Claude Sonnet 4.6"
                              :supports-reasoning true}
                             {:cwd cwd
                              :persist? false})
              session-id      (-> (ss/list-context-sessions-in ctx) first :session-id)
              sd              (ss/get-session-data-in ctx session-id)]
          (is (= "openai" (get-in sd [:model :provider])))
          (is (= "gpt-5.3-codex" (get-in sd [:model :id])))
          (is (= :high (:thinking-level sd))))))))

(deftest bootstrap-runtime-session-invalid-project-model-falls-back-test
  (let [cwd (test-support/temp-cwd)]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "nope"
      :model-id "missing"
      :thinking-level :xhigh})
    (with-redefs-fn (app-test-support/bootstrap-stub-bindings)
      (fn []
        (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                             {:provider :anthropic
                              :id "claude-sonnet-4-6"
                              :name "Claude Sonnet 4.6"
                              :supports-reasoning false}
                             {:cwd cwd
                              :persist? false})
              session-id      (-> (ss/list-context-sessions-in ctx) first :session-id)
              sd              (ss/get-session-data-in ctx session-id)]
          (is (= "anthropic" (get-in sd [:model :provider])))
          (is (= "claude-sonnet-4-6" (get-in sd [:model :id])))
          (is (= :off (:thinking-level sd))))))))

(deftest bootstrap-runtime-session-reuses-pre-created-session-test
  (let [cwd (test-support/temp-cwd)]
    (with-redefs-fn (app-test-support/bootstrap-stub-bindings)
      (fn []
        (let [{:keys [ctx]} (app-runtime/create-runtime-session-context
                             app-test-support/test-ai-model
                             {:ui-type  :console
                              :persist? false
                              :cwd      cwd})
              ;; Pre-create a session — simulates what main.clj does
              pre-created-id (:session-id (session/new-session-in! ctx nil {}))
              _              (is (= 1 (count (ss/list-context-sessions-in ctx)))
                                 "exactly one session before bootstrap")
              result         (app-runtime/bootstrap-runtime-session!
                              ctx app-test-support/test-ai-model {:session-id pre-created-id :cwd cwd})
              sessions-after (ss/list-context-sessions-in ctx)]
          (is (= pre-created-id (:session-id result))
              "bootstrap must reuse the pre-created session-id")
          (is (= 1 (count sessions-after))
              "no extra session created — still exactly one"))))))

(deftest bootstrap-runtime-session-applies-presence-aware-speed-preferences-test
  (let [cwd (test-support/temp-cwd)]
    (project-prefs/update-agent-session! cwd {:speed-mode :normal
                                              :effort-override :xhigh})
    (with-redefs-fn (app-test-support/bootstrap-stub-bindings)
      (fn []
        (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                             app-test-support/test-ai-model
                             {:cwd cwd
                              :persist? false})
              session-id (-> (ss/list-context-sessions-in ctx) first :session-id)
              sd         (ss/get-session-data-in ctx session-id)]
          (is (= :normal (:speed-mode sd)))
          (is (= :xhigh (:effort-override sd)))
          (is (= :normal (:psi.agent-session/speed-mode
                          (session/query-in ctx session-id [:psi.agent-session/speed-mode]))))
          (is (= :xhigh (:psi.agent-session/effort-override
                         (session/query-in ctx session-id [:psi.agent-session/effort-override])))))))))
