(ns psi.agent-session.scheduler-handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.dispatch-handlers.prompt-handlers :as prompt-handlers]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.dispatch-handlers.prompt-lifecycle :as prompt-lifecycle]
   [psi.agent-session.dispatch-handlers.scheduler :as scheduler-handlers]
   [psi.agent-session.dispatch-handlers.session-lifecycle :as session-lifecycle-handlers]
   [psi.agent-session.dispatch-handlers.session-mutations :as session-mutations]
   [psi.agent-session.dispatch-handlers.statechart-actions :as statechart-actions]
   [psi.agent-session.test-support :as test-support]))

(defn- invoke-handler
  [ctx event-type data]
  (let [handler-fn (get-in (kernel/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(defn- apply-root-state-update!
  [ctx result]
  (when-let [f (:root-state-update result)]
    (swap! (:state* ctx) f))
  result)

(defn- instant
  [s]
  (java.time.Instant/parse s))

(defn- with-registered-handlers
  [ctx f]
  (kernel/clear-handlers!)
  (try
    (scheduler-handlers/register! ctx)
    (prompt-handlers/register! ctx)
    (prompt-lifecycle/register! ctx)
    (session-lifecycle-handlers/register! ctx)
    (session-mutations/register! ctx)
    (statechart-actions/register! ctx)
    (f)
    (finally
      (kernel/clear-handlers!))))

(deftest scheduler-create-cancel-fire-deliver-handlers-test
  (let [delivered-at (instant "2026-04-21T18:12:00Z")
        [ctx session-id] (test-support/make-session-ctx {})]
    (with-registered-handlers
      ctx
      #(do
         (testing "create stores schedule and emits timer effect"
           (let [result (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-1"
                                                               :kind :message
                                                               :label "check-build"
                                                               :message "check build"
                                                               :created-at (instant "2026-04-21T18:00:00Z")
                                                               :fire-at (instant "2026-04-21T18:05:00Z")
                                                               :delay-ms 5000})]
             (apply-root-state-update! ctx result)
             (is (= :message (:kind (test-support/schedule-by-id ctx session-id "sch-1"))))
             (is (= session-id (:origin-session-id (test-support/schedule-by-id ctx session-id "sch-1"))))
             (is (= :pending (test-support/schedule-status ctx session-id "sch-1")))
             (is (= [{:effect/type :scheduler/start-timer
                      :session-id session-id
                      :schedule-id "sch-1"
                      :fire-at (instant "2026-04-21T18:05:00Z")}]
                    (:effects result)))))

         (testing "fired queues when session is busy"
           (test-support/set-session-streaming! ctx session-id true)
           (let [result (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :queued (test-support/schedule-status ctx session-id "sch-1")))
             (is (= ["sch-1"] (test-support/schedule-queue ctx session-id)))
             (is (nil? (:effects result)))))

         (testing "cancel removes queued schedule from queue and emits cancel effect"
           (let [result (invoke-handler ctx :scheduler/cancel {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :cancelled (test-support/schedule-status ctx session-id "sch-1")))
             (is (= [] (test-support/schedule-queue ctx session-id)))
             (is (= [{:effect/type :scheduler/cancel-timer
                      :schedule-id "sch-1"}]
                    (:effects result)))))

         (testing "cancel-all cancels all non-terminal schedules and emits one timer-cancel effect per schedule"
           (let [create-a (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-bulk-a"
                                                                 :kind :message
                                                                 :message "a"
                                                                 :created-at (instant "2026-04-21T18:20:00Z")
                                                                 :fire-at (instant "2026-04-21T18:21:00Z")
                                                                 :delay-ms 1000})
                 _ (apply-root-state-update! ctx create-a)
                 create-b (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-bulk-b"
                                                                 :kind :message
                                                                 :message "b"
                                                                 :created-at (instant "2026-04-21T18:20:01Z")
                                                                 :fire-at (instant "2026-04-21T18:21:01Z")
                                                                 :delay-ms 1000})
                 _ (apply-root-state-update! ctx create-b)
                 bulk-r (invoke-handler ctx :scheduler/cancel-all {:session-id session-id})]
             (apply-root-state-update! ctx bulk-r)
             (is (= :cancelled (test-support/schedule-status ctx session-id "sch-bulk-a")))
             (is (= :cancelled (test-support/schedule-status ctx session-id "sch-bulk-b")))
             (is (= [] (test-support/schedule-queue ctx session-id)))
             (is (= 2 (count (:effects bulk-r))))
             (is (= #{"sch-bulk-a" "sch-bulk-b"}
                    (set (map :schedule-id (:effects bulk-r)))))))

         (testing "deliver marks delivered and routes through canonical prompt lifecycle"
           (let [create-r (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-2"
                                                                 :kind :message
                                                                 :message "wake up"
                                                                 :created-at (instant "2026-04-21T18:10:00Z")
                                                                 :fire-at (instant "2026-04-21T18:11:00Z")
                                                                 :delay-ms 1000})]
             (apply-root-state-update! ctx create-r)
             (let [result (invoke-handler ctx :scheduler/deliver {:session-id session-id
                                                                  :schedule-id "sch-2"
                                                                  :delivered-at delivered-at})]
               (apply-root-state-update! ctx result)
               (is (= :delivered (test-support/schedule-status ctx session-id "sch-2")))
               (is (= 1 (count (:effects result))))
               (is (= delivered-at (-> result :effects first :event-data :user-msg :timestamp)))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type))))))))))

(deftest scheduler-deliver-and-drain-use-time-source-when-delivered-at-omitted-test
  (let [delivered-at (instant "2026-04-21T18:12:34Z")
        [ctx0 session-id] (test-support/make-session-ctx {})
        ctx (assoc ctx0 :scheduler-time-source (test-support/fixed-scheduler-time-source delivered-at))]
    (with-registered-handlers
      ctx
      #(do
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-deliver-from-source"
                                                 :kind :message
                                                 :message "wake up"
                                                 :created-at (instant "2026-04-21T18:10:00Z")
                                                 :fire-at (instant "2026-04-21T18:11:00Z")
                                                 :delay-ms 1000}))
         (testing "deliver stamps scheduled user message from scheduler time source"
           (let [result (invoke-handler ctx :scheduler/deliver {:session-id session-id
                                                                :schedule-id "sch-deliver-from-source"})]
             (apply-root-state-update! ctx result)
             (is (= delivered-at (-> result :effects first :event-data :user-msg :timestamp)))))

         (test-support/set-session-streaming! ctx session-id true)
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-drain-from-source"
                                                 :kind :message
                                                 :message "resume"
                                                 :created-at (instant "2026-04-21T18:13:00Z")
                                                 :fire-at (instant "2026-04-21T18:14:00Z")
                                                 :delay-ms 1000}))
         (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id
                                                                             :schedule-id "sch-drain-from-source"}))
         (test-support/set-session-streaming! ctx session-id false)
         (testing "drain stamps scheduled user message from scheduler time source"
           (let [result (invoke-handler ctx :scheduler/drain-queue {:session-id session-id})]
             (apply-root-state-update! ctx result)
             (is (= delivered-at (-> result :effects first :event-data :user-msg :timestamp)))))))))

(deftest scheduler-deliver-and-drain-require-time-source-when-delivered-at-omitted-test
  (let [[ctx session-id] (test-support/make-session-ctx {})]
    (with-registered-handlers
      ctx
      #(do
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-deliver-needs-time"
                                                 :kind :message
                                                 :message "wake up"
                                                 :created-at (instant "2026-04-21T18:10:00Z")
                                                 :fire-at (instant "2026-04-21T18:11:00Z")
                                                 :delay-ms 1000}))
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"scheduler time-source"
                               (invoke-handler (dissoc ctx :scheduler-time-source)
                                               :scheduler/deliver
                                               {:session-id session-id
                                                :schedule-id "sch-deliver-needs-time"})))

         (test-support/set-session-streaming! ctx session-id true)
         (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id
                                                                             :schedule-id "sch-deliver-needs-time"}))
         (test-support/set-session-streaming! ctx session-id false)
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"scheduler time-source"
                               (invoke-handler (dissoc ctx :scheduler-time-source)
                                               :scheduler/drain-queue
                                               {:session-id session-id})))))))

(deftest scheduler-session-deliver-requires-time-source-without-marking-failed-test
  (let [[ctx session-id] (test-support/make-session-ctx {})]
    (with-registered-handlers
      ctx
      #(do
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-session-needs-missing-time"
                                                 :kind :session
                                                 :message "run in fresh session"
                                                 :session-config {:session-name "later session"}
                                                 :created-at (instant "2026-04-21T18:30:00Z")
                                                 :fire-at (instant "2026-04-21T18:31:00Z")
                                                 :delay-ms 1000}))
         (testing "missing scheduler time source fails fast before failed-schedule handling"
           (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                 #"scheduler time-source"
                                 (invoke-handler (dissoc ctx :scheduler-time-source)
                                                 :scheduler/deliver
                                                 {:session-id session-id
                                                  :schedule-id "sch-session-needs-missing-time"})))
           (is (= :pending (test-support/schedule-status ctx session-id "sch-session-needs-missing-time"))))

         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-session-needs-valid-time"
                                                 :kind :session
                                                 :message "run in fresh session"
                                                 :session-config {:session-name "later session"}
                                                 :created-at (instant "2026-04-21T18:32:00Z")
                                                 :fire-at (instant "2026-04-21T18:33:00Z")
                                                 :delay-ms 1000}))
         (testing "invalid scheduler time source fails fast before failed-schedule handling"
           (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                 #"scheduler time-source"
                                 (invoke-handler (assoc ctx :scheduler-time-source (fn [] "not an instant"))
                                                 :scheduler/deliver
                                                 {:session-id session-id
                                                  :schedule-id "sch-session-needs-valid-time"})))
           (is (= :pending (test-support/schedule-status ctx session-id "sch-session-needs-valid-time"))))))))

(deftest scheduler-deliver-checks-schedule-before-time-source-test
  (let [[ctx session-id] (test-support/make-session-ctx {})]
    (with-registered-handlers
      ctx
      #(do
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"schedule not found"
                               (invoke-handler (dissoc ctx :scheduler-time-source)
                                               :scheduler/deliver
                                               {:session-id session-id
                                                :schedule-id "missing-schedule"})))
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/create {:session-id session-id
                                                 :schedule-id "sch-cancelled"
                                                 :kind :message
                                                 :message "wake up"
                                                 :created-at (instant "2026-04-21T18:10:00Z")
                                                 :fire-at (instant "2026-04-21T18:11:00Z")
                                                 :delay-ms 1000}))
         (apply-root-state-update!
          ctx
          (invoke-handler ctx :scheduler/cancel {:session-id session-id
                                                 :schedule-id "sch-cancelled"}))
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"schedule is not deliverable"
                               (invoke-handler (dissoc ctx :scheduler-time-source)
                                               :scheduler/deliver
                                               {:session-id session-id
                                                :schedule-id "sch-cancelled"})))))))

(deftest scheduler-session-kind-fires-without-origin-idle-test
  (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:is-streaming true}})]
    (with-registered-handlers
      ctx
      #(do
         (let [result (invoke-handler ctx :scheduler/create {:session-id session-id
                                                             :schedule-id "sch-session"
                                                             :kind :session
                                                             :message "run in fresh session"
                                                             :session-config {:session-name "later"}
                                                             :created-at (instant "2026-04-21T18:30:00Z")
                                                             :fire-at (instant "2026-04-21T18:31:00Z")
                                                             :delay-ms 1000})]
           (apply-root-state-update! ctx result))
         (let [fired (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-session"})]
           (apply-root-state-update! ctx fired)
           (is (= :session (:kind (test-support/schedule-by-id ctx session-id "sch-session"))))
           (is (= :pending (test-support/schedule-status ctx session-id "sch-session")))
           (is (= :create-session (get-in (first (:effects fired)) [:event-data :delivery-phase])))
           (is (= :scheduler/deliver (get-in (first (:effects fired)) [:event-type]))))))))

(deftest scheduler-session-deliver-creates-top-level-session-without-switching-test
  (let [[ctx session-id] (test-support/make-session-ctx {:persist? false})]
    (with-registered-handlers
      ctx
      #(let [create-r (invoke-handler ctx :scheduler/create {:session-id session-id
                                                             :schedule-id "sch-session-deliver"
                                                             :kind :session
                                                             :message "run in fresh session"
                                                             :label "later"
                                                             :session-config {:session-name "later session"
                                                                              :thinking-level :high
                                                                              :developer-prompt "dev layer"
                                                                              :developer-prompt-source :explicit
                                                                              :skills [{:name "test-skill" :description "d"}]
                                                                              :tool-ids ["read"]
                                                                              :prompt-component-selection {:tool-names ["read"]}
                                                                              :preloaded-messages [{:role "user"
                                                                                                    :content [{:type :text :text "seed"}]
                                                                                                    :timestamp (instant "2026-04-21T18:29:00Z")}]}
                                                             :created-at (instant "2026-04-21T18:30:00Z")
                                                             :fire-at (instant "2026-04-21T18:31:00Z")
                                                             :delay-ms 1000})]
         (apply-root-state-update! ctx create-r)
         (is (= :session (:kind (test-support/schedule-by-id ctx session-id "sch-session-deliver"))))
         (is (= "later session"
                (get-in (test-support/schedule-by-id ctx session-id "sch-session-deliver") [:session-config :session-name])))
         (is (= {:skill-count 1
                 :tool-count 1}
                (select-keys (:session-config-summary (test-support/schedule-by-id ctx session-id "sch-session-deliver"))
                             [:skill-count :tool-count])))))))

(deftest scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test
  (let [[ctx session-id] (test-support/make-session-ctx {:persist? false})]
    (with-registered-handlers
      ctx
      #(do
         ;; Drive the prompt-submit failure through the *real* dispatch pipeline
         ;; rather than stubbing dispatch/dispatch! (an infra-boundary var redef):
         ;; re-register the synthetic-prompt-submit handler to return
         ;; {:submitted? false}, which the real :scheduler/deliver catch branch
         ;; surfaces. This keeps the live session-kind failure round trip (real
         ;; top-level session creation, real catch-branch error mapping) while
         ;; using the kernel handler registry — the project's own dispatch seam —
         ;; not a global var stub.
         (kernel/register-handler!
          :session/submit-synthetic-user-prompt
          (fn [_ctx {:keys [user-msg]}]
            {:return {:submitted? false :user-msg user-msg}}))
         (let [create-r (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-session-fail"
                                                               :kind :session
                                                               :message "run in fresh session"
                                                               :session-config {:session-name "later session"}
                                                               :created-at (instant "2026-04-21T18:30:00Z")
                                                               :fire-at (instant "2026-04-21T18:31:00Z")
                                                               :delay-ms 1000})]
           (apply-root-state-update! ctx create-r)
           (let [result (invoke-handler ctx :scheduler/deliver {:session-id session-id :schedule-id "sch-session-fail"})]
             (apply-root-state-update! ctx result)
             (let [failed (test-support/schedule-by-id ctx session-id "sch-session-fail")]
               (is (= :failed (:status failed)))
               (is (= :prompt-submit (:delivery-phase failed)))
               ;; 201 verification: failure records error-summary and the created-session-id
               (is (some? (:error-summary failed)) "failure records an error summary")
               (is (= "scheduled session prompt submission failed"
                      (get-in failed [:error-summary :message])))
               (is (some? (:created-session-id failed))
                   "session created before prompt-submit failure is still recorded"))))))))

(deftest scheduler-drain-and-statechart-idle-hooks-test
  (let [drained-at (instant "2026-04-21T18:06:00Z")
        [ctx session-id] (test-support/make-session-ctx {:session-data {:is-streaming true}})]
    (with-registered-handlers
      ctx
      #(do
         (let [create-a (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-a"
                                                               :kind :message
                                                               :message "a"
                                                               :created-at (instant "2026-04-21T18:00:00Z")
                                                               :fire-at (instant "2026-04-21T18:05:00Z")
                                                               :delay-ms 1000})]
           (apply-root-state-update! ctx create-a)
           (let [create-b (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-b"
                                                                 :kind :message
                                                                 :message "b"
                                                                 :created-at (instant "2026-04-21T18:00:01Z")
                                                                 :fire-at (instant "2026-04-21T18:05:01Z")
                                                                 :delay-ms 1000})]
             (apply-root-state-update! ctx create-b))
           (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-a"}))
           (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-b"}))
           (test-support/set-session-streaming! ctx session-id false)

           (testing "drain-queue delivers one queued schedule when idle"
             (let [result (invoke-handler ctx :scheduler/drain-queue {:session-id session-id
                                                                      :delivered-at drained-at})]
               (apply-root-state-update! ctx result)
               (is (= ["sch-b"] (test-support/schedule-queue ctx session-id)))
               (is (= "sch-a" (get-in result [:return :schedule-id])))
               (is (= 1 (count (:effects result))))
               (is (= drained-at (-> result :effects first :event-data :user-msg :timestamp)))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type)))))

           (testing "idle transitions emit scheduler drain effects"
             (let [done-r (invoke-handler ctx :on-agent-done {:session-id session-id})
                   abort-r (invoke-handler ctx :on-abort {:session-id session-id})
                   compact-r (invoke-handler ctx :on-compact-done {:session-id session-id})]
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects done-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects abort-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects compact-r))))))))))
