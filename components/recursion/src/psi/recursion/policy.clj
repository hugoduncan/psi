(ns psi.recursion.policy
  "Malli schemas and pure policy functions for the feed-forward recursion loop.

   Defines the locked-decision defaults from the spec and all value-type schemas
   used across the recursion component."
  (:require
   [malli.core :as m]))

;;; Schemas

(def TriggerType
  [:enum :manual :session-end :graph-changed :memory-updated :verification-failed])

(def RiskLevel
  [:enum :low :medium :high])

(def ActionDomain
  [:enum :planning :code :tests :docs :tasks :config])

(def GoalStatus
  [:enum :proposed :active :blocked :complete])

(def FutureGoal
  [:map
   [:id :string]
   [:title :string]
   [:description :string]
   [:priority RiskLevel]
   [:success-criteria [:set :string]]
   [:constraints [:set :string]]
   [:status GoalStatus]])

(def Horizon
  [:enum :short :medium :long])

(def FutureStateSnapshot
  [:map
   [:version :int]
   [:generated-at inst?]
   [:horizon Horizon]
   [:goals [:vector FutureGoal]]
   [:assumptions [:set :string]]
   [:stop-conditions [:set :string]]])

(def TriggerSignal
  [:map
   [:type TriggerType]
   [:reason :string]
   [:payload :map]
   [:timestamp inst?]])

(def ProposedAction
  [:map
   [:id :string]
   [:title :string]
   [:description :string]
   [:domain ActionDomain]
   [:risk RiskLevel]
   [:atomic :boolean]
   [:expected-impact [:set :string]]
   [:verification-hints [:set :string]]])

(def VerificationCheckResult
  [:map
   [:name :string]
   [:passed :boolean]
   [:details {:optional true} [:maybe :string]]])

(def VerificationReport
  [:map
   [:checks [:vector VerificationCheckResult]]
   [:passed-all :boolean]
   [:completed-at inst?]])

(def CycleOutcome
  [:map
   [:status [:enum :success :failed :blocked :aborted]]
   [:summary :string]
   [:evidence [:set :string]]
   [:changed-goals [:set :string]]])

(def GuardrailPolicy
  [:map
   [:require-human-approval :boolean]
   [:max-actions-per-cycle :int]
   [:atomic-only :boolean]
   [:rollback-on-verification-failure :boolean]
   [:max-retries-per-goal :int]])

(def ControllerStatus
  [:enum :idle :observing :planning :awaiting-approval
   :executing :verifying :learning :paused :error])

(def CycleStatus
  [:enum :blocked :observing :planning :awaiting-approval
   :executing :verifying :learning :completed :failed :aborted])

;;; Pure functions

(defn default-policy
  "Return the locked-decision guardrail policy defaults from the spec."
  []
  {:require-human-approval true
   :max-actions-per-cycle 1
   :atomic-only true
   :rollback-on-verification-failure true
   :max-retries-per-goal 2})

(defn default-config
  "Return the default recursion config including accepted/enabled trigger hooks
   and required verification checks."
  []
  {:accepted-trigger-types #{:manual :session-end :graph-changed
                             :memory-updated :verification-failed}
   :enabled-trigger-hooks #{:manual :session-end :graph-changed
                            :memory-updated :verification-failed}
   :required-verification-checks #{"tests" "lint" "eql-health"}
   :default-horizon :medium
   :trusted-local-mode-enabled false
   :auto-approve-low-risk-in-trusted-local-mode true})

(def ExecutionAttempt
  [:map
   [:action-id :string]
   [:started-at inst?]
   [:ended-at {:optional true} [:maybe inst?]]
   [:status [:enum :success :failed :aborted]]
   [:output-summary {:optional true} [:maybe :string]]])

(defn requires-manual-approval?
  "Pure function: returns true if manual approval is required for `proposal`
   given `config`.

   Logic per spec:
   - Medium or high risk → always requires manual approval.
   - Low risk with trusted-local-mode-enabled AND auto-approve-low-risk → auto-approve.
   - Otherwise → manual approval required."
  [proposal config]
  (let [risk (:risk proposal)]
    (cond
      ;; Medium/high risk always requires manual approval
      (contains? #{:medium :high} risk)
      true

      ;; Low risk: auto-approve only when both trusted-local flags are true
      (= :low risk)
      (not (and (:trusted-local-mode-enabled config)
                (:auto-approve-low-risk-in-trusted-local-mode config)))

      ;; Default: require approval
      :else
      true)))

(defn auto-approve?
  "Convenience inverse of `requires-manual-approval?`."
  [proposal config]
  (not (requires-manual-approval? proposal config)))

(defn valid-policy?
  "Check if `policy` conforms to the GuardrailPolicy schema."
  [policy]
  (m/validate GuardrailPolicy policy))

(defn valid-trigger-signal?
  "Check if `signal` conforms to the TriggerSignal schema."
  [signal]
  (m/validate TriggerSignal signal))

(defn valid-future-state?
  "Check if `snapshot` conforms to the FutureStateSnapshot schema."
  [snapshot]
  (m/validate FutureStateSnapshot snapshot))
