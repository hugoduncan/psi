(ns psi.agent-session.workflow-tui-repro-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions.runtime-eql :as runtime-eql]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.workflow-loader.core :as workflow-file-loader]
   [psi.workflow-loader.compiler :as workflow-file-compiler]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn- create-context+session []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations
                                     :ui-type :tui
                                     :worktree-path "/Users/duncan/projects/hugoduncan/psi/workflow-extensions"})
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- init-built-in-workflow! [ctx session-id]
  ((requiring-resolve 'psi.agent-session.workflow.bootstrap/init-built-in!) ctx session-id))

(deftest direct-workflow-execution-vs-extension-mutation-test
  (testing "direct workflow execution and extension mutation execution both avoid the keyword contains? failure on lambda-build in TUI-like context"
    (let [[ctx session-id] (create-context+session)]
      (init-built-in-workflow! ctx session-id)
      (try
        (let [parsed (workflow-file-loader/scan-directory "/Users/duncan/projects/hugoduncan/psi/workflow-extensions/.psi/workflows")
              {:keys [definitions errors]} (workflow-file-compiler/compile-workflow-files parsed)
              _ (is (empty? errors))
              _ (doseq [d definitions]
                  (swap! (:state* ctx) assoc-in [:workflows :definitions (:definition-id d)] d))
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
    (let [[ctx session-id] (create-context+session)]
      (init-built-in-workflow! ctx session-id)
      (try
        (let [cmd (get-in @(:state (:extension-registry ctx))
                          [:extensions wl/built-in-workflow-path :commands "delegate"])
              _ (is (some? cmd))
              result ((:handler cmd) "lambda-build simple code is good code")]
          (is (string? result))
          (is (.contains ^String result "Delegated to lambda-build — run "))
          (Thread/sleep 2000)
          (let [rt (runtime-fns/make-extension-runtime-fns ctx session-id wl/built-in-workflow-path)
                jobs (:psi.agent-session/background-jobs
                      ((:query-fn rt) [:psi.agent-session/background-jobs]))
                delegate-jobs (filter #(= "delegate" (:psi.background-job/tool-name %)) jobs)]
            (is (seq delegate-jobs))
            (is (not-any? #(str/includes? (pr-str %) "contains? not supported on type: clojure.lang.Keyword")
                          delegate-jobs))))
        (finally
          (context/shutdown-context! ctx))))))
