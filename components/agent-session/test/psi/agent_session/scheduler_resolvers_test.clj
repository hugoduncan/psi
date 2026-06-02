(ns psi.agent-session.scheduler-resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

(deftest scheduler-resolver-test
  (testing "scheduler attrs resolve from session root and entity-seeded schedule id"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          created         (session/dispatch-in! ctx :scheduler/create
                                                {:session-id session-id
                                                 :schedule-id "sch-1"
                                                 :kind :message
                                                 :label "check-build"
                                                 :message "check build"
                                                 :created-at (test-support/instant "2099-04-21T17:59:00Z")
                                                 :fire-at (test-support/instant "2099-04-21T18:00:00Z")}
                                                {:origin :core})
          root-result     (session/query-in ctx session-id
                                            [:psi.scheduler/pending-count
                                             {:psi.scheduler/schedules
                                              [:psi.scheduler/schedule-id
                                               :psi.scheduler/kind
                                               :psi.scheduler/label
                                               :psi.scheduler/status
                                               :psi.scheduler/origin-session-id]}])
          detail-result   (session/query-in ctx session-id
                                            [:psi.scheduler/kind
                                             :psi.scheduler/message
                                             :psi.scheduler/fire-at
                                             :psi.scheduler/status
                                             :psi.scheduler/origin-session-id]
                                            {:psi.scheduler/schedule-id "sch-1"})]
      (is (= "sch-1" (:schedule-id created)))
      (is (= 1 (:psi.scheduler/pending-count root-result)))
      (is (= [{:psi.scheduler/schedule-id "sch-1"
               :psi.scheduler/kind :message
               :psi.scheduler/label "check-build"
               :psi.scheduler/status :pending
               :psi.scheduler/origin-session-id session-id}]
             (:psi.scheduler/schedules root-result)))
      (is (= :message (:psi.scheduler/kind detail-result)))
      (is (= "check build" (:psi.scheduler/message detail-result)))
      (is (= session-id (:psi.scheduler/origin-session-id detail-result)))
      (is (= :pending (:psi.scheduler/status detail-result))))))

;; --- 201 verification: EQL projections coherent across statuses + rich attrs ---

(deftest scheduler-resolver-projects-rich-attrs-across-statuses-test
  (testing "delivered/cancelled/failed schedules project full :psi.scheduler/* attrs coherently"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          base            {:source :scheduled
                           :message "m"
                           :created-at (test-support/instant "2099-04-21T17:59:00Z")
                           :fire-at (test-support/instant "2099-04-21T18:00:00Z")
                           :origin-session-id session-id}
          schedules       {"sch-delivered"
                           (merge base {:schedule-id "sch-delivered"
                                        :kind :message
                                        :label "delivered-one"
                                        :status :delivered})
                           "sch-cancelled"
                           (merge base {:schedule-id "sch-cancelled"
                                        :kind :message
                                        :label "cancelled-one"
                                        :status :cancelled})
                           "sch-failed"
                           (merge base {:schedule-id "sch-failed"
                                        :kind :session
                                        :label "failed-one"
                                        :status :failed
                                        :created-session-id "created-sid"
                                        :delivery-phase :prompt-submit
                                        :error-summary {:message "boom" :class "X" :data {}}
                                        :session-config-summary {:session-name "later"
                                                                 :skill-count 0
                                                                 :tool-count 0}})}]
      (swap! (:state* ctx)
             (ss/session-update session-id
                                (fn [sd] (assoc sd :scheduler {:schedules schedules :queue []}))))
      (doseq [[id expected]
              [["sch-delivered" {:status :delivered :kind :message}]
               ["sch-cancelled" {:status :cancelled :kind :message}]]]
        (let [r (session/query-in ctx session-id
                                  [:psi.scheduler/schedule-id
                                   :psi.scheduler/kind
                                   :psi.scheduler/status
                                   :psi.scheduler/origin-session-id]
                                  {:psi.scheduler/schedule-id id})]
          (is (= id (:psi.scheduler/schedule-id r)))
          (is (= (:status expected) (:psi.scheduler/status r)))
          (is (= (:kind expected) (:psi.scheduler/kind r)))
          (is (= session-id (:psi.scheduler/origin-session-id r)))))
      (testing "failed session-kind projects created-session-id / delivery-phase / error-summary / session-config-summary"
        (let [r (session/query-in ctx session-id
                                  [:psi.scheduler/status
                                   :psi.scheduler/kind
                                   :psi.scheduler/created-session-id
                                   :psi.scheduler/delivery-phase
                                   :psi.scheduler/error-summary
                                   :psi.scheduler/session-config-summary]
                                  {:psi.scheduler/schedule-id "sch-failed"})]
          (is (= :failed (:psi.scheduler/status r)))
          (is (= :session (:psi.scheduler/kind r)))
          (is (= "created-sid" (:psi.scheduler/created-session-id r)))
          (is (= :prompt-submit (:psi.scheduler/delivery-phase r)))
          (is (= "boom" (get-in r [:psi.scheduler/error-summary :message])))
          (is (= "later" (get-in r [:psi.scheduler/session-config-summary :session-name]))))))))
