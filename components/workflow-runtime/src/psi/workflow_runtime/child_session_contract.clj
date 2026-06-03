(ns psi.workflow-runtime.child-session-contract
  "Executable contract for workflow-owned child-session creation across the
   workflow-runtime ↔ agent-session seam."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(def request-schema
  [:map {:closed true}
   [:child-session-id :string]
   [:session-name {:optional true} [:maybe :string]]
   [:system-prompt {:optional true} [:maybe :string]]
   [:prompt-mode {:optional true} [:maybe keyword?]]
   [:response-mode {:optional true} [:maybe keyword?]]
   [:tool-ids {:optional true} [:maybe [:vector :string]]]
   [:thinking-level {:optional true} [:maybe keyword?]]
   [:speed-mode {:optional true} [:maybe keyword?]]
   [:effort-override {:optional true} [:maybe keyword?]]
   [:temperature {:optional true} [:maybe number?]]
   [:model {:optional true} [:maybe :map]]
   [:skills {:optional true} [:maybe [:vector :map]]]
   [:developer-prompt {:optional true} [:maybe :string]]
   [:developer-prompt-source {:optional true} [:maybe keyword?]]
   [:preloaded-messages {:optional true} [:maybe [:vector :map]]]
   [:cache-breakpoints {:optional true} [:maybe [:set keyword?]]]
   [:prompt-component-selection {:optional true} [:maybe :map]]
   [:logprobs {:optional true} [:maybe :boolean]]
   [:top-logprobs {:optional true} [:maybe [:int {:min 1 :max 20}]]]
   [:workflow-run-id {:optional true} [:maybe :string]]
   [:workflow-step-id {:optional true} [:maybe :string]]
   [:workflow-attempt-id {:optional true} [:maybe :string]]
   [:workflow-owned? {:optional true} [:maybe :boolean]]
   ;; task 207 (R4): true when the inherited defaults were resolved from the
   ;; invoke-time snapshot (resolver/attempt path). Snapshot-governed inherited
   ;; fields (:model :prompt-mode :speed-mode :effort-override) must NOT fall
   ;; back to the LIVE parent during child-state assembly, else a post-invoke
   ;; parent mutation leaks in. Workflow-owned children that are NOT
   ;; snapshot-governed (e.g. the workflow judge) keep parent inheritance.
   [:inherited-snapshot? {:optional true} [:maybe :boolean]]])

(def result-schema
  [:map {:closed true}
   [:psi.agent-session/session-id :string]])

(defn valid-request?
  [request]
  (m/validate request-schema request))

(defn valid-result?
  [result]
  (m/validate result-schema result))

(defn explain-request
  [request]
  (me/humanize (m/explain request-schema request)))

(defn explain-result
  [result]
  (me/humanize (m/explain result-schema result)))

(defn assert-valid-request!
  [request caller]
  (when-not (valid-request? request)
    (throw (ex-info "Invalid workflow child-session create request"
                    {:caller caller
                     :contract :workflow-child-session-create
                     :stage :request
                     :request request
                     :explain (explain-request request)})))
  request)

(defn assert-valid-result!
  [result caller]
  (when-not (valid-result? result)
    (throw (ex-info "Invalid workflow child-session create result"
                    {:caller caller
                     :contract :workflow-child-session-create
                     :stage :result
                     :result result
                     :explain (explain-result result)})))
  result)
