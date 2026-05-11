(ns psi.github.label-ops
  "Deterministic operation: edit labels on GitHub issues or PRs.

   Operation id: \"github/edit-labels\"

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
;;; Public operation handler

(defn edit-labels
  "Deterministic operation handler for `github/edit-labels`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :number  - issue or PR number (integer)
     :target  - \"issue\" (default) or \"pr\"
     :add     - vector of label strings to add (optional)
     :remove  - vector of label strings to remove (optional)

   Issues a single `gh <target> edit <number>` call combining --add-label and
   --remove-label flags as needed. Returns :ok when the gh command exits 0,
   :error otherwise."
  [{:keys [ctx args]}]
  (let [shell-fn (or (:github-shell-fn ctx) shell/sh)
        number   (:number args)
        target   (or (:target args) "issue")
        add      (seq (:add args))
        remove   (seq (:remove args))]
    (if (and (nil? add) (nil? remove))
      {:status  :ok
       :data    {:number number :target target :added-labels [] :removed-labels []}
       :summary (str "No label changes on " target " #" number)}
      (let [cmd    (cond-> ["gh" target "edit" (str number)]
                     add    (into ["--add-label"    (label-csv add)])
                     remove (into ["--remove-label" (label-csv remove)]))
            result (apply shell-fn cmd)]
        (if (not= 0 (:exit result))
          {:status  :error
           :reason  :psi.github/shell-error
           :message (:err result)}
          {:status  :ok
           :data    {:number         number
                     :target         target
                     :added-labels   (vec (or add []))
                     :removed-labels (vec (or remove []))}
           :summary (str "Edited labels on " target " #" number
                         (when add    (str " +[" (label-csv add) "]"))
                         (when remove (str " -[" (label-csv remove) "]")))})))))
