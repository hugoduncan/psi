(ns psi.workflow-runtime.delegated-failure-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]
   [psi.workflow-runtime.statechart-runtime.delegate :as delegate]))

(defn- workflow-run
  [{:keys [step-order step-runs terminal-outcome current-step-id]}]
  {:effective-definition {:step-order step-order}
   :step-runs step-runs
   :terminal-outcome terminal-outcome
   :current-step-id current-step-id})

(deftest safe-reason-test
  ;; Only bounded keyword reasons in the public grammar cross the boundary.
  (testing "accepts public reason keywords and rejects other values"
    (is (true? (delegated-failure/safe-reason? :iteration-limit-reached)))
    (is (true? (delegated-failure/safe-reason? :workflow/step-failed)))
    (is (false? (delegated-failure/safe-reason? (keyword "unsafe/reason/value"))))
    (is (false? (delegated-failure/safe-reason? :bad!reason)))
    (is (false? (delegated-failure/safe-reason? "tool-timeout")))
    (is (false? (delegated-failure/safe-reason?
                 (keyword (apply str (repeat 65 "a"))))))))

(deftest sanitize-component-test
  ;; The failure boundary removes unsafe detail before assembling public text.
  (testing "normalizes controls, whitespace, and sensitive lexical spans"
    (is (= "([STACKTRACE_REDACTED]), retry"
           (delegated-failure/sanitize-component
            "(at child.core/run(child.clj:42)),\nretry")))
    (is (= "[REDACTED], request rejected"
           (delegated-failure/sanitize-component
            "token=\"abc 123\", request rejected")))
    (is (= "Authorization: [REDACTED_TOKEN]."
           (delegated-failure/sanitize-component
            "Authorization: Bearer abcdefgh.")))
    (is (= "open [PATH_REDACTED]"
           (delegated-failure/sanitize-component "open C:\\private\\file.edn")))
    (is (= "open [PATH_REDACTED]"
           (delegated-failure/sanitize-component "open \\\\server\\share\\file.edn")))
    (let [three-backslashes (apply str (repeat 3 "\\"))
          input (str "open " three-backslashes "server\\share\\file.edn")]
      (is (= input (delegated-failure/sanitize-component input))))
    (is (= "open C:private\\file.edn"
           (delegated-failure/sanitize-component "open C:private\\file.edn")))
    (is (= "read [PATH_REDACTED]."
           (delegated-failure/sanitize-component "read config\\secret-store.edn.")))))

(deftest sanitize-component-boundary-test
  ;; The lexical scanner honours precedence, token minima, and span boundaries.
  (testing "redacts supported spans without consuming adjacent punctuation"
    (doseq [[input expected]
            [["api_key => 'secret value'; denied" "[REDACTED]; denied"]
             ["token=abc123 request rejected" "[REDACTED] request rejected"]
             ["password: \"abc\\\" 123\", denied" "[REDACTED], denied"]
             ["token=" "token="]
             ["token=\"\"" "token=\"\""]
             ["credential=''" "credential=''"]
             ["tokenish=abc" "[REDACTED]"]
             ["x-token=abc" "[REDACTED]"]
             ["sk-abcdefgh, denied" "[REDACTED_TOKEN], denied"]
             ["pk-1234567 denied" "pk-1234567 denied"]
             ["Bearer 12345678=." "[REDACTED_TOKEN]."]
             ["Bearer 1234567=" "Bearer 1234567="]
             ["Bearer 1234567." "Bearer 1234567."]
             ["sk-1234567." "sk-1234567."]
             ["ask-abcdefgh" "ask-abcdefgh"]
             ["open C:/private/file.edn" "open [PATH_REDACTED]"]
             ["open ~/private/file.edn" "open [PATH_REDACTED]"]
             ["read ./.ssh/id_rsa" "read [PATH_REDACTED]"]
             ["open ../private/file.edn!" "open [PATH_REDACTED]!"]
             ["read config\\secret-store.edn." "read [PATH_REDACTED]."]
             ["read public/file.edn" "read public/file.edn"]
             ["read config/Secret-store.edn" "read [PATH_REDACTED]"]
             ["at child.core/run(child.clj:42) token=secret"
              "[STACKTRACE_REDACTED] [REDACTED]"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input))))

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

(deftest delegated-failure-selection-test
  ;; A terminal attempt, rather than historical map order, owns the diagnostic.
  (testing "selects an actionable terminal execution error"
    (let [run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "old"
                                                 :status :execution-failed
                                                 :execution-error {:reason :old-error
                                                                   :message "old failure"}}
                                                {:attempt-id "latest"
                                                 :status :execution-failed
                                                 :execution-error {:reason :tool-timeout
                                                                   :message "tool timed out"}}]}}
                :terminal-outcome {:step-id "build"}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'build': tool timed out"
              :delegate-failure {:source :execution-error
                                 :run-id "run-1"
                                 :target "child"
                                 :reason :tool-timeout
                                 :step-id "build"
                                 :attempt-id "latest"}}
             (delegated-failure/delegated-failure run "run-1" "child")))))

  (testing "uses the latest attempt when the terminal outcome names no attempt"
    (let [run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id nil
                                                 :status :execution-failed
                                                 :execution-error {:message "historical failure"}}
                                                {:attempt-id "latest"
                                                 :status :execution-failed
                                                 :execution-error {:message "terminal failure"}}]}}
                :terminal-outcome {:step-id "build"}})]
      (is (= {:step-id "build"
              :attempt {:attempt-id "latest"
                        :status :execution-failed
                        :execution-error {:message "terminal failure"}}}
             (delegated-failure/terminal-step-attempt run)))))

  (testing "falls through an ineligible execution message to terminal outcome"
    (let [run (workflow-run
               {:step-order ["loop"]
                :current-step-id "loop"
                :step-runs {"loop" {:attempts [{:attempt-id "attempt-4"
                                                :status :execution-failed
                                                :execution-error {:message "at child.core/run(child.clj:42)"}}]}}
                :terminal-outcome {:reason :iteration-limit-reached
                                   :step-id "loop"
                                   :iteration-count 4
                                   :max-iterations 4}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 4 of 4)"
              :delegate-failure {:source :terminal-outcome
                                 :run-id "run-2"
                                 :target "child"
                                 :reason :iteration-limit-reached
                                 :step-id "loop"
                                 :attempt-id "attempt-4"}}
             (delegated-failure/delegated-failure run "run-2" "child")))))

  (testing "retains selected location on fallback without unsafe cause data"
    (let [run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "attempt-2"
                                                 :status :execution-failed
                                                 :execution-error {:message "token=secret"}}]}}
                :terminal-outcome {}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "run-3"
                                 :target "child"
                                 :step-id "build"
                                 :attempt-id "attempt-2"}}
             (delegated-failure/delegated-failure run "run-3" "child"))))))

(deftest delegated-failure-fallback-and-boundary-test
  ;; The envelope never exposes an unsafe cause and stays bounded by code points.
  (testing "falls back when target is non-actionable after selecting a nested error"
    (let [nested {:reason :delegated-workflow-failed
                  :message "Delegated workflow 'grandchild' failed: tool timed out"
                  :delegate-failure {:source :execution-error
                                     :run-id "grandchild-run"
                                     :target "grandchild"
                                     :reason :tool-timeout}}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "run-4"
                                 :target "/secret"
                                 :step-id "delegate"
                                 :attempt-id "attempt-1"}}
             (delegated-failure/delegated-failure run "run-4" "/secret")))))

  (testing "bounds a Unicode cause by code points rather than UTF-16 units"
    (let [cause (apply str (repeat 600 "𐐀"))
          run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                 :status :execution-failed
                                                 :execution-error {:message cause}}]}}
                :terminal-outcome {:step-id "build"}})
          result (delegated-failure/delegated-failure run "run-5" "child")]
      (is (= 512 (delegated-failure/code-point-count (:message result))))
      (is (str/ends-with? (:message result) " ... [truncated]")))))

(deftest delegate-boundary-failed-child-test
  ;; The runtime persists the lower-runtime envelope as the parent failure payload.
  (testing "failed child delegation returns the canonical envelope without details"
    (let [child (workflow-run
                 {:step-order ["build"]
                  :current-step-id "build"
                  :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                   :status :execution-failed
                                                   :execution-error {:reason :tool-timeout
                                                                     :message "tool timed out"}}]}}
                  :terminal-outcome {:step-id "build"}})
          result (#'delegate/delegate-run-runtime-result (assoc child :status :failed)
                                                         "child-run" "child" {})]
      (is (= :failure (:pending-kind result)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'build': tool timed out"
              :delegate-failure {:source :execution-error
                                 :run-id "child-run"
                                 :target "child"
                                 :reason :tool-timeout
                                 :step-id "build"
                                 :attempt-id "attempt-1"}}
             (:payload result))))))

(deftest nested-delegated-failure-test
  ;; Nested metadata is one-level, allowlisted identity only.
  (testing "copies independently valid immediate nested fields without recursion"
    (let [nested {:reason :delegated-workflow-failed
                  :message "Delegated workflow 'grandchild' failed: tool timed out"
                  :delegate-failure {:source :execution-error
                                     :run-id "grandchild-run"
                                     :target "grandchild"
                                     :reason :tool-timeout
                                     :step-id "work"
                                     :nested-cause {:run-id "ignored"}}}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})
          result (delegated-failure/delegated-failure run "child-run" "child")]
      (is (= :delegated-workflow-failed (get-in result [:delegate-failure :reason])))
      (is (= {:run-id "grandchild-run"
              :target "grandchild"
              :reason :tool-timeout
              :step-id "work"}
             (get-in result [:delegate-failure :nested-cause])))
      (is (= "Delegated workflow 'child' failed at step 'delegate': Delegated workflow 'grandchild' failed: tool timed out"
             (:message result)))))

  (testing "rejects malformed required nested identity while sanitizing its message normally"
    (let [nested {:reason :delegated-workflow-failed
                  :message "nested token=secret rejected"
                  :delegate-failure {:source :execution-error
                                     :run-id ""
                                     :target "grandchild"
                                     :step-id "work"}}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})
          result (delegated-failure/delegated-failure run "child-run" "child")]
      (is (= "Delegated workflow 'child' failed at step 'delegate': nested [REDACTED] rejected"
             (:message result)))
      (is (not (contains? (:delegate-failure result) :nested-cause))))))

(deftest delegated-failure-selection-invariants-test
  ;; Ordered run state, rather than map order or unallowlisted child data, defines the envelope.
  (testing "uses the effective step order and an explicitly named terminal attempt"
    (let [run (workflow-run
               {:step-order ["compile" "publish"]
                :step-runs {"publish" {:attempts [{:attempt-id "publish-1"
                                                   :status :execution-failed
                                                   :execution-error {:message "publish failed"}}]}
                            "compile" {:attempts [{:attempt-id "compile-1"
                                                   :status :execution-failed
                                                   :execution-error {:message "compile failed"}}
                                                  {:attempt-id "compile-2"
                                                   :status :execution-failed
                                                   :execution-error {:message "terminal compile failure"}}]}}
                :terminal-outcome {:step-id "compile" :attempt-id "compile-1"}})]
      (is (= {:step-id "compile"
              :attempt {:attempt-id "compile-1"
                        :status :execution-failed
                        :execution-error {:message "compile failed"}}}
             (delegated-failure/terminal-step-attempt run)))))

  (testing "uses the last failed effective step when terminal and current identities are unavailable"
    (let [run (workflow-run
               {:step-order ["first" "last"]
                :step-runs {"last" {:attempts [{:attempt-id "last-attempt"
                                                :status :execution-failed
                                                :execution-error {:message "last failure"}}]}
                            "first" {:attempts [{:attempt-id "first-attempt"
                                                 :status :execution-failed
                                                 :execution-error {:message "first failure"}}]}}
                :terminal-outcome {}})]
      (is (= {:step-id "last"
              :attempt {:attempt-id "last-attempt"
                        :status :execution-failed
                        :execution-error {:message "last failure"}}}
             (delegated-failure/terminal-step-attempt run)))))

  (testing "omits all unavailable location and cause fields in a source fallback"
    (let [run (workflow-run {:step-order [] :step-runs {} :terminal-outcome {}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "run-absent"
                                 :target "child"}}
             (delegated-failure/delegated-failure run "run-absent" "child"))))))

(deftest delegated-failure-message-contract-test
  ;; Public assembly keeps useful text while preserving the exact bounded grammar.
  (testing "uses an actionable redacted cause and escapes target and step identities"
    (let [run (workflow-run
               {:step-order ["build's\\step"]
                :current-step-id "build's\\step"
                :step-runs {"build's\\step" {:attempts [{:attempt-id "attempt-1"
                                                         :status :execution-failed
                                                         :execution-error {:message "token=secret request rejected"}}]}}
                :terminal-outcome {:step-id "build's\\step"}})]
      (is (= "Delegated workflow 'child\\'s\\\\flow' failed at step 'build\\'s\\\\step': [REDACTED] request rejected"
             (:message (delegated-failure/delegated-failure run "run-escape" "child's\\flow"))))))

  (testing "does not truncate a message already at the 512-code-point boundary"
    (let [prefix "Delegated workflow 'child' failed at step 'build': "
          cause (apply str (repeat (- 512 (delegated-failure/code-point-count prefix)) "a"))
          run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                 :status :execution-failed
                                                 :execution-error {:message cause}}]}}
                :terminal-outcome {:step-id "build"}})
          message (:message (delegated-failure/delegated-failure run "run-boundary" "child"))]
      (is (= 512 (delegated-failure/code-point-count message)))
      (is (not (str/ends-with? message " ... [truncated]")))))

  (testing "treats placeholder-only target, step, and cause text as non-actionable"
    (is (not (delegated-failure/actionable? "[REDACTED_TOKEN]")))
    (is (not (delegated-failure/actionable? "[PATH_REDACTED] [STACKTRACE_REDACTED]")))
    (is (true? (delegated-failure/actionable? "[REDACTED] request rejected")))))

(deftest nested-delegated-failure-allowlist-test
  ;; Recognized nesting copies only immediate safe identity and reuses normal sanitization.
  (testing "copies valid optional identity independently and excludes unallowlisted child data"
    (let [nested {:reason :delegated-workflow-failed
                  :message "nested token=secret rejected"
                  :delegate-failure {:source :fallback
                                     :run-id "grandchild-run"
                                     :target "grandchild"
                                     :reason :unsafe!reason
                                     :step-id ""
                                     :attempt-id "grandchild-attempt"
                                     :nested-cause {:run-id "ignored"}
                                     :details {:provider-response "must not cross"}}
                  :data {:exception "must not cross"}
                  :result "must not cross"}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'delegate': nested [REDACTED] rejected"
              :delegate-failure {:source :execution-error
                                 :run-id "child-run"
                                 :target "child"
                                 :reason :delegated-workflow-failed
                                 :step-id "delegate"
                                 :attempt-id "attempt-1"
                                 :nested-cause {:run-id "grandchild-run"
                                                :target "grandchild"
                                                :attempt-id "grandchild-attempt"}}}
             (delegated-failure/delegated-failure run "child-run" "child")))))

  (testing "rejects a nested envelope with an invalid source without changing ordinary cause selection"
    (let [nested {:reason :delegated-workflow-failed
                  :message "nested failure"
                  :delegate-failure {:source :unknown
                                     :run-id "grandchild-run"
                                     :target "grandchild"}}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})
          result (delegated-failure/delegated-failure run "child-run" "child")]
      (is (= "Delegated workflow 'child' failed at step 'delegate': nested failure"
             (:message result)))
      (is (not (contains? (:delegate-failure result) :nested-cause))))))

(deftest terminal-outcome-numeric-metadata-test
  ;; Iteration metadata is public only for the exact reason and bounded integer pair.
  (testing "renders only a complete, nonnegative, Long-bounded iteration pair"
    (doseq [{:keys [label reason iteration-count max-iterations expected-cause]}
            [{:label "zero bounds"
              :reason :iteration-limit-reached
              :iteration-count 0
              :max-iterations 0
              :expected-cause "terminal outcome :iteration-limit-reached (iteration 0 of 0)"}
             {:label "maximum bounds"
              :reason :iteration-limit-reached
              :iteration-count Long/MAX_VALUE
              :max-iterations Long/MAX_VALUE
              :expected-cause (str "terminal outcome :iteration-limit-reached (iteration "
                                   Long/MAX_VALUE " of " Long/MAX_VALUE ")")}
             {:label "negative count"
              :reason :iteration-limit-reached
              :iteration-count -1
              :max-iterations 4
              :expected-cause "terminal outcome :iteration-limit-reached"}
             {:label "over-range bigint"
              :reason :iteration-limit-reached
              :iteration-count 4
              :max-iterations (inc (bigint Long/MAX_VALUE))
              :expected-cause "terminal outcome :iteration-limit-reached"}
             {:label "non-integer count"
              :reason :iteration-limit-reached
              :iteration-count 1.5
              :max-iterations 4
              :expected-cause "terminal outcome :iteration-limit-reached"}
             {:label "missing maximum"
              :reason :iteration-limit-reached
              :iteration-count 4
              :expected-cause "terminal outcome :iteration-limit-reached"}
             {:label "missing iteration count"
              :reason :iteration-limit-reached
              :max-iterations 4
              :expected-cause "terminal outcome :iteration-limit-reached"}
             {:label "different safe reason"
              :reason :judge-no-match
              :iteration-count 4
              :max-iterations 4
              :expected-cause "terminal outcome :judge-no-match"}]]
      (let [terminal-outcome (cond-> {:reason reason :step-id "loop"}
                               (some? iteration-count) (assoc :iteration-count iteration-count)
                               (some? max-iterations) (assoc :max-iterations max-iterations))
            run (workflow-run
                 {:step-order ["loop"]
                  :current-step-id "loop"
                  :step-runs {"loop" {:attempts [{:attempt-id "attempt-1"
                                                  :status :execution-failed}]}}
                  :terminal-outcome terminal-outcome})]
        (is (= (str "Delegated workflow 'child' failed at step 'loop': " expected-cause)
               (:message (delegated-failure/delegated-failure run "child-run" "child")))
            label)))))

(deftest nested-envelope-recognition-boundary-test
  ;; Every required nested-envelope field must be valid before identity is copied.
  (testing "treats each malformed required condition as ordinary untrusted error text"
    (let [valid-failure {:source :execution-error
                         :run-id "grandchild-run"
                         :target "grandchild"}
          ordinary-message "nested token=secret rejected"
          overlong-message (apply str (repeat 513 "a"))]
      (doseq [{:keys [label error expected-source expected-message]}
              [{:label "outer reason"
                :error {:reason :tool-timeout
                        :message ordinary-message
                        :delegate-failure valid-failure}
                :expected-source :execution-error
                :expected-message "nested [REDACTED] rejected"}
               {:label "blank message"
                :error {:reason :delegated-workflow-failed
                        :message "   "
                        :delegate-failure valid-failure}
                :expected-source :fallback
                :expected-message nil}
               {:label "over-512 message"
                :error {:reason :delegated-workflow-failed
                        :message overlong-message
                        :delegate-failure valid-failure}
                :expected-source :execution-error
                :expected-message (apply str (repeat 32 "a"))}
               {:label "non-map delegate failure"
                :error {:reason :delegated-workflow-failed
                        :message ordinary-message
                        :delegate-failure "invalid"}
                :expected-source :execution-error
                :expected-message "nested [REDACTED] rejected"}
               {:label "invalid source"
                :error {:reason :delegated-workflow-failed
                        :message ordinary-message
                        :delegate-failure (assoc valid-failure :source :unknown)}
                :expected-source :execution-error
                :expected-message "nested [REDACTED] rejected"}
               {:label "blank run id"
                :error {:reason :delegated-workflow-failed
                        :message ordinary-message
                        :delegate-failure (assoc valid-failure :run-id " ")}
                :expected-source :execution-error
                :expected-message "nested [REDACTED] rejected"}
               {:label "blank target"
                :error {:reason :delegated-workflow-failed
                        :message ordinary-message
                        :delegate-failure (assoc valid-failure :target "")}
                :expected-source :execution-error
                :expected-message "nested [REDACTED] rejected"}]]
        (let [run (workflow-run
                   {:step-order ["delegate"]
                    :current-step-id "delegate"
                    :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                        :status :execution-failed
                                                        :execution-error error}]}}
                    :terminal-outcome {:step-id "delegate"}})
              result (delegated-failure/delegated-failure run "child-run" "child")
              failure (:delegate-failure result)]
          (is (= expected-source (:source failure)) label)
          (is (not (contains? failure :nested-cause)) label)
          (if expected-message
            (is (str/includes? (:message result)
                               (delegated-failure/sanitize-component expected-message))
                label)
            (is (= delegated-failure/fallback-message (:message result)) label)))))))

(deftest delegate-boundary-nonfailed-regression-test
  ;; Delegated-failure normalization changes only failed-child diagnostics;
  ;; existing child outcomes keep their established parent payloads.
  (testing "completed, blocked, cancelled, and removed child outcomes are unchanged"
    (let [completed {:status :completed
                     :effective-definition {:step-order ["done"]}
                     :step-runs {"done" {:accepted-result
                                         {:outcome :ok
                                          :outputs {:final-llm-reply "child result"}
                                          :diagnostics {:child :complete}}}}}
          boundary {:delegate {:target "child"
                               :resolved-target "child"
                               :run-id "child-run"
                               :step-id "delegate-child"}}
          result (fn [run]
                   (#'delegate/delegate-run-runtime-result run "child-run" "child" boundary))]
      (is (= {:pending-kind :success
              :payload {:outcome :ok
                        :outputs {:final-llm-reply "child result"}
                        :diagnostics {:delegate (:delegate boundary)
                                      :child :complete}}}
             (result completed)))
      (is (= {:pending-kind :blocked
              :payload {:outcome :blocked
                        :blocked {:delegate-run-id "child-run"
                                  :target "child"
                                  :step-id "child-step"}
                        :diagnostics boundary}}
             (result {:status :blocked
                      :blocked {:step-id "child-step"}})))
      (is (= {:pending-kind :failure
              :payload {:message "Delegated workflow cancelled"
                        :delegate-run-id "child-run"
                        :target "child"
                        :details {:status :cancelled}}}
             (result {:status :cancelled})))
      (is (= {:pending-kind :failure
              :payload {:message "Delegated workflow cancelled or removed"
                        :delegate-run-id "child-run"
                        :target "child"
                        :details {:status :removed}}}
             (result nil))))))

(deftest sanitize-component-remaining-contract-matrix-test
  ;; Each scanner family retains its exact delimiter and punctuation contract.
  (testing "redacts all remaining positive families and rejects partial spans"
    (doseq [[input expected]
            [["open /home/alice/private.edn, then retry"
              "open [PATH_REDACTED], then retry"]
             ["open ./private/file.edn: retry"
              "open [PATH_REDACTED]: retry"]
             ["open ../private/file.edn? retry"
              "open [PATH_REDACTED]? retry"]
             ["read config/.ssh/settings"
              "read [PATH_REDACTED]"]
             ["read credentials/id_rsa"
              "read [PATH_REDACTED]"]
             ["read public\\file.edn"
              "read public\\file.edn"]
             ["x-token=abc"
              "[REDACTED]"]
             ["token:abc) retry"
              "[REDACTED]) retry"]
             ["token='abc\\' 123', retry"
              "[REDACTED], retry"]
             ["token=abc; retry"
              "[REDACTED]; retry"]
             ["token=abc\"def denied"
              "[REDACTED] denied"]
             ["token=abc'def denied"
              "[REDACTED] denied"]
             ["Bearer abcdefgh==, retry"
              "[REDACTED_TOKEN], retry"]
             ["Bearer\tabcdefgh retry"
              "[REDACTED_TOKEN] retry"]
             ["sk-abcdefgh. retry"
              "[REDACTED_TOKEN]. retry"]
             ["(at child.core/run(child.clj:42))."
              "([STACKTRACE_REDACTED])."]
             ["at child.core/run(child.clj:x)"
              "at child.core/run(child.clj:x)"]
             ["at child.core/run(child.clj:42 extra)"
              "at child.core/run(child.clj:42 extra)"]]]
      (is (= expected (delegated-failure/sanitize-component input)) input)))

  (testing "normalization is idempotent, including an immediate nested message"
    (let [raw " token=secret at child.core/run(child.clj:42) open /private/file.edn "
          sanitized (delegated-failure/sanitize-component raw)]
      (is (= sanitized (delegated-failure/sanitize-component sanitized))))))
