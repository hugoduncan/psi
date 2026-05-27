(ns psi.workflow-loader.core
  "Discover, parse, compile, and validate workflow definitions from disk.

   Scans `.psi/workflows/` directories (global + project) for `.md` and `.edn`
   files, parses by file kind, compiles with `psi.workflow-loader.compiler`, and
   validates name-collision policy.

   Directory precedence (later wins on same-kind name collision):
   1. `~/.psi/workflows/`          (legacy global fallback)
   2. `~/.psi/agent/workflows/`    (preferred global)
   3. `<project>/.psi/workflows/`  (project-local)"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-loader.parser :as parser]))

(defn global-workflow-dirs
  "Return supported global workflow definition directories in precedence order.
   Later directories win when names collide."
  []
  [(str (System/getProperty "user.home") "/.psi/workflows")
   (str (System/getProperty "user.home") "/.psi/agent/workflows")])

(defn project-workflow-dir
  "Return the project-local workflow directory for a given worktree path."
  [worktree-path]
  (when worktree-path
    (str worktree-path "/.psi/workflows")))

(defn- workflow-file-kind
  [filename]
  (cond
    (str/ends-with? filename ".md") :md
    (str/ends-with? filename ".edn") :edn
    :else nil))

(defn- workflow-files-in-dir
  "List workflow definition files in a directory. Returns empty seq if dir doesn't exist."
  [dir-path]
  (let [dir (some-> dir-path io/file)]
    (if (and dir (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter (fn [f]
                     (and (.isFile f)
                          (some? (workflow-file-kind (.getName f))))))
           (sort-by #(.getName %))
           vec)
      [])))

(defn- parse-file
  "Parse a single workflow file. Returns parsed map with source metadata added."
  [file]
  (try
    (let [raw (slurp file)
          file-kind (workflow-file-kind (.getName file))
          parsed (parser/parse-workflow-file file-kind raw)]
      (assoc parsed
             :source-path (.getAbsolutePath file)
             :file-kind file-kind))
    (catch Exception e
      {:error (str "Failed to read file: " (.getMessage e))
       :source-path (.getAbsolutePath file)
       :file-kind (workflow-file-kind (.getName file))})))

(defn scan-directory
  "Scan a directory for workflow files. Returns seq of parsed workflow data."
  [dir-path]
  (mapv parse-file (workflow-files-in-dir dir-path)))

(defn- scan-all-directories
  [worktree-path]
  (let [dirs (concat (global-workflow-dirs)
                     [(project-workflow-dir worktree-path)])]
    (into [] (mapcat scan-directory) (remove nil? dirs))))

(defn- partition-parsed-files
  [parsed-files]
  (group-by #(boolean (:error %)) parsed-files))

(defn- parsed-file-error
  [parsed]
  {:name (:name parsed)
   :error (:error parsed)
   :source-path (:source-path parsed)})

(defn- mixed-kind-collision-errors
  [parsed-files]
  (->> parsed-files
       (remove :error)
       (group-by :name)
       (keep (fn [[workflow-name entries]]
               (when (and workflow-name
                          (> (count (set (map :file-kind entries))) 1))
                 {:name workflow-name
                  :error (str "Workflow name `"
                              workflow-name
                              "` is defined by both `.md` and `.edn` files")
                  :source-path (mapv :source-path entries)})))
       vec))

(defn- merge-by-name-and-kind
  "Merge parsed files by [name kind], later entries win (precedence order).
   Same-kind duplicates become warnings later; mixed-kind duplicates remain errors."
  [parsed-files]
  (vals
   (reduce (fn [acc parsed]
             (if-let [n (:name parsed)]
               (assoc acc [n (:file-kind parsed)] parsed)
               (assoc acc [(or (:source-path parsed) (gensym)) (:file-kind parsed)] parsed)))
           {}
           parsed-files)))

(defn- duplicate-kind-warnings
  [parsed-files]
  (->> parsed-files
       (group-by (juxt :name :file-kind))
       (keep (fn [[[workflow-name file-kind] entries]]
               (when (and workflow-name (> (count entries) 1))
                 {:message (str "Duplicate workflow name `"
                                workflow-name
                                "` for `."
                                (name file-kind)
                                "` files — last definition wins")})))
       vec))

(defn- compile-and-validate
  [parsed-files]
  (let [{errored true valid false} (partition-parsed-files parsed-files)
        merged-valid (merge-by-name-and-kind valid)
        mixed-kind-errors (mixed-kind-collision-errors merged-valid)
        safe-valid (if (seq mixed-kind-errors)
                     (remove (fn [parsed]
                               (some #(= (:name %) (:name parsed)) mixed-kind-errors))
                             merged-valid)
                     merged-valid)
        {:keys [definitions errors]} (compiler/compile-workflow-files safe-valid)]
    {:definitions definitions
     :errors (vec (concat (map parsed-file-error errored)
                          mixed-kind-errors
                          errors))
     :warnings (duplicate-kind-warnings valid)}))

(defn- definition-map
  [definitions]
  (into {} (map (juxt :name identity)) definitions))

(defn- load-result
  [{:keys [definitions errors warnings]}]
  {:definitions (definition-map definitions)
   :errors errors
   :warnings warnings})

(defn load-workflow-definitions
  "Load all workflow definitions from disk.

   Scans global + project directories, parses, applies same-kind precedence,
   compiles, and validates.

   Returns:
   {:definitions {name -> definition}
    :errors      [{:name ... :error ... :source-path ...} ...]
    :warnings    [{:message ...} ...]}"
  [worktree-path]
  (-> worktree-path
      scan-all-directories
      compile-and-validate
      load-result))