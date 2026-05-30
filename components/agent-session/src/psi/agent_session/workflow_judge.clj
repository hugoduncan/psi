(ns psi.agent-session.workflow-judge
  "Impure judge execution for deterministic workflow runs.

   Pure judge projection and routing logic lives in `psi.workflow-judge`.
   This namespace owns only judge-session execution/orchestration above that boundary."
  (:require
   [clojure.string :as str]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.session-persistence.core :as persist]
   [psi.workflow-judge :as workflow-judge]
   [psi.workflow-runtime.child-session-contract :as child-session-contract]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.structured-output :as structured-output]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]))

;;; Judge session execution — impure

(def ^:private max-judge-retries 2)

(defn- judge-prompt
  "Derive the judge prompt string from the compiled judge spec.

   For `:invoke`-style specs the prompt lives at `:prompt` (legacy path).
   For `:llm`-style specs the prompt comes from the last `:template` contribution
   in `:session :contributions`. Template vars are not rendered here; all current
   judge specs use static template text with empty `:vars {}`."
  [judge-spec]
  (or (:prompt judge-spec)
      (some->> (get-in judge-spec [:session :contributions])
               (filter #(= :template (:type %)))
               last
               :text)))

(defn- judge-retry-feedback
  "Build a feedback message for a judge retry when no signal matched."
  [judge-output expected-signals]
  (str "Your response '" judge-output "' did not match any expected signal. "
       "Expected exactly one of: " (str/join ", " (sort expected-signals)) ". "
       "Respond with exactly one of those words, nothing else."))

(defn- invoke-judge-error-result
  [operation-id operation-result]
  {:judge-session-id nil
   :judge-output {:routing-result {:status :error
                                   :reason (:reason operation-result)
                                   :message (:message operation-result)
                                   :details (:details operation-result)}}
   :judge-event nil
   :routing-result {:action :fail
                    :reason (:reason operation-result)
                    :output-key :routing-result
                    :details {:operation operation-id
                              :message (:message operation-result)
                              :operation-result operation-result}}})

(defn- execute-invoke-judge!
  [ctx parent-session-id judge-spec routing-table {:keys [current-step-id step-order step-runs workflow-run-id workflow-run]}]
  (let [invoke-spec (or (:invoke judge-spec) judge-spec)
        args (workflow-source-resolution/resolve-invoke-args workflow-run current-step-id (:args invoke-spec))
        operation-result (op-reg/invoke-operation-in
                          (:deterministic-operation-registry ctx)
                          (:operation invoke-spec)
                          {:ctx ctx
                           :parent-session-id parent-session-id
                           :workflow-run-id workflow-run-id
                           :step-id current-step-id
                           :args args}
                          deterministic-op-runtime/invoke-operation)]
    (if (= :ok (:status operation-result))
      (let [judge-event (:data operation-result)
            routing-result (workflow-judge/evaluate-routing judge-event routing-table
                                                            current-step-id step-order step-runs)]
        {:judge-session-id nil
         :judge-output {:routing-result operation-result}
         :judge-event judge-event
         :routing-result routing-result})
      (invoke-judge-error-result (:operation invoke-spec) operation-result))))

(defn execute-judge!
  "Execute the judge phase for a workflow step.

   Creates a judge child session with projected actor messages as preloaded context,
   prompts it, and matches the response against the routing table.
   Retries up to `max-judge-retries` times on no-match with feedback injection.

   `routing-context` is {:current-step-id :step-order :step-runs}.

   Returns {:judge-session-id :judge-output :judge-event :routing-result}."
  [ctx parent-session-id actor-session-id judge-spec routing-table routing-context]
  (let [{:keys [current-step-id step-order step-runs]} routing-context]
    (if (= :invoke (:type judge-spec))
      (execute-invoke-judge! ctx parent-session-id judge-spec routing-table routing-context)
      (let [projection    (or (:projection judge-spec) :full)
            actor-msgs    (vec (persist/messages-from-entries-in ctx actor-session-id))
            projected     (workflow-judge/project-messages actor-msgs projection)
            judge-sid     (str (java.util.UUID/randomUUID))
            expected-sigs (keys routing-table)]
        (-> (child-session-contract/assert-valid-request!
             {:child-session-id   judge-sid
              :session-name       "workflow judge"
              :system-prompt      (:system-prompt judge-spec)
              :tool-ids           []
              :thinking-level     :off
              :preloaded-messages projected
              :workflow-owned?    true}
             :psi.agent-session.workflow-judge/execute-judge!)
            (#(execution-adapter/create-child-session! ctx parent-session-id %))
            (child-session-contract/assert-valid-result!
             :psi.agent-session.workflow-judge/execute-judge!))
        (let [structured-entry (structured-output/single-structured-output-entry (:outputs judge-spec))
              request-result (when-let [[output-key output-spec] structured-entry]
                               (structured-output/structured-output-request output-key output-spec))]
          (if (false? (:ok? request-result))
            {:judge-session-id judge-sid
             :judge-output {(get-in request-result [:details :output-key])
                            {:structured-output {:status :invalid
                                                 :errors [{:type (:reason request-result)
                                                           :message (:message request-result)}]}}}
             :judge-event nil
             :routing-result {:action :fail
                              :reason (:reason request-result)
                              :output-key (get-in request-result [:details :output-key])
                              :details (:details request-result)}}
            (let [initial-result (if-let [opts (:opts request-result)]
                                   (turn-execution/execute-judge-turn! ctx judge-sid (judge-prompt judge-spec) opts)
                                   (turn-execution/execute-judge-turn! ctx judge-sid (judge-prompt judge-spec)))]
              (loop [attempt 0
                     last-output (str/trim (:assistant-text initial-result))
                     last-structured-output (:structured-output initial-result)]
                (if-let [[output-key output-spec] structured-entry]
                  (if (= :unsupported-structured-output (get-in last-structured-output [:reason]))
                    {:judge-session-id judge-sid
                     :judge-output {output-key {:structured-output last-structured-output}}
                     :judge-event nil
                     :routing-result {:action :fail
                                      :reason :unsupported-structured-output
                                      :output-key output-key
                                      :details {:structured-output last-structured-output}}}
                    (let [structured-result (structured-output/output-result output-spec last-output last-structured-output)
                          judge-output {output-key structured-result}]
                      (if (structured-output/valid-output-result? structured-result)
                        (let [raw-value (get-in structured-result [:structured-output :value])
                              judge-event (if (map? raw-value) (:decision raw-value) raw-value)
                              routing-result (workflow-judge/evaluate-routing judge-event routing-table
                                                                              current-step-id step-order step-runs)]
                          {:judge-session-id judge-sid
                           :judge-output judge-output
                           :judge-event judge-event
                           :routing-result routing-result})
                        (if (< attempt max-judge-retries)
                          (let [retry-result (if-let [opts (:opts request-result)]
                                               (turn-execution/execute-judge-turn!
                                                ctx judge-sid
                                                (judge-retry-feedback last-output expected-sigs)
                                                opts)
                                               (turn-execution/execute-judge-turn!
                                                ctx judge-sid
                                                (judge-retry-feedback last-output expected-sigs)))]
                            (recur (inc attempt)
                                   (str/trim (:assistant-text retry-result))
                                   (:structured-output retry-result)))
                          {:judge-session-id judge-sid
                           :judge-output judge-output
                           :judge-event nil
                           :routing-result (cond-> {:action :fail
                                                    :reason :invalid-structured-output
                                                    :output-key output-key}
                                             (or (:opts request-result) last-structured-output)
                                             (assoc :details {:structured-output (:structured-output structured-result)}))}))))
                  (let [routing-result (workflow-judge/evaluate-routing last-output routing-table
                                                                        current-step-id step-order step-runs)]
                    (if (and (= :no-match (:action routing-result))
                             (< attempt max-judge-retries))
                      (let [retry-result (turn-execution/execute-judge-turn! ctx judge-sid
                                                                             (judge-retry-feedback last-output expected-sigs))]
                        (recur (inc attempt)
                               (str/trim (:assistant-text retry-result))
                               (:structured-output retry-result)))
                      {:judge-session-id judge-sid
                       :judge-output last-output
                       :judge-event (when (not= :no-match (:action routing-result))
                                      last-output)
                       :routing-result routing-result})))))))))))
