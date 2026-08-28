(ns psi.workflow-runtime.terminal-contract
  "Workflow-level terminal export contract helpers.

   First cut scope:
   - a workflow may declare `:terminal-contract {:handoff {:type :markdown-handoff-data}}`
   - when such a workflow completes, delegate callers may read the parsed map via
     the delegate step's canonical `:output :handoff` surface
   - yielded text remains separate and continues to flow through `:yield :text`"
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(defn- effective-steps
  [workflow-run]
  (workflow-statechart/effective-steps (:effective-definition workflow-run)))

(defn terminal-step-id
  [workflow-run]
  (or (get-in workflow-run [:terminal-outcome :step-id])
      (last (:step-order (:effective-definition workflow-run)))))

(defn terminal-result-envelope
  [workflow-run]
  (or (get-in workflow-run [:terminal-outcome :result-envelope])
      (some (fn [step-id]
              (get-in workflow-run [:step-runs step-id :accepted-result]))
            (reverse (:step-order (:effective-definition workflow-run))))))

(defn terminal-yielded-text
  [workflow-run]
  (when (or (nil? (:terminal-outcome workflow-run))
            (= :completed (get-in workflow-run [:terminal-outcome :outcome])))
    (let [step-id (terminal-step-id workflow-run)
          step-def (get (effective-steps workflow-run) step-id)
          accepted-result (get-in workflow-run [:step-runs step-id :accepted-result])]
      (workflow-ir/step-yield-field-value step-def accepted-result :text))))

(defn parse-markdown-handoff-data
  [text]
  (when (string? text)
    (let [lines (str/split-lines text)
          in-section? (atom false)
          parsed (reduce (fn [acc line]
                           (let [trimmed (str/trim line)]
                             (cond
                               (re-matches #"##\s+Handoff Data\s*" trimmed)
                               (do
                                 (reset! in-section? true)
                                 acc)

                               (and @in-section?
                                    (re-matches #"##\s+.*" trimmed))
                               (do
                                 (reset! in-section? false)
                                 acc)

                               @in-section?
                               (if-let [[_ raw-key raw-value]
                                        (re-matches #"[-*]\s+`?([^:`]+)`?:\s*(.*)" trimmed)]
                                 (let [k (-> raw-key str/trim keyword)
                                       v (some-> raw-value str/trim not-empty)]
                                   (assoc acc k v))
                                 acc)

                               :else
                               acc)))
                         {}
                         lines)]
      (not-empty parsed))))

(defn terminal-contract-outputs
  [workflow-run]
  (let [contract (:terminal-contract (:effective-definition workflow-run))]
    (cond-> {}
      (= :markdown-handoff-data (get-in contract [:handoff :type]))
      (assoc :handoff (parse-markdown-handoff-data (terminal-yielded-text workflow-run))))))
