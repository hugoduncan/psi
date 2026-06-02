(ns psi.agent-session.scheduler-tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]))

(deftest make-psi-tool-scheduler-test
  (testing "scheduler create stores a pending schedule"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "check build"
                                             "label" "check-build"
                                             "delay-ms" 1000})
          parsed           (read-string (:content result))
          schedule         (get-in parsed [:psi-tool/scheduler :schedule])]
      (is (false? (:is-error result)))
      (is (= :scheduler (:psi-tool/action parsed)))
      (is (= :create (:psi-tool/scheduler-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= :message (:kind schedule)))
      (is (= session-id (:origin-session-id schedule)))
      (is (= "check-build" (:label schedule)))
      (is (= :pending (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules (:schedule-id schedule) :status])))))

  (testing "scheduler list returns pending and queued schedules"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          create-1         ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "first"
                                             "delay-ms" 1000})
          schedule-id      (get-in (read-string (:content create-1)) [:psi-tool/scheduler :schedule :schedule-id])
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :schedules schedule-id :status] :queued)
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :queue] [schedule-id])
          list-result      ((:execute tool) {"action" "scheduler" "op" "list"})
          parsed           (read-string (:content list-result))]
      (is (false? (:is-error list-result)))
      (is (= :list (:psi-tool/scheduler-op parsed)))
      (is (= 1 (get-in parsed [:psi-tool/scheduler :schedule-count])))
      (is (= schedule-id (get-in parsed [:psi-tool/scheduler :schedules 0 :schedule-id])))
      (is (= :queued (get-in parsed [:psi-tool/scheduler :schedules 0 :status])))))

  (testing "scheduler cancel cancels a pending schedule"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          create-result    ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "cancel me"
                                             "delay-ms" 1000})
          schedule-id      (get-in (read-string (:content create-result)) [:psi-tool/scheduler :schedule :schedule-id])
          cancel-result    ((:execute tool) {"action" "scheduler"
                                             "op" "cancel"
                                             "schedule-id" schedule-id})
          parsed           (read-string (:content cancel-result))]
      (is (false? (:is-error cancel-result)))
      (is (= :cancel (:psi-tool/scheduler-op parsed)))
      (is (true? (get-in parsed [:psi-tool/scheduler :cancelled?])))
      (is (= :cancelled (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules schedule-id :status])))))

  (testing "scheduler create rejects too-short delay"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "too soon"
                                             "delay-ms" 999})
          parsed           (read-string (:content result))]
      (is (:is-error result))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :scheduler (:psi-tool/action parsed)))))

  (testing "scheduler create normalizes past absolute instants to immediate fire-at"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "wake now"
                                             "at" "2020-01-01T00:00:00Z"})
          parsed           (read-string (:content result))
          fire-at          (get-in parsed [:psi-tool/scheduler :schedule :fire-at])]
      (is (false? (:is-error result)))
      (is (string? fire-at))))

  (testing "scheduler create rejects the 51st pending schedule"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (dotimes [i 50]
        (let [result ((:execute tool) {"action" "scheduler"
                                       "op" "create"
                                       "kind" "message"
                                       "message" (str "m-" i)
                                       "delay-ms" 1000})]
          (is (false? (:is-error result)))))
      (let [result ((:execute tool) {"action" "scheduler"
                                     "op" "create"
                                     "kind" "message"
                                     "message" "overflow"
                                     "delay-ms" 1000})
            parsed (read-string (:content result))]
        (is (:is-error result))
        (is (= :error (:psi-tool/overall-status parsed)))))))
