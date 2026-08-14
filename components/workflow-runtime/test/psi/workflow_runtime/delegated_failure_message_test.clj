(ns psi.workflow-runtime.delegated-failure-message-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(defn- workflow-run
  [execution-error terminal-outcome]
  {:effective-definition {:step-order ["loop"]}
   :current-step-id "loop"
   :step-runs {"loop" {:attempts [{:attempt-id "attempt-2"
                                   :status :execution-failed
                                   :execution-error execution-error}]}}
   :terminal-outcome terminal-outcome})

(deftest non-string-execution-message-fallthrough-test
  ;; Absent and non-string execution messages are ineligible before sanitizing;
  ;; a safe terminal outcome wins, otherwise the exact fallback wins.
  (testing "falls through invalid execution messages deterministically"
    (doseq [{:keys [label execution-error terminal-outcome expected-source expected-message]}
            [{:label "absent message with terminal outcome"
              :execution-error {:reason :tool-timeout}
              :terminal-outcome {:reason :iteration-limit-reached
                                 :step-id "loop"
                                 :iteration-count 2
                                 :max-iterations 2}
              :expected-source :terminal-outcome
              :expected-message "Delegated workflow 'child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 2 of 2)"}
             {:label "non-string message with terminal outcome"
              :execution-error {:reason :tool-timeout :message {:raw "unsafe"}}
              :terminal-outcome {:reason :iteration-limit-reached
                                 :step-id "loop"
                                 :iteration-count 2
                                 :max-iterations 2}
              :expected-source :terminal-outcome
              :expected-message "Delegated workflow 'child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 2 of 2)"}
             {:label "absent message without terminal outcome"
              :execution-error {:reason :tool-timeout}
              :terminal-outcome {:step-id "loop"}
              :expected-source :fallback
              :expected-message delegated-failure/fallback-message}
             {:label "non-string message without terminal outcome"
              :execution-error {:reason :tool-timeout :message 42}
              :terminal-outcome {:step-id "loop"}
              :expected-source :fallback
              :expected-message delegated-failure/fallback-message}]]
      (let [result (delegated-failure/delegated-failure
                    (workflow-run execution-error terminal-outcome)
                    "run-invalid-message"
                    "child")]
        (is (= expected-source (get-in result [:delegate-failure :source])) label)
        (is (= expected-message (:message result)) label)
        (is (= {:step-id "loop" :attempt-id "attempt-2"}
               (select-keys (:delegate-failure result) [:step-id :attempt-id]))
            label)))))
