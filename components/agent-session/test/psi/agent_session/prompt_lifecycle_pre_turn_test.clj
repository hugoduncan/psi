(ns psi.agent-session.prompt-lifecycle-pre-turn-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]
   [psi.turn-runtime.core]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- install-workflow-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[state* _ _] (workflow-registry/register-definition
                               state
                               {:definition-id "pre-turn-cancel-test"
                                :steps [{:name "build" :type :session}]})
                 [state* _ _] (workflow-runtime/create-run
                               state*
                               {:definition-id "pre-turn-cancel-test"
                                :run-id run-id
                                :workflow-input {:input "ship it"}})]
             state*))))

(defn- workflow-attempt-session!
  [ctx session-id run-id]
  (swap! (:state* ctx)
         (fn [state]
           (-> state
               (assoc-in (ss/session-data-path session-id)
                         (assoc (get-in state (ss/session-data-path session-id))
                                :workflow-owned? true
                                :workflow-run-id run-id
                                :workflow-step-id "build"
                                :workflow-attempt-id "attempt-build"))
               (assoc-in [:workflows :runs run-id :current-step-id] "build")
               (assoc-in [:workflows :runs run-id :status] :running)
               (assoc-in [:workflows :runs run-id :step-runs "build" :attempts]
                         [{:attempt-id "attempt-build"
                           :status :running
                           :execution-session-id session-id}])))))

(deftest pre-turn-augmentation-opens-closes-and-schedules-prepare-test
  ;; Pre-turn augmentation is an explicit lifecycle barrier before request preparation.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (kernel/clear-event-log!)
    (kernel/clear-dispatch-trace!)
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-barrier"
                             :user-msg user-msg}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-barrier"
                             :user-msg user-msg}
                            {:origin :core}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          entries (kernel/dispatch-trace-entries)]
      (is (= :turn/augmentation-closed
             (get-in session-data [:prompt-turns "turn-barrier" :state])))
      (is (= :no-op
             (get-in session-data [:turn-augmentations "turn-barrier" :status])))
      (is (= [:session/prompt-submit
              :session/pre-turn-augment
              :session/close-pre-turn-augmentation
              :session/prompt-prepare-request]
             (->> entries
                  (filter #(= :dispatch/received (:trace/kind %)))
                  (keep :event-type)
                  (filter #{:session/prompt-submit
                            :session/pre-turn-augment
                            :session/close-pre-turn-augmentation
                            :session/prompt-prepare-request})
                  vec))))))

(deftest live-turn-augmentation-invokes-provider-and-inserts-context-test
  ;; Authorized live turn augmenters run at the effect boundary and their
  ;; accepted operations are recorded before request preparation consumes them.
  (let [[ctx session-id] (create-session-context {:persist? false})
        ext-id "manifest:psi/context-manager"
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (ext/register-extension-in! (:extension-registry ctx) ext-id)
    (ext/set-effective-permissions-in!
     (:extension-registry ctx)
     ext-id
     #{ext/turn-augmentation-capability})
    (ss/update-state-value-in!
     ctx
     (ss/session-data-path session-id)
     assoc-in
     [:available-extension-capabilities :extensions ext-id]
     #{ext/turn-augmentation-capability})
    (ext/register-turn-augmenter-in!
     (:extension-registry ctx)
     ext-id
     {:augmenter-id "project-context"
      :handler (fn [projection]
                 {:turn-augmentation/status :success
                  :turn-augmentation/operations
                  [{:op :append-context-block
                    :id "project-context"
                    :title "Project context"
                    :content (str "Working directory: "
                                  (:turn-augmentation/effective-cwd projection))}]})})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-live"
                             :user-msg user-msg}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-live"
                             :user-msg user-msg}
                            {:origin :core}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          record (get-in session-data [:turn-augmentations "turn-live"])
          summary (:last-prepared-request-summary session-data)]
      (is (= :success (:status record)))
      (is (= [{:extension-id ext-id
               :augmenter-id "project-context"
               :status :success
               :operation-count 1
               :accepted-operation-count 1
               :rejected-operation-count 0
               :child-session-ids []
               :reasons []}]
             (:providers record)))
      (is (= "project-context" (get-in record [:operations 0 :id])))
      (is (= :success (get-in summary [:augmentation :status])))
      (is (= 1 (get-in summary [:augmentation :accepted-operation-count]))))))

(deftest live-turn-augmentation-skips-session-unauthorized-provider-test
  ;; Stale registrations that are not available to the parent session are skipped
  ;; and diagnosed without invoking the handler.
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        invoked? (atom false)
        ext-id "manifest:psi/context-manager"
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (ext/register-extension-in! (:extension-registry ctx) ext-id)
    (ext/set-effective-permissions-in!
     (:extension-registry ctx)
     ext-id
     #{ext/turn-augmentation-capability})
    (ext/register-turn-augmenter-in!
     (:extension-registry ctx)
     ext-id
     {:augmenter-id "project-context"
      :handler (fn [_]
                 (reset! invoked? true)
                 {:turn-augmentation/status :success
                  :turn-augmentation/operations
                  [{:op :append-context-block
                    :id "unauthorized"
                    :title "Unauthorized"
                    :content "must not be accepted"}]})})
    (let [session-id (:session-id (session/new-session-in! ctx nil {}))]
      (ss/update-state-value-in!
       ctx
       (ss/session-data-path session-id)
       assoc-in
       [:available-extension-capabilities :extensions]
       {})
      (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                    (fn [_ai-ctx _ctx sid prepared _pq]
                      {:execution-result/turn-id (:prepared-request/id prepared)
                       :execution-result/session-id sid
                       :execution-result/assistant-message {:role "assistant"
                                                            :content [{:type :text :text "ok"}]
                                                            :stop-reason :stop
                                                            :timestamp (java.time.Instant/now)}
                       :execution-result/turn-outcome :turn.outcome/stop
                       :execution-result/tool-calls []
                       :execution-result/stop-reason :stop})]
        (session/dispatch-in! ctx :session/prompt-submit
                              {:session-id session-id
                               :turn-id "turn-unauthorized"
                               :user-msg user-msg}
                              {:origin :core})
        (session/dispatch-in! ctx :session/pre-turn-augment
                              {:session-id session-id
                               :turn-id "turn-unauthorized"
                               :user-msg user-msg}
                              {:origin :core}))
      (let [record (get-in (ss/get-session-data-in ctx session-id)
                           [:turn-augmentations "turn-unauthorized"])]
        (is (false? @invoked?))
        (is (= :failed (:status record)))
        (is (= [{:extension-id ext-id
                 :augmenter-id "project-context"
                 :status :unauthorized
                 :operation-count 0
                 :accepted-operation-count 0
                 :rejected-operation-count 0
                 :child-session-ids []
                 :reasons [:unauthorized]}]
               (:providers record)))))))

(deftest live-turn-augmentation-records-stale-replacement-diagnostics-test
  ;; Provider results are accepted only for the registration token selected when
  ;; the phase opened; replacing a provider during invocation marks the result stale.
  (let [[ctx session-id] (create-session-context {:persist? false})
        ext-id "manifest:psi/context-manager"
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (ext/register-extension-in! (:extension-registry ctx) ext-id)
    (ext/set-effective-permissions-in!
     (:extension-registry ctx)
     ext-id
     #{ext/turn-augmentation-capability})
    (ss/update-state-value-in!
     ctx
     (ss/session-data-path session-id)
     assoc-in
     [:available-extension-capabilities :extensions ext-id]
     #{ext/turn-augmentation-capability})
    (ext/register-turn-augmenter-in!
     (:extension-registry ctx)
     ext-id
     {:augmenter-id "project-context"
      :handler (fn [_projection]
                 (ext/register-turn-augmenter-in!
                  (:extension-registry ctx)
                  ext-id
                  {:augmenter-id "project-context"
                   :handler (fn [_]
                              {:turn-augmentation/status :no-op
                               :turn-augmentation/operations []})})
                 {:turn-augmentation/status :success
                  :turn-augmentation/operations
                  [{:op :append-context-block
                    :id "stale"
                    :title "Stale"
                    :content "must not be accepted"}]})})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-stale"
                             :user-msg user-msg}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-stale"
                             :user-msg user-msg}
                            {:origin :core}))
    (let [record (get-in (ss/get-session-data-in ctx session-id)
                         [:turn-augmentations "turn-stale"])]
      (is (= :failed (:status record)))
      (is (= [] (:operations record)))
      (is (= [{:extension-id ext-id
               :augmenter-id "project-context"
               :status :stale
               :operation-count 0
               :accepted-operation-count 0
               :rejected-operation-count 0
               :child-session-ids []
               :reasons [:late-stale-result]}]
             (:providers record))))))

(deftest parent-cancellation-closes-open-augmentation-without-preparing-request-test
  ;; Workflow cancellation during open augmentation records a canceled terminal
  ;; augmentation record and skips request preparation/execution.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-pre-turn-cancel"
        ext-id "manifest:psi/context-manager"
        prepare-calls* (atom 0)
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (install-workflow-run! ctx run-id)
    (workflow-attempt-session! ctx session-id run-id)
    (ext/register-extension-in! (:extension-registry ctx) ext-id)
    (ext/set-effective-permissions-in!
     (:extension-registry ctx)
     ext-id
     #{ext/turn-augmentation-capability})
    (ss/update-state-value-in!
     ctx
     (ss/session-data-path session-id)
     assoc-in
     [:available-extension-capabilities :extensions ext-id]
     #{ext/turn-augmentation-capability})
    (ext/register-turn-augmenter-in!
     (:extension-registry ctx)
     ext-id
     {:augmenter-id "project-context"
      :handler (fn [_]
                 (session/dispatch-in! ctx
                                       :psi.workflow/cancel-run
                                       {:run-id run-id
                                        :reason "cancel during augmentation"}
                                       {:origin :core})
                 {:turn-augmentation/status :success
                  :turn-augmentation/operations
                  [{:op :append-context-block
                    :id "late"
                    :title "Late"
                    :content "must not apply"}]})})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [& _]
                    (swap! prepare-calls* inc)
                    {:execution-result/turn-id "turn-cancel"})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-cancel"
                             :workflow-run-id run-id
                             :user-msg user-msg}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-cancel"
                             :workflow-run-id run-id
                             :user-msg user-msg}
                            {:origin :core}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          record (get-in session-data [:turn-augmentations "turn-cancel"])]
      (is (= :turn/canceled
             (get-in session-data [:prompt-turns "turn-cancel" :state])))
      (is (= :canceled (:status record)))
      (is (= [] (:operations record)))
      (is (= [{:extension-id ext-id
               :augmenter-id "project-context"
               :status :canceled
               :operation-count 0
               :accepted-operation-count 0
               :rejected-operation-count 0
               :child-session-ids []
               :reasons [:provider-canceled]}]
             (:providers record)))
      (is (nil? (:last-prepared-request-summary session-data)))
      (is (= 0 @prepare-calls*)))))

(deftest prompt-prepare-request-rejects-before-augmentation-closed-test
  ;; Direct prepare attempts cannot bypass the augmentation lifecycle barrier.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (kernel/clear-dispatch-trace!)
    (session/dispatch-in! ctx :session/prompt-submit
                          {:session-id session-id
                           :turn-id "turn-direct"
                           :user-msg user-msg}
                          {:origin :core})
    (let [result (session/dispatch-in! ctx :session/prompt-prepare-request
                                       {:session-id session-id
                                        :turn-id "turn-direct"
                                        :user-msg user-msg}
                                       {:origin :core})
          session-data (ss/get-session-data-in ctx session-id)]
      (is (nil? result))
      (is (nil? (:last-prepared-request-summary session-data)))
      (is (= :turn/submitted
             (get-in session-data [:prompt-turns "turn-direct" :state]))))))

(defn- replayable-close-record
  [session-id turn-id]
  {:session-id session-id
   :turn-id turn-id
   :workflow-run-id nil
   :status :success
   :replay? false
   :accepted-operation-count 1
   :operations [{:op :append-context-block
                 :id "recorded-context"
                 :title "Recorded context"
                 :content "Recorded replay context"
                 :source {:type :extension
                          :extension-id "manifest:psi/context-manager"
                          :augmenter-id "project-context"
                          :child-session-ids []}}]
   :providers [{:extension-id "manifest:psi/context-manager"
                :augmenter-id "project-context"
                :status :success
                :operation-count 1
                :accepted-operation-count 1
                :rejected-operation-count 0
                :child-session-ids []
                :reasons []}]})

(deftest replayed-turn-augmentation-uses-close-payload-without-live-invocation-test
  ;; Replay recreates the open phase, consumes the recorded terminal close
  ;; payload, and never invokes live turn augmenters.
  (let [[ctx session-id] (create-session-context {:persist? false})
        invoked? (atom false)
        ext-id "manifest:psi/context-manager"
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}
        close-record (replayable-close-record session-id "turn-replay")]
    (ext/register-extension-in! (:extension-registry ctx) ext-id)
    (ext/set-effective-permissions-in!
     (:extension-registry ctx)
     ext-id
     #{ext/turn-augmentation-capability})
    (ss/update-state-value-in!
     ctx
     (ss/session-data-path session-id)
     assoc-in
     [:available-extension-capabilities :extensions ext-id]
     #{ext/turn-augmentation-capability})
    (ext/register-turn-augmenter-in!
     (:extension-registry ctx)
     ext-id
     {:augmenter-id "project-context"
      :handler (fn [_]
                 (reset! invoked? true)
                 {:turn-augmentation/status :success
                  :turn-augmentation/operations
                  [{:op :append-context-block
                    :id "live"
                    :title "Live"
                    :content "must not run"}]})})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-replay"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-replay"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/close-pre-turn-augmentation
                            {:session-id session-id
                             :turn-id "turn-replay"
                             :user-msg user-msg
                             :close-record close-record}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/prompt-prepare-request
                            {:session-id session-id
                             :turn-id "turn-replay"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          record (get-in session-data [:turn-augmentations "turn-replay"])
          summary (:last-prepared-request-summary session-data)]
      (is (false? @invoked?))
      (is (= :turn/augmentation-closed
             (get-in session-data [:prompt-turns "turn-replay" :state])))
      (is (= :replay-used (:status record)))
      (is (true? (:replay? record)))
      (is (= "recorded-context" (get-in record [:operations 0 :id])))
      (is (= :replay-used (get-in summary [:augmentation :status])))
      (is (= 1 (get-in summary [:augmentation :accepted-operation-count]))))))

(deftest replayed-turn-augmentation-fails-closed-for-missing-close-payload-test
  ;; Missing replay close payload records replay-missing and does not prepare a request.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [& _]
                    (throw (ex-info "must not prepare" {})))]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-replay-missing"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-replay-missing"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/close-pre-turn-augmentation
                            {:session-id session-id
                             :turn-id "turn-replay-missing"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          record (get-in session-data [:turn-augmentations "turn-replay-missing"])]
      (is (= :turn/augmentation-failed
             (get-in session-data [:prompt-turns "turn-replay-missing" :state])))
      (is (= :replay-missing (:status record)))
      (is (true? (:replay? record)))
      (is (= [:missing-record] (get-in record [:providers 0 :reasons])))
      (is (nil? (:last-prepared-request-summary session-data))))))

(deftest replayed-turn-augmentation-fails-closed-for-wrong-turn-close-payload-test
  ;; Wrong-turn replay close payload records replay-invalid and does not prepare a request.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [& _]
                    (throw (ex-info "must not prepare" {})))]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-replay-invalid"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-replay-invalid"
                             :user-msg user-msg}
                            {:origin :core
                             :replaying? true})
      (session/dispatch-in! ctx :session/close-pre-turn-augmentation
                            {:session-id session-id
                             :turn-id "turn-replay-invalid"
                             :user-msg user-msg
                             :close-record (replayable-close-record session-id "other-turn")}
                            {:origin :core
                             :replaying? true}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          record (get-in session-data [:turn-augmentations "turn-replay-invalid"])]
      (is (= :turn/augmentation-failed
             (get-in session-data [:prompt-turns "turn-replay-invalid" :state])))
      (is (= :replay-invalid (:status record)))
      (is (true? (:replay? record)))
      (is (= [:wrong-turn-id] (get-in record [:providers 0 :reasons])))
      (is (nil? (:last-prepared-request-summary session-data))))))

(deftest prompt-prepare-request-consumes-queued-steering-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/enqueue-steering-message
                          {:session-id session-id
                           :text "Please be brief."}
                          {:origin :core})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-steer"
                             :user-msg nil}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-steer"
                             :user-msg nil}
                            {:origin :core}))
    (is (= [] (:steering-messages (ss/get-session-data-in ctx session-id))))))
