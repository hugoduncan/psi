(ns psi.github.label-ops
  "Deterministic operations: add and remove labels on GitHub issues or PRs.

   Operation ids: \"github/add-label\" and \"github/remove-label\"

   Invoked via the workflow `:invoke` step type — never exposed to AI agents
   as a tool. Shell invocations are behind a `:github-shell-fn` ctx key so
   tests can inject a nullable stub."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Shared helpers

(defn- label-csv
  "Join labels into a comma-separated string for the gh CLI."
  [labels]
  (str/join "," labels))

;;; ---------------------------------------------------------------------------
;;; Private shell dispatch

(defn- invoke-edit
  "Run `gh <target> edit <number> <flag> <csv>` via `shell-fn`.
   Returns `{:status :ok/:error ...}` with `result-key` holding `labels` on success."
  [shell-fn number target flag result-key labels]
  (let [csv    (label-csv labels)
        result (shell-fn "gh" target "edit" (str number) flag csv)]
    (if (not= 0 (:exit result))
      {:status  :error
       :reason  :psi.github/shell-error
       :message (:err result)}
      {:status  :ok
       :data    {:number     number
                 :target     target
                 result-key  labels}
       :summary (str (if (= flag "--add-label") "Added" "Removed")
                     " label(s) [" csv "]"
                     (if (= flag "--add-label") " to " " from ")
                     target " #" number)})))

;;; ---------------------------------------------------------------------------
;;; Public operation handlers

(defn add-label
  "Deterministic operation handler for `github/add-label`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :number  - issue or PR number (integer)
     :labels  - vector of label strings to add
     :target  - \"issue\" (default) or \"pr\""
  [{:keys [ctx args]}]
  (invoke-edit (or (:github-shell-fn ctx) shell/sh)
               (:number args)
               (or (:target args) "issue")
               "--add-label"
               :added-labels
               (:labels args)))

(defn remove-label
  "Deterministic operation handler for `github/remove-label`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :number  - issue or PR number (integer)
     :labels  - vector of label strings to remove
     :target  - \"issue\" (default) or \"pr\""
  [{:keys [ctx args]}]
  (invoke-edit (or (:github-shell-fn ctx) shell/sh)
               (:number args)
               (or (:target args) "issue")
               "--remove-label"
               :removed-labels
               (:labels args)))
