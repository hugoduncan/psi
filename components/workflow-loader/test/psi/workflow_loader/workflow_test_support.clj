(ns psi.workflow-loader.workflow-test-support
  "Shared loader-test fixtures for the workflow-definitions test namespaces.

   Single-sources the loader seam (`with-workflow-dir` temp-dir + the
   `loader/global-workflow-dirs`/`loader/project-workflow-dir` redefs),
   the real-`.psi/workflows` slurp + single-edn loader, and the step/var
   inspection helpers so the seam is defined once and cannot drift between
   `workflow-definitions-test` and `task-209-workflow-definitions-test`."
  (:require
   [clojure.java.io :as io]
   [psi.workflow-loader.core :as loader]))

(defn slurp-workflow-file
  [filename]
  (slurp (io/file (System/getProperty "user.dir")
                  ".psi/workflows"
                  filename)))

(defn with-workflow-dir
  "Write files to a temp dir and call f with the loader result.
   files is a map of filename -> content string."
  [files f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wf-def-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (doseq [[filename content] files]
        (spit (io/file dir filename) content))
      (with-redefs [loader/global-workflow-dirs (constantly [])
                    loader/project-workflow-dir (constantly (.getAbsolutePath dir))]
        (f (loader/load-workflow-definitions (.getAbsolutePath dir))))
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn load-edn-only
  "Load a single edn workflow (no .md refs) from the real .psi/workflows dir."
  [edn-filename f]
  (with-workflow-dir
    {edn-filename (slurp-workflow-file edn-filename)}
    f))

(defn input-var-wired?
  "True if the contribution has :vars with 'input' wired to :workflow-input."
  [contribution]
  (= {:from :workflow-input :path [:input]}
     (get-in contribution [:vars "input"])))

(defn step-has-input-var-wired?
  "True if any template contribution in step has 'input' wired to :workflow-input."
  [step]
  (some (fn [c]
          (and (= :template (:type c))
               (input-var-wired? c)))
        (:contributions step)))

(defn step-template-text
  "Concatenated text of all template contributions in step."
  [step]
  (->> (:contributions step)
       (filter #(= :template (:type %)))
       (map :text)
       (apply str)))
