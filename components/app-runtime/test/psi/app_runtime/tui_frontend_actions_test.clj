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
          (fn [provider id]
            (when (= [provider id] ["openai" "gpt-5.3-codex"])
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
