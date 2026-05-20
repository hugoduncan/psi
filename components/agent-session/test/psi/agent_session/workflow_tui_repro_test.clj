(ns psi.agent-session.workflow-tui-repro-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.extensions.runtime-eql :as runtime-eql]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(deftest direct-workflow-execution-vs-extension-mutation-test
  (testing "direct workflow execution and extension mutation execution both avoid the keyword contains? failure on lambda-build in TUI-like context"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (workflow-test-support/init-built-in-workflow! ctx session-id)
      (try
        (let [_ (workflow-test-support/load-all-workflow-definitions! ctx)
              [st direct-run-id _] (workflow-runtime/create-run @(:state* ctx)
                                                                {:definition-id "lambda-build"
                                                                 :run-id "lambda-build-direct"
                                                                 :workflow-input {:input "simple code is good code"
                                                                                  :original "simple code is good code"}})
              _ (reset! (:state* ctx) st)
              direct-result (workflow-execution/execute-run! ctx session-id direct-run-id)
              mutation-result (runtime-eql/run-extension-mutation-in! ctx session-id 'psi.workflow/execute-run
                                                                      {:run-id direct-run-id})]
          (is (map? direct-result))
          (is (not (str/includes? (pr-str direct-result) "contains? not supported on type: clojure.lang.Keyword")))
          (is (map? mutation-result))
          (is (not (nil? (:psi.workflow/run-id mutation-result))))
          (is (not (str/includes? (pr-str mutation-result)
                                  "contains? not supported on type: clojure.lang.Keyword"))))
        (finally
          (context/shutdown-context! ctx))))))

(deftest delegate-lambda-build-from-tui-like-session-test
  (testing "built-in workflow /delegate can launch lambda-build from a real TUI-like session context without keyword contains? failure"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (workflow-test-support/init-built-in-workflow! ctx session-id)
      (try
        (let [cmd (command-registry/get-command-in (:extension-registry ctx) "delegate")
              _ (is (some? cmd))
              _ (is (= :built-in (:source cmd)) "delegate command carries :source :built-in provenance")
              result ((:handler cmd) "lambda-build simple code is good code")]
          (is (string? result))
          (is (.contains ^String result "Delegated to lambda-build — run "))
          (let [rt          (runtime-fns/make-extension-runtime-fns ctx session-id wl/built-in-workflow-path)
                query-jobs  (fn []
                              (let [jobs (:psi.agent-session/background-jobs
                                          ((:query-fn rt) [:psi.agent-session/background-jobs]))]
                                (filter #(= "delegate" (:psi.background-job/tool-name %)) jobs)))
                _           (workflow-test-support/poll-until #(seq (filter (fn [j]
                                                                              (#{:failed :completed}
                                                                               (:psi.background-job/status j)))
                                                                            (query-jobs))))
                delegate-jobs (query-jobs)]
            (is (seq delegate-jobs))
            (is (not-any? #(str/includes? (pr-str %) "contains? not supported on type: clojure.lang.Keyword")
                          delegate-jobs))))
        (finally
          (context/shutdown-context! ctx))))))
