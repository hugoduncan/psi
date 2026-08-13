(ns psi.workflow-runtime.delegated-failure-sanitize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(defn- workflow-run
  [{:keys [step-order step-runs terminal-outcome current-step-id]}]
  {:effective-definition {:step-order step-order}
   :step-runs step-runs
   :terminal-outcome terminal-outcome
   :current-step-id current-step-id})

(deftype CountingCharSequence [^String text examined-position-count]
  CharSequence
  (length [_] (.length text))
  (charAt [_ index]
    (aset-int examined-position-count 0 (inc (aget examined-position-count 0)))
    (.charAt text index))
  (subSequence [_ start end] (.subSequence text start end)))

(deftest safe-reason-test
  ;; The exact public keyword grammar and bound govern both validation and envelopes.
  (let [body-64 (apply str (repeat 64 "a"))
        body-65 (apply str (repeat 65 "a"))
        cases [{:label "leading letter"
                :reason :a
                :safe? true}
               {:label "leading digit"
                :reason :0reason
                :safe? true}
               {:label "allowed interior punctuation"
                :reason :a.b_c-d
                :safe? true}
               {:label "one namespace slash"
                :reason :n.s/name_1-x
                :safe? true}
               {:label "64-character body"
                :reason (keyword body-64)
                :safe? true}
               {:label "65-character body"
                :reason (keyword body-65)
                :safe? false}
               {:label "empty body"
                :reason (keyword "")
                :safe? false}
               {:label "empty namespace component"
                :reason (keyword "/reason")
                :safe? false}
               {:label "empty name component"
                :reason (keyword "namespace/")
                :safe? false}
               {:label "leading dot"
                :reason (keyword ".reason")
                :safe? false}
               {:label "leading underscore"
                :reason (keyword "_reason")
                :safe? false}
               {:label "leading hyphen"
                :reason (keyword "-reason")
                :safe? false}
               {:label "disallowed character"
                :reason (keyword "reason!bad")
                :safe? false}
               {:label "multiple slashes"
                :reason (keyword "a/b/c")
                :safe? false}
               {:label "non-keyword"
                :reason "tool-timeout"
                :safe? false}]]
    (doseq [{:keys [label reason safe?]} cases]
      (testing label
        (let [run (workflow-run {:step-order []
                                 :step-runs {}
                                 :terminal-outcome {:reason reason}})
              result (delegated-failure/delegated-failure run "run-reason" "child")
              reason-body (when safe?
                            (if-let [reason-namespace (namespace reason)]
                              (str reason-namespace "/" (name reason))
                              (name reason)))
              expected (if safe?
                         {:reason :delegated-workflow-failed
                          :message (str "Delegated workflow 'child' failed: "
                                        "terminal outcome :" reason-body)
                          :delegate-failure {:source :terminal-outcome
                                             :run-id "run-reason"
                                             :target "child"
                                             :reason reason}}
                         {:reason :delegated-workflow-failed
                          :message delegated-failure/fallback-message
                          :delegate-failure {:source :fallback
                                             :run-id "run-reason"
                                             :target "child"}})]
          (is (= safe? (delegated-failure/safe-reason? reason)))
          (is (= expected result)))))))

(deftest execution-error-reason-source-isolation-test
  ;; An actionable execution error owns its source without borrowing terminal metadata.
  (testing "omits unsafe or absent execution reasons despite a safe terminal reason"
    (doseq [{:keys [label execution-error]}
            [{:label "unsafe execution reason"
              :execution-error {:reason (keyword "!unsafe")
                                :message "tool timed out"}}
             {:label "absent execution reason"
              :execution-error {:message "tool timed out"}}]]
      (let [run (workflow-run
                 {:step-order ["build"]
                  :current-step-id "build"
                  :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                   :status :execution-failed
                                                   :execution-error execution-error}]}}
                  :terminal-outcome {:reason :iteration-limit-reached
                                     :step-id "build"}})]
        (is (= {:reason :delegated-workflow-failed
                :message "Delegated workflow 'child' failed at step 'build': tool timed out"
                :delegate-failure {:source :execution-error
                                   :run-id "child-run"
                                   :target "child"
                                   :step-id "build"
                                   :attempt-id "attempt-1"}}
               (delegated-failure/delegated-failure run "child-run" "child"))
            label)))))

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

(deftest sanitize-component-large-input-test
  ;; Full input is scanned for redaction/actionability while retained normalized
  ;; output stays bounded independently of raw message size.
  (let [plain-input (apply str (repeat 25000 "x"))
        sanitized (delegated-failure/sanitize-component plain-input)]
    (is (= 512 (delegated-failure/code-point-count sanitized)))
    (is (every? #(= \x %) sanitized)))
  (let [control-prefixed-input (str "\u0000" (apply str (repeat 25000 "x")))
        sanitized (delegated-failure/sanitize-component control-prefixed-input)]
    (is (= 512 (delegated-failure/code-point-count sanitized)))
    (is (every? #(= \x %) sanitized)))
  (let [whole-input-credential (str "token=" (apply str (repeat 25000 "x")))]
    (is (= "[REDACTED]"
           (delegated-failure/sanitize-component whole-input-credential))))
  (doseq [prefix-character ["x" "." ":"]]
    (let [input (str (apply str (repeat 10000 prefix-character))
                     " token=secret denied")
          sanitized (delegated-failure/sanitize-component input)]
      (is (= 512 (delegated-failure/code-point-count sanitized)))
      (is (every? #(= (first prefix-character) %) sanitized))))
  (let [rejected-prefix (apply str (repeat 2000 ":.ssh/"))]
    (is (= ":[PATH_REDACTED]"
           (delegated-failure/sanitize-component rejected-prefix))))
  (testing "late slash and backslash inputs retain bounded output"
    ;; Each delimiter-started run shares one unrelated separator at the end.
    (doseq [separator ["/" "\\"]]
      (let [delimiter-runs-before-late-path
            (str (apply str (repeat 16000 "x ")) separator "tail")
            sanitized (delegated-failure/sanitize-component
                       delimiter-runs-before-late-path)]
        (is (= 512 (delegated-failure/code-point-count sanitized)))
        (is (= (apply str (take 512 (cycle "x "))) sanitized)))))
  (testing "late separator lookup examines no input after indexing"
    ;; A counting CharSequence observes every character inspection performed by
    ;; the scanner. Replacing cached queries with suffix searches increases this
    ;; count, without relying on production-maintained counters or elapsed time.
    (doseq [separator ["/" "\\"]]
      (let [delimiter-runs-before-late-path
            (str (apply str (repeat 16000 "x ")) separator "tail")
            examined-position-count (int-array 1)
            counting-text (CountingCharSequence.
                           delimiter-runs-before-late-path
                           examined-position-count)
            separator-at-or-after?
            (#'delegated-failure/path-separator-scanner counting-text)]
        (is (= (count delimiter-runs-before-late-path)
               (aget examined-position-count 0)))
        (doseq [index (range 0 (count delimiter-runs-before-late-path) 2)]
          (separator-at-or-after? index))
        (is (= (count delimiter-runs-before-late-path)
               (aget examined-position-count 0))))))
  (let [late-actionable-message (str (apply str (repeat 5000 "token=secret "))
                                     "request denied")
        run (workflow-run
             {:step-order ["build"]
              :current-step-id "build"
              :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                               :status :execution-failed
                                               :execution-error
                                               {:message late-actionable-message}}]}}})
        result (delegated-failure/delegated-failure run "child-run" "child")]
    (is (= :execution-error (get-in result [:delegate-failure :source])))
    (is (= 512 (delegated-failure/code-point-count (:message result))))))

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
             ["token=\"abc" "token=\"abc"]
             ["credential='abc" "credential='abc"]
             ["token=\"abc\\\"def" "token=\"abc\\\"def"]
             ["credential='abc\\'def" "credential='abc\\'def"]
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
