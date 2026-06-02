(ns psi.agent-session.psi-tool-scheduler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.psi-tool-scheduler :as psi-tool-scheduler]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]))

(deftest psi-tool-scheduler-create-list-cancel-test
  (let [fixed-now (test-support/instant "2026-04-21T18:00:00Z")
        [ctx session-id] (test-support/create-test-session {:scheduler-time-source (test-support/fixed-scheduler-time-source fixed-now)})
        tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
    (testing "create adds a pending schedule"
      (let [result ((:execute tool) {"action" "scheduler"
                                     "op" "create"
                                     "kind" "message"
                                     "message" "wake later"
                                     "delay-ms" 1000
                                     "label" "wake-test"})
            parsed (read-string (:content result))
            schedule (get-in parsed [:psi-tool/scheduler :schedule])]
        (is (false? (:is-error result)))
        (is (= :scheduler (:psi-tool/action parsed)))
        (is (= :create (:psi-tool/scheduler-op parsed)))
        (is (= :ok (:psi-tool/overall-status parsed)))
        (is (= :message (:kind schedule)))
        (is (= session-id (:origin-session-id schedule)))
        (is (= :pending (:status schedule)))
        (is (= "wake-test" (:label schedule)))
        (is (= fixed-now (java.time.Instant/parse (:created-at schedule))))
        (is (= (.plusMillis fixed-now 1000) (java.time.Instant/parse (:fire-at schedule))))
        (is (string? (:schedule-id schedule)))))

    (testing "list returns pending schedules"
      (let [result ((:execute tool) {"action" "scheduler" "op" "list"})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :list (:psi-tool/scheduler-op parsed)))
        (is (= 1 (get-in parsed [:psi-tool/scheduler :schedule-count])))
        (is (= :pending (get-in parsed [:psi-tool/scheduler :schedules 0 :status])))))

    (testing "cancel marks schedule cancelled"
      (let [schedule-id (-> ((:execute tool) {"action" "scheduler" "op" "list"})
                            :content read-string
                            (get-in [:psi-tool/scheduler :schedules 0 :schedule-id]))
            result ((:execute tool) {"action" "scheduler" "op" "cancel" "schedule-id" schedule-id})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :cancel (:psi-tool/scheduler-op parsed)))
        (is (= :cancelled (get-in parsed [:psi-tool/scheduler :schedule :status])))))))

(deftest psi-tool-scheduler-time-source-required-test
  (testing "missing scheduler time source fails create instead of falling back to wall-clock"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx (dissoc ctx :scheduler-time-source)
                                                  :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "message"
                                   "message" "wake later"
                                   "delay-ms" 1000})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :scheduler-time-source (get-in parsed [:psi-tool/error :data :boundary])))))

  (testing "invalid scheduler time source fails create instead of falling back to wall-clock"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx (assoc ctx :scheduler-time-source (fn [] "not-an-instant"))
                                                  :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "message"
                                   "message" "wake later"
                                   "delay-ms" 1000})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :scheduler-time-source (get-in parsed [:psi-tool/error :data :boundary]))))))

(deftest psi-tool-scheduler-bounds-and-cap-test
  (testing "bounds rejection surfaces as scheduler error"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "message"
                                   "message" "too fast"
                                   "delay-ms" 10})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= "delay-ms is below the minimum bound"
             (get-in parsed [:psi-tool/error :message])))))

  (testing "cap rejection blocks the 51st pending schedule"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (dotimes [i scheduler/default-max-pending-per-session]
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
        (is (true? (:is-error result)))
        (is (= :error (:psi-tool/overall-status parsed)))
        (is (= "scheduler pending cap exceeded"
               (get-in parsed [:psi-tool/error :message])))))))

(deftest psi-tool-scheduler-session-id-resolution-test
  (testing "scheduler requires invoking or explicit session-id"
    (let [[ctx _session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "list"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "explicit session-id is used when provided directly to scheduler report"
    (let [[ctx session-id] (test-support/create-test-session)
          report (psi-tool-scheduler/execute-psi-tool-scheduler-report
                  {:ctx ctx :session-id session-id}
                  {:op "create"
                   :kind "message"
                   :message "wake later"
                   :delay-ms 1000})]
      (is (= :ok (:psi-tool/overall-status report)))
      (is (= :message (get-in report [:psi-tool/scheduler :schedule :kind])))
      (is (= :pending (get-in report [:psi-tool/scheduler :schedule :status]))))))

(deftest psi-tool-scheduler-kind-validation-test
  (testing "scheduler create kind :session requires session-config"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "session"
                                   "message" "run later"
                                   "delay-ms" 1000})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "scheduler create kind :message rejects session-config"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "message"
                                   "message" "run later"
                                   "delay-ms" 1000
                                   "session-config" "{:session-name \"later\"}"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "scheduler create rejects unsupported session-config keys"
    (let [[ctx session-id] (test-support/create-test-session)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "session"
                                   "message" "run later"
                                   "delay-ms" 1000
                                   "session-config" "{:session-name \"later\" :workflow-run-id \"wr-1\"}"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase]))))))

;; --- 201 verification: :at resolution matrix
;;     (absolute-instant delay calc / past fires / near-future rejected / above-max rejected) ---

(deftest psi-tool-scheduler-at-resolution-matrix-test
  (testing "absolute instant calculates delay from scheduler time source"
    (let [fixed-now (test-support/instant "2026-04-21T18:00:00Z")
          fire-at (.plusMillis fixed-now 5000)
          [ctx session-id] (test-support/create-test-session {:scheduler-time-source (test-support/fixed-scheduler-time-source fixed-now)})
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "kind" "message"
                                   "message" "wake later"
                                   "at" (str fire-at)})
          parsed (read-string (:content result))
          schedule (get-in parsed [:psi-tool/scheduler :schedule])]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= fixed-now (java.time.Instant/parse (:created-at schedule))))
      (is (= fire-at (java.time.Instant/parse (:fire-at schedule))))
      (is (string? (:schedule-id schedule)))))

  (testing "past :at resolves to delay 0, skips min-delay check, and FIRES immediately via the seam"
    (let [fixed-now        (test-support/instant "2026-04-21T18:00:00Z")
          past-at          (.minusSeconds fixed-now 60)
          [ctx session-id] (test-support/create-test-session
                            {:scheduler-time-source (test-support/fixed-scheduler-time-source fixed-now)})
          [capture* callback*] (test-support/capturing-delay-fn)
          ctx*             (assoc ctx :scheduler-run-after-delay-fn capture*)
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx* :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "fire now"
                                             "at" (str past-at)})
          parsed           (read-string (:content result))
          schedule         (get-in parsed [:psi-tool/scheduler :schedule])]
      (is (false? (:is-error result)) "past :at is accepted (no min-delay rejection)")
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= 0 (:delay-ms @callback*)) "timer scheduled with delay 0")
      (is (= past-at (java.time.Instant/parse (:fire-at schedule))))
      ;; drive the delay-0 timer via the captured seam (no wall-clock wait)
      ((:f @callback*))
      (is (= :delivered (test-support/schedule-status ctx* session-id (:schedule-id schedule)))
          "delay-0 schedule fires and delivers")))

  (testing "future :at below min-delay-ms (1-999ms) is rejected with the below-minimum bound error"
    (let [fixed-now        (test-support/instant "2026-04-21T18:00:00Z")
          near-future-at   (.plusMillis fixed-now 500)
          [ctx session-id] (test-support/create-test-session
                            {:scheduler-time-source (test-support/fixed-scheduler-time-source fixed-now)})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "too soon"
                                             "at" (str near-future-at)})
          parsed           (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= "delay-ms is below the minimum bound"
             (get-in parsed [:psi-tool/error :message]))
          "near-future :at is rejected by the minimum (not maximum) bound")))

  (testing "future :at above max-delay-ms (>24h) is rejected with the exceeds-maximum bound error"
    (let [fixed-now        (test-support/instant "2026-04-21T18:00:00Z")
          far-future-at    (.plusMillis fixed-now (inc scheduler/max-delay-ms))
          [ctx session-id] (test-support/create-test-session
                            {:scheduler-time-source (test-support/fixed-scheduler-time-source fixed-now)})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "scheduler"
                                             "op" "create"
                                             "kind" "message"
                                             "message" "too far"
                                             "at" (str far-future-at)})
          parsed           (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= "delay-ms exceeds the maximum bound"
             (get-in parsed [:psi-tool/error :message]))
          "far-future :at is rejected by the maximum (not minimum) bound"))))
