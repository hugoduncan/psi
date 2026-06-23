(ns psi.agent-session.workflow.text-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.text :as text]))

(deftest build-prompt-contribution-test
  (testing "lists all workflows when none opt out"
    (let [defs {"alpha" {:name "alpha" :summary "Alpha flow"}
                "beta"  {:name "beta" :summary "Beta flow"}}
          result (text/build-prompt-contribution defs)]
      (is (str/includes? result "- alpha: Alpha flow"))
      (is (str/includes? result "- beta: Beta flow"))))

  (testing "omits workflows with :advertise false"
    (let [defs {"public"   {:name "public" :summary "Public"}
                "internal" {:name "internal" :summary "Internal" :advertise false}}
          result (text/build-prompt-contribution defs)]
      (is (str/includes? result "- public: Public"))
      (is (not (str/includes? result "internal")))))

  (testing "absent or true :advertise keeps a workflow advertised"
    (let [defs {"plain"   {:name "plain" :summary "Plain"}
                "flagged" {:name "flagged" :summary "Flagged" :advertise true}}
          result (text/build-prompt-contribution defs)]
      (is (str/includes? result "- plain: Plain"))
      (is (str/includes? result "- flagged: Flagged"))))

  (testing "reports none available when every workflow opts out"
    (let [defs {"internal" {:name "internal" :summary "Internal" :advertise false}}]
      (is (= "tool: delegate\nNo workflows available."
             (text/build-prompt-contribution defs))))))

(deftest non-advertised-workflow-stays-listed-and-invocable-test
  (testing "an :advertise false workflow is omitted from the system context but
            stays in the user-facing list (registered + invocable by name)"
    (let [defs {"public"   {:name "public" :summary "Public"}
                "internal" {:name "internal" :summary "Internal" :advertise false}}]
      ;; Dropped from the agent-facing prompt contribution.
      (is (not (str/includes? (text/build-prompt-contribution defs) "internal")))
      ;; Still present in the user-facing listing, hence still registered and
      ;; invocable by name via delegate.
      (is (str/includes? (text/available-workflows-text defs) "internal")))))

(deftest resolve-runnable-definition-test
  (testing "resolves an :advertise false workflow for execution by name"
    (let [internal {:name "internal" :summary "Internal" :advertise false}
          defs {"public"   {:name "public" :summary "Public"}
                "internal" internal}]
      ;; Execution resolution is the gate used by /delegate run and delegate
      ;; sub-steps: a non-advertised workflow that is dropped from the
      ;; agent-facing prompt contribution must still resolve-for-execution, so
      ;; a future change that drops it from registration/execution is caught.
      (is (not (str/includes? (text/build-prompt-contribution defs) "internal")))
      (is (= internal (text/resolve-runnable-definition defs "internal")))
      (is (= {:name "public" :summary "Public"}
             (text/resolve-runnable-definition defs "public")))))

  (testing "returns nil for an unregistered workflow name"
    (is (nil? (text/resolve-runnable-definition
               {"public" {:name "public"}} "missing")))))
