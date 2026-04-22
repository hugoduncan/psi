(ns psi.agent-session.dispatch-schema
  (:require
   [malli.core :as m]))

(def effect-schema
  "Schema for a single dispatch effect description.
   Dispatch on :effect/type to validate per-effect payload."
  [:multi {:dispatch :effect/type}
   [:runtime/agent-abort
    [:map [:effect/type [:= :runtime/agent-abort]]]]
   [:runtime/agent-clear-steering-queue
    [:map [:effect/type [:= :runtime/agent-clear-steering-queue]]]]
   [:runtime/agent-clear-follow-up-queue
    [:map [:effect/type [:= :runtime/agent-clear-follow-up-queue]]]]
   [:runtime/agent-drain-follow-up-queue
    [:map [:effect/type [:= :runtime/agent-drain-follow-up-queue]]
     [:messages [:vector :map]]]]
   [:runtime/agent-start-loop
    [:map [:effect/type [:= :runtime/agent-start-loop]]]]
   [:runtime/agent-reset
    [:map [:effect/type [:= :runtime/agent-reset]]]]
   [:runtime/mark-workflow-jobs-terminal
    [:map [:effect/type [:= :runtime/mark-workflow-jobs-terminal]]]]
   [:runtime/emit-background-job-terminal-messages
    [:map [:effect/type [:= :runtime/emit-background-job-terminal-messages]]]]
   [:runtime/reconcile-and-emit-background-job-terminals
    [:map [:effect/type [:= :runtime/reconcile-and-emit-background-job-terminals]]]]
   [:runtime/refresh-system-prompt
    [:map [:effect/type [:= :runtime/refresh-system-prompt]]]]
   [:runtime/agent-queue-steering
    [:map [:effect/type [:= :runtime/agent-queue-steering]] [:message :string]]]
   [:runtime/agent-queue-follow-up
    [:map [:effect/type [:= :runtime/agent-queue-follow-up]] [:message :string]]]
   [:runtime/agent-start-loop-with-messages
    [:map [:effect/type [:= :runtime/agent-start-loop-with-messages]]
     [:messages [:vector :any]]]]
   [:runtime/agent-set-model
    [:map [:effect/type [:= :runtime/agent-set-model]] [:model :map]]]
   [:runtime/agent-set-thinking-level
    [:map [:effect/type [:= :runtime/agent-set-thinking-level]] [:level :keyword]]]
   [:runtime/agent-set-system-prompt
    [:map [:effect/type [:= :runtime/agent-set-system-prompt]] [:prompt :string]]]
   [:runtime/agent-set-tools
    [:map [:effect/type [:= :runtime/agent-set-tools]] [:tool-maps [:vector :map]]]]
   [:runtime/agent-replace-messages
    [:map [:effect/type [:= :runtime/agent-replace-messages]] [:messages [:vector :any]]]]
   [:runtime/agent-append-message
    [:map [:effect/type [:= :runtime/agent-append-message]] [:message :map]]]
   [:runtime/agent-emit
    [:map [:effect/type [:= :runtime/agent-emit]] [:event :map]]]
   [:runtime/agent-emit-tool-start
    [:map [:effect/type [:= :runtime/agent-emit-tool-start]] [:tool-call :map]]]
   [:runtime/agent-emit-tool-end
    [:map [:effect/type [:= :runtime/agent-emit-tool-end]]
     [:tool-call :map] [:result :any] [:is-error? :boolean]]]
   [:runtime/agent-record-tool-result
    [:map [:effect/type [:= :runtime/agent-record-tool-result]] [:tool-result-msg :map]]]
   [:runtime/tool-execute
    [:map [:effect/type [:= :runtime/tool-execute]]
     [:tool-name :string] [:args :map] [:opts {:optional true} [:maybe :map]]]]
   [:runtime/prompt-execute-and-record
    [:map [:effect/type [:= :runtime/prompt-execute-and-record]]
     [:prepared-request :map]
     [:progress-queue {:optional true} :any]]]
   [:runtime/prompt-continue-chain
    [:map [:effect/type [:= :runtime/prompt-continue-chain]]
     [:execution-result :map]
     [:progress-queue {:optional true} :any]]]
   [:runtime/dispatch-event
    [:map [:effect/type [:= :runtime/dispatch-event]]
     [:event-type :keyword]
     [:event-data [:maybe :map]]
     [:origin {:optional true} :keyword]]]
   [:runtime/dispatch-event-with-effect-result
    [:map [:effect/type [:= :runtime/dispatch-event-with-effect-result]]
     [:event-type :keyword]
     [:event-data [:maybe :map]]
     [:origin {:optional true} :keyword]]]
   [:runtime/event-queue-offer
    [:map [:effect/type [:= :runtime/event-queue-offer]] [:event :any]]]
   [:statechart/send-event
    [:map [:effect/type [:= :statechart/send-event]] [:event :any]]]
   [:runtime/schedule-thread-sleep-send-event
    [:map [:effect/type [:= :runtime/schedule-thread-sleep-send-event]]
     [:delay-ms pos-int?] [:event :any]]]
   [:scheduler/start-timer
    [:map [:effect/type [:= :scheduler/start-timer]]
     [:schedule-id :string]
     [:fire-at inst?]]]
   [:scheduler/cancel-timer
    [:map [:effect/type [:= :scheduler/cancel-timer]]
     [:schedule-id :string]]]
   [:scheduler/drain-queue
    [:map [:effect/type [:= :scheduler/drain-queue]]]]
   [:persist/journal-append-model-entry
    [:map [:effect/type [:= :persist/journal-append-model-entry]]
     [:provider :string] [:model-id :string]]]
   [:persist/journal-append-message-entry
    [:map [:effect/type [:= :persist/journal-append-message-entry]] [:message :map]]]
   [:persist/journal-append-thinking-level-entry
    [:map [:effect/type [:= :persist/journal-append-thinking-level-entry]]
     [:level :keyword]]]
   [:persist/journal-append-session-info-entry
    [:map [:effect/type [:= :persist/journal-append-session-info-entry]] [:name :string]]]
   [:persist/project-prefs-update
    [:map [:effect/type [:= :persist/project-prefs-update]] [:prefs :map]]]
   [:persist/user-config-update
    [:map [:effect/type [:= :persist/user-config-update]] [:prefs :map]]]
   [:notify/extension-dispatch
    [:map [:effect/type [:= :notify/extension-dispatch]]
     [:event-name :string] [:payload :any]]]
   [:runtime/schedule-extension-dispatch
    [:map [:effect/type [:= :runtime/schedule-extension-dispatch]]
     [:delay-ms pos-int?] [:event-name :string] [:payload :any]]]
   [:runtime/auto-compact-workflow
    [:map [:effect/type [:= :runtime/auto-compact-workflow]]
     [:reason :any] [:will-retry? :boolean]]]
   [:model-registry/reload
    [:map [:effect/type [:= :model-registry/reload]]
     [:cwd :string]]]
   [:background-job/cancel
    [:map [:effect/type [:= :background-job/cancel]]
     [:job-id :string] [:job [:maybe :map]] [:reason :keyword]]]
   [:memory/capture
    [:map [:effect/type [:= :memory/capture]]
     [:text [:maybe :string]]
     [:memory-ctx :any]
     [:provenance :map]]]
   [:memory/recover-query
    [:map [:effect/type [:= :memory/recover-query]]
     [:query-text [:maybe :string]]]]
   [:oauth/begin-login
    [:map [:effect/type [:= :oauth/begin-login]]
     [:provider-id :keyword]
     [:oauth-ctx :any]]]
   [:oauth/logout
    [:map [:effect/type [:= :oauth/logout]]
     [:provider-ids [:vector :keyword]]
     [:oauth-ctx :any]]]
   [:projection/context-changed
    [:map [:effect/type [:= :projection/context-changed]]
     [:reason {:optional true} [:maybe :keyword]]
     [:session-id {:optional true} [:maybe :string]]
     [:active-session-id {:optional true} [:maybe :string]]]]
   [:projection/ui-changed
    [:map [:effect/type [:= :projection/ui-changed]]
     [:reason {:optional true} [:maybe :keyword]]
     [:session-id {:optional true} [:maybe :string]]]]])

(def pure-result-schema
  "Schema for the unified pure handler result shape.
   At least one recognized key must be present."
  [:and
   [:map
    [:root-state-update {:optional true} fn?]
    [:effects {:optional true} [:vector effect-schema]]
    [:return {:optional true} :any]
    [:return-key {:optional true} [:or :keyword [:vector :any]]]
    [:return-effect-result? {:optional true} :boolean]]
   [:fn {:error/message "must contain at least one of :root-state-update, :effects, :return, :return-key, :return-effect-result?"}
    (fn [m]
      (or (contains? m :root-state-update)
          (contains? m :effects)
          (contains? m :return)
          (contains? m :return-key)
          (contains? m :return-effect-result?)))]])

(def valid-effect?
  "Compiled malli validator for effect descriptions."
  (m/validator effect-schema))

(def explain-effect
  "Compiled malli explainer for effect descriptions."
  (m/explainer effect-schema))

(def valid-pure-result?*
  "Compiled malli validator for pure handler results."
  (m/validator pure-result-schema))

(def explain-pure-result
  "Compiled malli explainer for pure handler results."
  (m/explainer pure-result-schema))

(def validate-dispatch-schemas
  "Malli schema validator for the dispatch pipeline.
   Checks pure-result shape and nested effects against compiled schemas.
   Compiled out when *assert* is false."
  (when *assert*
    (fn [_ctx ictx]
      (if-let [pr (:pure-result ictx)]
        (if (valid-pure-result?* pr)
          true
          {:valid? false
           :reason {:type :schema-validation-failed
                    :explanation (explain-pure-result pr)}})
        true))))
