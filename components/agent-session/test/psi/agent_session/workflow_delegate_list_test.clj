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

(defn- with-workflow-runtime-state
  [state f]
  (let [original-state @runtime-state/state]
    (try
      (reset! runtime-state/state state)
      (f)
      (finally
        (reset! runtime-state/state original-state)))))

(defn- eql-jobs
  [jobs]
  (mapv background-jobs/job->eql jobs))

(deftest delegate-list-tool-path-shows-active-same-session-run-test
  ;; The actual delegate list path must use the invoking session's background
  ;; jobs, not only the canonical workflow registry, so active runs render.
  (testing "same-session active delegated run appears in delegate list text"
    (with-workflow-runtime-state
      {:current-session-id "session-1"
       :loaded-definitions {}
       :mutate-fn (fn [op _args]
                    (case op
                      psi.workflow/list-runs
                      {:psi.workflow/runs [base-run]}))
       :query-fn (fn [_]
                   {:psi.agent-session/background-jobs (eql-jobs [base-job])})}
      (fn []
        (let [text (#'workflow-core/delegate-list)]
          (is (string? text))
          (is (re-find #"Active runs:\n  run-1" text))
          (is (re-find #"run-1 — running" text))
          (is (re-find #"\[delegate running\]" text)))))))

(deftest delegate-list-tool-path-excludes-unrelated-session-runs-test
  ;; Session visibility is enforced through background-job thread ownership.
  (testing "other-session delegate jobs do not render through the tool path"
    (with-workflow-runtime-state
      {:current-session-id "session-1"
       :loaded-definitions {}
       :mutate-fn (fn [op _args]
                    (case op
                      psi.workflow/list-runs
                      {:psi.workflow/runs [base-run]}))
       :query-fn (fn [_]
                   {:psi.agent-session/background-jobs
                    (eql-jobs [(assoc base-job :thread-id "session-2")])})}
      (fn []
        (let [text (#'workflow-core/delegate-list)]
          (is (re-find #"Active runs:\nNo active runs\." text))
          (is (not (re-find #"  run-1 —" text))))))))

(deftest delegate-list-returned-id-can-continue-blocked-run-test
  ;; A run id surfaced by list remains the canonical management id accepted by
  ;; continue when canonical workflow status supports continuation.
  (testing "listed blocked run id can be passed to delegate continue"
    (let [started-jobs* (atom [])
          resumed* (atom [])]
      (with-workflow-runtime-state
        {:current-session-id "session-1"
         :loaded-definitions {}
         :notify-fn (fn [_ _] nil)
         :mutate-fn (fn [op args]
                      (case op
                        psi.workflow/list-runs
                        {:psi.workflow/runs [(assoc base-run :status :blocked)]}

                        psi.extension/start-background-job
                        (do
                          (swap! started-jobs* conj args)
                          {:psi.background-job/job-id (:job-id args)
                           :psi.background-job/status :running})

                        psi.workflow/resume-run
                        (do
                          (swap! resumed* conj args)
                          {:psi.workflow/status :completed
                           :psi.workflow/result "done"})

                        psi.extension/mark-background-job-terminal
                        {:psi.background-job/job-id (:job-id args)
                         :psi.background-job/status (:outcome args)}

                        psi.extension/append-entry
                        {:ok true}))
         :query-fn (fn [_]
                     {:psi.agent-session/background-jobs
                      (eql-jobs [(assoc base-job :status :completed
                                        :completed-at #inst "2026-05-30T10:02:00.000Z"
                                        :completed-seq 1)])})}
        (fn []
          (let [list-text (#'workflow-core/delegate-list)
                run-id (second (re-find #"Active runs:\n  ([^ ]+)" list-text))
                continue-text (#'workflow-core/execute-delegate-tool
                               {:action "continue"
                                :id run-id
                                :prompt "next"}
                               nil)]
            (is (= "run-1" run-id))
            (is (= "Resuming run run-1 asynchronously." continue-text))
            (is (= "run-1" (:workflow-id (first @started-jobs*))))
            (is (= [{:run-id "run-1"
                     :session-id "session-1"
                     :workflow-input {:input "next" :original "next"}}]
                   @resumed*))))))))

(deftest delegate-list-returned-id-can-remove-existing-run-test
  ;; A run id surfaced by list remains the canonical management id accepted by
  ;; remove while the canonical workflow run exists.
  (testing "listed run id can be passed to delegate remove"
    (let [removed* (atom [])]
      (with-workflow-runtime-state
        {:current-session-id "session-1"
         :loaded-definitions {}
         :mutate-fn (fn [op args]
                      (case op
                        psi.workflow/list-runs
                        {:psi.workflow/runs [base-run]}

                        psi.workflow/remove-run
                        (do
                          (swap! removed* conj args)
                          {:psi.workflow/removed? true
                           :psi.workflow/run-id (:run-id args)})))
         :query-fn (fn [_]
                     {:psi.agent-session/background-jobs
                      (eql-jobs [(assoc base-job
                                        :status :completed
                                        :completed-at #inst "2026-05-30T10:02:00.000Z"
                                        :completed-seq 1)])})}
        (fn []
          (let [list-text (#'workflow-core/delegate-list)
                run-id (second (re-find #"Active runs:\n  ([^ ]+)" list-text))
                remove-text (#'workflow-core/execute-delegate-tool
                             {:action "remove" :id run-id}
                             nil)]
            (is (= "run-1" run-id))
            (is (= "Removed run run-1" remove-text))
            (is (= [{:run-id "run-1"}] @removed*))))))))

(deftest delegate-list-and-remove-reject-non-shaped-background-job-payloads-test
  ;; The background-job query result must contain a collection of job maps. Nil
  ;; or scalar payloads are query-shape failures, not empty job sets.
  (testing "delegate list rejects nil/non-collection/non-map job payloads"
    (doseq [payload [nil :not-a-collection ["not-a-map"]]]
      (with-workflow-runtime-state
        {:current-session-id "session-1"
         :loaded-definitions {}
         :mutate-fn (fn [op _args]
                      (case op
                        psi.workflow/list-runs
                        {:psi.workflow/runs [base-run]}))
         :query-fn (fn [_]
                     {:psi.agent-session/background-jobs payload})}
        (fn []
          (let [text (#'workflow-core/delegate-list)]
            (is (re-find #"Error: delegate list background-job visibility surface returned a non-shaped jobs payload"
                         text))
            (is (not (re-find #"No active runs\." text))))))))
  (testing "delegate remove rejects nil/non-collection/non-map job payloads before canonical removal"
    (doseq [payload [nil :not-a-collection ["not-a-map"]]]
      (let [removed* (atom false)]
        (with-workflow-runtime-state
          {:current-session-id "session-1"
           :loaded-definitions {}
           :mutate-fn (fn [op _args]
                        (case op
                          psi.workflow/remove-run
                          (do
                            (reset! removed* true)
                            {:psi.workflow/removed? true})))
           :query-fn (fn [_]
                       {:psi.agent-session/background-jobs payload})}
          (fn []
            (let [result (#'workflow-core/delegate-remove {:id "run-1"})]
              (is (= {:error "delegate remove background-job visibility surface returned a non-shaped jobs payload"}
                     result))
              (is (false? @removed*)))))))))

(deftest terminal-duplicate-selection-tie-breakers-are-deterministic-test
  ;; Completion ordering falls through completed-at, completed-seq, job-seq, and
  ;; job-id with present values newer than missing values at each level.
  (testing "completed-at beats later tie-breakers"
    (let [older (assoc base-job :job-id "job-z" :status :failed
                       :completed-at #inst "2026-05-30T10:01:00.000Z"
                       :completed-seq 9 :job-seq 9)
          newer (assoc base-job :job-id "job-a" :status :completed
                       :completed-at #inst "2026-05-30T10:02:00.000Z"
                       :completed-seq 1 :job-seq 1)
          result (project [base-run] [older newer])]
      (is (= :ok (:status result)))
      (is (= "job-a" (get-in result [:runs 0 :job-id])))))
  (testing "completed-seq breaks completed-at ties"
    (let [lower (assoc base-job :job-id "job-z" :status :failed
                       :completed-at #inst "2026-05-30T10:01:00.000Z"
                       :completed-seq 1 :job-seq 9)
          higher (assoc base-job :job-id "job-a" :status :completed
                        :completed-at #inst "2026-05-30T10:01:00.000Z"
                        :completed-seq 2 :job-seq 1)
          result (project [base-run] [lower higher])]
      (is (= :ok (:status result)))
      (is (= "job-a" (get-in result [:runs 0 :job-id])))))
  (testing "job-seq breaks missing completion marker ties"
    (let [lower (assoc base-job :job-id "job-a" :status :failed :job-seq 1)
          higher (assoc base-job :job-id "job-b" :status :completed :job-seq 2)
          result (project [base-run] [lower higher])]
      (is (= :ok (:status result)))
      (is (= "job-b" (get-in result [:runs 0 :job-id])))))
  (testing "job-id breaks final ties lexicographically"
    (let [lower (assoc base-job :job-id "job-a" :status :failed)
          higher (assoc base-job :job-id "job-b" :status :completed)
          result (project [base-run] [lower higher])]
      (is (= :ok (:status result)))
      (is (= "job-b" (get-in result [:runs 0 :job-id]))))))

(deftest final-row-ordering-tie-breakers-are-deterministic-test
  ;; Final rows sort by started-at, then job-seq, job-id, and canonical run-id.
  (testing "started-at orders rows newest first"
    (let [run-2 (assoc base-run :run-id "run-2")
          job-2 (assoc base-job :job-id "job-a" :workflow-id "run-2"
                       :started-at #inst "2026-05-30T10:02:00.000Z")
          result (project [base-run run-2] [base-job job-2])]
      (is (= :ok (:status result)))
      (is (= ["run-2" "run-1"] (mapv :run-id (:runs result))))))
  (testing "job-seq breaks missing started-at ties"
    (let [run-2 (assoc base-run :run-id "run-2")
          job-1 (assoc base-job :started-at nil :job-seq 1)
          job-2 (assoc base-job :job-id "job-a" :workflow-id "run-2"
                       :started-at nil :job-seq 2)
          result (project [base-run run-2] [job-1 job-2])]
      (is (= :ok (:status result)))
      (is (= ["run-2" "run-1"] (mapv :run-id (:runs result))))))
  (testing "job-id breaks ordering ties"
    (let [run-2 (assoc base-run :run-id "run-2")
          job-1 (assoc base-job :started-at nil :job-seq nil :job-id "job-a")
          job-2 (assoc base-job :workflow-id "run-2" :started-at nil :job-seq nil :job-id "job-b")
          result (project [base-run run-2] [job-1 job-2])]
      (is (= :ok (:status result)))
      (is (= ["run-2" "run-1"] (mapv :run-id (:runs result))))))
  (testing "run-id breaks final ordering ties"
    (let [run-2 (assoc base-run :run-id "run-2")
          job-1 (assoc base-job :started-at nil :job-seq nil :job-id nil)
          job-2 (assoc base-job :workflow-id "run-2" :started-at nil :job-seq nil :job-id nil)
          result (project [base-run run-2] [job-1 job-2])]
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
