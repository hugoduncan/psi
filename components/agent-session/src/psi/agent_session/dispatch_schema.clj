(ns psi.agent-session.dispatch-schema
  (:require
   [malli.core :as m]))

(def effect-schema
  "Agent-session-owned effect schema layered above the generic state kernel.
   The kernel owns dispatch orchestration; this domain still owns the concrete
   effect catalog and effect payload validation."
  [:multi {:dispatch :effect/type}
   [:runtime/agent-abort
    [:and
     [:map
      [:effect/type [:= :runtime/agent-abort]]
      [:session-id {:optional true} :string]
      [:workflow-run-id {:optional true} :string]
      [:workflow-step-id {:optional true} :string]
      [:workflow-attempt-id {:optional true} :string]
      [:expected-session-id {:optional true} :string]
      [:workflow-session-kind {:optional true} [:enum :attempt :judge]]]
     [:fn {:error/message "workflow abort guard keys must be all present or all absent, with explicit session-id"}
      (fn [effect]
        (let [guard-keys [:workflow-run-id :workflow-step-id :workflow-attempt-id :expected-session-id]
              present-count (count (filter #(contains? effect %) guard-keys))]
          (or (and (zero? present-count)
                   (not (contains? effect :workflow-session-kind)))
              (and (= present-count (count guard-keys))
                   (contains? effect :session-id)))))]]]
   [:runtime/cancel-inflight-run
    [:map [:effect/type [:= :runtime/cancel-inflight-run]]
     [:run-id :string]]]
   [:runtime/drop-inflight-run
    [:map [:effect/type [:= :runtime/drop-inflight-run]]
     [:run-id :string]]]
   [:runtime/drop-workflow-cancellation-entry-lock
    [:map [:effect/type [:= :runtime/drop-workflow-cancellation-entry-lock]]
     [:run-id :string]]]
   [:runtime/agent-clear-steering-queue
    [:map [:effect/type [:= :runtime/agent-clear-steering-queue]]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/agent-clear-follow-up-queue
    [:map [:effect/type [:= :runtime/agent-clear-follow-up-queue]]]]
   [:runtime/agent-drain-follow-up-queue
    [:map [:effect/type [:= :runtime/agent-drain-follow-up-queue]]
     [:messages [:vector :map]]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/agent-start-loop
    [:map [:effect/type [:= :runtime/agent-start-loop]]]]
   [:runtime/agent-reset
    [:map [:effect/type [:= :runtime/agent-reset]]]]
   [:runtime/mark-workflow-jobs-terminal
    [:map [:effect/type [:= :runtime/mark-workflow-jobs-terminal]]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/emit-background-job-terminal-messages
    [:map [:effect/type [:= :runtime/emit-background-job-terminal-messages]]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/reconcile-and-emit-background-job-terminals
    [:map [:effect/type [:= :runtime/reconcile-and-emit-background-job-terminals]]
     [:workflow-run-id {:optional true} :string]]]
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
    [:map [:effect/type [:= :runtime/agent-set-model]]
     [:model :map]
     [:scope {:optional true} [:enum :session :project :user]]]]
   [:runtime/agent-set-thinking-level
    [:map [:effect/type [:= :runtime/agent-set-thinking-level]] [:level :keyword]]]
   [:runtime/agent-set-speed-mode
    [:map [:effect/type [:= :runtime/agent-set-speed-mode]] [:mode [:maybe :keyword]]]]
   [:runtime/agent-set-effort-override
    [:map [:effect/type [:= :runtime/agent-set-effort-override]] [:effort [:maybe :keyword]]]]
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
   [:runtime/record-pending-tool-call-interrupts
    [:map [:effect/type [:= :runtime/record-pending-tool-call-interrupts]]
     [:session-id :string]
     [:reason :keyword]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/tool-execute
    [:map [:effect/type [:= :runtime/tool-execute]]
     [:tool-name :string] [:args :map] [:opts {:optional true} [:maybe :map]]]]
   [:runtime/prompt-execute-and-record
    [:map [:effect/type [:= :runtime/prompt-execute-and-record]]
     [:prepared-request :map]
     [:progress-queue {:optional true} :any]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/prompt-continue-chain
    [:map [:effect/type [:= :runtime/prompt-continue-chain]]
     [:execution-result :map]
     [:progress-queue {:optional true} :any]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/dispatch-event
    [:map [:effect/type [:= :runtime/dispatch-event]]
     [:event-type :keyword]
     [:event-data [:maybe :map]]
     [:origin {:optional true} :keyword]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/dispatch-event-with-effect-result
    [:map [:effect/type [:= :runtime/dispatch-event-with-effect-result]]
     [:event-type :keyword]
     [:event-data [:maybe :map]]
     [:origin {:optional true} :keyword]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/turn-augmentation-invoke
    [:map [:effect/type [:= :runtime/turn-augmentation-invoke]]
     [:session-id :string]
     [:turn-id :string]
     [:workflow-run-id {:optional true} [:maybe :string]]
     [:user-msg {:optional true} [:maybe :map]]
     [:selected-providers [:vector :map]]
     [:prepare-event-data [:maybe :map]]]]
   [:runtime/event-queue-offer
    [:map [:effect/type [:= :runtime/event-queue-offer]] [:event :any]]]
   [:statechart/send-event
    [:map [:effect/type [:= :statechart/send-event]]
     [:event :any]
     [:session-id {:optional true} :string]
     [:workflow-run-id {:optional true} :string]]]
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
    [:map [:effect/type [:= :scheduler/drain-queue]]
     [:workflow-run-id {:optional true} :string]]]
   [:persist/session-journal-io
    [:map [:effect/type [:= :persist/session-journal-io]]
     [:workflow-run-id {:optional true} :string]
     [:request [:map
                [:op [:enum :append-entry :flush-journal]]
                [:session-id :string]
                [:session-file :any]
                [:worktree-path [:maybe :string]]
                [:parent-session-id {:optional true} [:maybe :string]]
                [:parent-session-path {:optional true} [:maybe :string]]
                [:entry {:optional true} :map]
                [:entries {:optional true} [:vector :map]]]]]]
   [:persist/project-prefs-update
    [:map [:effect/type [:= :persist/project-prefs-update]] [:prefs :map]]]
   [:persist/user-config-update
    [:map [:effect/type [:= :persist/user-config-update]] [:prefs :map]]]
   [:notify/extension-dispatch
    [:map [:effect/type [:= :notify/extension-dispatch]]
     [:event-name :string] [:payload :any]
     [:workflow-run-id {:optional true} :string]]]
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
     [:query-text [:maybe :string]]
     [:workflow-run-id {:optional true} :string]]]
   [:runtime/recover-query-prompt-execute-and-record
    [:map [:effect/type [:= :runtime/recover-query-prompt-execute-and-record]]
     [:prepared-request :map]
     [:progress-queue {:optional true} :any]
     [:query-text [:maybe :string]]
     [:workflow-run-id {:optional true} :string]]]
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

(def valid-effect? (m/validator effect-schema))
(def explain-effect (m/explainer effect-schema))
(def valid-pure-result?* (m/validator pure-result-schema))
(def explain-pure-result (m/explainer pure-result-schema))

(def validate-dispatch-schemas
  (when *assert*
    (fn [_ctx ictx]
      (if-let [pr (:pure-result ictx)]
        (if (valid-pure-result?* pr)
          true
          {:valid? false
           :reason {:type :schema-validation-failed
                    :explanation (explain-pure-result pr)}})
        true))))
