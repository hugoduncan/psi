(ns psi.agent-session.workflow-delegate-list-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-jobs :as background-jobs]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.agent-session.workflow.delegate-list :as delegate-list]
   [psi.agent-session.workflow.runtime-state :as runtime-state]))

(def base-run
  {:run-id "run-1"
   :source-definition-id "lambda-build"
   :status :running
   :current-step-id "step-1"})

(def base-job
  {:job-id "job-1"
   :thread-id "session-1"
   :tool-call-id "delegate/run-1/attempt-1"
   :tool-name "delegate"
   :job-kind :workflow
   :workflow-ext-path "built-in:workflow"
   :workflow-id "run-1"
   :job-seq 1
   :started-at #inst "2026-05-30T10:00:00.000Z"
   :status :running})

(defn- project
  [runs jobs]
  (delegate-list/project-visible-runs {:session-id "session-1"
                                       :runs runs
                                       :background-jobs jobs}))

(deftest active-same-session-delegate-job-is-visible-test
  ;; Proves the observed failure mode: an active delegate background job joined
  ;; to a canonical run produces a non-empty delegate-list projection.
  (testing "active same-session delegate jobs are listed"
    (let [result (project [base-run] [base-job])]
      (is (= :ok (:status result)))
      (is (= ["run-1"] (mapv :run-id (:runs result))))
      (is (= :running (get-in result [:runs 0 :workflow-status])))
      (is (= :running (get-in result [:runs 0 :delegate-status]))))))

(deftest unrelated-session-and-non-workflow-jobs-are-ignored-test
  ;; Visibility is owned by same-session delegate workflow background jobs only.
  (testing "jobs outside the delegate workflow visibility predicate are ignored"
    (let [foreign-session (assoc base-job :job-id "job-foreign" :thread-id "session-2")
          foreign-tool (assoc base-job :job-id "job-tool" :tool-name "schedule")
          non-workflow (assoc base-job :job-id "job-kind" :job-kind :generic)
          result (project [base-run] [foreign-session foreign-tool non-workflow])]
      (is (= :ok (:status result)))
      (is (empty? (:runs result))))))

(deftest malformed-delegate-workflow-provenance-is-actionable-test
  ;; Same-session delegate workflow jobs are inside the contract and malformed
  ;; provenance must not collapse into an empty list.
  (testing "missing or foreign built-in workflow provenance returns an error"
    (let [result (project [base-run]
                          [(assoc base-job :workflow-ext-path "foreign:workflow")])]
      (is (= :error (:status result)))
      (is (= :malformed-delegate-workflow-job (:reason result))))))

(deftest malformed-non-terminal-workflow-id-is-actionable-test
  ;; Non-terminal delegate jobs without a usable workflow-id cannot be managed.
  (testing "blank workflow id on a running job returns an error"
    (let [result (project [base-run]
                          [(assoc base-job :workflow-id "")])]
      (is (= :error (:status result)))
      (is (= :malformed-delegate-workflow-id (:reason result))))))

(deftest terminal-malformed-workflow-id-is-hidden-test
  ;; Terminal retained history without a workflow id is non-manageable history,
  ;; not a blocker for otherwise empty list results.
  (testing "blank workflow id on a terminal job is hidden"
    (let [result (project [base-run]
                          [(assoc base-job
                                  :workflow-id ""
                                  :status :completed
                                  :completed-at #inst "2026-05-30T10:02:00.000Z"
                                  :completed-seq 1)])]
      (is (= :ok (:status result)))
      (is (empty? (:runs result))))))

(deftest missing-canonical-run-rules-follow-terminality-test
  ;; Missing canonical runs are corruption only for still-active delegate jobs.
  (testing "non-terminal missing canonical run is an error"
    (let [result (project [] [base-job])]
      (is (= :error (:status result)))
      (is (= :missing-canonical-workflow-run (:reason result)))))
  (testing "terminal missing canonical run is hidden"
    (let [result (project [] [(assoc base-job
                                     :status :failed
                                     :completed-at #inst "2026-05-30T10:01:00.000Z"
                                     :completed-seq 1)])]
      (is (= :ok (:status result)))
      (is (empty? (:runs result))))))

(deftest duplicate-job-reduction-rules-test
  ;; Retained terminal history plus one active attempt reduces to one row, while
  ;; multiple active attempts for one canonical run is actionable corruption.
  (testing "one non-terminal duplicate wins over terminal history"
    (let [terminal (assoc base-job
                          :job-id "job-old"
                          :tool-call-id "delegate/run-1/old"
                          :status :completed
                          :completed-at #inst "2026-05-30T10:01:00.000Z"
                          :completed-seq 1)
          active (assoc base-job
                        :job-id "job-new"
                        :tool-call-id "delegate/run-1/new"
                        :status :running)
          result (project [base-run] [terminal active])]
      (is (= :ok (:status result)))
      (is (= ["run-1"] (mapv :run-id (:runs result))))
      (is (= "job-new" (get-in result [:runs 0 :job-id])))))
  (testing "multiple non-terminal duplicates error"
    (let [result (project [base-run]
                          [base-job
                           (assoc base-job :job-id "job-2" :tool-call-id "delegate/run-1/attempt-2")])]
      (is (= :error (:status result)))
      (is (= :duplicate-non-terminal-delegate-jobs (:reason result))))))

(deftest blocked-run-retained-wrapper-completion-is-listable-test
  ;; Blocked canonical workflow status remains primary/continuable while the
  ;; delegate wrapper attempt can be terminal completed history.
  (testing "blocked canonical run lists with completed delegate status"
    (let [job (assoc base-job
                     :status :completed
                     :completed-at #inst "2026-05-30T10:02:00.000Z"
                     :completed-seq 1)
          result (project [(assoc base-run :status :blocked)] [job])]
      (is (= :ok (:status result)))
      (is (= ["run-1"] (mapv :run-id (:runs result))))
      (is (= :blocked (get-in result [:runs 0 :workflow-status])))
      (is (= :completed (get-in result [:runs 0 :delegate-status]))))))

(deftest terminal-duplicate-selection-is-deterministic-test
  ;; Terminal-only duplicate groups choose newest completion markers with stable
  ;; tie-breakers so displayed background status is deterministic.
  (testing "terminal duplicate representative uses completion ordering"
    (let [old (assoc base-job
                     :job-id "job-old"
                     :tool-call-id "delegate/run-1/old"
                     :status :failed
                     :completed-at #inst "2026-05-30T10:01:00.000Z"
                     :completed-seq 2
                     :job-seq 2)
          newer (assoc base-job
                       :job-id "job-new"
                       :tool-call-id "delegate/run-1/new"
                       :status :completed
                       :completed-at #inst "2026-05-30T10:01:00.000Z"
                       :completed-seq 3
                       :job-seq 1)
          result (project [(assoc base-run :status :blocked)] [old newer])]
      (is (= :ok (:status result)))
      (is (= "job-new" (get-in result [:runs 0 :job-id])))
      (is (= :blocked (get-in result [:runs 0 :workflow-status])))
      (is (= :completed (get-in result [:runs 0 :delegate-status]))))))

(deftest final-row-ordering-is-newest-representative-job-first-test
  ;; Final list rows sort by representative delegate job recency, not canonical
  ;; workflow registry traversal order or status grouping.
  (testing "rows are ordered newest first by started-at and stable tie-breakers"
    (let [run-2 (assoc base-run :run-id "run-2" :status :blocked)
          job-2 (assoc base-job
                       :job-id "job-2"
                       :tool-call-id "delegate/run-2/attempt-1"
                       :workflow-id "run-2"
                       :status :completed
                       :started-at #inst "2026-05-30T10:05:00.000Z"
                       :completed-at #inst "2026-05-30T10:06:00.000Z"
                       :completed-seq 1)
          result (project [run-2 base-run] [base-job job-2])]
      (is (= :ok (:status result)))
      (is (= ["run-2" "run-1"] (mapv :run-id (:runs result)))))))

(deftest delegate-remove-terminalizes-active-background-job-before-canonical-removal-test
  ;; Removing an active listed run resolves the delegate background job before
  ;; deleting the canonical run, preventing later list corruption.
  (testing "active delegate background jobs become terminal before run removal"
    (let [store (-> (background-jobs/empty-state)
                    (background-jobs/start-background-job
                     {:tool-call-id "delegate/run-1/attempt-1"
                      :thread-id "session-1"
                      :tool-name "delegate"
                      :job-id "job-1"
                      :job-kind :workflow
                      :workflow-ext-path "built-in:workflow"
                      :workflow-id "run-1"})
                    :state)
          state* (atom {:background-jobs {:store store}})
          removed* (atom false)
          original-state @runtime-state/state
          query-fn (fn [_]
                     {:psi.agent-session/background-jobs
                      (mapv background-jobs/job->eql
                            (vals (get-in @state* [:background-jobs :store :jobs-by-id])))})
          mutate-fn (fn [op args]
                      (case op
                        psi.extension/mark-background-job-terminal
                        (let [state' (background-jobs/mark-terminal
                                      (get-in @state* [:background-jobs :store])
                                      {:job-id (:job-id args)
                                       :outcome (:outcome args)
                                       :payload (:payload args)
                                       :suppress-terminal-message? (:suppress-terminal-message? args)})]
                          (swap! state* assoc-in [:background-jobs :store] state')
                          {:psi.background-job/job-id (:job-id args)
                           :psi.background-job/status (:outcome args)})

                        psi.workflow/remove-run
                        (do
                          (reset! removed* true)
                          {:psi.workflow/removed? true
                           :psi.workflow/run-id (:run-id args)})))]
      (try
        (reset! runtime-state/state {:current-session-id "session-1"
                                     :query-fn query-fn
                                     :mutate-fn mutate-fn})
        (let [result (#'workflow-core/delegate-remove {:id "run-1"})
              job (background-jobs/get-job-in (get-in @state* [:background-jobs :store]) "job-1")]
          (is (= {:ok true :run-id "run-1"} result))
          (is (true? @removed*))
          (is (= :cancelled (:status job)))
          (is (= :delegate-remove (get-in job [:terminal-payload :reason]))))
        (finally
          (reset! runtime-state/state original-state))))))

(deftest delegate-remove-stops-before-canonical-removal-when-background-cleanup-fails-test
  ;; If active background cleanup fails, canonical removal is skipped so the run
  ;; remains visible/manageable rather than becoming list corruption.
  (testing "cleanup failure prevents canonical workflow removal"
    (let [store (-> (background-jobs/empty-state)
                    (background-jobs/start-background-job
                     {:tool-call-id "delegate/run-1/attempt-1"
                      :thread-id "session-1"
                      :tool-name "delegate"
                      :job-id "job-1"
                      :job-kind :workflow
                      :workflow-ext-path "built-in:workflow"
                      :workflow-id "run-1"})
                    :state)
          removed* (atom false)
          original-state @runtime-state/state
          query-fn (fn [_]
                     {:psi.agent-session/background-jobs
                      (mapv background-jobs/job->eql (vals (:jobs-by-id store)))})
          mutate-fn (fn [op _args]
                      (case op
                        psi.extension/mark-background-job-terminal
                        (throw (ex-info "boom" {:op op}))

                        psi.workflow/remove-run
                        (do
                          (reset! removed* true)
                          {:psi.workflow/removed? true})))]
      (try
        (reset! runtime-state/state {:current-session-id "session-1"
                                     :query-fn query-fn
                                     :mutate-fn mutate-fn})
        (let [result (#'workflow-core/delegate-remove {:id "run-1"})]
          (is (= {:error "delegate remove could not clean up active delegate background jobs"}
                 result))
          (is (false? @removed*)))
        (finally
          (reset! runtime-state/state original-state))))))
