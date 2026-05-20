(ns psi.app-runtime-bootstrap-test
  (:require
   [clojure.test :refer [deftest is]]

   [psi.app-runtime :as app-runtime]
   [psi.introspection.core :as introspection]
   [psi.memory.runtime :as memory-runtime]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.prompt-assets.skills :as skills]
   [psi.prompt-assets.system-prompt :as sys-prompt]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]))

(deftest bootstrap-runtime-session-applies-project-preferences-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "openai"
      :model-id "gpt-5.3-codex"
      :thinking-level :high})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})]
      (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
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
        (is (= :high (:thinking-level sd)))))))

(deftest bootstrap-runtime-session-invalid-project-model-falls-back-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "nope"
      :model-id "missing"
      :thinking-level :xhigh})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})]
      (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
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
        (is (= :off (:thinking-level sd)))))))
