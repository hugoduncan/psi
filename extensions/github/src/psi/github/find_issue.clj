(ns psi.github.find-issue
  "Deterministic operation: find a GitHub issue matching labels and optional
   narrowing input.

   Operation id: \"github/find-issue\"

   Invoked via the workflow `:invoke` step type — never exposed to AI agents
   as a tool. Shell invocations are behind a `:github-shell-fn` ctx key so
   tests can inject a nullable stub."
  (:require
   [cheshire.core :as json]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.github.slug :as slug]))

;;; ---------------------------------------------------------------------------
;;; Narrowing

(defn- extract-url-number
  "Returns a Long from a GitHub issue URL, or nil if no /issues/NNN segment."
  [input]
  (when-let [[_ n] (re-find #"/issues/(\d+)" input)]
    (Long/parseLong n)))

(defn- narrow-candidates
  "Apply narrowing rules to `candidates` based on `input` string.
   Returns {:status :ok :candidates [...]} or {:status :error ...}."
  [candidates input]
  (cond
    (nil? input)
    {:status :ok :candidates candidates}

    (re-matches #"^\d+$" input)
    (let [n (Long/parseLong input)]
      {:status :ok
       :candidates (filter #(= (get % "number") n) candidates)})

    (str/starts-with? input "https://")
    (if-let [n (extract-url-number input)]
      {:status :ok
       :candidates (filter #(= (get % "number") n) candidates)}
      {:status :error
       :reason :psi.github/invalid-url-input
       :message (str "Cannot extract issue number from URL: " input)})

    :else
    {:status :ok
     :candidates (filter #(str/includes?
                           (str/lower-case (get % "title" ""))
                           (str/lower-case input))
                         candidates)}))

;;; ---------------------------------------------------------------------------
;;; Handoff Markdown serialization

(defn- result->handoff-md
  [{:keys [issue-number issue-title issue-url worktree-description]}]
  (str "## Issue Selection\n\n"
       "Selected issue #" issue-number ": " issue-title "\n\n"
       "## Handoff Data\n"
       "- issue_number: " issue-number "\n"
       "- issue_title: " issue-title "\n"
       "- issue_url: " issue-url "\n"
       "- worktree_description: " worktree-description "\n"))

;;; ---------------------------------------------------------------------------
;;; Public operation handler

(defn invoke
  "Deterministic operation handler for `github/find-issue`.

   Invocation map: {:ctx ctx :args args :workflow-run-id run-id :step-id step-id}

   Args:
     :labels  - vector of label strings (AND filter)
     :input   - optional narrowing hint (integer string, URL, or text); nil = no narrowing
     :state   - optional issue state string (default \"open\")"
  [{:keys [ctx args]}]
  (let [shell-fn   (or (:github-shell-fn ctx) shell/sh)
        labels     (:labels args)
        input      (:input args)
        state      (or (:state args) "open")
        label-args (into [] (mapcat #(list "--label" %) labels))
        gh-args    (into ["gh" "issue" "list"
                          "--state" state
                          "--json" "number,title,url,state,labels"]
                         label-args)
        result     (apply shell-fn gh-args)]
    (if (not= 0 (:exit result))
      {:status  :error
       :reason  :psi.github/shell-error
       :message (:err result)}
      (let [issues     (json/parse-string (:out result))
            narrow-res (narrow-candidates issues input)]
        (if (= :error (:status narrow-res))
          narrow-res
          (let [candidates (:candidates narrow-res)
                selected   (first (sort-by #(get % "number") candidates))]
            (if (nil? selected)
              {:status  :error
               :reason  :psi.github/no-matching-issue
               :message (str "No open issue matching labels " (pr-str labels)
                             (when input (str " and input " (pr-str input))))}
              (let [data {:issue-number        (get selected "number")
                          :issue-title         (get selected "title")
                          :issue-url           (get selected "url")
                          :worktree-description (slug/derive-slug (get selected "title"))}]
                {:status  :ok
                 :data    data
                 :summary (result->handoff-md data)}))))))))
