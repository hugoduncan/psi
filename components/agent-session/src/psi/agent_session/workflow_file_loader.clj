(ns psi.agent-session.workflow-file-loader
  "Discover, parse, compile, and validate workflow definitions from disk.

   Scans `.psi/workflows/` directories (global + project) for `*.md` files,
   parses them with `workflow-file-parser`, compiles with `workflow-file-compiler`,
   and validates step references and name collisions.

   Directory precedence (later wins on name collision):
   1. `~/.psi/workflows/`          (legacy global fallback)
   2. `~/.psi/agent/workflows/`    (preferred global)
   3. `<project>/.psi/workflows/`  (project-local)"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.workflow-file-compiler :as compiler]
   [psi.agent-session.workflow-file-parser :as parser]))

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

(defn- md-files-in-dir
  "List .md files in a directory. Returns empty seq if dir doesn't exist."
  [dir-path]
  (let [dir (some-> dir-path io/file)]
    (if (and dir (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter (fn [f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".md"))))
           (sort-by #(.getName %))
           vec)
      [])))

(defn- parse-file
  "Parse a single workflow file. Returns parsed map with :source-path added."
  [file]
  (try
    (let [raw (slurp file)
          parsed (parser/parse-workflow-file raw)]
      (assoc parsed :source-path (.getAbsolutePath file)))
    (catch Exception e
      {:error (str "Failed to read file: " (.getMessage e))
       :source-path (.getAbsolutePath file)})))

(defn scan-directory
  "Scan a directory for workflow files. Returns seq of parsed workflow data."
  [dir-path]
  (mapv parse-file (md-files-in-dir dir-path)))

(defn scan-all-directories
  "Scan all workflow directories in precedence order.
   Returns seq of parsed workflow data from all sources."
  [worktree-path]
  (let [dirs (concat (global-workflow-dirs)
                     [(project-workflow-dir worktree-path)])]
    (into [] (mapcat scan-directory) (remove nil? dirs))))

(defn- merge-by-name
  "Merge parsed files by name, later entries win (precedence order).
   Returns a seq of parsed files with duplicates resolved."
  [parsed-files]
  (vals
   (reduce (fn [acc parsed]
             (if-let [n (:name parsed)]
               (assoc acc n parsed)
               ;; Keep errored entries (no name) as-is keyed by source path
               (assoc acc (or (:source-path parsed) (gensym)) parsed)))
           {}
           parsed-files)))

(defn load-workflow-definitions
  "Load all workflow definitions from disk.

   Scans global + project directories, parses, merges by name (later wins),
   compiles, and validates.

   Returns:
   {:definitions {name -> definition}
    :errors      [{:name ... :error ... :source-path ...} ...]
    :warnings    [{:message ...} ...]}"
  [worktree-path]
  (let [all-parsed (scan-all-directories worktree-path)
        merged (merge-by-name all-parsed)
        ;; Separate parse errors from valid parses
        {errored true valid false} (group-by #(boolean (:error %)) merged)
        ;; Compile valid parses
        {:keys [definitions errors]} (compiler/compile-workflow-files valid)
        ;; Validate step references
        ref-result (compiler/validate-step-references definitions)
        ;; Validate name collisions (should be resolved by merge, but check compiled output)
        collision-result (compiler/validate-no-name-collisions definitions)
        ;; Validate judge/routing constraints
        judge-result (compiler/validate-judge-routing definitions)
        ;; Build definition map keyed by name
        def-map (into {} (map (juxt :name identity)) definitions)
        ;; Collect all errors
        all-errors (into (vec (concat
                               (map (fn [p]
                                      {:name (:name p)
                                       :error (:error p)
                                       :source-path (:source-path p)})
                                    errored)
                               errors))
                         (concat
                          (when-not (:valid? ref-result)
                            (mapv (fn [{:keys [definition step missing]}]
                                    {:name definition
                                     :error (str "Step `" step "` references unknown workflow `" missing "`")})
                                  (:errors ref-result)))
                          (when-not (:valid? judge-result)
                            (mapv (fn [{:keys [definition step error target]}]
                                    {:name definition
                                     :error (case error
                                              :on-without-judge
                                              (str "Step `" step "` has `:on` routing table but no `:judge`")
                                              :unknown-goto-target
                                              (str "Step `" step "` has `:goto` target `" target "` which is not a known step")
                                              (str "Step `" step "` has judge/routing error: " error))})
                                  (:errors judge-result)))))
        ;; Warnings for collisions (shouldn't happen after merge, but defensive)
        warnings (when-not (:valid? collision-result)
                   (mapv (fn [dup-name]
                           {:message (str "Duplicate workflow name `" dup-name "` — last definition wins")})
                         (:duplicates collision-result)))]
    {:definitions def-map
     :errors all-errors
     :warnings (vec (or warnings []))}))
