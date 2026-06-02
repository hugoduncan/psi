(ns psi.workflow-runtime.attempts
  "Workflow step-attempt session orchestration for deterministic workflows.

   This slice owns creation of one canonical execution session per workflow step attempt,
   with explicit workflow linkage written onto the child session data.

   Child-session creation is delegated via the named workflow execution adapter
   to avoid a load cycle through mutations/session → core → context."
  (:require
   [clojure.string :as str]
   [psi.session-state.model :as session]
   [psi.workflow-runtime.child-session-contract :as child-session-contract]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]))

(defn- now []
  (java.time.Instant/now))

(defn- blankish? [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn normalize-attempt-id
  [attempt-id]
  (if (blankish? attempt-id)
    (str (java.util.UUID/randomUUID))
    (str attempt-id)))

(defn new-attempt
  [{:keys [attempt-id status execution-session-id effective-args result-envelope validation-outcome execution-error blocked]}]
  (let [ts (now)]
    (cond-> {:attempt-id attempt-id
             :status status
             :created-at ts
             :updated-at ts}
      execution-session-id (assoc :execution-session-id execution-session-id)
      effective-args (assoc :effective-args effective-args)
      result-envelope (assoc :result-envelope result-envelope)
      validation-outcome (assoc :validation-outcome validation-outcome)
      execution-error (assoc :execution-error execution-error)
      blocked (assoc :blocked blocked))))

(defn append-attempt-to-run
  [workflow-run step-id attempt]
  (-> workflow-run
      (update-in [:step-runs step-id :attempts] (fnil conj []) attempt)
      (assoc :updated-at (now))))

(defn set-execution-session-model!
  [ctx execution-session model]
  (execution-adapter/set-session-model! ctx (:session-id execution-session) model :session)
  (assoc execution-session :model model))

(defn create-step-attempt-session!
  "Create one canonical execution child session for a workflow step attempt.

   Returns {:attempt attempt-map :execution-session session-data}.

   The created session is marked as workflow-owned and linked by run/step/attempt ids."
  [ctx parent-session-id {:keys [workflow-run-id workflow-step-id attempt-id session-name system-prompt prompt-mode response-mode logprobs top-logprobs tool-defs thinking-level speed-mode effort-override model skills developer-prompt developer-prompt-source preloaded-messages cache-breakpoints prompt-component-selection model-fallback]}]
  (let [attempt-id'      (normalize-attempt-id attempt-id)
        child-session-id (str (java.util.UUID/randomUUID))
        request          (child-session-contract/assert-valid-request!
                          {:child-session-id           child-session-id
                           :session-name               session-name
                           :system-prompt              system-prompt
                           :prompt-mode                prompt-mode
                           :response-mode              response-mode
                           :logprobs                   logprobs
                           :top-logprobs               top-logprobs
                           :tool-ids                   (when tool-defs (mapv :name tool-defs))
                           :thinking-level             thinking-level
                           :speed-mode                 speed-mode
                           :effort-override            effort-override
                           :model                      model
                           :skills                     skills
                           :developer-prompt           developer-prompt
                           :developer-prompt-source    developer-prompt-source
                           :preloaded-messages         preloaded-messages
                           :cache-breakpoints          cache-breakpoints
                           :prompt-component-selection prompt-component-selection
                           :workflow-run-id            workflow-run-id
                           :workflow-step-id           workflow-step-id
                           :workflow-attempt-id        attempt-id'
                           :workflow-owned?            true}
                          :psi.workflow-runtime.attempts/create-step-attempt-session!)
        result           (-> (execution-adapter/create-child-session!
                              ctx
                              parent-session-id
                              request)
                             (child-session-contract/assert-valid-result!
                              :psi.workflow-runtime.attempts/create-step-attempt-session!))
        _                result
        child-sd         (execution-adapter/get-session-data ctx child-session-id)
        attempt          (new-attempt {:attempt-id attempt-id'
                                       :status :pending
                                       :execution-session-id child-session-id})]
    (when-not (session/valid-session? child-sd)
      ;; intentionally defensive; child session shape must remain schema-safe
      (throw (ex-info "Workflow attempt child session is not schema-valid"
                      {:session-id child-session-id})))
    {:attempt attempt
     :execution-session (cond-> child-sd
                          model-fallback (assoc :model-fallback model-fallback))}))
