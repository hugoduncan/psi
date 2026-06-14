(ns psi.workflow-runtime.step-test-support
  (:require
   [psi.test-support.workflow-test-fixtures :as workflow-fixtures]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.attempts :as workflow-attempts]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-runtime.progression-recording :as workflow-recording]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]))

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

;;;; Shared multi-prompt drain fixtures + SUT-invocation helper (task 226 TS-1).
;;;;
;;;; The two sibling drain test namespaces (step_execution_drive_prompt_queue_test
;;;; + step_execution_drive_prompt_queue_abort_test) exercise the SAME SUT
;;;; (`drive-session-prompt-queue!`). These helpers are the single home for the
;;;; fixtures + the keyword-arg `drive!` invocation both namespaces share, so the
;;;; fixtures are not defined verbatim per file and the SUT is invoked by named
;;;; keys (intent) rather than argument position.

(defn assistant-text-message
  "An assistant message carrying a single text block — the shape the drain's
   per-turn outcome returns as its `:assistant-message`."
  [text]
  {:role "assistant" :content [{:type :text :text text}]})

(defn ok-turn
  "The canonical successful per-turn result the drain seam returns for a given
   submitted `prompt`: `:status :ok` with `reply-{prompt}` text and the matching
   assistant message. Shared by both sibling drain namespaces so the OK-turn
   contract has one spelling (TS-2)."
  [prompt]
  {:status :ok
   :assistant-text (str "reply-" prompt)
   :execution-result nil
   :assistant-message (assistant-text-message (str "reply-" prompt))})

(defn running-attempt-state*
  "A canonical state* atom with one started (running, no per-prompt records)
   latest attempt for `run-id`/`step-id`, built via the real run/attempt
   constructors."
  [run-id step-id]
  (atom (canonical-running-run-state run-id step-id)))

(defn recorded-turns-state*
  "A canonical state* reconstructed (as if reloaded after a process restart /
   rebuilt by event-log replay) carrying the given per-prompt turn `records` on
   the latest attempt for `run-id`/`step-id`, built via the real run/attempt
   constructors — modelling persisted progression that survives a restart
   independent of any in-memory queue-driver loop state."
  [run-id step-id records]
  (atom (canonical-recorded-run-state run-id step-id records)))

(defn recording-record-turn-fn
  "Mirror the production record-turn-fn: persist one per-prompt turn record
   through the canonical progression substrate; returns truthy (live)."
  [state* run-id step-id]
  (fn [index group-name outputs]
    (swap! state* workflow-recording/record-prompt-group-turn run-id step-id
           {:index index :name group-name :outputs outputs})
    true))

(defn prompt-builder
  "Shared next-group-prompt builder used by the drain tests: a group's turn
   prompt is `PROMPT-{name}`. The first group's pre-split prompt (materialized at
   :step/enter in production) is the same builder applied to the queue head, so
   `drive!` derives the first-prompt as `(prompt-builder (first prompt-queue))`,
   making the first-prompt = builder-of-queue-head invariant explicit rather than
   a magic positional literal."
  [group]
  (str "PROMPT-" (:name group)))

(defn drive!
  "Invoke `drive-session-prompt-queue!` by named keys. Derives the first group's
   pre-split prompt from `prompt-builder` applied to the queue head and supplies
   the shared `prompt-builder` + a canonical `recording-record-turn-fn` over
   `state*`, so each call site states only what varies (`:ctx :step-def :state*
   :run-id :step-id :working-memory* :event-queue* :prompt-queue :stopped?`)."
  [{:keys [ctx step-def state* run-id step-id working-memory* event-queue*
           prompt-queue stopped?]}]
  (step-execution/drive-session-prompt-queue!
   ctx {:session-id "child-session"} step-def
   step-id "attempt-1" working-memory* event-queue*
   run-id prompt-queue (prompt-builder (first prompt-queue))
   prompt-builder
   (recording-record-turn-fn state* run-id step-id)
   (or stopped? (constantly false))))

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
