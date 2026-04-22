(ns psi.agent-session.scheduler-handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.prompt-handlers :as prompt-handlers]
   [psi.agent-session.dispatch-handlers.prompt-lifecycle :as prompt-lifecycle]
   [psi.agent-session.dispatch-handlers.scheduler :as scheduler-handlers]
   [psi.agent-session.dispatch-handlers.session-lifecycle :as session-lifecycle-handlers]
   [psi.agent-session.dispatch-handlers.session-mutations :as session-mutations]
   [psi.agent-session.dispatch-handlers.statechart-actions :as statechart-actions]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- invoke-handler
  [ctx event-type data]
  (let [handler-fn (get-in (dispatch/handler-entry event-type) [:fn])]
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
  (dispatch/clear-handlers!)
  (try
    (scheduler-handlers/register! ctx)
    (prompt-handlers/register! ctx)
    (prompt-lifecycle/register! ctx)
    (session-lifecycle-handlers/register! ctx)
    (session-mutations/register! ctx)
    (statechart-actions/register! ctx)
    (f)
    (finally
      (dispatch/clear-handlers!))))

(deftest scheduler-create-cancel-fire-deliver-handlers-test
  (let [[ctx session-id] (test-support/make-session-ctx {})]
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
             (is (= :message (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :kind])))
             (is (= session-id (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :origin-session-id])))
             (is (= :pending (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= [{:effect/type :scheduler/start-timer
                      :session-id session-id
                      :schedule-id "sch-1"
                      :fire-at (instant "2026-04-21T18:05:00Z")}]
                    (:effects result)))))

         (testing "fired queues when session is busy"
           (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
           (let [result (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :queued (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= ["sch-1"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
             (is (nil? (:effects result)))))

         (testing "cancel removes queued schedule from queue and emits cancel effect"
           (let [result (invoke-handler ctx :scheduler/cancel {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
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
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-bulk-a" :status])))
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-bulk-b" :status])))
             (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
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
             (let [result (invoke-handler ctx :scheduler/deliver {:session-id session-id :schedule-id "sch-2"})]
               (apply-root-state-update! ctx result)
               (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-2" :status])))
               (is (= 1 (count (:effects result))))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type))))))))))

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
           (is (= :session (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-session" :kind])))
           (is (= :pending (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-session" :status])))
           (is (= :create-session (get-in (first (:effects fired)) [:event-data :delivery-phase])))
           (is (= :scheduler/deliver (get-in (first (:effects fired)) [:event-type]))))))))

(deftest scheduler-session-deliver-creates-top-level-session-without-switching-test
  (let [[ctx session-id] (test-support/make-session-ctx {:persist? false})]
    (with-registered-handlers
      ctx
      #(do
         (let [create-r (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-session-deliver"
                                                               :kind :session
                                                               :message "run in fresh session"
                                                               :label "later"
                                                               :session-config {:session-name "later session"
                                                                                :thinking-level :high
                                                                                :developer-prompt "dev layer"
                                                                                :developer-prompt-source :explicit
                                                                                :skills [{:name "test-skill" :description "d"}]
                                                                                :tool-defs [{:name "read" :description "Read" :parameters {:type "object"}}]
                                                                                :prompt-component-selection {:tool-names ["read"]}
                                                                                :preloaded-messages [{:role "user"
                                                                                                      :content [{:type :text :text "seed"}]
                                                                                                      :timestamp (instant "2026-04-21T18:29:00Z")}]}
                                                               :created-at (instant "2026-04-21T18:30:00Z")
                                                               :fire-at (instant "2026-04-21T18:31:00Z")
                                                               :delay-ms 1000})]
           (apply-root-state-update! ctx create-r))
         (let [origin-before session-id
               result        (invoke-handler ctx :scheduler/deliver {:session-id session-id :schedule-id "sch-session-deliver"})
               _             (apply-root-state-update! ctx result)
               schedule      (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-session-deliver"])
               created-id    (:created-session-id schedule)
               created-sd    (ss/get-session-data-in ctx created-id)]
           (is (= origin-before session-id))
           (is (string? created-id))
           (is (not= session-id created-id))
           (is (= :delivered (:status schedule)))
           (is (= :prompt-submit (:delivery-phase schedule)))
           (is (= session-id (:scheduled-origin-session-id created-sd)))
           (is (= "sch-session-deliver" (:scheduled-from-schedule-id created-sd)))
           (is (= "later" (:scheduled-from-label created-sd)))
           (is (= "later session" (:session-name created-sd)))
           (is (= :high (:thinking-level created-sd)))
           (is (= "dev layer" (:developer-prompt created-sd)))
           (is (= :explicit (:developer-prompt-source created-sd)))
           (is (= ["test-skill"] (mapv :name (:skills created-sd))))
           (is (= ["read"] (mapv :name (:tool-defs created-sd))))
           (is (= {:tool-names ["read"]} (:prompt-component-selection created-sd)))
           (is (= session-id (:origin-session-id schedule)))
           (is (some (fn [entry]
                       (= "seed" (get-in entry [:data :message :content 0 :text])))
                     (persist/all-entries-in ctx created-id))))))))

(deftest scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test
  (let [[ctx session-id] (test-support/make-session-ctx {:persist? false})]
    (with-registered-handlers
      ctx
      #(with-redefs [psi.agent-session.dispatch/dispatch!
                     (let [real-dispatch psi.agent-session.dispatch/dispatch!]
                       (fn [ctx* event-type event-data opts]
                         (if (= :session/submit-synthetic-user-prompt event-type)
                           (throw (ex-info "boom" {:created-session-id (:session-id event-data)
                                                   :delivery-phase :prompt-submit}))
                           (real-dispatch ctx* event-type event-data opts))))]
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
             (is (= :failed (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-session-fail" :status])))
             (is (= :prompt-submit (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-session-fail" :delivery-phase])))))))))

(deftest scheduler-drain-and-statechart-idle-hooks-test
  (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:is-streaming true}})]
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
           (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))

           (testing "drain-queue delivers one queued schedule when idle"
             (let [result (invoke-handler ctx :scheduler/drain-queue {:session-id session-id})]
               (apply-root-state-update! ctx result)
               (is (= ["sch-b"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
               (is (= "sch-a" (get-in result [:return :schedule-id])))
               (is (= 1 (count (:effects result))))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type)))))

           (testing "idle transitions emit scheduler drain effects"
             (let [done-r (invoke-handler ctx :on-agent-done {:session-id session-id})
                   abort-r (invoke-handler ctx :on-abort {:session-id session-id})
                   compact-r (invoke-handler ctx :on-compact-done {:session-id session-id})]
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects done-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects abort-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:effect/type effect))) (:effects compact-r))))))))))
