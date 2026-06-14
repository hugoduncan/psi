(ns psi.workflow-runtime.compilation-error-format-test
  "Unit tests for format-compilation-errors covering each semantic error type,
   structural error rendering, compile-error step context, and multi-error output."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.ir-error-formatting :as ir-error-formatting]
   [psi.workflow-runtime.target-ir-compiler :as target-compiler]
   [psi.workflow-runtime.core :as workflow-runtime]))

;;;; Helpers

(defn- format-errors
  ([compile-error structural-errors semantic-errors]
   (ir-error-formatting/format-compilation-errors compile-error structural-errors semantic-errors)))

(defn- contains-line?
  "Returns true when the formatted output contains a line matching `substr`."
  [output substr]
  (some #(str/includes? % substr) (str/split-lines output)))

;;;; Prefix

(deftest format-compilation-errors-prefix-test
  ;; Every error output starts with the standard prefix
  (testing "output always starts with the failure prefix"
    (let [out (format-errors {:message "boom" :data {}} nil [])]
      (is (str/starts-with? out "Workflow IR compilation failed:")))))

;;;; compile-error formatting

(deftest format-compile-error-with-step-context-test
  ;; compile-error that carries step-name and step-index in :data
  (testing "compile-error with step context includes step name and index on the same line"
    (let [out (format-errors
               {:message "Unsupported target workflow step type"
                :data    {:step-name "my-step" :step-index 2
                          :step {:name "my-step" :type :unknown}}}
               nil [])]
      ;; Both the step context prefix and the message must appear on the SAME line.
      ;; Two independent contains-line? checks would pass even if they were on
      ;; separate lines; this assertion pins the combined contract.
      (is (contains-line? out "Step 'my-step' (index 2): Unsupported target workflow step type"))))

  (testing "compile-error without step context emits the message directly"
    (let [out (format-errors
               {:message "Target workflow definition must be of the form `{:steps [...]}`"
                :data    {}}
               nil [])]
      (is (contains-line? out "Target workflow definition must be of the form")))))

;;;; semantic error types

(deftest format-semantic-error-routing-without-judge-test
  ;; :routing-without-judge — step has :on but no :judge
  (testing ":routing-without-judge includes step name and constraint"
    (let [out (format-errors nil nil [{:type :routing-without-judge :step "route-step"}])]
      (is (contains-line? out "Step 'route-step'"))
      (is (contains-line? out "routing table (:on) requires a judge")))))

(deftest format-semantic-error-judge-without-routing-test
  ;; :judge-without-routing — step has :judge but empty :on
  (testing ":judge-without-routing includes step name and constraint"
    (let [out (format-errors nil nil [{:type :judge-without-routing :step "judge-step"}])]
      (is (contains-line? out "Step 'judge-step'"))
      (is (contains-line? out "judge requires a non-empty routing table (:on)")))))

(deftest format-semantic-error-missing-yields-test
  ;; :missing-yields — step has no :yields
  (testing ":missing-yields includes step name"
    (let [out (format-errors nil nil [{:type :missing-yields :step "no-yield-step"}])]
      (is (contains-line? out "Step 'no-yield-step'"))
      (is (contains-line? out "missing :yields")))))

(deftest format-semantic-error-missing-local-yield-output-key-test
  ;; :missing-local-yield-output-key — yield references undeclared output key
  (testing ":missing-local-yield-output-key includes step name, key, and available outputs"
    (let [out (format-errors nil nil [{:type             :missing-local-yield-output-key
                                       :step              "yield-step"
                                       :output-key        :final-llm-reply
                                       :available-outputs [:transcript]}])]
      (is (contains-line? out "Step 'yield-step'"))
      (is (contains-line? out "final-llm-reply"))
      (is (contains-line? out "transcript")))))

(deftest format-semantic-error-missing-step-ref-test
  ;; :missing-step-ref — step references an unknown step
  (testing ":missing-step-ref includes referring step and unknown target"
    (let [out (format-errors nil nil [{:type :missing-step-ref
                                       :step "consumer"
                                       :ref  {:step "ghost" :output :data}}])]
      (is (contains-line? out "Step 'consumer'"))
      (is (contains-line? out "references unknown step 'ghost'")))))

(deftest format-semantic-error-non-prior-step-ref-test
  ;; :non-prior-step-ref — forward or self reference
  (testing ":non-prior-step-ref includes referring step and forward target"
    (let [out (format-errors nil nil [{:type :non-prior-step-ref
                                       :step "early"
                                       :ref  {:step "later" :output :data}}])]
      (is (contains-line? out "Step 'early'"))
      (is (contains-line? out "references step 'later'"))
      (is (contains-line? out "not prior")))))

(deftest format-semantic-error-missing-output-key-test
  ;; :missing-output-key — step references undeclared output key on a prior step
  (testing ":missing-output-key includes step, key, target step, and available keys"
    (let [out (format-errors nil nil [{:type              :missing-output-key
                                       :step              "consumer"
                                       :ref               {:step "producer" :output :missing}
                                       :available-outputs [:data :summary]}])]
      (is (contains-line? out "Step 'consumer'"))
      (is (contains-line? out ":missing"))
      (is (contains-line? out "producer"))
      (is (contains-line? out "data")))))

(deftest format-semantic-error-missing-yield-field-test
  ;; :missing-yield-field — step references undeclared yield field on a prior step
  (testing ":missing-yield-field includes step, field, target step, and available fields"
    (let [out (format-errors nil nil [{:type                    :missing-yield-field
                                       :step                    "consumer"
                                       :ref                     {:step "producer" :yield :custom}
                                       :available-yield-fields  [:text]}])]
      (is (contains-line? out "Step 'consumer'"))
      (is (contains-line? out ":custom"))
      (is (contains-line? out "producer"))
      (is (contains-line? out "text")))))

(deftest format-semantic-error-skills-without-read-tool-test
  ;; :skills-without-read-tool — step declares skills but 'read' is absent from :tools
  (testing ":skills-without-read-tool includes step name and constraint"
    (let [out (format-errors nil nil [{:type   :skills-without-read-tool
                                       :step   "skill-step"
                                       :skills ["my-skill"]}])]
      (is (contains-line? out "Step 'skill-step'"))
      (is (contains-line? out "skills require the 'read' tool")))))

(deftest format-semantic-error-unknown-type-fallback-test
  ;; Unknown semantic error type uses fallback format
  (testing "unknown semantic error type falls back to raw representation"
    (let [out (format-errors nil nil [{:type :some-future-error :step "s" :extra :value}])]
      (is (contains-line? out "Step 's'"))
      (is (contains-line? out "some-future-error")))))

;;;; structural error formatting

(deftest format-structural-errors-test
  ;; Structural errors come from Malli explain-data; each has :path and :message
  (testing "structural errors render path and message without raw Malli dump"
    (let [explain-data {:errors [{:path    [:steps 0 :type]
                                  :message "should be :invoke, :session, or :delegate"}
                                 {:path    [:version]
                                  :message "missing required key"}]}
          out (format-errors nil explain-data [])]
      (is (contains-line? out "Structural error"))
      (is (contains-line? out "steps"))
      (is (contains-line? out "version"))))

  (testing "structural error with empty path still renders"
    (let [explain-data {:errors [{:path [] :message "top-level shape invalid"}]}
          out (format-errors nil explain-data [])]
      (is (contains-line? out "Structural error: top-level shape invalid"))))

  (testing "\"invalid value\" fallback when entry has neither :message nor :type"
    ;; Hand-crafted entry with only :path — no :message, no :type.
    ;; format-structural-error must fall back to the \"invalid value\" catch-all
    ;; and must NOT produce a line ending with a bare \":\"."
    (let [explain-data {:errors [{:path [:steps 0 :session :contributions 0 :value]
                                  :in   [:steps 0 :session :contributions 0 :value]}]}
          out (format-errors nil explain-data [])]
      (is (contains-line? out "invalid value")
          "fallback text 'invalid value' must appear in output")
      (doseq [line (str/split-lines out)]
        (when (str/includes? line "Structural error")
          (is (not (str/ends-with? (str/trim line) ":"))
              (str "line must not end with bare ':' — got: " line))))))

  (testing "real Malli explain-data (no :message key) produces non-blank description with path"
    ;; Real Malli explain-data entries carry :path, :in, :schema, :value, :type
    ;; (e.g. :malli.core/missing-key) but NOT :message.  The formatter must fall
    ;; back to (name :type) so the description is never blank.
    ;; A :workflow-runtime source ref compiles but fails structural validation.
    (let [real-explain-data
          (workflow-ir/explain-workflow-ir
           {:version :workflow-ir/v1
            :steps   [{:name "status"
                       :type :session
                       :session
                       {:contributions
                        [{:type :template
                          :text "Status: {{status}}"
                          :vars {"status" {:from :workflow-runtime
                                           :path [:status]}}}]}}]})
          out (format-errors nil real-explain-data [])]
      (is (some? real-explain-data) "expected structural validation to fail")
      ;; Each error line must contain a non-blank description
      (let [structural-lines (->> (str/split-lines out)
                                  (filter #(str/includes? % "Structural error")))]
        (is (seq structural-lines) "expected at least one structural error line")
        (doseq [line structural-lines]
          (is (not (str/ends-with? (str/trim line) ":"))
              (str "description is blank in: " line))))
      ;; Design criterion #4: the output must include a path or field name.
      ;; The :workflow-runtime source ref is nested under :steps / :contributions,
      ;; so at least one of those path segments must appear in the formatted output.
      (is (or (str/includes? out "steps")
              (str/includes? out "contributions"))
          "formatted output must include a path segment (e.g. 'steps' or 'contributions')"))))

;;;; multiple errors

(deftest format-multiple-errors-test
  ;; All errors enumerated, not just the first; constraint text appears per entry.
  (testing "multiple semantic errors: step names AND constraint text all appear in output"
    (let [out (format-errors nil nil [{:type :missing-yields :step "step-a"}
                                      {:type :missing-yields :step "step-b"}
                                      {:type :judge-without-routing :step "step-c"}])]
      ;; Step names present
      (is (contains-line? out "step-a"))
      (is (contains-line? out "step-b"))
      (is (contains-line? out "step-c"))
      ;; Constraint text present for each error type — a formatter bug that drops
      ;; constraint text but keeps the step name would still pass name-only checks.
      (is (str/includes? out "missing :yields")
          "constraint text for :missing-yields must appear")
      (is (str/includes? out "routing table")
          "constraint text for :judge-without-routing must appear")))

  (testing "mixed compile-error + semantic-errors: both channels rendered"
    ;; The formatter supports compile-error AND semantic-errors simultaneously.
    ;; This path was previously untested.
    (let [out (format-errors
               {:message "Unsupported target workflow step type"
                :data    {:step-name "bad-step" :step-index 0}}
               nil
               [{:type :missing-yields :step "other-step"}])]
      ;; compile-error line
      (is (contains-line? out "Step 'bad-step' (index 0): Unsupported target workflow step type")
          "compile-error line must appear")
      ;; semantic-error line
      (is (contains-line? out "Step 'other-step': missing :yields")
          "semantic-error line must appear")
      ;; both in same output
      (is (str/includes? out "bad-step"))
      (is (str/includes? out "other-step")))))

;;;; Integration: compile-step-with-context enriches exceptions

(deftest compile-step-with-context-enriches-exception-test
  ;; Step context (name + index) flows through compile-and-validate-workflow-definition
  (testing "unsupported step type carries step name and index in compile-error data"
    (let [{:keys [valid? compile-error]}
          (target-compiler/compile-and-validate-workflow-definition
           {:steps [{:name "first-step" :type :invoke
                     :operation "op/do-thing" :args {}}
                    {:name "bad-step" :type :unsupported}]})]
      (is (false? valid?))
      (is (= "bad-step" (get-in compile-error [:data :step-name])))
      (is (= 1 (get-in compile-error [:data :step-index])))
      (is (str/includes? (:message compile-error) "Unsupported target workflow step type"))))

  (testing "delegate target shape error carries step name and index"
    (let [{:keys [valid? compile-error]}
          (target-compiler/compile-and-validate-workflow-definition
           {:steps [{:name "run-selected-workflow"
                     :type :delegate
                     :target {:path [:selected-workflow]}
                     :prompt-string "Handle the issue."}]})]
      (is (false? valid?))
      (is (= "run-selected-workflow" (get-in compile-error [:data :step-name])))
      (is (= 0 (get-in compile-error [:data :step-index]))))))

;;;; Integration: create-run surfaces step-contextual message

(deftest create-run-surfaces-step-contextual-message-test
  ;; create-run (via compile-definition-to-ir!) must emit actionable messages
  (testing "unsupported step type produces message with step name"
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}]
      (try
        (workflow-runtime/create-run
         state
         {:definition {:steps [{:name "broken-step" :type :unsupported}]}
          :run-id     "bad-run"})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (str/includes? (ex-message e) "broken-step"))
          (is (str/includes? (ex-message e) "Workflow IR compilation failed"))))))

  (testing "forward step ref: step-b references step-a which comes after it — message names both steps"
    ;; step-b appears first and forward-references step-a (which is later).
    ;; The referrer (step-b) and the target (step-a) have distinct names so the
    ;; test distinguishes the two roles rather than relying on a single name appearing twice.
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}]
      (try
        (workflow-runtime/create-run
         state
         {:definition {:steps [{:name "step-b"
                                :type :session
                                :contributions [{:type :template
                                                 :text "{{x}}"
                                                 :vars {"x" {:from {:step "step-a"
                                                                    :output :final-llm-reply}}}}]}
                               {:name "step-a"
                                :type :session
                                :contributions [{:type :source :from :workflow-input}]}]}
          :run-id     "forward-ref-run"})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (str/includes? (ex-message e) "step-b")
              "referrer step name must appear in message")
          (is (str/includes? (ex-message e) "step-a")
              "referenced step name must appear in message")
          (is (str/includes? (ex-message e) "not prior")
              "constraint text must appear in message")))))

  (testing "judge without routing produces message naming the step and constraint"
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}]
      (try
        (workflow-runtime/create-run
         state
         {:definition {:steps [{:name  "judge-step"
                                :type  :session
                                :contributions [{:type :source :from :workflow-input}]
                                :judge {:type :llm
                                        :session {:contributions [{:type :template
                                                                   :text "APPROVED?"
                                                                   :vars {}}]}}}]}
          :run-id     "judge-no-routing-run"})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (str/includes? (ex-message e) "judge-step"))
          (is (str/includes? (ex-message e) "routing table"))))))

  (testing "structurally invalid IR produces message with path segment and no raw Malli schema dump"
    ;; A :workflow-runtime source ref compiles but fails structural validation.
    ;; The message must contain a path segment and must not dump raw Malli schema data.
    (let [state {:workflows {:definitions {} :runs {} :run-order []}}]
      (try
        (workflow-runtime/create-run
         state
         {:definition {:steps [{:name "status"
                                :type :session
                                :contributions [{:type :template
                                                 :text "Status: {{status}}"
                                                 :vars {"status" {:from :workflow-runtime
                                                                  :path [:status]}}}]}]}
          :run-id     "structural-error-run"})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (let [msg (ex-message e)]
            (is (str/includes? msg "Workflow IR compilation failed"))
            ;; path segment appears (not raw Malli schema dump)
            (is (str/includes? msg "Structural error"))
            ;; description is not blank — the line does not end with just ":"
            (is (not (re-find #"Structural error[^:]*: *\n" msg))
                "structural error description must not be blank")
            ;; no raw Malli schema keyword dumps (schema vectors like [:= :session])
            (is (not (str/includes? msg "malli.core"))
                "raw Malli internals must not appear in message")))))))
