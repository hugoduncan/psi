(ns psi.prompt-assets.skills-discovery-test
  "Tests for multi-source skill discovery, nested discovery, built-in
   materialization, and end-to-end discover/format/invoke."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.prompt-assets.skills :as skills]))

;; ============================================================
;; Test helpers — temp directories with skill files
;; ============================================================

(defn- make-temp-dir
  "Create a temporary directory with a unique name."
  [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- write-skill!
  "Write a SKILL.md file into `dir`/`skill-name`/SKILL.md."
  [dir skill-name content]
  (let [skill-dir (io/file dir skill-name)]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md") content)))

(defn- cleanup-dir!
  "Recursively delete directory and all contents."
  [dir]
  (when (.isDirectory dir)
    (doseq [f (.listFiles dir)]
      (cleanup-dir! f)))
  (.delete dir))

(defn with-temp-skills*
  "Create a temp dir, write skill files, call (f dir), clean up.
   `skill-map` is {\"name\" \"SKILL.md content\" ...} for SKILL.md in subdirs."
  [skill-map f]
  (let [dir (make-temp-dir "psi-skill-test")]
    (doseq [[n c] skill-map]
      (write-skill! dir n c))
    (try (f dir)
         (finally (cleanup-dir! dir)))))

;; ============================================================
;; Multi-source discovery
;; ============================================================

(deftest discover-skills-test
  (testing "discovers from global and project dirs"
    (with-temp-skills*
      {"global-skill" "---\nname: global-skill\ndescription: Global\n---\nBody"}
      (fn [global-dir]
        (with-temp-skills*
          {"project-skill" "---\nname: project-skill\ndescription: Project\n---\nBody"}
          (fn [project-dir]
            (let [{:keys [skills]}
                  (skills/discover-skills
                   {:global-skills-dirs  [(str global-dir)]
                    :project-skills-dirs [(str project-dir)]
                    :config {:built-in-resource-root "psi/test-built-in-skills"}})]
              (is (= 4 (count skills)))
              (is (some #(= "global-skill" (:name %)) skills))
              (is (some #(= "project-skill" (:name %)) skills))
              (is (some #(= "packaged-test-skill" (:name %)) skills))))))))

  (testing "project wins over user on cross-source name collision"
    (with-temp-skills*
      {"shared" "---\nname: shared\ndescription: Global version\n---\nGlobal"}
      (fn [global-dir]
        (with-temp-skills*
          {"shared" "---\nname: shared\ndescription: Project version\n---\nProject"}
          (fn [project-dir]
            (let [{:keys [skills diagnostics]}
                  (skills/discover-skills
                   {:global-skills-dirs  [(str global-dir)]
                    :project-skills-dirs [(str project-dir)]})]
              (is (some #(= "shared" (:name %)) skills))
              (is (= "Project version"
                     (:description (some #(when (= "shared" (:name %)) %) skills))))
              (is (some #(= :collision (:type %)) diagnostics))))))))

  (testing "extra path wins over project, user, and built-in"
    (with-temp-skills*
      {"built-in-shared" "---\nname: built-in-shared\ndescription: Override\n---\nOverride body"}
      (fn [extra-dir]
        (let [{:keys [skills diagnostics]}
              (skills/discover-skills
               {:global-skills-dirs  ["/nonexistent"]
                :project-skills-dirs ["/nonexistent"]
                :extra-paths         [(str extra-dir)]
                :config {:built-in-resource-root "psi/test-built-in-skills"}})
              selected (some #(when (= "built-in-shared" (:name %)) %) skills)]
          (is (= :path (:source selected)))
          (is (= "Override" (:description selected)))
          (is (some #(= :collision (:type %)) diagnostics))))))

  (testing "same-source ties prefer earlier configured container order"
    (with-temp-skills*
      {"shared" "---\nname: shared\ndescription: Earlier global\n---\nEarlier"}
      (fn [earlier-global-dir]
        (with-temp-skills*
          {"shared" "---\nname: shared\ndescription: Later global\n---\nLater"}
          (fn [later-global-dir]
            (let [{:keys [skills diagnostics]}
                  (skills/discover-skills
                   {:global-skills-dirs  [(str earlier-global-dir) (str later-global-dir)]
                    :project-skills-dirs ["/nonexistent"]})
                  selected (some #(when (= "shared" (:name %)) %) skills)
                  collision (some #(when (= :collision (:type %)) %) diagnostics)]
              (is (= :user (:source selected)))
              (is (= "Earlier global" (:description selected)))
              (is (= (str earlier-global-dir "/shared/SKILL.md") (:file-path selected)))
              (is (= {:name "shared"
                      :source :user
                      :path (str later-global-dir "/shared/SKILL.md")}
                     (:shadowed collision)))))))))

  (testing "same-source ties within one container prefer lexicographically earlier canonical skill path"
    (let [dir (make-temp-dir "psi-same-source-tie")
          aaa-dir (io/file dir "aaa-shared")
          zzz-dir (io/file dir "zzz-shared")]
      (.mkdirs aaa-dir)
      (.mkdirs zzz-dir)
      (spit (io/file aaa-dir "SKILL.md")
            "---\nname: shared\ndescription: Earlier canonical path\n---\nAlpha")
      (spit (io/file zzz-dir "SKILL.md")
            "---\nname: shared\ndescription: Later canonical path\n---\nZeta")
      (try
        (let [{:keys [skills diagnostics]}
              (skills/discover-skills
               {:global-skills-dirs  [(str dir)]
                :project-skills-dirs ["/nonexistent"]})
              selected (some #(when (= "shared" (:name %)) %) skills)
              collision (some #(when (= :collision (:type %)) %) diagnostics)]
          (is (= :user (:source selected)))
          (is (= "Earlier canonical path" (:description selected)))
          (is (= (-> (io/file aaa-dir "SKILL.md") .getAbsolutePath io/file .getCanonicalPath)
                 (-> (:file-path selected) io/file .getCanonicalPath)))
          (is (= {:name "shared"
                  :source :user
                  :path (-> (io/file zzz-dir "SKILL.md") .getAbsolutePath io/file .getCanonicalPath)}
                 (update (:shadowed collision) :path #(some-> % io/file .getCanonicalPath)))))
        (finally
          (cleanup-dir! dir)))))

  (testing "disabled flag skips built-in, global, and project, keeps extra-paths"
    (with-temp-skills*
      {"global-skill" "---\nname: global-skill\ndescription: Global\n---\nBody"}
      (fn [global-dir]
        (with-temp-skills*
          {"extra" "---\nname: extra\ndescription: Extra\n---\nBody"}
          (fn [extra-dir]
            (let [{:keys [skills]}
                  (skills/discover-skills
                   {:global-skills-dirs [(str global-dir)]
                    :project-skills-dirs ["/nonexistent"]
                    :extra-paths        [(str extra-dir)]
                    :disabled           true})]
              (is (some #(= "extra" (:name %)) skills))
              (is (= "extra" (:name (first skills))))
              (is (= :path (:source (first skills))))))))))

  (testing "extra-paths are loaded"
    (with-temp-skills*
      {"extra" "---\nname: extra\ndescription: Extra skill\n---\nBody"}
      (fn [extra-dir]
        (let [{:keys [skills]}
              (skills/discover-skills
               {:global-skills-dirs  ["/nonexistent"]
                :project-skills-dirs ["/nonexistent"]
                :extra-paths         [(str extra-dir)]})]
          (is (some #(= "extra" (:name %)) skills))
          (is (= :path (:source (some #(when (= "extra" (:name %)) %) skills))))))))

  (testing "non-existent extra path produces warning"
    (let [{:keys [diagnostics]}
          (skills/discover-skills
           {:global-skills-dirs  ["/nonexistent"]
            :project-skills-dirs ["/nonexistent"]
            :extra-paths         ["/no/such/path"]})]
      (is (some #(str/includes? (:message %) "does not exist") diagnostics)))))

;; ============================================================
;; Progressive Disclosure — system prompt formatting

(deftest nested-skill-discovery-test
  (testing "discovers nested skills in subdirectories"
    (let [dir (make-temp-dir "psi-nested-skill-test")
          ;; Create parent/child skill structure
          parent-dir (io/file dir "parent-skill")
          child-dir  (io/file parent-dir "skills" "child-skill")]
      (.mkdirs parent-dir)
      (.mkdirs child-dir)
      (spit (io/file parent-dir "SKILL.md")
            "---\nname: parent-skill\ndescription: Parent\n---\nParent body")
      (spit (io/file child-dir "SKILL.md")
            "---\nname: child-skill\ndescription: Child\n---\nChild body")
      (try
        (let [{:keys [skills]} (skills/load-skills-from-dir (str dir) :user)]
          (is (= 2 (count skills)))
          (is (some #(= "parent-skill" (:name %)) skills))
          (is (some #(= "child-skill" (:name %)) skills)))
        (finally (cleanup-dir! dir))))))

;; ============================================================
;; End-to-end: discover + format + invoke
;; ============================================================

(deftest built-in-skill-materialization-test
  (testing "packaged built-in skills materialize to a readable deterministic snapshot"
    (let [opts {:config {:built-in-resource-root "psi/test-built-in-skills"}}
          {:keys [dir resource-paths reused?]} (skills/materialize-built-in-skills! opts)
          skill-path (str dir "/packaged-test-skill/SKILL.md")]
      (is (seq resource-paths))
      (is (.exists (io/file skill-path)))
      (is (str/includes? skill-path "/.psi/agent/built-in-skills/"))
      (is (str/includes? (slurp skill-path) "packaged-test-skill"))
      (is (contains? #{true false} reused?))))

  (testing "production built-in skill packaging includes extension-development with readable file semantics"
    (let [source-path "bases/main/resources/psi/skills/extension-development/SKILL.md"
          resource-path "psi/skills/extension-development/SKILL.md"
          opts {:config {:built-in-resource-root "psi/skills"}}
          {:keys [dir resource-paths]} (skills/materialize-built-in-skills! opts)
          built-in-path (str dir "/extension-development/SKILL.md")
          {:keys [skills materialization]} (skills/built-in-skills-discovery opts)
          built-in (some #(when (= "extension-development" (:name %)) %) skills)
          invocation (skills/invoke-skill skills "/skill:extension-development verify")]
      (is (.exists (io/file source-path)))
      (is (some #(= resource-path %) resource-paths))
      (is (.exists (io/file built-in-path)))
      (is (= :built-in (:source built-in)))
      (is (= "extension-development" (:name built-in)))
      (is (str/starts-with? (:file-path built-in) (:dir materialization)))
      (is (str/includes? (slurp (:file-path built-in)) "https://github.com/hugoduncan/psi/blob/main/doc/extension-api.md"))
      (is (some? invocation))
      (is (str/includes? (:content invocation) "Extension development"))))

  (testing "production built-in skill packaging includes workflow with readable file semantics"
    (let [source-path "bases/main/resources/psi/skills/workflow/SKILL.md"
          resource-path "psi/skills/workflow/SKILL.md"
          opts {:config {:built-in-resource-root "psi/skills"}}
          {:keys [dir resource-paths]} (skills/materialize-built-in-skills! opts)
          built-in-path (str dir "/workflow/SKILL.md")
          {:keys [skills materialization]} (skills/built-in-skills-discovery opts)
          built-in (some #(when (= "workflow" (:name %)) %) skills)
          invocation (skills/invoke-skill skills "/skill:workflow update delegate workflow")]
      (is (.exists (io/file source-path)))
      (is (some #(= resource-path %) resource-paths))
      (is (.exists (io/file built-in-path)))
      (is (= :built-in (:source built-in)))
      (is (= "workflow" (:name built-in)))
      (is (str/starts-with? (:file-path built-in) (:dir materialization)))
      (is (str/includes? (slurp (:file-path built-in)) "doc/workflow-grammar.md"))
      (is (str/includes? (slurp (:file-path built-in)) ".psi/workflows/create-task-plan.edn"))
      (is (some? invocation))
      (is (str/includes? (:content invocation) "Workflow"))))

  (testing "snapshot id changes when the packaged resource set changes"
    (let [base-dir (skills/built-in-snapshot-dir {:config {:built-in-resource-root "psi/test-built-in-skills"}})
          changed-dir (skills/built-in-snapshot-dir
                       {:config {:built-in-resource-root "psi/test-built-in-skills"}
                        :resource-paths-override ["psi/test-built-in-skills/packaged-test-skill/SKILL.md"]})]
      (is (not= base-dir changed-dir))))

  (testing "built-in discovery loads materialized packaged skills as ordinary file-backed skills"
    (let [{:keys [skills materialization]} (skills/built-in-skills-discovery {:config {:built-in-resource-root "psi/test-built-in-skills"}})
          built-in (some #(when (= "packaged-test-skill" (:name %)) %) skills)]
      (is (= :built-in (:source built-in)))
      (is (.exists (io/file (:file-path built-in))))
      (is (str/starts-with? (:file-path built-in) (:dir materialization))))))

(deftest end-to-end-discover-format-invoke-test
  (testing "discover skills, format for prompt, then invoke one"
    (with-temp-skills*
      {"coding" (str "---\nname: coding\ndescription: Coding best practices\n---\n"
                     "# Coding Standards\n\nFollow these practices.")}
      (fn [dir]
        (let [{:keys [skills]} (skills/discover-skills
                                {:global-skills-dirs  [(str dir)]
                                 :project-skills-dirs ["/nonexistent"]})
              prompt-section (skills/format-skills-for-prompt skills)]
          ;; Progressive disclosure: only name + description in prompt
          (is (str/includes? prompt-section "<name>coding</name>"))
          (is (str/includes? prompt-section "Coding best practices"))
          (is (not (str/includes? prompt-section "Follow these practices")))

          ;; Full invocation: loads entire content
          (let [result (skills/invoke-skill skills "/skill:coding apply to my project")]
            (is (str/includes? (:content result) "Follow these practices"))
            (is (str/includes? (:content result) "apply to my project"))))))))
