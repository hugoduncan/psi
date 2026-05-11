(ns psi.github.find-pr
  "Deterministic operation: find a GitHub PR matching labels and optional
   narrowing input.

   Operation id: \"github/find-pr\"

   Invoked via the workflow `:invoke` step type — never exposed to AI agents
   as a tool. Shell invocations are behind a `:github-shell-fn` ctx key so
   tests can inject a nullable stub."
  (:require
   [cheshire.core :as json]
   [clojure.java.shell :as shell]
   [psi.github.narrowing :as narrowing]
   [psi.github.slug :as slug]))

;;; ---------------------------------------------------------------------------
;;; Handoff Markdown serialization

(defn- result->handoff-md
  [{:keys [pr-number pr-title pr-url pr-branch base-branch worktree-description]}]
  (str "## PR Selection\n\n"
       "Selected PR #" pr-number ": " pr-title "\n\n"
       "## Handoff Data\n"
       "- pr_number: " pr-number "\n"
       "- pr_title: " pr-title "\n"
       "- pr_url: " pr-url "\n"
       "- pr_branch: " pr-branch "\n"
       "- base_branch: " base-branch "\n"
       "- worktree_description: " worktree-description "\n"))

;;; ---------------------------------------------------------------------------
;;; Public operation handler

(defn invoke
  "Deterministic operation handler for `github/find-pr`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :labels  - vector of label strings (AND filter)
     :input   - optional narrowing hint (integer string, PR URL, or text); nil = no narrowing
     :state   - optional PR state string (default \"open\")"
  [{:keys [ctx args]}]
  (let [shell-fn   (or (:github-shell-fn ctx) shell/sh)
        labels     (:labels args)
        input      (:input args)
        state      (or (:state args) "open")
        label-args (into [] (mapcat #(list "--label" %) labels))
        gh-args    (into ["gh" "pr" "list"
                          "--state" state
                          "--json" "number,title,url,state,labels,headRefName,baseRefName"]
                         label-args)
        result     (apply shell-fn gh-args)]
    (if (not= 0 (:exit result))
      {:status  :error
       :reason  :psi.github/shell-error
       :message (:err result)}
      (let [prs        (json/parse-string (:out result))
            narrow-res (narrowing/narrow-candidates
                        prs input
                        #"/pull/(\d+)"
                        (str "Cannot extract PR number from URL: " input))]
        (if (= :error (:status narrow-res))
          narrow-res
          (let [candidates (:candidates narrow-res)
                selected   (first (sort-by #(get % "number") candidates))]
            (if (nil? selected)
              {:status  :error
               :reason  :psi.github/no-matching-pr
               :message (str "No open PR matching labels " (pr-str labels)
                             (when input (str " and input " (pr-str input))))}
              (let [data {:pr-number           (get selected "number")
                          :pr-title            (get selected "title")
                          :pr-url              (get selected "url")
                          :pr-branch           (get selected "headRefName")
                          :base-branch         (get selected "baseRefName")
                          :worktree-description (slug/derive-slug (get selected "headRefName"))}]
                {:status  :ok
                 :data    data
                 :summary (result->handoff-md data)}))))))))
