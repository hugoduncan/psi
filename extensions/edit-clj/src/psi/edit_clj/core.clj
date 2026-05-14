(ns psi.edit-clj.core
  "Pure structural-edit logic — no file I/O.

   All functions take/return plain values; the extension layer owns I/O and JSON
   serialisation."
  (:require
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [rewrite-clj.parser :as p]
   [rewrite-clj.zip :as z]))

;; ── Form parsing ──────────────────────────────────────────────────────────────

(defn parse-single-form
  "Parse `s` as exactly one Clojure form.
   Returns `{:ok node}` or `{:error {:code :parse-error :argument arg-name :message ...}}`.
   Errors on blank input, unparseable input, or more than one top-level form."
  [s arg-name]
  (if (or (nil? s) (str/blank? s))
    {:error {:code     :parse-error
             :argument arg-name
             :message  (str arg-name " is empty or blank")}}
    (try
      (let [forms-node  (p/parse-string-all s)
            significant (remove n/whitespace-or-comment? (n/children forms-node))]
        (cond
          (empty? significant)
          {:error {:code     :parse-error
                   :argument arg-name
                   :message  (str arg-name " contains no parseable form")}}

          (> (count significant) 1)
          {:error {:code     :parse-error
                   :argument arg-name
                   :message  (str arg-name " must be exactly one form; found "
                                  (count significant) " top-level forms")}}

          :else
          {:ok (first significant)}))
      (catch Exception e
        {:error {:code     :parse-error
                 :argument arg-name
                 :message  (str "could not parse " arg-name ": " (.getMessage e))}}))))

;; ── Candidate discovery ───────────────────────────────────────────────────────

(defn find-candidates
  "Depth-first walk of `file-content`; return a vector of candidate maps whose
   `sexpr` equals the `sexpr` of `old-node`.  Nodes where `sexpr` throws
   (comments, whitespace, uneval nodes, reader macros) are silently skipped.

   Each candidate map: `{:node node :row row :col col :text text}`."
  [old-node file-content]
  (let [target-sexpr (try (n/sexpr old-node) (catch Exception _ ::uneval))
        zloc         (z/of-string file-content {:track-position? true})]
    (if (or (= target-sexpr ::uneval) (nil? zloc))
      []
      (loop [z   zloc
             acc []]
        (if (z/end? z)
          acc
          (let [cand (when-not (n/whitespace-or-comment? (z/node z))
                       (try
                         (let [sx        (z/sexpr z)
                               [row col] (z/position z)]
                           (when (= sx target-sexpr)
                             {:node (z/node z)
                              :row  row
                              :col  col
                              :text (z/string z)}))
                         (catch Exception _ nil)))]
            (recur (z/next z) (if cand (conj acc cand) acc))))))))

;; ── Line-range filter ─────────────────────────────────────────────────────────

(defn apply-line-filter
  "Filter `candidates` to those whose `:row` (start row) falls within the optional
   bounds.  Each bound is independently open when absent (nil).  When neither
   bound is supplied this is a no-op."
  [candidates {:keys [start-line end-line]}]
  (if (and (nil? start-line) (nil? end-line))
    candidates
    (filter (fn [{:keys [row]}]
              (and (or (nil? start-line) (<= start-line row))
                   (or (nil? end-line)   (<= row end-line))))
            candidates)))

;; ── Replacement ───────────────────────────────────────────────────────────────

(defn replace-in
  "Given already-filtered `candidates`, perform the replacement in `file-content`
   and return a result map.  Never touches the filesystem.

   On success the map contains `:content` (the updated file-content string) as
   well as `:status`, `:location`, `:old`, and `:new`.  The extension layer
   writes `:content` to disk and merges `:filename` before JSON serialisation."
  [_old-node new-node file-content candidates]
  (case (count candidates)
    0
    {:status  "error"
     :code    "no-match"
     :message "no matching form found in file"
     :hint    "Try adding or widening the `start-line`/`end-line` range, or verify that `old-string` appears in the file."}

    1
    (let [{:keys [row col text]} (first candidates)
          zloc                   (z/of-string file-content {:track-position? true})
          target                 (loop [z zloc]
                                   (if (z/end? z)
                                     nil
                                     (let [[r c] (z/position z)]
                                       (if (and (= r row) (= c col))
                                         z
                                         (recur (z/next z))))))]
      (if target
        {:status   "ok"
         :location {:line row :column col}
         :old      text
         :new      (n/string new-node)
         :content  (z/root-string (z/replace target new-node))}
        {:status  "error"
         :code    "replace-failed"
         :message "candidate found during scan but could not be located for replacement"}))

    ;; multiple matches
    {:status      "error"
     :code        "ambiguous-match"
     :match-count (count candidates)
     :matches     (mapv (fn [{:keys [row col text]}]
                          {:line row :column col :text text})
                        candidates)
     :message     "multiple matching forms found in file"
     :hint        "Narrow the `start-line`/`end-line` range to isolate the intended occurrence."}))
