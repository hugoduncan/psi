(ns psi.workflow-runtime.delegated-failure-actionability-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(defn- workflow-run
  [{:keys [step-order step-runs terminal-outcome current-step-id]}]
  {:effective-definition {:step-order step-order}
   :step-runs step-runs
   :terminal-outcome terminal-outcome
   :current-step-id current-step-id})

(deftest sanitize-component-lexical-boundaries-test
  ;; Only complete, delimited sensitive spans are replaced.
  (testing "preserves negative boundaries and removes controls before whitespace normalization"
    (doseq [[input expected]
            [["fooat child.core/run(child.clj:42)" "fooat child.core/run(child.clj:42)"]
             ["xBearer abcdefgh" "xBearer abcdefgh"]
             ["ask-abcdefgh" "ask-abcdefgh"]
             ["prefix/home/alice/private.edn" "prefix/home/alice/private.edn"]
             ["open=/home/alice/private.edn" "open=[PATH_REDACTED]"]
             ["token=abc\u0000\nrequest rejected" "[REDACTED] request rejected"]
             ["token=\"abc\\\n123\", request rejected"
              "[REDACTED], request rejected"]
             ["open /private/file.edn\u0085then retry"
              "open [PATH_REDACTED] then retry"]
             ["at child.core/run(child.clj:42), token=secret"
              "[STACKTRACE_REDACTED], [REDACTED]"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input))))

(deftest control-split-placeholder-actionability-test
  ;; Control removal cannot turn a placeholder into actionable public text.
  (testing "recognizes placeholders through the virtual control-stripped view"
    (doseq [[input expected]
            [["[REDAC\u0000TED]" "[REDACTED]"]
             ["[PATH_REDAC\u0000TED]" "[PATH_REDACTED]"]
             ["[STACKTRACE_REDAC\u0000TED]" "[STACKTRACE_REDACTED]"]
             ["[REDACTED_TOK\u0000EN]" "[REDACTED_TOKEN]"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input)))

  (testing "applies placeholder-only fallback and step omission after control removal"
    (let [control-split-placeholder "[REDAC\u0000TED]"
          run-with-cause (workflow-run
                          {:step-order ["build"]
                           :current-step-id "build"
                           :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                            :status :execution-failed
                                                            :execution-error
                                                            {:message control-split-placeholder}}]}}
                           :terminal-outcome {:step-id "build"}})
          run-with-step (workflow-run
                         {:step-order [control-split-placeholder]
                          :current-step-id control-split-placeholder
                          :step-runs {control-split-placeholder
                                      {:attempts [{:attempt-id "attempt-2"
                                                   :status :execution-failed
                                                   :execution-error {:message "request rejected"}}]}}
                          :terminal-outcome {:step-id control-split-placeholder}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "cause-run"
                                 :target "child"
                                 :step-id "build"
                                 :attempt-id "attempt-1"}}
             (delegated-failure/delegated-failure run-with-cause "cause-run" "child")))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "target-run"
                                 :target control-split-placeholder
                                 :step-id control-split-placeholder
                                 :attempt-id "attempt-2"}}
             (delegated-failure/delegated-failure
              run-with-step "target-run" control-split-placeholder)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed: request rejected"
              :delegate-failure {:source :execution-error
                                 :run-id "step-run"
                                 :target "child"
                                 :step-id control-split-placeholder
                                 :attempt-id "attempt-2"}}
             (delegated-failure/delegated-failure run-with-step "step-run" "child"))))))
