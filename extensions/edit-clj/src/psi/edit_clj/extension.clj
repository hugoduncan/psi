(ns psi.edit-clj.extension
  "psi/edit-clj extension entry point.

   Registers the `edit-clj` tool via `(:register-tool api)`.  All file I/O and
   JSON serialisation lives here; the pure structural logic lives in
   `psi.edit-clj.core`."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [psi.edit-clj.core :as core]))

;; ── Path resolution ───────────────────────────────────────────────────────────

(defn- resolve-path
  "Resolve `filename` against `cwd` when relative; return absolute path string.
   When `cwd` is nil, relative paths resolve against the process working directory."
  [filename cwd]
  (let [f (io/file filename)]
    (if (or (.isAbsolute f) (nil? cwd))
      (str f)
      (str (io/file cwd filename)))))

;; ── Tool execution ────────────────────────────────────────────────────────────

(defn- execute
  "Validate, find, filter, replace, write, and return a JSON result string."
  [args opts]
  ;; Validation order per design contract: old-string → new-string → file
  (let [filename   (get args "filename")
        old-string (get args "old-string")
        new-string (get args "new-string")
        start-line (get args "start-line")
        end-line   (get args "end-line")
        cwd        (:cwd opts)
        old-result (core/parse-single-form old-string "old-string")]
    (if (:error old-result)
      (json/generate-string (assoc (:error old-result) :status "error"))
      (let [new-result (core/parse-single-form new-string "new-string")]
        (if (:error new-result)
          (json/generate-string (assoc (:error new-result) :status "error"))
          (let [resolved (resolve-path filename cwd)
                f        (io/file resolved)]
            (if-not (.canRead f)
              (json/generate-string {:status   "error"
                                     :code     "file-not-found"
                                     :filename resolved
                                     :message  (str "file not found or not readable: " resolved)})
              (let [file-content (slurp f)
                    new-node     (:ok new-result)
                    candidates   (core/find-candidates (:ok old-result) file-content)
                    filtered     (core/apply-line-filter candidates
                                                         {:start-line start-line
                                                          :end-line   end-line})
                    result       (core/replace-in new-node new-string filtered)]
                (if (= "ok" (:status result))
                  (do
                    (spit f (:content result))
                    (json/generate-string (-> result
                                              (assoc :filename resolved)
                                              (dissoc :content))))
                  (json/generate-string (assoc result :filename resolved)))))))))))

;; ── Tool definition ───────────────────────────────────────────────────────────

(def ^:private tool-def
  {:name        "edit-clj"
   :description "Replace text in a file by structural equality; old-string and new-string must each be one complete, parseable form."
   :parameters  {:type       "object"
                 :properties {"filename"   {:type        "string"
                                            :description "Path to the Clojure (.clj, .cljc, .bb .edn, etc) source file; relative paths resolve against the session worktree."}
                              "old-string" {:type        "string"
                                            :description "Exactly one complete Clojure form; matched against file nodes by sexpr equality."}
                              "new-string" {:type        "string"
                                            :description "Exactly one complete Clojure form; replaces the matched node verbatim."}
                              "start-line" {:type        "integer"
                                            :description "1-indexed first line of the match window (inclusive, optional)."}
                              "end-line"   {:type        "integer"
                                            :description "1-indexed last line of the match window (inclusive, optional)."}}
                 :required   ["filename" "old-string" "new-string"]}
   :execute     (fn
                  ([args]      (execute args nil))
                  ([args opts] (execute args opts)))})

;; ── Extension entry point ─────────────────────────────────────────────────────

(defn init
  [api]
  ((:register-tool api) tool-def))
