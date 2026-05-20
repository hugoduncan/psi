(ns psi.agent-session.workflow-async-path-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn- poll-until
  "Poll `pred-fn` every `interval-ms` milliseconds until it returns truthy or
  `timeout-ms` elapses.  Returns the last value of `pred-fn`."
  ([pred-fn] (poll-until pred-fn 3000 50))
  ([pred-fn timeout-ms interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (pred-fn)]
         (if (or v (>= (System/currentTimeMillis) deadline))
           v
           (do (Thread/sleep ^long interval-ms)
               (recur))))))))

(deftest delegate-async-path-avoids-keyword-contains-error-test
  (testing "built-in workflow delegate async path no longer produces the keyword contains? failure in a TUI-like context"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (workflow-test-support/init-built-in-workflow! ctx session-id)
      (try
        (workflow-test-support/load-all-workflow-definitions! ctx)
        (let [cmd (command-registry/get-command-in (:extension-registry ctx) "delegate")
              _ (is (= :built-in (:source cmd)) "delegate command carries :source :built-in provenance")
              result ((:handler cmd) "lambda-build simple code is good code")]
          (is (string? result))
          (is (.contains ^String result "Delegated to lambda-build — run "))
          (let [rt          (runtime-fns/make-extension-runtime-fns ctx session-id wl/built-in-workflow-path)
                query-jobs  (fn []
                              (let [jobs (:psi.agent-session/background-jobs
                                          ((:query-fn rt) [:psi.agent-session/background-jobs]))]
                                (filter #(= "delegate" (:psi.background-job/tool-name %)) jobs)))
                _             (poll-until #(seq (filter (fn [j]
                                                          (#{:failed :completed}
                                                           (:psi.background-job/status j)))
                                                        (query-jobs))))
                all-delegate-jobs (query-jobs)
                failed-job  (first (filter #(= :failed (:psi.background-job/status %)) all-delegate-jobs))]
            (is (seq all-delegate-jobs))
            (is (some? failed-job))
            (is (not (str/includes? (pr-str failed-job)
                                    "contains? not supported on type: clojure.lang.Keyword")))))
        (finally
          (context/shutdown-context! ctx))))))

(deftest direct-run-creation-does-not-have-keyword-contains-error-test
  (testing "creating the same run directly in the same context does not itself inject the keyword contains? failure"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (workflow-test-support/init-built-in-workflow! ctx session-id)
      (try
        (workflow-test-support/load-all-workflow-definitions! ctx)
        (let [[st run-id _] (workflow-runtime/create-run @(:state* ctx)
                                                         {:definition-id "lambda-build"
                                                          :run-id "lambda-build-direct"
                                                          :workflow-input {:input "simple code is good code"
                                                                           :original "simple code is good code"}})]
          (reset! (:state* ctx) st)
          (is (= "lambda-build-direct" run-id))
          (is (not (str/includes? (pr-str (workflow-runtime/workflow-run-in @(:state* ctx) run-id))
                                  "contains? not supported on type: clojure.lang.Keyword"))))
        (finally
          (context/shutdown-context! ctx))))))
