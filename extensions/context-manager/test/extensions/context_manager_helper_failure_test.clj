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
