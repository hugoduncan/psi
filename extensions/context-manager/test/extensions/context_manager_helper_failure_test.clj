(ns extensions.context-manager-helper-failure-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(deftest default-run-helper-child-creation-failure-test
  (testing "create-child-session returning nil-shaped result: no run, no track, no leak"
    (let [ran? (atom false)
          ;; create-child-session yields no :psi.agent-session/session-id
          ;; (models a session-limit / dispatch failure), so child-session-id
          ;; is nil and the whole run must be gated off.
          api {:mutate-session
               (fn [_sid op _params]
                 (case op
                   psi.extension/create-child-session {}
                   psi.extension/run-agent-loop-in-session (reset! ran? true)))
               :mutate (fn [_op _params] nil)}
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (nil? result) "nil child id → nil result (→ :no-op)")
      (is (false? @ran?) "run-agent-loop-in-session never invoked when child creation fails")
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids nil))
          "no nil/orphan id tracked in the recursion-avoidance atom")))

  (testing "create-child-session throwing: caught → nil child, no run, no track, no leak"
    (let [ran? (atom false)
          api {:mutate-session
               (fn [_sid op _params]
                 (case op
                   psi.extension/create-child-session (throw (ex-info "boom" {}))
                   psi.extension/run-agent-loop-in-session (reset! ran? true)))
               :mutate (fn [_op _params] nil)}
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (nil? result) "thrown child creation caught → nil result (→ :no-op)")
      (is (false? @ran?) "run-agent-loop-in-session never invoked when child creation throws")
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids nil))
          "no nil/orphan id tracked in the recursion-avoidance atom"))))

(deftest default-run-helper-run-throws-deref-error-branch-test
  (testing "run-agent-loop-in-session throwing surfaces the ::error deref-catch:
            nil text (→ :no-op), no propagation, child still closed/untracked"
    ;; The run future's blocking call throws (an uncaught run error surfacing
    ;; through deref as e.g. ExecutionException). default-run-helper's
    ;; (try (deref fut ..) (catch Exception _ ::error)) must catch it → ::error
    ;; → settled branch (map? ::error is false) → :text nil, WITHOUT the
    ;; exception propagating onto 237's blocking pre-turn path. The future's
    ;; own `finally` still closes + untracks the child. Distinct from the
    ;; ok?-false (gates-on-run-ok), ::timeout, and pre-run-gate branches.
    (let [closed (atom nil)
          api {:mutate-session
               (fn [_sid op _params]
                 (case op
                   psi.extension/create-child-session
                   {:psi.agent-session/session-id "child-1"}
                   psi.extension/run-agent-loop-in-session
                   (throw (ex-info "run boom" {}))))
               :mutate (fn [_op params]
                         (reset! closed (:session-id params))
                         nil)}
          result (#'context-manager/default-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"})]
      (is (= "child-1" (:child-session-id result))
          "a thrown run is a settled result, not a propagated exception")
      (is (nil? (:text result))
          "::error branch surfaces no text (→ :no-op)")
      ;; The future's finally closes + untracks on its own thread; await it.
      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (while (and (contains? @context-manager/entity-resolution-helper-session-ids
                               "child-1")
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))
      (is (= "child-1" @closed)
          "thrown run still closes the child in the future's finally")
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids
                          "child-1"))
          "thrown run still untracks the child from the recursion-avoidance atom"))))
