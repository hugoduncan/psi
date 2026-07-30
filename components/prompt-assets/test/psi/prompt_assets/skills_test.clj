(ns psi.prompt-assets.skills-test
  "Tests for skill discovery, parsing, validation, progressive disclosure,
   invocation, and EQL introspection."
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

(defn- write-root-skill!
  "Write a .md file directly into `dir`/`name`.md."
  [dir filename content]
  (spit (io/file dir filename) content))

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
;; Frontmatter Extraction
;; ============================================================

(deftest extract-frontmatter-test
  (testing "parses YAML frontmatter from skill file"
    (let [raw (str "---\n"
                   "name: my-skill\n"
                   "description: A test skill\n"
                   "---\n"
                   "# Skill content")
          {:keys [frontmatter body]} (skills/extract-frontmatter raw)]
      (is (= "my-skill" (:name frontmatter)))
      (is (= "A test skill" (:description frontmatter)))
      (is (= "# Skill content" body))))

  (testing "handles disable-model-invocation"
    (let [raw "---\nname: hidden\ndescription: Hidden skill\ndisable-model-invocation: true\n---\nBody"
          {:keys [frontmatter]} (skills/extract-frontmatter raw)]
      (is (= "true" (:disable-model-invocation frontmatter)))))

  (testing "handles allowed-tools"
    (let [raw "---\nname: tooled\ndescription: Has tools\nallowed-tools: bash read write\n---\nBody"
          {:keys [frontmatter]} (skills/extract-frontmatter raw)]
      (is (= "bash read write" (:allowed-tools frontmatter))))))

;; ============================================================
;; Validation
;; ============================================================

(deftest validate-name-test
  (let [config skills/default-config]

    (testing "valid name passes"
      (let [result (skills/validate-name "my-skill" "my-skill" config)]
        (is (empty? (:warnings result)))
        (is (empty? (:errors result)))))

    (testing "name mismatch with parent dir warns"
      (let [result (skills/validate-name "my-skill" "other-dir" config)]
        (is (some #(str/includes? % "doesn't match") (:warnings result)))))

    (testing "invalid characters warn"
      (let [result (skills/validate-name "My_Skill" "My_Skill" config)]
        (is (some #(str/includes? % "invalid characters") (:warnings result)))))

    (testing "consecutive hyphens warn"
      (let [result (skills/validate-name "my--skill" "my--skill" config)]
        (is (some #(str/includes? % "consecutive hyphens") (:warnings result)))))

    (testing "exceeds max length warns"
      (let [long-name (apply str (repeat 65 "a"))
            result    (skills/validate-name long-name long-name config)]
        (is (some #(str/includes? % "exceeds") (:warnings result)))))))

(deftest validate-description-test
  (let [config skills/default-config]

    (testing "missing description is fatal"
      (let [result (skills/validate-description nil config)]
        (is (seq (:errors result)))))

    (testing "blank description is fatal"
      (let [result (skills/validate-description "  " config)]
        (is (seq (:errors result)))))

    (testing "valid description passes"
      (let [result (skills/validate-description "A valid description" config)]
        (is (empty? (:errors result)))
        (is (empty? (:warnings result)))))

    (testing "exceeds max length warns"
      (let [long-desc (apply str (repeat 1025 "a"))
            result    (skills/validate-description long-desc config)]
        (is (some #(str/includes? % "exceeds") (:warnings result)))
        (is (empty? (:errors result)))))))

(deftest validate-skill-test
  (let [config skills/default-config]

    (testing "valid skill passes"
      (let [parsed {:name "my-skill" :description "A skill" :parent-dir-name "my-skill"}
            result (skills/validate-skill parsed config)]
        (is (:valid result))
        (is (empty? (:errors result)))))

    (testing "missing description is invalid"
      (let [parsed {:name "my-skill" :description nil :parent-dir-name "my-skill"}
            result (skills/validate-skill parsed config)]
        (is (not (:valid result)))
        (is (seq (:errors result)))))))

;; ============================================================
;; Parsing
;; ============================================================

(deftest parse-skill-file-test
  (testing "parses skill file with frontmatter"
    (with-temp-skills*
      {"my-skill" "---\nname: my-skill\ndescription: A test skill\n---\n# Content here"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/my-skill/SKILL.md"))]
          (is (= "my-skill" (:name parsed)))
          (is (= "A test skill" (:description parsed)))
          (is (= "my-skill" (:parent-dir-name parsed)))
          (is (str/ends-with? (:file-path parsed) "SKILL.md"))
          (is (= "# Content here" (:body parsed)))
          (is (false? (:disable-model-invocation parsed)))))))

  (testing "falls back to parent dir name when no name in frontmatter"
    (with-temp-skills*
      {"fallback-name" "---\ndescription: No name field\n---\nBody"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/fallback-name/SKILL.md"))]
          (is (= "fallback-name" (:name parsed)))))))

  (testing "parses disable-model-invocation flag"
    (with-temp-skills*
      {"hidden" "---\nname: hidden\ndescription: Hidden\ndisable-model-invocation: true\n---\nBody"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/hidden/SKILL.md"))]
          (is (true? (:disable-model-invocation parsed)))))))

  (testing "advertise defaults to true when absent"
    (with-temp-skills*
      {"plain" "---\nname: plain\ndescription: Plain\n---\nBody"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/plain/SKILL.md"))]
          (is (true? (:advertise parsed)))))))

  (testing "advertise: false parses to false"
    (with-temp-skills*
      {"quiet" "---\nname: quiet\ndescription: Quiet\nadvertise: false\n---\nBody"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/quiet/SKILL.md"))]
          (is (false? (:advertise parsed)))))))

  (testing "advertise typo defaults to advertised (only literal false disables)"
    (with-temp-skills*
      {"typo" "---\nname: typo\ndescription: Typo\nadvertise: flase\n---\nBody"}
      (fn [dir]
        (let [parsed (skills/parse-skill-file (str dir "/typo/SKILL.md"))]
          (is (true? (:advertise parsed)))))))

  (testing "returns nil for non-existent file"
    (is (nil? (skills/parse-skill-file "/nonexistent/path/SKILL.md")))))

;; ============================================================
;; Skill construction
;; ============================================================

(deftest skill-construction-test
  (testing "->skill produces canonical skill map"
    (let [parsed {:name "test" :description "Test skill"
                  :file-path "/path/SKILL.md" :base-dir "/path"
                  :disable-model-invocation false}
          skill  (skills/->skill parsed :user)]
      (is (= "test" (:name skill)))
      (is (= "Test skill" (:description skill)))
      (is (= "/path/SKILL.md" (:file-path skill)))
      (is (= "/path" (:base-dir skill)))
      (is (= :user (:source skill)))
      (is (false? (:disable-model-invocation skill)))))
  (testing "->skill propagates explicit :advertise false"
    (let [parsed {:name "test" :description "Test skill"
                  :file-path "/path/SKILL.md" :base-dir "/path"
                  :disable-model-invocation false :advertise false}
          skill  (skills/->skill parsed :user)]
      (is (false? (:advertise skill)))))
  (testing "->skill leaves :advertise absent when not parsed (treated as advertised)"
    (let [parsed {:name "test" :description "Test skill"
                  :file-path "/path/SKILL.md" :base-dir "/path"
                  :disable-model-invocation false}
          skill  (skills/->skill parsed :user)]
      (is (nil? (:advertise skill)))
      (is (not (false? (:advertise skill)))))))

;; ============================================================
;; Directory loading
;; ============================================================

(deftest load-skills-from-dir-test
  (testing "loads skills from subdirectories with SKILL.md"
    (with-temp-skills*
      {"alpha" "---\nname: alpha\ndescription: Alpha skill\n---\nAlpha body"
       "beta"  "---\nname: beta\ndescription: Beta skill\n---\nBeta body"}
      (fn [dir]
        (let [{:keys [skills]} (skills/load-skills-from-dir (str dir) :user)]
          (is (= 2 (count skills)))
          (is (some #(= "alpha" (:name %)) skills))
          (is (some #(= "beta" (:name %)) skills))
          (is (every? #(= :user (:source %)) skills))))))

  (testing "loads direct .md files from root when include-root-files? is true"
    (let [dir (make-temp-dir "psi-root-skill-test")]
      (write-root-skill! dir "direct.md"
                         "---\nname: direct\ndescription: Direct skill\n---\nDirect body")
      (try
        (let [{:keys [skills]} (skills/load-skills-from-dir (str dir) :user true)]
          (is (some #(= "direct" (:name %)) skills))
          (is (= "direct" (:name (first skills)))))
        (finally (cleanup-dir! dir)))))

  (testing "returns empty for non-existent directory"
    (let [{:keys [skills]} (skills/load-skills-from-dir "/nonexistent" :user)]
      (is (empty? skills))))

  (testing "skips skills with missing description"
    (with-temp-skills*
      {"no-desc" "---\nname: no-desc\n---\nBody but no description"}
      (fn [dir]
        (let [{:keys [skills diagnostics]} (skills/load-skills-from-dir (str dir) :user)]
          (is (empty? skills))
          (is (some #(= :error (:type %)) diagnostics)))))))

;; ============================================================

(deftest format-skills-for-prompt-test
  (testing "formats visible skills as XML in canonical skill-name order"
    (let [all-skills [{:name "beta" :description "Beta skill"
                       :file-path "/beta/SKILL.md" :base-dir "/beta"
                       :source :project :disable-model-invocation false}
                      {:name "alpha" :description "Alpha skill"
                       :file-path "/alpha/SKILL.md" :base-dir "/alpha"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "<available_skills>"))
      (is (str/includes? result "</available_skills>"))
      (is (< (str/index-of result "<name>alpha</name>")
             (str/index-of result "<name>beta</name>")))
      (is (str/includes? result "<description>Alpha skill</description>"))
      (is (str/includes? result "<location>/alpha/SKILL.md</location>"))))

  (testing "excludes skills with disable-model-invocation=true"
    (let [all-skills [{:name "visible" :description "Visible"
                       :file-path "/v/SKILL.md" :base-dir "/v"
                       :source :user :disable-model-invocation false}
                      {:name "hidden" :description "Hidden"
                       :file-path "/h/SKILL.md" :base-dir "/h"
                       :source :user :disable-model-invocation true}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "<name>visible</name>"))
      (is (not (str/includes? result "<name>hidden</name>")))))

  (testing "excludes skills with advertise false"
    (let [all-skills [{:name "visible" :description "Visible"
                       :file-path "/v/SKILL.md" :base-dir "/v"
                       :source :user :disable-model-invocation false :advertise true}
                      {:name "internal" :description "Internal"
                       :file-path "/i/SKILL.md" :base-dir "/i"
                       :source :user :disable-model-invocation false :advertise false}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "<name>visible</name>"))
      (is (not (str/includes? result "<name>internal</name>")))))

  (testing "absent :advertise keeps a skill advertised"
    (let [all-skills [{:name "legacy" :description "Legacy"
                       :file-path "/l/SKILL.md" :base-dir "/l"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "<name>legacy</name>"))))

  (testing "returns empty string when no visible skills"
    (let [all-skills [{:name "hidden" :description "Hidden"
                       :file-path "/h/SKILL.md" :base-dir "/h"
                       :source :user :disable-model-invocation true}]]
      (is (= "" (skills/format-skills-for-prompt all-skills)))))

  (testing "returns empty string for empty skills"
    (is (= "" (skills/format-skills-for-prompt []))))

  (testing "escapes XML special characters"
    (let [all-skills [{:name "amp" :description "Uses <special> & \"chars\""
                       :file-path "/a/SKILL.md" :base-dir "/a"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "&amp;"))
      (is (str/includes? result "&lt;special&gt;"))
      (is (str/includes? result "&quot;chars&quot;")))))

(deftest format-skills-for-prompt-lambda-test
  (testing "formats visible skills in lambda notation"
    (let [all-skills [{:name "alpha" :description "Alpha skill"
                       :file-path "/alpha/SKILL.md" :base-dir "/alpha"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt-lambda all-skills)]
      (is (str/includes? result "λ skills."))
      (is (str/includes? result "alpha → Alpha skill @ /alpha/SKILL.md"))))

  (testing "uses :lambda-description when present"
    (let [all-skills [{:name "alpha" :description "Alpha skill"
                       :lambda-description "λx. alpha(x)"
                       :file-path "/alpha/SKILL.md" :base-dir "/alpha"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt-lambda all-skills)]
      (is (str/includes? result "alpha → λx. alpha(x) @ /alpha/SKILL.md"))))

  (testing "excludes skills with advertise false"
    (let [all-skills [{:name "visible" :description "Visible"
                       :file-path "/v/SKILL.md" :base-dir "/v"
                       :source :user :disable-model-invocation false :advertise true}
                      {:name "internal" :description "Internal"
                       :file-path "/i/SKILL.md" :base-dir "/i"
                       :source :user :disable-model-invocation false :advertise false}]
          result (skills/format-skills-for-prompt-lambda all-skills)]
      (is (str/includes? result "visible → "))
      (is (not (str/includes? result "internal → ")))))

  (testing "excludes skills with disable-model-invocation=true"
    (let [all-skills [{:name "visible" :description "Visible"
                       :file-path "/v/SKILL.md" :base-dir "/v"
                       :source :user :disable-model-invocation false}
                      {:name "hidden" :description "Hidden"
                       :file-path "/h/SKILL.md" :base-dir "/h"
                       :source :user :disable-model-invocation true}]
          result (skills/format-skills-for-prompt-lambda all-skills)]
      (is (str/includes? result "visible → "))
      (is (not (str/includes? result "hidden → ")))))

  (testing "absent :advertise keeps a skill advertised"
    (let [all-skills [{:name "legacy" :description "Legacy"
                       :file-path "/l/SKILL.md" :base-dir "/l"
                       :source :user :disable-model-invocation false}]
          result (skills/format-skills-for-prompt-lambda all-skills)]
      (is (str/includes? result "legacy → Legacy @ /l/SKILL.md"))))

  (testing "returns nil when no visible skills"
    (let [all-skills [{:name "hidden" :description "Hidden"
                       :file-path "/h/SKILL.md" :base-dir "/h"
                       :source :user :disable-model-invocation true}]]
      (is (nil? (skills/format-skills-for-prompt-lambda all-skills)))))

  (testing "returns nil for empty skills"
    (is (nil? (skills/format-skills-for-prompt-lambda [])))))

;; ============================================================
;; Skill command parsing
;; ============================================================

(deftest parse-skill-command-test
  (testing "parses /skill:name with args"
    (is (= {:skill-name "my-skill" :args-text "arg1 arg2"}
           (skills/parse-skill-command "/skill:my-skill arg1 arg2"))))

  (testing "parses /skill:name without args"
    (is (= {:skill-name "my-skill" :args-text ""}
           (skills/parse-skill-command "/skill:my-skill"))))

  (testing "returns nil for non-skill command"
    (is (nil? (skills/parse-skill-command "/help")))
    (is (nil? (skills/parse-skill-command "regular text")))
    (is (nil? (skills/parse-skill-command nil)))
    (is (nil? (skills/parse-skill-command "")))))

;; ============================================================
;; Skill lookup
;; ============================================================

(deftest find-skill-test
  (let [all-skills [{:name "alpha" :description "Alpha" :file-path "/a/SKILL.md"
                     :base-dir "/a" :source :user :disable-model-invocation false}
                    {:name "beta" :description "Beta" :file-path "/b/SKILL.md"
                     :base-dir "/b" :source :project :disable-model-invocation false}]]

    (testing "finds by name"
      (is (= "Alpha" (:description (skills/find-skill all-skills "alpha")))))

    (testing "returns nil for unknown name"
      (is (nil? (skills/find-skill all-skills "gamma"))))))

;; ============================================================
;; Invocation
;; ============================================================

(deftest invoke-skill-test
  (testing "expands /skill:name command with file content"
    (with-temp-skills*
      {"test-skill" "---\nname: test-skill\ndescription: A test skill\n---\n# Skill Content\nDetailed instructions"}
      (fn [dir]
        (let [all-skills [{:name "test-skill" :description "A test skill"
                           :file-path (str dir "/test-skill/SKILL.md")
                           :base-dir (str dir "/test-skill")
                           :source :user :disable-model-invocation false}]
              result (skills/invoke-skill all-skills "/skill:test-skill do the thing")]
          (is (some? result))
          (is (= "test-skill" (:skill-name result)))
          (is (str/includes? (:content result) "<skill name=\"test-skill\""))
          (is (str/includes? (:content result) "location="))
          (is (str/includes? (:content result) "# Skill Content"))
          (is (str/includes? (:content result) "do the thing"))))))

  (testing "returns nil for unknown skill"
    (let [all-skills [{:name "known" :description "Known"
                       :file-path "/k/SKILL.md" :base-dir "/k"
                       :source :user :disable-model-invocation false}]]
      (is (nil? (skills/invoke-skill all-skills "/skill:unknown")))))

  (testing "hidden skills are still invocable"
    (with-temp-skills*
      {"hidden-skill" "---\nname: hidden-skill\ndescription: Hidden\ndisable-model-invocation: true\n---\nHidden content"}
      (fn [dir]
        (let [all-skills [{:name "hidden-skill" :description "Hidden"
                           :file-path (str dir "/hidden-skill/SKILL.md")
                           :base-dir (str dir "/hidden-skill")
                           :source :user :disable-model-invocation true}]
              result (skills/invoke-skill all-skills "/skill:hidden-skill")]
          (is (some? result))
          (is (str/includes? (:content result) "Hidden content"))))))

  (testing "non-advertised skills are still findable and invocable by name"
    (with-temp-skills*
      {"quiet-skill" "---\nname: quiet-skill\ndescription: Quiet\nadvertise: false\n---\nQuiet content"}
      (fn [dir]
        (let [all-skills [{:name "quiet-skill" :description "Quiet"
                           :file-path (str dir "/quiet-skill/SKILL.md")
                           :base-dir (str dir "/quiet-skill")
                           :source :user :disable-model-invocation false
                           :advertise false}]]
          ;; Dropped from the system context prompt.
          (is (not (str/includes? (skills/format-skills-for-prompt all-skills)
                                  "<name>quiet-skill</name>")))
          ;; Still registered (findable) and invocable by name.
          (is (some? (skills/find-skill all-skills "quiet-skill")))
          (let [result (skills/invoke-skill all-skills "/skill:quiet-skill")]
            (is (some? result))
            (is (str/includes? (:content result) "Quiet content")))))))

  (testing "returns nil for non-skill commands"
    (is (nil? (skills/invoke-skill [] "/help")))
    (is (nil? (skills/invoke-skill [] "regular text")))))

;; ============================================================
;; Introspection helpers
;; ============================================================

(deftest skill-summary-test
  (testing "summarizes skills in canonical skill-name order"
    (let [all-skills [{:name "c" :description "C" :source :user :disable-model-invocation true}
                      {:name "a" :description "A" :source :user :disable-model-invocation false}
                      {:name "b" :description "B" :source :project :disable-model-invocation false}]
          summary (skills/skill-summary all-skills)]
      (is (= 3 (:skill-count summary)))
      (is (= 2 (:visible-count summary)))
      (is (= 1 (:hidden-count summary)))
      (is (= ["a" "b" "c"] (mapv :name (:skills summary))))))
  (testing "advertise: false counts as hidden, not visible"
    (let [all-skills [{:name "a" :description "A" :source :user :disable-model-invocation false :advertise false}
                      {:name "b" :description "B" :source :user :disable-model-invocation false :advertise true}]
          summary (skills/skill-summary all-skills)]
      (is (= 1 (:visible-count summary)))
      (is (= 1 (:hidden-count summary))))))

(deftest skill-names-test
  (testing "returns canonical name vector"
    (let [all-skills [{:name "y" :description "Y" :source :user :disable-model-invocation false}
                      {:name "x" :description "X" :source :user :disable-model-invocation false}]]
      (is (= ["x" "y"] (skills/skill-names all-skills))))))

(deftest skills-by-source-test
  (testing "groups by source with each source group in canonical skill-name order"
    (let [all-skills [{:name "z" :description "Z" :source :user :disable-model-invocation false}
                      {:name "b" :description "B" :source :project :disable-model-invocation false}
                      {:name "a" :description "A" :source :user :disable-model-invocation false}
                      {:name "c" :description "C" :source :project :disable-model-invocation false}]
          grouped (skills/skills-by-source all-skills)]
      (is (= ["a" "z"] (mapv :name (:user grouped))))
      (is (= ["b" "c"] (mapv :name (:project grouped)))))))

(deftest visible-hidden-skills-test
  (testing "visible-skills excludes hidden and returns canonical order"
    (let [all-skills [{:name "z" :description "Z" :source :user :disable-model-invocation false}
                      {:name "h" :description "H" :source :user :disable-model-invocation true}
                      {:name "a" :description "A" :source :user :disable-model-invocation false}]]
      (is (= ["a" "z"] (mapv :name (skills/visible-skills all-skills))))))

  (testing "hidden-skills returns only hidden in canonical order"
    (let [all-skills [{:name "v" :description "V" :source :user :disable-model-invocation false}
                      {:name "z-hidden" :description "Z" :source :user :disable-model-invocation true}
                      {:name "a-hidden" :description "A" :source :user :disable-model-invocation true}]]
      (is (= ["a-hidden" "z-hidden"] (mapv :name (skills/hidden-skills all-skills))))))

  (testing "advertise: false partitions as hidden, not visible"
    (let [all-skills [{:name "shown" :description "S" :source :user :disable-model-invocation false :advertise true}
                      {:name "unadvertised" :description "U" :source :user :disable-model-invocation false :advertise false}]]
      (is (= ["shown"] (mapv :name (skills/visible-skills all-skills))))
      (is (= ["unadvertised"] (mapv :name (skills/hidden-skills all-skills)))))))

(deftest enrich-skill-test
  (testing "adds is-available-to-model"
    (let [skill {:name "test" :description "Test" :source :user :disable-model-invocation false}
          enriched (skills/enrich-skill skill)]
      (is (true? (:is-available-to-model enriched)))))

  (testing "hidden skill is not available to model"
    (let [skill {:name "test" :description "Test" :source :user :disable-model-invocation true}
          enriched (skills/enrich-skill skill)]
      (is (false? (:is-available-to-model enriched)))))

  (testing "advertise: false skill is not available to model"
    (let [skill {:name "test" :description "Test" :source :user :disable-model-invocation false :advertise false}
          enriched (skills/enrich-skill skill)]
      (is (false? (:is-available-to-model enriched))))))

;; ============================================================
;; EQL Introspection (resolvers)
;; ============================================================

