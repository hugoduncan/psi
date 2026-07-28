(ns psi.app-runtime.tui-frontend-actions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.ai.model-registry :as model-registry]
   [psi.app-runtime.tui-frontend-actions :as sut]
   [psi.session-state.state :as ss]
   [psi.shared-config.project :as project-prefs]))

(defn- create-session-context
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- select-model
  "Invoke the TUI select-model action-result handler for `value`, using the
  fixed resolver/no-op wiring shared by every model-selection case. Returns the
  handler's result map."
  [ctx sid value]
  (sut/handle-action-result
   {:ctx ctx
    :sid sid
    :action-result {:ui.result/action-key :select-model
                    :ui.result/status :submitted
                    :ui.result/value value}
    :resolve-model-by-provider+id model-registry/resolve-runtime-model
    :switch-session-fn! (fn [_] nil)
    :fork-session-fn! (fn [_] nil)
    :set-focus! (fn [_] nil)}))

(deftest handle-action-result-model-selection-uses-omitted-scope-default-test
  (testing "TUI direct model selection persists through the omitted-scope default"
    (let [cwd      (str (System/getProperty "java.io.tmpdir") "/psi-tui-model-scope-" (java.util.UUID/randomUUID))
          _        (.mkdirs (java.io.File. cwd))
          local-f  (project-prefs/project-local-preferences-file cwd)
          [ctx sid] (create-session-context {:cwd cwd})]
      (is (= {:type :text
              :message "✓ Model set to openai gpt-5.3-codex"}
             (select-model ctx sid {:provider "openai" :id "gpt-5.3-codex"})))
      (is (= {:provider "openai"
              :id "gpt-5.3-codex"
              :reasoning true}
             (:model (ss/get-session-data-in ctx sid))))
      (is (.exists local-f))
      (is (= "openai" (get-in (read-string (slurp local-f)) [:agent-session :model-provider])))
      (is (= "gpt-5.3-codex" (get-in (read-string (slurp local-f)) [:agent-session :model-id]))))))

(deftest handle-action-result-model-selection-rejects-unsupported-runtime-model-test
  (testing "TUI direct model selection rejects unsupported runtime models without mutating the session"
    (let [[ctx sid] (create-session-context {:oauth-ctx (test-support/oauth-openai-ctx)})
          original  (:model (ss/get-session-data-in ctx sid))]
      (is (= {:type :text
              :message "Unsupported model: openai gpt-5.6 — gpt-5.6 is not supported for OpenAI OAuth without an evidenced ChatGPT/Codex alias or alternate OAuth-compatible transport"}
             (select-model ctx sid {:provider "openai" :id "gpt-5.6"})))
      (is (= original (:model (ss/get-session-data-in ctx sid)))))))
