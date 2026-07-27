(ns psi.app-runtime.tui-frontend-actions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.app-runtime.tui-frontend-actions :as sut]))

(deftest handle-action-result-model-selection-uses-omitted-scope-default-test
  (testing "TUI direct model selection preserves omitted-scope/default helper semantics"
    (let [ctx      {:k :v}
          sid      "sid-1"
          captured (atom nil)
          result   {:ui.result/action-key :select-model
                    :ui.result/status :submitted
                    :ui.result/value {:provider "openai" :id "gpt-5.3-codex"}}
          resolve-model-by-provider+id
          (fn [ctx' provider id]
            (when (and (= ctx' ctx)
                       (= [provider id] ["openai" "gpt-5.3-codex"]))
              {:provider :openai :id "gpt-5.3-codex" :supports-reasoning true}))]
      (with-redefs [session/set-model-in! (fn [ctx' sid' model & [scope]]
                                            (reset! captured {:ctx ctx'
                                                              :sid sid'
                                                              :model model
                                                              :scope scope})
                                            {:model model})]
        (is (= {:type :text
                :message "✓ Model set to openai gpt-5.3-codex"}
               (sut/handle-action-result {:ctx ctx
                                          :sid sid
                                          :action-result result
                                          :resolve-model-by-provider+id resolve-model-by-provider+id
                                          :switch-session-fn! (fn [_] nil)
                                          :fork-session-fn! (fn [_] nil)
                                          :set-focus! (fn [_] nil)})))
        (is (= {:ctx ctx
                :sid sid
                :model {:provider "openai"
                        :id "gpt-5.3-codex"
                        :reasoning true}
                :scope nil}
               @captured))))))

(deftest handle-action-result-model-selection-rejects-unsupported-runtime-model-test
  (testing "TUI direct model selection rejects unsupported runtime models without mutating the session"
    (let [ctx      {:k :v}
          sid      "sid-1"
          captured (atom nil)
          message  "gpt-5.6 is not supported for OpenAI OAuth credentials."
          result   {:ui.result/action-key :select-model
                    :ui.result/status :submitted
                    :ui.result/value {:provider "openai" :id "gpt-5.6"}}
          resolve-model-by-provider+id
          (fn [ctx' provider id]
            (when (and (= ctx' ctx)
                       (= [provider id] ["openai" "gpt-5.6"]))
              {:provider :openai
               :id "gpt-5.6"
               :supports-reasoning true
               :runtime/unsupported? true
               :runtime/unsupported-reason :openai-oauth-model-unsupported
               :runtime/unsupported-message message}))]
      (with-redefs [session/set-model-in! (fn [& args]
                                            (reset! captured args)
                                            {:model (nth args 2)})]
        (is (= {:type :text
                :message (str "Unsupported model: openai gpt-5.6 — " message)}
               (sut/handle-action-result {:ctx ctx
                                          :sid sid
                                          :action-result result
                                          :resolve-model-by-provider+id resolve-model-by-provider+id
                                          :switch-session-fn! (fn [_] nil)
                                          :fork-session-fn! (fn [_] nil)
                                          :set-focus! (fn [_] nil)})))
        (is (nil? @captured))))))
