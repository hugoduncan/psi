(ns psi.agent-session.workflow-judge
  "Impure judge execution for deterministic workflow runs.

   Pure judge projection and routing logic lives in `psi.workflow-judge`.
   This namespace owns only judge-session execution/orchestration above that boundary."
  (:require
   [clojure.string :as str]
   [psi.agent-session.turn :as turn]
   [psi.session-persistence.core :as persist]
   [psi.workflow-judge :as workflow-judge]))

;;; Judge session execution — impure

(def ^:private max-judge-retries 2)

(defn- assistant-message-text
  "Extract text content from an assistant message.
   Handles both string content and structured content blocks.
   Mirrors workflow-execution/assistant-message-text (cannot share due to dep direction)."
  [assistant-message]
  (or (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      (when (string? (:content assistant-message))
        (:content assistant-message))
      ""))

(defn- judge-retry-feedback
  "Build a feedback message for a judge retry when no signal matched."
  [judge-output expected-signals]
  (str "Your response '" judge-output "' did not match any expected signal. "
       "Expected exactly one of: " (str/join ", " (sort expected-signals)) ". "
       "Respond with exactly one of those words, nothing else."))

(defn execute-judge!
  "Execute the judge phase for a workflow step.

   Creates a judge child session with projected actor messages as preloaded context,
   prompts it, and matches the response against the routing table.
   Retries up to `max-judge-retries` times on no-match with feedback injection.

   `routing-context` is {:current-step-id :step-order :step-runs}.

   Returns {:judge-session-id :judge-output :judge-event :routing-result}."
  [ctx parent-session-id actor-session-id judge-spec routing-table routing-context]
  (let [{:keys [current-step-id step-order step-runs]} routing-context
        projection    (or (:projection judge-spec) :full)
        actor-msgs    (vec (persist/messages-from-entries-in ctx actor-session-id))
        projected     (workflow-judge/project-messages actor-msgs projection)
        judge-sid     (str (java.util.UUID/randomUUID))
        expected-sigs (keys routing-table)]
    ((:create-workflow-child-session-fn ctx)
     ctx
     parent-session-id
     {:child-session-id   judge-sid
      :session-name       "workflow judge"
      :system-prompt      (:system-prompt judge-spec)
      :tool-defs          []
      :thinking-level     :off
      :preloaded-messages projected
      :workflow-owned?    true})
    ;; First attempt
    (let [initial-result (turn/prompt-execution-result-in! ctx judge-sid (:prompt judge-spec))]
      (loop [attempt 0
             last-output (str/trim (assistant-message-text (:execution-result/assistant-message initial-result)))]
        (let [routing-result (workflow-judge/evaluate-routing last-output routing-table
                                                              current-step-id step-order step-runs)]
          (if (and (= :no-match (:action routing-result))
                   (< attempt max-judge-retries))
            ;; Retry: inject feedback into the same judge session
            (let [retry-result (turn/prompt-execution-result-in! ctx judge-sid
                                                                 (judge-retry-feedback last-output expected-sigs))]
              (recur (inc attempt)
                     (str/trim (assistant-message-text (:execution-result/assistant-message retry-result)))))
            ;; Matched, or retries exhausted
            {:judge-session-id judge-sid
             :judge-output     last-output
             :judge-event      (when (not= :no-match (:action routing-result))
                                 last-output)
             :routing-result   routing-result}))))))