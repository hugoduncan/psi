(ns psi.agent-session.workflow-loader-async-path-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.workflow-loader :as wl]
   [psi.agent-session.context :as context]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow-file-compiler :as workflow-file-compiler]
   [psi.agent-session.workflow-file-loader :as workflow-file-loader]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-context+session []
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations/all-mutations
                                     :ui-type :tui
                                     :worktree-path "/Users/duncan/projects/hugoduncan/psi/workflow-extensions"})
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- register-workflow-loader! [ctx session-id]
  (let [reg (:extension-registry ctx)
        ext-path "extensions/workflow-loader"
        _ (ext/register-extension-in! reg ext-path)
        api (ext/create-extension-api reg ext-path (runtime-fns/make-extension-runtime-fns ctx session-id ext-path))]
    (wl/init api)
    api))

(defn- load-all-workflow-definitions! [ctx]
  (let [parsed (workflow-file-loader/scan-directory "/Users/duncan/projects/hugoduncan/psi/workflow-extensions/.psi/workflows")
        {:keys [definitions errors]} (workflow-file-compiler/compile-workflow-files parsed)]
    (when (seq errors)
      (throw (ex-info "compile errors" {:errors errors})))
    (doseq [d definitions]
      (swap! (:state* ctx) assoc-in [:workflows :definitions (:definition-id d)] d))))

(deftest delegate-async-path-avoids-keyword-contains-error-test
  (testing "workflow-loader delegate async path no longer produces the keyword contains? failure in a TUI-like context"
    (let [[ctx session-id] (create-context+session)]
      (register-workflow-loader! ctx session-id)
      (try
        (load-all-workflow-definitions! ctx)
        (let [cmd (get-in @(:state (:extension-registry ctx))
                          [:extensions "extensions/workflow-loader" :commands "delegate"])
              result ((:handler cmd) "lambda-build simple code is good code")]
          (is (string? result))
          (is (.contains ^String result "Delegated to lambda-build — run "))
          (Thread/sleep 2000)
          (let [rt (runtime-fns/make-extension-runtime-fns ctx session-id "extensions/workflow-loader")
                jobs (:psi.agent-session/background-jobs
                      ((:query-fn rt) [:psi.agent-session/background-jobs]))
                delegate-jobs (filter #(= "delegate" (:psi.background-job/tool-name %)) jobs)
                failed-job (first (filter #(= :failed (:psi.background-job/status %)) delegate-jobs))]
            (is (seq delegate-jobs))
            (is (some? failed-job))
            (is (not (str/includes? (pr-str failed-job)
                                    "contains? not supported on type: clojure.lang.Keyword")))))
        (finally
          (context/shutdown-context! ctx))))))

(deftest direct-run-creation-does-not-have-keyword-contains-error-test
  (testing "creating the same run directly in the same context does not itself inject the keyword contains? failure"
    (let [[ctx session-id] (create-context+session)]
      (register-workflow-loader! ctx session-id)
      (try
        (load-all-workflow-definitions! ctx)
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
