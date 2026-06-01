(ns psi.agent-session.workflow-async-path-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.workflow.orchestration :as orchestration]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

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
                _             (workflow-test-support/poll-until #(seq (filter (fn [j]
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

(deftest background-job-tool-call-id-is-attempt-specific-test
  ;; Delegate background-job tool-call-id identifies an execution attempt, not the
  ;; canonical workflow run, so retained attempt history can coexist with resume.
  (testing "tool call ids remain tied to the run but unique per attempt"
    (let [first-id (orchestration/background-job-tool-call-id "run-1")
          second-id (orchestration/background-job-tool-call-id "run-1")]
      (is (str/starts-with? first-id "delegate/run-1/"))
      (is (str/starts-with? second-id "delegate/run-1/"))
      (is (not= first-id second-id))))
  (testing "background job start keeps workflow-id as the canonical management id"
    (let [calls* (atom [])
          mutate! (fn [op args]
                    (swap! calls* conj {:op op :args args})
                    {:psi.background-job/job-id (:job-id args)
                     :psi.background-job/status :running})]
      (orchestration/start-background-job! mutate! "session-1" "run-1" "lambda-build")
      (let [{:keys [op args]} (first @calls*)]
        (is (= 'psi.extension/start-background-job op))
        (is (= "run-1" (:workflow-id args)))
        (is (str/starts-with? (:tool-call-id args) "delegate/run-1/"))))))

(deftest blocked-workflow-publication-completes-wrapper-job-test
  ;; A blocked canonical workflow is a valid pause, so the delegate wrapper
  ;; attempt completes while the canonical status remains blocked for management.
  (testing "blocked canonical result maps to completed delegate wrapper status"
    (let [publication (orchestration/delegated-result-publication
                       {:run-id "run-1"
                        :workflow-name "review-task"
                        :parent-session-id "session-1"
                        :include-result? false
                        :exec-result {:psi.workflow/status :blocked
                                      :psi.workflow/result "needs input"}})]
      (is (= :blocked (get-in publication [:completion :status])))
      (is (= :completed (get-in publication [:background-job :status])))
      (is (= :blocked (get-in publication [:background-job :payload :status])))
      (is (= :completed (get-in publication [:background-job :payload :delegate-status])))
      (is (= :info (get-in publication [:notification :level]))))))

(deftest blocked-run-continue-error-terminalizes-wrapper-and-cleans-inflight-test
  ;; resume-run may return an error map without throwing. That path must still
  ;; resolve the newly-started delegate attempt and remove inflight tracking.
  (testing "resume error map marks wrapper failed and clears inflight run"
    (let [inflight* (atom {})
          terminal-calls* (atom [])
          notifications* (atom [])
          refresh-count* (atom 0)
          started* (atom [])
          result (orchestration/continue-blocked-run-async!
                  {:mutate! (fn [op _args]
                              (case op
                                psi.workflow/resume-run
                                {:psi.workflow/error "resume rejected"}))
                   :start-background-job! (fn [session-id run-id workflow-name]
                                            (swap! started* conj {:session-id session-id
                                                                  :run-id run-id
                                                                  :workflow-name workflow-name})
                                            {:job-id "job-resume"})
                   :mark-background-job-terminal! (fn [job-id status payload & _]
                                                    (swap! terminal-calls* conj {:job-id job-id
                                                                                 :status status
                                                                                 :payload payload}))
                   :notify! (fn [message level]
                              (swap! notifications* conj {:message message :level level}))
                   :refresh-widgets! (fn [] (swap! refresh-count* inc))
                   :inflight-runs inflight*}
                  "run-1" "session-1" "next" false)]
      (is (= {:ok true :run-id "run-1" :status :resuming} result))
      (is (= [{:session-id "session-1" :run-id "run-1" :workflow-name "resume-run-1"}]
             @started*))
      (workflow-test-support/poll-until #(empty? @inflight*))
      (is (= [{:job-id "job-resume"
               :status :failed
               :payload {:run-id "run-1"
                         :workflow "resume-run-1"
                         :status :failed
                         :error "resume rejected"}}]
             @terminal-calls*))
      (is (= [{:message "Resume of run 'run-1' failed: resume rejected"
               :level :error}]
             @notifications*))
      (is (pos? @refresh-count*)))))
