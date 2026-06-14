(ns psi.workflow-runtime.step-test-support
  (:require
   [psi.test-support.workflow-test-fixtures :as workflow-fixtures]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.attempts :as workflow-attempts]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-runtime.progression-recording :as workflow-recording]))

(def create-session-context workflow-fixtures/create-session-context)
(def multi-step-definition-with-meta workflow-fixtures/multi-step-definition-with-meta)

;;;; Canonical drive/abort fixture state (task 226 TR-4).
;;;;
;;;; The multi-prompt drain tests (step_execution_drive_prompt_queue_test +
;;;; step_execution_drive_prompt_queue_abort_test) need a state* atom carrying a
;;;; started latest attempt (and, for resume/replay cases, recorded per-prompt
;;;; turns). Building those by hand-rolled literals couples the tests to a stale
;;;; run/attempt shape that can drift from the canonical constructors the
;;;; production `latest-attempt`-based readers navigate. These helpers build the
;;;; same state via the real constructors (create-run + append-attempt-to-run +
;;;; start-latest-attempt + record-prompt-group-turn), as progression_recording_test
;;;; already does, so a canonical-shape change cannot leave the tests green
;;;; against a stale literal while production breaks.

(def ^:private drive-definition-id "multi-prompt-drive")

(defn- drive-session-definition
  "A canonical single-session-step definition the drive/abort fixtures register so
   their state matches the real run/attempt shape. The prompt-queue the driver
   consumes is supplied separately per test; this only seeds `:step-runs`."
  [step-id]
  {:definition-id drive-definition-id
   :name "Multi Prompt Drive"
   :steps [{:name step-id
            :type :session
            :contributions [{:type :template :text "drive"}]}]})

(defn canonical-running-run-state
  "Canonical workflow state with one started (:running) latest attempt
   (`attempt-1`) for `run-id`/`step-id`, built via the real run/attempt
   constructors."
  [run-id step-id]
  (let [[state1 _ _] (workflow-registry/register-definition
                      {:workflows (workflow-model/initial-workflow-state)}
                      (drive-session-definition step-id))
        [state2 run-id* _] (workflow-runtime/create-run
                            state1 {:definition-id drive-definition-id
                                    :run-id run-id})
        attempt (workflow-attempts/new-attempt {:attempt-id "attempt-1"
                                                :status :pending})
        state3 (update-in state2 [:workflows :runs run-id*]
                          #(workflow-attempts/append-attempt-to-run % step-id attempt))]
    (workflow-recording/start-latest-attempt state3 run-id* step-id)))

(defn canonical-recorded-run-state
  "`canonical-running-run-state` plus the given per-prompt turn `records`
   recorded canonically on the latest attempt (models persisted progression that
   survives a process restart / event-log replay)."
  [run-id step-id records]
  (reduce (fn [state record]
            (workflow-recording/record-prompt-group-turn state run-id step-id record))
          (canonical-running-run-state run-id step-id)
          records))

(def single-step-definition-with-meta
  {:definition-id "planner"
   :name "planner"
   :steps [{:name "step-1"
            :type :session
            :tools ["read" "bash"]
            :skills ["clojure-coding-standards"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}]
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :medium}})

(def builder-definition-with-meta
  {:definition-id "builder"
   :name "builder"
   :steps [{:name "step-1"
            :type :session
            :tools ["read" "bash" "edit" "write"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "You are a builder."
                        :tools ["read" "bash" "edit" "write"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :off}})
