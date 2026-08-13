(ns psi.agent-session.workflow-async-path-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-jobs :as background-jobs]
   [psi.agent-session.context :as context]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.workflow.orchestration :as orchestration]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.session-state.state :as ss]
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

(deftest delegated-failure-publication-preserves-canonical-message-test
  ;; Projection receives an already normalized envelope message and must preserve
  ;; it exactly in every asynchronous result surface and wrapper.
  (testing "failed delegate publication neither replaces nor re-sanitizes the canonical message"
    (let [message "Delegated workflow 'child' failed at step 'build': tool timed out"
          publication (orchestration/delegated-result-publication
                       {:run-id "parent-run"
                        :workflow-name "parent"
                        :parent-session-id "session-1"
                        :include-result? false
                        :exec-result {:psi.workflow/status :failed
                                      :psi.workflow/result nil
                                      :psi.workflow/error message}})
          notification (get-in publication [:notification :message])
          entry (get-in publication [:append-entry :data])]
      (is (= message (get-in publication [:completion :error])))
      (is (= message (get-in publication [:background-job :payload :error])))
      (is (= :failed (get-in publication [:background-job :status])))
      (is (= "Workflow 'parent' failed: Delegated workflow 'child' failed at step 'build': tool timed out (run parent-run)"
             notification))
      (is (= "Workflow 'parent' — failed: Delegated workflow 'child' failed at step 'build': tool timed out (run parent-run)"
             entry))
      (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote message)) notification))))
      (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote message)) entry)))))))

(deftest delegated-failure-publication-preserves-bounded-message-before-result-test
  (testing "512-code-point messages remain byte-exact before the optional result section"
    (let [message (str "Delegated workflow failed: "
                       (apply str (repeat (- 512 (count "Delegated workflow failed: ")) "x")))
          result-text "optional result"
          publication (orchestration/delegated-result-publication
                       {:run-id "parent-run"
                        :workflow-name "parent"
                        :parent-session-id "session-1"
                        :include-result? false
                        :exec-result {:psi.workflow/status :failed
                                      :psi.workflow/result result-text
                                      :psi.workflow/error message}})
          notification (get-in publication [:notification :message])
          entry (get-in publication [:append-entry :data])
          occurrence-count (fn [text]
                             (count (re-seq (re-pattern (java.util.regex.Pattern/quote message))
                                            text)))]
      (is (= 512 (.codePointCount message 0 (.length message))))
      (is (= message (get-in publication [:completion :error])))
      (is (= message (get-in publication [:background-job :payload :error])))
      (is (= 1 (occurrence-count notification)))
      (is (= 1 (occurrence-count entry)))
      (is (= (str "Workflow 'parent' failed: " message " (run parent-run)")
             notification))
      (is (= (str "Workflow 'parent' — failed: " message " (run parent-run)"
                  "\n\nResult:\n" result-text)
             entry))
      (is (true? (get-in publication [:append-entry :enabled?])))
      (is (not (str/includes? notification result-text)))
      (is (< (str/index-of entry message)
             (str/index-of entry "Result:"))))))

(deftest async-delegated-failure-return-record-preserves-canonical-message-test
  ;; Real workflow mutation and publication logic carry the normalized child
  ;; failure through canonical job state, notification, and session append entry.
  (testing "async execution returns and publishes a real delegated error verbatim"
    (let [child-definition {:definition-id "async-failing-child"
                            :name "async-failing-child"
                            :steps [{:name "child-step"
                                     :type :session
                                     :contributions [{:type :template
                                                      :text "Do {{input}}"
                                                      :vars {"input" {:from :workflow-input}}}]}]}
          parent-definition {:definition-id "async-failing-parent"
                             :name "async-failing-parent"
                             :steps [{:name "delegate-child"
                                      :type :delegate
                                      :target "async-failing-child"
                                      :prompt-string "Carry out the child workflow."
                                      :context []}]}
          actor-release (promise)
          base-ctx (session/create-context {:persist? false
                                            :mutations mutations/all-mutations
                                            :ui-type :tui})
          session-id (:session-id (session/new-session-in! base-ctx nil {}))
          ctx (assoc base-ctx
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx child-session-id _prompt & _]
                       @actor-release
                       {:status :error
                        :session-id child-session-id
                        :assistant-message {:role "assistant"
                                            :error-message "upstream request rejected"
                                            :content [{:type :error
                                                       :text "upstream request rejected"}]}
                        :assistant-text ""
                        :execution-result {}
                        :failure {:reason :provider-unavailable
                                  :message "upstream request rejected"}}))
          runtime (runtime-fns/make-extension-runtime-fns
                   ctx session-id wl/built-in-workflow-path)
          real-mutate! (:mutate-fn runtime)
          appended* (atom [])
          mutate! (fn [operation params]
                    (when (= 'psi.extension/append-entry operation)
                      (swap! appended* conj params))
                    (real-mutate! operation params))
          inflight* (atom {})
          notifications* (atom [])
          refresh! (fn [] nil)
          notify! (fn [message level]
                    (swap! notifications* conj {:message message :level level}))
          mark-terminal! (partial orchestration/mark-background-job-terminal! mutate!)
          completion! (fn [run-id workflow-name parent-session-id include-result? exec-result]
                        (orchestration/on-async-completion!
                         {:mutate! mutate!
                          :notify! notify!
                          :mark-background-job-terminal! mark-terminal!
                          :inject-result-into-context! (fn [& _] nil)
                          :refresh-widgets! refresh!
                          :inflight-runs inflight*}
                         run-id workflow-name parent-session-id include-result? exec-result))]
      (mutate! 'psi.workflow/register-definition {:definition child-definition})
      (mutate! 'psi.workflow/register-definition {:definition parent-definition})
      (mutate! 'psi.workflow/create-run
               {:definition-id "async-failing-parent"
                :workflow-input "async proof"
                :run-id "async-parent-run"})
      (let [run-id (orchestration/execute-async!
                    {:mutate! mutate!
                     :start-background-job! (partial orchestration/start-background-job! mutate!)
                     :mark-background-job-terminal! mark-terminal!
                     :notify! notify!
                     :refresh-widgets! refresh!
                     :inflight-runs inflight*
                     :on-async-completion-fn completion!}
                    "async-parent-run" session-id "async-failing-parent" false)
            _ (deliver actor-release true)
            result (orchestration/await-run-completion inflight* run-id 3000)
            message "Delegated workflow 'async-failing-child' failed at step 'child-step': upstream request rejected"
            job (->> (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                     :jobs-by-id
                     vals
                     (filter #(= "async-parent-run" (:workflow-id %)))
                     first)
            entry (last @appended*)]
        (is (= {:run-id "async-parent-run"
                :workflow "async-failing-parent"
                :status :failed
                :error message}
               result))
        (is (= :failed (:status job)))
        (is (= message (get-in job [:terminal-payload :error])))
        (is (= [{:message (str "Workflow 'async-failing-parent' failed: " message
                               " (run async-parent-run)")
                 :level :warn}]
               @notifications*))
        (is (= "delegate-result" (:custom-type entry)))
        (is (= (str "Workflow 'async-failing-parent' — failed: " message
                    " (run async-parent-run)")
               (:data entry)))))))

(deftest top-level-worker-cancel-wakes-parked-wait-and-terminalizes-cleanly-test
  ;; Tests the acceptance path with real futures and dispatch effects: cancelling
  ;; a top-level run interrupts the parked worker future, then terminalizes the
  ;; workflow background job while leaving the canonical run cancelled.
  (testing "cancel effect wakes a parked top-level worker and terminalizes its job"
    (let [ctx (context/create-context {:persist? false})
          inflight* (atom {})
          worker-started (promise)
          worker-finished (promise)
          fut (future
                (try
                  (deliver worker-started true)
                  (Thread/sleep 10000)
                  :unexpected-natural-return
                  (catch InterruptedException _
                    (deliver worker-finished :interrupted)
                    :interrupted)))
          run {:run-id "run-1"
               :status :running
               :current-step-id "step-1"
               :effective-definition {:definition-id "flow"
                                      :step-order []}
               :step-runs {"step-1" {:attempts []}}
               :history []}]
      (swap! (:state* ctx)
             (fn [state]
               (-> state
                   (assoc :workflows {:runs {"run-1" run}
                                      :run-order ["run-1"]})
                   (assoc-in (ss/state-path :background-jobs)
                             (:state (background-jobs/start-background-job
                                      (background-jobs/empty-state)
                                      {:tool-call-id "tool-call-1"
                                       :thread-id "session-1"
                                       :tool-name "delegate"
                                       :job-id "job-1"
                                       :job-kind :workflow
                                       :workflow-ext-path "built-in:workflow"
                                       :workflow-id "run-1"}))))))
      (is (true? (deref worker-started 1000 false)))
      (swap! inflight* assoc "run-1" {:future fut :job-id "job-1"})
      ((:execute-effect-fn ctx)
       (assoc ctx :workflow-inflight-runs-handle inflight*)
       {:effect/type :runtime/cancel-inflight-run :run-id "run-1"})
      (workflow-test-support/poll-until #(realized? worker-finished))
      (is (= :interrupted @worker-finished))
      (is (true? (future-cancelled? fut)))
      (try
        @fut
        (catch java.util.concurrent.CancellationException _ nil))
      (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :status] :cancelled)
      ((:execute-effect-fn ctx)
       ctx
       {:effect/type :runtime/mark-workflow-jobs-terminal})
      (let [job (background-jobs/get-job-in
                 (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                 "job-1")]
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))
        (is (= :cancelled (:status job)))))))

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
