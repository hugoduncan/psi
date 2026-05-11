(ns psi.github.narrowing
  "Shared narrowing logic for GitHub issue and PR candidate lists.

   Used by `psi.github.find-issue` and `psi.github.find-pr`."
  (:require
   [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; URL number extraction

(defn extract-url-number
  "Returns a Long from a GitHub URL matching `url-pattern`, or nil if no match.
   `url-pattern` must capture the number in group 1 (e.g. #\"/issues/(\\d+)\")."
  [url-pattern input]
  (when-let [[_ n] (re-find url-pattern input)]
    (Long/parseLong n)))

;;; ---------------------------------------------------------------------------
;;; Candidate narrowing

(defn narrow-candidates
  "Apply narrowing rules to `candidates` based on `input` string.

   `url-pattern`  — regex matching the URL path segment; must capture number in group 1.
   `url-error-msg` — error message string when the URL does not match the pattern.

   Returns {:status :ok :candidates [...]} or {:status :error ...}."
  [candidates input url-pattern url-error-msg]
  (cond
    (nil? input)
    {:status :ok :candidates candidates}

    (re-matches #"^\d+$" input)
    (let [n (Long/parseLong input)]
      {:status :ok
       :candidates (filter #(= (get % "number") n) candidates)})

    (str/starts-with? input "https://")
    (if-let [n (extract-url-number url-pattern input)]
      {:status :ok
       :candidates (filter #(= (get % "number") n) candidates)}
      {:status :error
       :reason :psi.github/invalid-url-input
       :message url-error-msg})

    :else
    {:status :ok
     :candidates (filter #(str/includes?
                           (str/lower-case (get % "title" ""))
                           (str/lower-case input))
                         candidates)}))
