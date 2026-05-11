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
;;; Public operation handlers

(defn add-label
  "Deterministic operation handler for `github/add-label`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :number  - issue or PR number (integer)
     :labels  - vector of label strings to add
     :target  - \"issue\" (default) or \"pr\""
  [{:keys [ctx args]}]
  (let [shell-fn (or (:github-shell-fn ctx) shell/sh)
        number   (:number args)
        labels   (:labels args)
        target   (or (:target args) "issue")
        csv      (label-csv labels)
        result   (shell-fn "gh" target "edit" (str number) "--add-label" csv)]
    (if (not= 0 (:exit result))
      {:status  :error
       :reason  :psi.github/shell-error
       :message (:err result)}
      {:status  :ok
       :data    {:number        number
                 :target        target
                 :added-labels  labels}
       :summary (str "Added label(s) [" csv "] to " target " #" number)})))

(defn remove-label
  "Deterministic operation handler for `github/remove-label`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :number  - issue or PR number (integer)
     :labels  - vector of label strings to remove
     :target  - \"issue\" (default) or \"pr\""
  [{:keys [ctx args]}]
  (let [shell-fn (or (:github-shell-fn ctx) shell/sh)
        number   (:number args)
        labels   (:labels args)
        target   (or (:target args) "issue")
        csv      (label-csv labels)
        result   (shell-fn "gh" target "edit" (str number) "--remove-label" csv)]
    (if (not= 0 (:exit result))
      {:status  :error
       :reason  :psi.github/shell-error
       :message (:err result)}
      {:status  :ok
       :data    {:number          number
                 :target          target
                 :removed-labels  labels}
       :summary (str "Removed label(s) [" csv "] from " target " #" number)})))
