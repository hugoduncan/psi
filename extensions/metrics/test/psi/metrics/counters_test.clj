(ns psi.metrics.counters-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.metrics.counters :as counters]))

;;; empty-metrics

(deftest empty-metrics-has-expected-shape-test
  ;; empty-metrics returns a map with all required keys set to empty collections.
  (let [m (counters/empty-metrics)]
    (is (= {} (:tools m)))
    (is (= {} (:workflows m)))
    (is (= {} (:commands m)))
    (is (= {} (:operations m)))
    (is (= {} (:tokens m)))
    (is (nil? (:updated-at m)))))

;;; inc-tool-invocation

(deftest inc-tool-invocation-increments-counter-test
  ;; Each call to inc-tool-invocation adds 1 to :invocations for the named tool.
  (let [m0 (counters/empty-metrics)
        m1 (counters/inc-tool-invocation m0 "bash")
        m2 (counters/inc-tool-invocation m1 "bash")]
    (is (= 1 (get-in m1 [:tools "bash" :invocations])))
    (is (= 2 (get-in m2 [:tools "bash" :invocations])))))

(deftest inc-tool-invocation-initialises-error-fields-test
  ;; A new tool entry gets :errors 0 and :error-reasons {} on first invocation.
  (let [m (counters/inc-tool-invocation (counters/empty-metrics) "read")]
    (is (= 0 (get-in m [:tools "read" :errors])))
    (is (= {} (get-in m [:tools "read" :error-reasons])))))

(deftest inc-tool-invocation-sets-updated-at-test
  ;; updated-at is set after incrementing.
  (let [m (counters/inc-tool-invocation (counters/empty-metrics) "write")]
    (is (string? (:updated-at m)))))

(deftest inc-tool-invocation-tracks-multiple-tools-independently-test
  ;; Incrementing one tool does not affect another.
  (let [m (-> (counters/empty-metrics)
              (counters/inc-tool-invocation "read")
              (counters/inc-tool-invocation "read")
              (counters/inc-tool-invocation "bash"))]
    (is (= 2 (get-in m [:tools "read" :invocations])))
    (is (= 1 (get-in m [:tools "bash" :invocations])))))

;;; inc-tool-error

(deftest inc-tool-error-increments-error-counter-test
  ;; inc-tool-error increments :errors for the named tool.
  (let [m (-> (counters/empty-metrics)
              (counters/inc-tool-invocation "bash")
              (counters/inc-tool-error "bash" "timeout"))]
    (is (= 1 (get-in m [:tools "bash" :errors])))
    (is (= 1 (get-in m [:tools "bash" :error-reasons "timeout"])))))

(deftest inc-tool-error-accumulates-multiple-reasons-test
  ;; Different error reasons are tracked independently under :error-reasons.
  (let [m (-> (counters/empty-metrics)
              (counters/inc-tool-error "bash" "timeout")
              (counters/inc-tool-error "bash" "timeout")
              (counters/inc-tool-error "bash" "parse-error"))]
    (is (= 2 (get-in m [:tools "bash" :error-reasons "timeout"])))
    (is (= 1 (get-in m [:tools "bash" :error-reasons "parse-error"])))))

;;; inc-workflow/command/operation-invocation

(deftest inc-workflow-invocation-test
  ;; Workflow invocations are counted under :workflows.
  (let [m (-> (counters/empty-metrics)
              (counters/inc-workflow-invocation "builder")
              (counters/inc-workflow-invocation "builder"))]
    (is (= 2 (get-in m [:workflows "builder" :invocations])))))

(deftest inc-command-invocation-test
  ;; Command invocations are counted under :commands.
  (let [m (counters/inc-command-invocation (counters/empty-metrics) "metrics")]
    (is (= 1 (get-in m [:commands "metrics" :invocations])))))

(deftest inc-operation-invocation-test
  ;; Operation invocations are counted under :operations.
  (let [m (counters/inc-operation-invocation (counters/empty-metrics) "metrics/summary")]
    (is (= 1 (get-in m [:operations "metrics/summary" :invocations])))))

;;; add-token-delta

(deftest add-token-delta-accumulates-per-model-test
  ;; Token deltas accumulate under the model-id key.
  (let [m (-> (counters/empty-metrics)
              (counters/add-token-delta "claude-3" {:input 100 :output 50 :cache-read 200 :cache-write 10})
              (counters/add-token-delta "claude-3" {:input 50  :output 20 :cache-read 0   :cache-write 5}))]
    (is (= 150 (get-in m [:tokens "claude-3" :input])))
    (is (= 70  (get-in m [:tokens "claude-3" :output])))
    (is (= 200 (get-in m [:tokens "claude-3" :cache-read])))
    (is (= 15  (get-in m [:tokens "claude-3" :cache-write])))))

(deftest add-token-delta-uses-unknown-for-nil-model-test
  ;; A nil model-id falls back to the "unknown" key.
  (let [m (counters/add-token-delta (counters/empty-metrics) nil {:input 10 :output 5 :cache-read 0 :cache-write 0})]
    (is (= 10 (get-in m [:tokens "unknown" :input])))))

(deftest add-token-delta-tracks-multiple-models-independently-test
  ;; Different model-ids accumulate in separate map entries.
  (let [m (-> (counters/empty-metrics)
              (counters/add-token-delta "gpt-4o" {:input 500 :output 100 :cache-read 0 :cache-write 0})
              (counters/add-token-delta "claude-3" {:input 300 :output 80 :cache-read 0 :cache-write 0}))]
    (is (= 500 (get-in m [:tokens "gpt-4o" :input])))
    (is (= 300 (get-in m [:tokens "claude-3" :input])))))

;;; compute-token-delta

(deftest compute-token-delta-first-turn-is-full-value-test
  ;; When prev-totals is nil (first turn), the full current value is the delta.
  (let [cur {:psi.agent-session/usage-input 100
             :psi.agent-session/usage-output 50
             :psi.agent-session/usage-cache-read 20
             :psi.agent-session/usage-cache-write 5}
        delta (counters/compute-token-delta cur nil)]
    (is (= 100 (:input delta)))
    (is (= 50  (:output delta)))
    (is (= 20  (:cache-read delta)))
    (is (= 5   (:cache-write delta)))))

(deftest compute-token-delta-subsequent-turn-is-incremental-test
  ;; When prev-totals is known, the delta is the difference.
  (let [prev {:psi.agent-session/usage-input 100
              :psi.agent-session/usage-output 50
              :psi.agent-session/usage-cache-read 20
              :psi.agent-session/usage-cache-write 5}
        cur  {:psi.agent-session/usage-input 150
              :psi.agent-session/usage-output 70
              :psi.agent-session/usage-cache-read 20
              :psi.agent-session/usage-cache-write 5}
        delta (counters/compute-token-delta cur prev)]
    (is (= 50 (:input delta)))
    (is (= 20 (:output delta)))
    (is (= 0  (:cache-read delta)))
    (is (= 0  (:cache-write delta)))))

(deftest compute-token-delta-clamps-to-zero-test
  ;; Negative deltas (e.g., counter reset) are clamped to 0.
  (let [prev {:psi.agent-session/usage-input 200
              :psi.agent-session/usage-output 100
              :psi.agent-session/usage-cache-read 0
              :psi.agent-session/usage-cache-write 0}
        cur  {:psi.agent-session/usage-input 50
              :psi.agent-session/usage-output 30
              :psi.agent-session/usage-cache-read 0
              :psi.agent-session/usage-cache-write 0}
        delta (counters/compute-token-delta cur prev)]
    (is (= 0 (:input delta)))
    (is (= 0 (:output delta)))))
