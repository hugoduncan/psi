(ns extensions.workflow-loader.text
  (:require
   [clojure.string :as str]))

(defn parse-mode [mode-str]
  (case (some-> mode-str str str/lower-case str/trim)
    ("sync" "synchronous") :sync
    ("async" "asynchronous" nil) :async
    ::invalid))

(defn available-workflows-text
  "Return human-readable list of available workflows."
  [definitions]
  (if (empty? definitions)
    "No workflows loaded."
    (str/join "\n"
              (for [[name defn-map] (sort-by key definitions)]
                (let [step-count (count (:step-order defn-map))]
                  (str "  " name " — " (or (:summary defn-map) "")
                       (when (> step-count 1)
                         (str " (" step-count " steps)"))))))))

(defn active-runs-text
  "Return human-readable list of active/recent workflow runs backed by canonical workflow + background-job state."
  [runs jobs]
  (let [async-run-ids (->> jobs
                           (filter #(= "delegate" (:psi.background-job/tool-name %)))
                           (filter #(contains? #{:running :pending-cancel}
                                               (:psi.background-job/status %)))
                           (map :psi.background-job/workflow-id)
                           set)]
    (if (empty? runs)
      "No active runs."
      (->> runs
           (map (fn [{:keys [run-id status source-definition-id]}]
                  (str "  " run-id " — " (name status)
                       (when source-definition-id
                         (str " (" source-definition-id ")"))
                       (when (contains? async-run-ids run-id)
                         " [async]"))))
           (str/join "\n")))))

(defn delegate-list-text [definitions runs jobs]
  (str "Available workflows:\n" (available-workflows-text definitions)
       "\n\nActive runs:\n" (active-runs-text runs jobs)))

(defn workflow-run-started-text [run-id]
  (str "Workflow run " run-id " started asynchronously. "
       "Use action=list to check progress."))

(defn workflow-run-result-text [run-id status result]
  (str "Workflow run " run-id " — " (name (or status :unknown))
       (when result
         (str "\n\n" result))))

(defn delegate-continued-text [run-id]
  (str "Resuming run " run-id " asynchronously."))

(defn delegate-removed-text [run-id]
  (str "Removed run " run-id))

(defn unknown-action-text [action]
  (str "Unknown action: " action ". Use: run, list, continue, remove"))

(defn error-text [message]
  (str "Error: " message))

(def widget-placement :bottom)

(defn run-status-icon [status]
  (case status
    :pending "○"
    :running "▸"
    :blocked "◆"
    :completed "✓"
    :failed "✗"
    :cancelled "⊘"
    "?"))

(defn run-widget-lines
  "Build display lines for a single workflow run."
  [run-id now-ms job-info run-info]
  (let [status (or (:status run-info)
                   (:psi.background-job/status job-info)
                   :running)
        started-at (or (:started-at job-info)
                       (:psi.background-job/started-at job-info))
        elapsed (when started-at
                  (quot (- now-ms
                           (.toEpochMilli ^java.time.Instant started-at))
                        1000))
        source (or (:source-definition-id run-info) (:workflow job-info))
        top-line (str (run-status-icon status)
                      " " run-id
                      (when source (str " · @" source))
                      (when elapsed (str " · " elapsed "s")))]
    [top-line]))

(defn build-prompt-contribution
  "Build the prompt contribution text listing available workflows."
  [definitions]
  (if (empty? definitions)
    "tool: delegate\nNo workflows available."
    (str "tool: delegate\navailable workflows:\n"
         (str/join "\n"
                   (for [[name defn-map] (sort-by key definitions)]
                     (str "- " name ": " (or (:summary defn-map)
                                             (:description defn-map)
                                             "")))))))

(defn parse-delegate-command
  "Parse `/delegate <workflow> <prompt>` args."
  [args-str]
  (let [trimmed (str/trim (or args-str ""))]
    (when-not (str/blank? trimmed)
      (let [space-idx (str/index-of trimmed " ")]
        (if space-idx
          {:workflow (subs trimmed 0 space-idx)
           :prompt (str/trim (subs trimmed (inc space-idx)))}
          {:workflow trimmed})))))

(defn completion-notification-text [workflow-name status run-id]
  (str "Workflow '" workflow-name "' " (name (or status :unknown))
       " (run " run-id ")"))

(defn completion-entry-heading [workflow-name status run-id]
  (str "Workflow '" workflow-name "' — " (name (or status :unknown))
       " (run " run-id ")"))

(defn completion-entry-content [workflow-name status run-id result-text include-result?]
  (let [heading (completion-entry-heading workflow-name status run-id)]
    (if (and result-text (not include-result?))
      (str heading "\n\nResult:\n"
           (if (> (count result-text) 8000)
             (str (subs result-text 0 8000) "\n\n... [truncated]")
             result-text))
      heading)))
