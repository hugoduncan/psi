(ns psi.agent-session.workflow-cancellation-dispatch-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-jobs :as background-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-schema :as dispatch-schema]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.workflow-coordination.cancellation-entry :as cancellation-entry]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-runtime.statechart-runtime.delegate :as delegate]
   [psi.workflow-registry.registry :as registry]
   [psi.state-kernel.dispatch :as kernel]))

(defn- make-ctx []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))]
    (swap! (:state* ctx) assoc :workflows (workflow-model/initial-workflow-state))
    ctx))

(defn- install-run!
  [ctx run]
  (swap! (:state* ctx)
         (fn [state]
           (-> state
               (assoc-in [:workflows :runs (:run-id run)] run)
               (update-in [:workflows :run-order] (fnil conj []) (:run-id run))))))

(defn- run
  [run-id status & {:as extra}]
  (merge {:run-id run-id
          :status status
          :current-step-id "step-1"
          :step-runs {"step-1" {:attempts []}}
          :history []}
         extra))

(defn- last-log-entry [event-type]
  (->> (kernel/event-log-entries)
       (filter #(= event-type (:event-type %)))
       last))

(deftest workflow-cancel-remove-effect-schema-test
  ;; Tests the canonical cancellation/cleanup effect payloads and guarded abort
  ;; schema without mocks; validation uses the real malli schema.
  (testing "cancel/drop inflight-run effects require exact run ids"
    (is (true? (dispatch-schema/valid-effect? {:effect/type :runtime/cancel-inflight-run
                                               :run-id "run-1"})))
    (is (true? (dispatch-schema/valid-effect? {:effect/type :runtime/drop-inflight-run
                                               :run-id "run-1"})))
    (is (true? (dispatch-schema/valid-effect? {:effect/type :runtime/drop-workflow-cancellation-entry-lock
                                               :run-id "run-1"})))
    (is (false? (dispatch-schema/valid-effect? {:effect/type :runtime/cancel-inflight-run}))))

  (testing "guarded workflow abort requires the complete flat guard"
    (is (true? (dispatch-schema/valid-effect? {:effect/type :runtime/agent-abort
                                               :session-id "child-session"
                                               :workflow-run-id "run-1"
                                               :workflow-step-id "step-1"
                                               :workflow-attempt-id "attempt-1"
                                               :expected-session-id "child-session"
                                               :workflow-session-kind :attempt})))
    (is (true? (dispatch-schema/valid-effect? {:effect/type :runtime/agent-abort})))
    (is (false? (dispatch-schema/valid-effect? {:effect/type :runtime/agent-abort
                                                :workflow-run-id "run-1"})))))

(deftest cancel-run-dispatch-effects-test
  ;; Tests top-level vs nested cancellation effect routing through the real
  ;; dispatch pipeline and event log, asserting state/effect outcomes.
  (testing "top-level cancel marks cancelled and emits terminalization plus worker cancel"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "run-1" :running
                             :step-runs {"step-1" {:attempts [{:attempt-id "attempt-1"
                                                               :status :running
                                                               :execution-session-id "child-session"}]}}))
      (let [result (dispatch/dispatch! ctx :psi.workflow/cancel-run {:run-id "run-1"} {:origin :core})
            entry (last-log-entry :psi.workflow/cancel-run)]
        (is (= :cancelled (:psi.workflow/status result)))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))
        (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/cancel-inflight-run :run-id "run-1"}
                {:effect/type :runtime/agent-abort
                 :session-id "child-session"
                 :workflow-run-id "run-1"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "attempt-1"
                 :expected-session-id "child-session"
                 :workflow-session-kind :attempt}]
               (:declared-effects entry))))))

  (testing "nested cancel aborts the child attempt without cancelling the parent worker"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "parent" :running))
      (install-run! ctx (run "child" :running
                             :delegating-run-id "parent"
                             :step-runs {"step-1" {:attempts [{:attempt-id "child-attempt"
                                                               :status :running
                                                               :execution-session-id "child-session"}]}}))
      (let [result (dispatch/dispatch! ctx :psi.workflow/cancel-run {:run-id "child"} {:origin :core})
            entry (last-log-entry :psi.workflow/cancel-run)]
        (is (= :cancelled (:psi.workflow/status result)))
        (is (= :running (get-in @(:state* ctx) [:workflows :runs "parent" :status])))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "child" :status])))
        (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/agent-abort
                 :session-id "child-session"
                 :workflow-run-id "child"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "child-attempt"
                 :expected-session-id "child-session"
                 :workflow-session-kind :attempt}]
               (:declared-effects entry))))))

  (testing "parent cancel cascades to live descendants and aborts cascade-set attempts"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "parent" :running
                             :step-runs {"step-1" {:attempts [{:attempt-id "parent-attempt"
                                                               :status :running
                                                               :execution-session-id "parent-session"}]}}))
      (install-run! ctx (run "child" :running
                             :delegating-run-id "parent"
                             :step-runs {"step-1" {:attempts [{:attempt-id "child-attempt"
                                                               :status :validating
                                                               :execution-session-id "child-session"}]}}))
      (install-run! ctx (run "grandchild" :blocked :delegating-run-id "child"))
      (install-run! ctx (run "done-child" :completed :delegating-run-id "parent"))
      (let [result (dispatch/dispatch! ctx :psi.workflow/cancel-run {:run-id "parent"} {:origin :core})
            entry (last-log-entry :psi.workflow/cancel-run)]
        (is (= :cancelled (:psi.workflow/status result)))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "parent" :status])))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "child" :status])))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "grandchild" :status])))
        (is (= :completed (get-in @(:state* ctx) [:workflows :runs "done-child" :status])))
        (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/cancel-inflight-run :run-id "parent"}
                {:effect/type :runtime/agent-abort
                 :session-id "parent-session"
                 :workflow-run-id "parent"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "parent-attempt"
                 :expected-session-id "parent-session"
                 :workflow-session-kind :attempt}
                {:effect/type :runtime/agent-abort
                 :session-id "child-session"
                 :workflow-run-id "child"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "child-attempt"
                 :expected-session-id "child-session"
                 :workflow-session-kind :attempt}]
               (:declared-effects entry)))))))

(deftest remove-run-dispatch-cleanup-effects-test
  ;; Tests remove cleanup ordering for terminal/absent and nested/top-level cases
  ;; through state and dispatch-log effects, not interaction spies.
  (testing "live top-level remove cancels then re-enters remove and drops canonical record"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "run-1" :running))
      (let [result (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "run-1"} {:origin :core})
            entries (filter #(= :psi.workflow/remove-run (:event-type %)) (kernel/event-log-entries))]
        (is (true? (:psi.workflow/removed? result)))
        (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
        (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/cancel-inflight-run :run-id "run-1"}
                {:effect/type :runtime/dispatch-event
                 :event-type :psi.workflow/remove-run
                 :event-data {:run-id "run-1" :reason "cancelled by remove"}
                 :origin :core}]
               (:declared-effects (last entries))))
        (is (= [{:effect/type :runtime/cancel-inflight-run :run-id "run-1"}
                {:effect/type :runtime/drop-inflight-run :run-id "run-1"}
                {:effect/type :runtime/drop-workflow-cancellation-entry-lock :run-id "run-1"}]
               (:declared-effects (first entries)))))))

  (testing "live nested remove cancels then removes without cancelling the parent worker"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "parent" :running))
      (install-run! ctx (run "child" :running
                             :delegating-run-id "parent"
                             :step-runs {"step-1" {:attempts [{:attempt-id "child-attempt"
                                                               :status :running
                                                               :execution-session-id "child-session"}]}}))
      (let [result (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "child"} {:origin :core})
            entries (filter #(= :psi.workflow/remove-run (:event-type %)) (kernel/event-log-entries))]
        (is (true? (:psi.workflow/removed? result)))
        (is (= :running (get-in @(:state* ctx) [:workflows :runs "parent" :status])))
        (is (nil? (get-in @(:state* ctx) [:workflows :runs "child"])))
        (is (= [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/agent-abort
                 :session-id "child-session"
                 :workflow-run-id "child"
                 :workflow-step-id "step-1"
                 :workflow-attempt-id "child-attempt"
                 :expected-session-id "child-session"
                 :workflow-session-kind :attempt}
                {:effect/type :runtime/dispatch-event
                 :event-type :psi.workflow/remove-run
                 :event-data {:run-id "child" :reason "cancelled by remove"}
                 :origin :core}]
               (:declared-effects (last entries))))
        (is (= [{:effect/type :runtime/drop-inflight-run :run-id "child"}
                {:effect/type :runtime/drop-workflow-cancellation-entry-lock :run-id "child"}]
               (:declared-effects (first entries)))))))

  (testing "terminal nested remove does not infer or cancel a parent worker"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)]
      (install-run! ctx (run "parent" :running))
      (install-run! ctx (run "child" :cancelled :delegating-run-id "parent"))
      (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "child"} {:origin :core})
      (is (= [{:effect/type :runtime/drop-inflight-run :run-id "child"}
              {:effect/type :runtime/drop-workflow-cancellation-entry-lock :run-id "child"}]
             (:declared-effects (last-log-entry :psi.workflow/remove-run))))))

  (testing "absent remove cancels a possible stale handle before dropping it"
    (kernel/clear-event-log!)
    (let [ctx (make-ctx)
          result (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "ghost"} {:origin :core})]
      (is (false? (:psi.workflow/removed? result)))
      (is (true? (:psi.workflow/noop? result)))
      (is (= [{:effect/type :runtime/cancel-inflight-run :run-id "ghost"}
              {:effect/type :runtime/drop-inflight-run :run-id "ghost"}
              {:effect/type :runtime/drop-workflow-cancellation-entry-lock :run-id "ghost"}]
             (:declared-effects (last-log-entry :psi.workflow/remove-run)))))))

(deftest workflow-cancel-remove-background-job-terminalization-test
  ;; Tests cancellation terminalizes workflow background jobs through the
  ;; canonical dispatch effect while the workflow run remains readable.
  (testing "cancel without remove terminalizes the background job as cancelled"
    (let [ctx (make-ctx)]
      (install-run! ctx (run "run-1" :running))
      (swap! (:state* ctx) assoc-in (ss/state-path :background-jobs)
             (:state (background-jobs/start-background-job
                      (background-jobs/empty-state)
                      {:tool-call-id "tool-call-1"
                       :thread-id "session-1"
                       :tool-name "delegate"
                       :job-id "job-1"
                       :job-kind :workflow
                       :workflow-ext-path "built-in:workflow"
                       :workflow-id "run-1"})))
      (dispatch/dispatch! ctx :psi.workflow/cancel-run {:run-id "run-1"} {:origin :core})
      (let [job (background-jobs/get-job-in
                 (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                 "job-1")]
        (is (= :cancelled (:status job)))
        (is (= {:workflow-id "run-1"
                :status :cancelled
                :reason "cancelled"}
               (:terminal-payload job)))
        (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-1" :status]))))))

  (testing "live remove terminalizes before the re-entrant remove drops the run"
    (let [ctx (make-ctx)]
      (install-run! ctx (run "run-1" :running))
      (swap! (:state* ctx) assoc-in (ss/state-path :background-jobs)
             (:state (background-jobs/start-background-job
                      (background-jobs/empty-state)
                      {:tool-call-id "tool-call-1"
                       :thread-id "session-1"
                       :tool-name "delegate"
                       :job-id "job-1"
                       :job-kind :workflow
                       :workflow-ext-path "built-in:workflow"
                       :workflow-id "run-1"})))
      (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "run-1"} {:origin :core})
      (let [job (background-jobs/get-job-in
                 (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                 "job-1")]
        (is (= :cancelled (:status job)))
        (is (= {:workflow-id "run-1"
                :status :cancelled
                :reason "cancelled by remove"}
               (:terminal-payload job)))
        (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))))))

(deftest guarded-judge-abort-effect-test
  ;; Tests task 225 judge cancellation: guarded abort can target a judge session
  ;; recorded on a live workflow attempt, but stale/completed judge guards no-op.
  (let [effect {:effect/type :runtime/agent-abort
                :session-id "judge-session"
                :workflow-run-id "run-judge"
                :workflow-step-id "step-1"
                :workflow-attempt-id "attempt-1"
                :expected-session-id "judge-session"
                :workflow-session-kind :judge}
        ctx (make-ctx)]
    (install-run! ctx (run "run-judge" :running
                           :step-runs {"step-1" {:attempts [{:attempt-id "attempt-1"
                                                             :status :succeeded
                                                             :execution-session-id "actor-session"
                                                             :judge-session-id "judge-session"}]}}))
    (testing "in-flight judge sessions remain abortable on judged succeeded actor attempts"
      (is (= {:aborted? true :session-id "judge-session" :guarded? true}
             ((:execute-effect-fn ctx) ctx effect))))
    (testing "stale judge session guard remains a no-op"
      (is (nil? ((:execute-effect-fn ctx) ctx (assoc effect :expected-session-id "stale-judge-session")))))
    (testing "completed judge sessions are no longer abortable"
      (swap! (:state* ctx) assoc-in
             [:workflows :runs "run-judge" :step-runs "step-1" :attempts 0 :judge-output]
             "APPROVED")
      (swap! (:state* ctx) assoc-in
             [:workflows :runs "run-judge" :step-runs "step-1" :attempts 0 :judge-event]
             "APPROVED")
      (is (nil? ((:execute-effect-fn ctx) ctx effect))))))

(deftest delegate-cancelled-run-result-test
  ;; Tests direct nested-run cancellation through the existing delegate result
  ;; path: a cancelled child is a failed delegate step, so the parent worker can
  ;; continue normal step-failure handling instead of being halted.
  (let [state* (atom {})
        ctx {:state* state*}
        create-context-fn (fn [_ctx _parent-session-id run-id]
                            {:wm {}
                             :run-id run-id})
        send-and-drain-fn (fn [wf-ctx _wm _event _data]
                            (swap! state* assoc-in [:workflows :runs (:run-id wf-ctx) :status] :cancelled))]
    (swap! state* assoc :workflows (workflow-model/initial-workflow-state))
    (let [[registered-state definition-id _]
          (registry/register-definition @state*
                                        {:definition-id "child-flow"
                                         :steps [{:name "only"
                                                  :type :session
                                                  :contributions [{:type :template
                                                                   :text "done"
                                                                   :vars {}}]}]})]
      (reset! state* registered-state)
      (let [parent-run {:run-id "parent"
                        :status :running
                        :effective-definition {:definition-id "parent-flow"}}
            _ (swap! state* assoc-in [:workflows :runs "parent"] parent-run)
            step-def {:delegate {:target definition-id
                                 :prompt-string "go"}}
            result (delegate/delegate-step-runtime-result
                    create-context-fn
                    send-and-drain-fn
                    nil
                    ctx
                    "parent-session"
                    "delegate-step"
                    step-def
                    parent-run)]
        (is (= :failure (:pending-kind result)))
        (is (= "Delegated workflow cancelled" (get-in result [:payload :message])))
        (is (= {:status :cancelled} (get-in result [:payload :details])))
        (is (= :running (get-in @state* [:workflows :runs "parent" :status])))))))

(deftest delegate-removed-run-result-test
  ;; Tests removed delegate run result handling as state-based behavior: the
  ;; child run is removed before result collection and the parent receives the
  ;; cancellation/removal failure class.
  (let [state* (atom {})
        ctx {:state* state*}
        create-context-fn (fn [_ctx _parent-session-id run-id]
                            {:wm {}
                             :run-id run-id})
        send-and-drain-fn (fn [wf-ctx _wm _event _data]
                            (swap! state* update-in [:workflows :runs] dissoc (:run-id wf-ctx)))]
    (swap! state* assoc :workflows (workflow-model/initial-workflow-state))
    (let [[registered-state definition-id _]
          (registry/register-definition @state*
                                        {:definition-id "child-flow"
                                         :steps [{:name "only"
                                                  :type :session
                                                  :contributions [{:type :template
                                                                   :text "done"
                                                                   :vars {}}]}]})]
      (reset! state* registered-state)
      (let [parent-run {:run-id "parent"
                        :status :running
                        :effective-definition {:definition-id "parent-flow"}}
            _ (swap! state* assoc-in [:workflows :runs "parent"] parent-run)
            step-def {:delegate {:target definition-id
                                 :prompt-string "go"}}
            result (delegate/delegate-step-runtime-result
                    create-context-fn
                    send-and-drain-fn
                    nil
                    ctx
                    "parent-session"
                    "delegate-step"
                    step-def
                    parent-run)]
        (is (= :failure (:pending-kind result)))
        (is (= "Delegated workflow cancelled or removed" (get-in result [:payload :message])))
        (is (= {:status :removed} (get-in result [:payload :details])))))))

(deftest live-top-level-remove-cancels-parked-future-and-drops-inflight-entry-test
  ;; Tests acceptance criterion #2 end-to-end through canonical live remove:
  ;; a real parked top-level worker future is interrupted before the re-entrant
  ;; terminal remove drops the inflight handle.
  (let [started (promise)
        interrupted (promise)
        release (promise)
        fut (future
              (deliver started true)
              (try
                @release
                (catch InterruptedException _
                  (deliver interrupted true))))
        ctx (assoc (make-ctx)
                   :workflow-inflight-runs-handle (atom {"run-1" {:future fut}}))]
    @started
    (install-run! ctx (run "run-1" :running))
    (let [result (dispatch/dispatch! ctx :psi.workflow/remove-run {:run-id "run-1"} {:origin :core})]
      (is (true? (:psi.workflow/removed? result)))
      (is (true? (deref interrupted 1000 false))
          "live top-level remove must interrupt the parked worker future")
      (is (true? (future-cancelled? fut))
          "live top-level remove must future-cancel the top-level worker")
      (is (nil? (get @(:workflow-inflight-runs-handle ctx) "run-1"))
          "re-entrant terminal remove must drop the inflight entry")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"]))))
    (deliver release true)
    (future-cancel fut)))

(deftest inflight-run-effect-execution-test
  ;; Tests cancellation/cleanup effects against real isolated runtime-handle atoms.
  (testing "cancel-inflight-run future-cancels exact run handle before drop removes it"
    (let [ctx (assoc (make-ctx) :workflow-inflight-runs-handle (atom {}))
          fut (future (Thread/sleep 10000))]
      (swap! (:workflow-inflight-runs-handle ctx) assoc "run-1" {:future fut})
      (try
        (let [cancel-result ((:execute-effect-fn ctx) ctx {:effect/type :runtime/cancel-inflight-run
                                                           :run-id "run-1"})
              drop-result ((:execute-effect-fn ctx) ctx {:effect/type :runtime/drop-inflight-run
                                                         :run-id "run-1"})]
          (is (true? (:found? cancel-result)))
          (is (true? (future-cancelled? fut)))
          (is (true? (:found? drop-result)))
          (is (nil? (get @(:workflow-inflight-runs-handle ctx) "run-1"))))
        (finally
          (future-cancel fut)))))

  (testing "drop-workflow-cancellation-entry-lock removes exact lock entries"
    (let [ctx (make-ctx)
          lock-1 (cancellation-entry/lock-for ctx "run-1")
          lock-2 (cancellation-entry/lock-for ctx "run-2")]
      (is (some? lock-1))
      (is (some? lock-2))
      (is (= {:run-id "run-1" :found? true :dropped? true}
             ((:execute-effect-fn ctx) ctx {:effect/type :runtime/drop-workflow-cancellation-entry-lock
                                            :run-id "run-1"})))
      (is (nil? (get @(:workflow-cancellation-entry-locks-handle ctx) "run-1")))
      (is (identical? lock-2 (get @(:workflow-cancellation-entry-locks-handle ctx) "run-2"))))))
