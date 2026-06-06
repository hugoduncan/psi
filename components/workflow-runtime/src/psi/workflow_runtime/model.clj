(ns psi.workflow-runtime.model
  "Pure workflow domain model for deterministic workflow runtime state.

   Owns canonical workflow entity schemas, status enums, linkage conventions,
   and the initial root-state shape for workflow definitions/runs.

   Canonical root-state placement:
   - [:workflows :definitions] => {definition-id -> workflow-definition}
   - [:workflows :runs]        => {run-id -> workflow-run}
   - [:workflows :run-order]   => [run-id ...] creation order"
  (:require
   [malli.core :as m]
   [psi.session-state.model :as session]))

(def workflow-definition-id-schema :string)
(def workflow-run-id-schema :string)
(def workflow-step-id-schema :string)
(def workflow-attempt-id-schema :string)

(def workflow-run-status-schema
  [:enum :pending :running :blocked :completed :failed :cancelled])

(def workflow-step-attempt-status-schema
  [:enum :pending
   :running
   :validating
   :succeeded
   :blocked
   :validation-failed
   :execution-failed
   :cancelled])

(def retryable-failure-schema
  [:enum :execution-failed :validation-failed])

(def workflow-ref-schema
  [:map
   [:type [:= :workflow-ref]]
   [:name workflow-definition-id-schema]])

(def workflow-retry-policy-schema
  [:map
   [:max-attempts pos-int?]
   [:retry-on [:set retryable-failure-schema]]])

(def workflow-capability-policy-schema
  [:map
   [:tools {:optional true} [:maybe [:set :string]]]])

(def workflow-executor-schema
  [:map
   [:type [:= :agent]]
   [:profile {:optional true} [:maybe :string]]
   [:mode {:optional true} [:maybe [:enum :sync :async]]]
   [:skill {:optional true} [:maybe :string]]])

(def workflow-binding-ref-schema
  [:map
   [:source [:enum :workflow-input :step-output :workflow-runtime]]
   [:path [:vector [:or :keyword :string :int]]]])

(def workflow-result-envelope-schema
  [:multi {:dispatch :outcome}
   [:ok
    [:map
     [:outcome [:= :ok]]
     [:outputs :map]
     [:diagnostics {:optional true} [:maybe :map]]]]
   [:blocked
    [:map
     [:outcome [:= :blocked]]
     [:blocked :map]
     [:diagnostics {:optional true} [:maybe :map]]]]])

;;; Judge, projection, and routing schemas

(def projection-schema
  "Projection spec controlling what the judge sees from the actor session."
  [:or
   [:enum :none :full]
   [:map
    [:type [:= :tail]]
    [:turns pos-int?]
    [:tool-output {:optional true} [:maybe :boolean]]]])

(def judge-schema
  "Judge definition: a separate agent that classifies actor output."
  [:map
   [:prompt :string]
   [:system-prompt {:optional true} [:maybe :string]]
   [:projection {:optional true} [:maybe projection-schema]]])

(def routing-directive-schema
  "A single routing directive mapping a judge signal to a target."
  [:map
   [:goto [:or [:enum :next :previous :done] :string]]
   [:max-iterations {:optional true} [:maybe pos-int?]]])

(def routing-table-schema
  "Maps judge signal strings to routing directives."
  [:map-of :string routing-directive-schema])

(def workflow-step-definition-schema
  [:map
   [:label {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:executor workflow-executor-schema]
   [:prompt-template {:optional true} [:maybe :string]]
   [:input-bindings {:optional true} [:map-of :keyword workflow-binding-ref-schema]]
   [:result-schema :any]
   [:retry-policy workflow-retry-policy-schema]
   [:capability-policy {:optional true} workflow-capability-policy-schema]
   [:judge {:optional true} [:maybe judge-schema]]
   [:on {:optional true} [:maybe routing-table-schema]]
   [:session-preload {:optional true}
    [:vector
     [:or
      [:map
       [:kind [:= :value]]
       [:role :string]
       [:binding workflow-binding-ref-schema]]
      [:map
       [:kind [:= :session-transcript]]
       [:step-id workflow-step-id-schema]
       [:projection {:optional true} [:maybe projection-schema]]]]]]
   [:session-overrides {:optional true}
    [:map
     [:system-prompt {:optional true} :string]
     [:tools {:optional true} [:vector :string]]
     [:skills {:optional true} [:vector :string]]
     [:model {:optional true} [:or :string :map]]
     [:thinking-level {:optional true} [:enum :off :minimal :low :medium :high :xhigh]]
     [:prompt-component-selection {:optional true} [:maybe session/prompt-component-selection-schema]]]]])

(def workflow-definition-schema
  [:map
   [:definition-id {:optional true} [:maybe workflow-definition-id-schema]]
   [:name {:optional true} [:maybe :string]]
   [:summary {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:step-order [:vector workflow-step-id-schema]]
   [:steps [:map-of workflow-step-id-schema workflow-step-definition-schema]]])

(def workflow-validation-outcome-schema
  [:map
   [:accepted? :boolean]
   [:errors {:optional true} [:vector :map]]])

(def workflow-step-attempt-schema
  [:map
   [:attempt-id workflow-attempt-id-schema]
   [:status workflow-step-attempt-status-schema]
   [:execution-session-id {:optional true} [:maybe :string]]
   [:effective-args {:optional true} [:maybe :map]]
   [:result-envelope {:optional true} [:maybe workflow-result-envelope-schema]]
   [:validation-outcome {:optional true} [:maybe workflow-validation-outcome-schema]]
   [:execution-error {:optional true} [:maybe :map]]
   [:blocked {:optional true} [:maybe :map]]
   [:judge-session-id {:optional true} [:maybe :string]]
   [:judge-output {:optional true} [:maybe :any]]
   [:judge-event {:optional true} [:maybe :string]]
   [:created-at inst?]
   [:updated-at inst?]
   [:finished-at {:optional true} [:maybe inst?]]])

(def workflow-step-run-schema
  [:map
   [:step-id workflow-step-id-schema]
   [:attempts [:vector workflow-step-attempt-schema]]
   [:accepted-result {:optional true} [:maybe workflow-result-envelope-schema]]
   [:iteration-count {:optional true} [:maybe :int]]])

(def workflow-history-entry-schema
  [:map
   [:event :keyword]
   [:timestamp inst?]
   [:data {:optional true} [:maybe :map]]])

(def inherited-defaults-schema
  "Resolved inherited-default session details snapshotted on a workflow run at
   invoke time (task 207).

   `:model` is the parent's `{:provider :id}`-shaped value (matching the live
   `(:model parent-session)` consumed by `model-query->selection-request`), not
   a bare id string. All fields are optional/nilable so partial parent state
   (e.g. no speed-mode/effort-override set) still validates."
  [:map
   [:model {:optional true} [:maybe [:map
                                     [:provider {:optional true} [:maybe [:or :string :keyword]]]
                                     [:id {:optional true} [:maybe :string]]]]]
   [:prompt-mode {:optional true} [:maybe :keyword]]
   [:tool-defs {:optional true} [:maybe [:sequential :map]]]
   [:skills {:optional true} [:maybe [:sequential :map]]]
   [:thinking-level {:optional true} [:maybe :keyword]]
   [:speed-mode {:optional true} [:maybe :keyword]]
   [:effort-override {:optional true} [:maybe :keyword]]])

(def workflow-run-schema
  [:map
   [:run-id workflow-run-id-schema]
   [:status workflow-run-status-schema]
   [:effective-definition :map]
   [:source-definition-id {:optional true} [:maybe workflow-definition-id-schema]]
   [:parent-session-id {:optional true} [:maybe :string]]
   [:delegating-run-id {:optional true} [:maybe workflow-run-id-schema]]
   [:inherited-defaults {:optional true} [:maybe inherited-defaults-schema]]
   [:workflow-input {:optional true} :any]
   [:workflow-original {:optional true} :any]
   [:current-step-id {:optional true} [:maybe workflow-step-id-schema]]
   [:step-runs [:map-of workflow-step-id-schema workflow-step-run-schema]]
   [:history [:vector workflow-history-entry-schema]]
   [:blocked {:optional true} [:maybe :map]]
   [:terminal-outcome {:optional true} [:maybe :map]]
   [:created-at inst?]
   [:updated-at inst?]
   [:finished-at {:optional true} [:maybe inst?]]])

(def workflow-state-schema
  [:map
   [:definitions [:map-of workflow-definition-id-schema workflow-definition-schema]]
   [:runs [:map-of workflow-run-id-schema workflow-run-schema]]
   [:run-order [:vector workflow-run-id-schema]]])

(defn initial-workflow-state
  "Return the canonical empty workflow root-state slice."
  []
  {:definitions {}
   :runs {}
   :run-order []})

(defn valid-workflow-definition? [x]
  (m/validate workflow-definition-schema x))

(defn valid-workflow-run? [x]
  (m/validate workflow-run-schema x))

(defn valid-workflow-state? [x]
  (m/validate workflow-state-schema x))

(defn explain-workflow-definition [x]
  (m/explain workflow-definition-schema x))

(defn explain-workflow-run [x]
  (m/explain workflow-run-schema x))

(defn explain-workflow-state [x]
  (m/explain workflow-state-schema x))
