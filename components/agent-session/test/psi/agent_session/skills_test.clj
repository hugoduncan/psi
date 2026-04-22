(ns psi.agent-session.skills-test
  "Tests for skill discovery, parsing, validation, progressive disclosure,
   invocation, and EQL introspection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.core :as session-core]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

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
      (is (false? (:disable-model-invocation skill))))))

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
          (is (= 1 (count skills)))
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
                    :project-skills-dirs [(str project-dir)]})]
              (is (= 2 (count skills)))
              (is (some #(= "global-skill" (:name %)) skills))
              (is (some #(= "project-skill" (:name %)) skills))))))))

  (testing "first-discovered wins on name collision"
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
              (is (= 1 (count skills)))
              (is (= "Global version" (:description (first skills))))
              (is (some #(= :collision (:type %)) diagnostics))))))))

  (testing "extra-paths are loaded"
    (with-temp-skills*
      {"extra" "---\nname: extra\ndescription: Extra skill\n---\nBody"}
      (fn [extra-dir]
        (let [{:keys [skills]}
              (skills/discover-skills
               {:global-skills-dirs  ["/nonexistent"]
                :project-skills-dirs ["/nonexistent"]
                :extra-paths         [(str extra-dir)]})]
          (is (= 1 (count skills)))
          (is (= :path (:source (first skills))))))))

  (testing "disabled flag skips global/project, keeps extra-paths"
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
              (is (= 1 (count skills)))
              (is (= "extra" (:name (first skills))))))))))

  (testing "non-existent extra path produces warning"
    (let [{:keys [diagnostics]}
          (skills/discover-skills
           {:global-skills-dirs  ["/nonexistent"]
            :project-skills-dirs ["/nonexistent"]
            :extra-paths         ["/no/such/path"]})]
      (is (some #(str/includes? (:message %) "does not exist") diagnostics)))))

;; ============================================================
;; Progressive Disclosure — system prompt formatting
;; ============================================================

(deftest format-skills-for-prompt-test
  (testing "formats visible skills as XML"
    (let [all-skills [{:name "alpha" :description "Alpha skill"
                       :file-path "/alpha/SKILL.md" :base-dir "/alpha"
                       :source :user :disable-model-invocation false}
                      {:name "beta" :description "Beta skill"
                       :file-path "/beta/SKILL.md" :base-dir "/beta"
                       :source :project :disable-model-invocation false}]
          result (skills/format-skills-for-prompt all-skills)]
      (is (str/includes? result "<available_skills>"))
      (is (str/includes? result "</available_skills>"))
      (is (str/includes? result "<name>alpha</name>"))
      (is (str/includes? result "<name>beta</name>"))
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
      (is (str/includes? result "visible"))
      (is (not (str/includes? result "<name>hidden</name>")))))

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

  (testing "returns nil for non-skill commands"
    (is (nil? (skills/invoke-skill [] "/help")))
    (is (nil? (skills/invoke-skill [] "regular text")))))

;; ============================================================
;; Introspection helpers
;; ============================================================

(deftest skill-summary-test
  (testing "summarizes skills"
    (let [all-skills [{:name "a" :description "A" :source :user :disable-model-invocation false}
                      {:name "b" :description "B" :source :project :disable-model-invocation false}
                      {:name "c" :description "C" :source :user :disable-model-invocation true}]
          summary (skills/skill-summary all-skills)]
      (is (= 3 (:skill-count summary)))
      (is (= 2 (:visible-count summary)))
      (is (= 1 (:hidden-count summary)))
      (is (= 3 (count (:skills summary)))))))

(deftest skill-names-test
  (testing "returns name vector"
    (let [all-skills [{:name "x" :description "X" :source :user :disable-model-invocation false}
                      {:name "y" :description "Y" :source :user :disable-model-invocation false}]]
      (is (= ["x" "y"] (skills/skill-names all-skills))))))

(deftest skills-by-source-test
  (testing "groups by source"
    (let [all-skills [{:name "a" :description "A" :source :user :disable-model-invocation false}
                      {:name "b" :description "B" :source :project :disable-model-invocation false}
                      {:name "c" :description "C" :source :user :disable-model-invocation false}]
          grouped (skills/skills-by-source all-skills)]
      (is (= 2 (count (:user grouped))))
      (is (= 1 (count (:project grouped)))))))

(deftest visible-hidden-skills-test
  (testing "visible-skills excludes hidden"
    (let [all-skills [{:name "v" :description "V" :source :user :disable-model-invocation false}
                      {:name "h" :description "H" :source :user :disable-model-invocation true}]]
      (is (= 1 (count (skills/visible-skills all-skills))))
      (is (= "v" (:name (first (skills/visible-skills all-skills)))))))

  (testing "hidden-skills returns only hidden"
    (let [all-skills [{:name "v" :description "V" :source :user :disable-model-invocation false}
                      {:name "h" :description "H" :source :user :disable-model-invocation true}]]
      (is (= 1 (count (skills/hidden-skills all-skills))))
      (is (= "h" (:name (first (skills/hidden-skills all-skills))))))))

(deftest enrich-skill-test
  (testing "adds is-available-to-model"
    (let [skill {:name "test" :description "Test" :source :user :disable-model-invocation false}
          enriched (skills/enrich-skill skill)]
      (is (true? (:is-available-to-model enriched)))))

  (testing "hidden skill is not available to model"
    (let [skill {:name "test" :description "Test" :source :user :disable-model-invocation true}
          enriched (skills/enrich-skill skill)]
      (is (false? (:is-available-to-model enriched))))))

;; ============================================================
;; EQL Introspection (resolvers)
;; ============================================================

(deftest skill-eql-introspection-test
  (let [all-skills [{:name "alpha"
                     :description "Alpha skill"
                     :file-path "/alpha/SKILL.md"
                     :base-dir "/alpha"
                     :source :user
                     :disable-model-invocation false}
                    {:name "beta"
                     :description "Beta skill"
                     :file-path "/beta/SKILL.md"
                     :base-dir "/beta"
                     :source :project
                     :disable-model-invocation true}]
        ctx     (session-core/create-context
                 {:session-defaults {:skills all-skills}})
        sd      (session-core/new-session-in! ctx nil {})
        session-id (:session-id sd)]

    (testing "query skill count via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/count])]
        (is (= 2 (:psi.skill/count result)))))

    (testing "query visible/hidden counts via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/visible-count
                                                          :psi.skill/hidden-count])]
        (is (= 1 (:psi.skill/visible-count result)))
        (is (= 1 (:psi.skill/hidden-count result)))))

    (testing "query skill names via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.skill/names])]
        (is (= ["alpha" "beta"] (:psi.skill/names result)))))

    (testing "query skill summary via EQL"
      (let [result  (session-core/query-in ctx session-id [:psi.skill/summary])
            summary (:psi.skill/summary result)]
        (is (= 2 (:skill-count summary)))
        (is (= 1 (:visible-count summary)))
        (is (= 1 (:hidden-count summary)))))

    (testing "query skills by source via EQL"
      (let [result  (session-core/query-in ctx session-id [:psi.skill/by-source])
            grouped (:psi.skill/by-source result)]
        (is (= 1 (count (:user grouped))))
        (is (= 1 (count (:project grouped))))))))

(deftest skill-detail-eql-test
  (let [all-skills [{:name "alpha"
                     :description "Alpha skill"
                     :file-path "/alpha/SKILL.md"
                     :base-dir "/alpha"
                     :source :user
                     :disable-model-invocation false}]
        ctx     (session-core/create-context
                 {:session-defaults {:skills all-skills}})
        sd      (session-core/new-session-in! ctx nil {})
        env     (pci/register resolvers/all-resolvers)
        result  (p.eql/process env
                               {:psi/agent-session-ctx ctx
                                :psi.agent-session/session-id (:session-id sd)
                                :psi.skill/name "alpha"}
                               [:psi.skill/detail])
        detail (:psi.skill/detail result)]

    (testing "detail includes enriched fields"
      (is (= "alpha" (:name detail)))
      (is (= "Alpha skill" (:description detail)))
      (is (true? (:is-available-to-model detail))))

    (testing "detail for unknown skill is nil"
      (let [r (p.eql/process env
                             {:psi/agent-session-ctx ctx
                              :psi.agent-session/session-id (:session-id sd)
                              :psi.skill/name "unknown"}
                             [:psi.skill/detail])]
        (is (nil? (:psi.skill/detail r)))))))

;; ============================================================
;; System prompt introspection
;; ============================================================

(deftest system-prompt-introspectable-test
  (testing "system prompt stored in session data is queryable"
    (let [ctx     (session-core/create-context
                   {:session-defaults {:system-prompt "Test system prompt with skills"}})
          sd      (session-core/new-session-in! ctx nil {})
          result  (session-core/query-in ctx (:session-id sd) [:psi.agent-session/system-prompt])]
      (is (= "Test system prompt with skills"
             (:psi.agent-session/system-prompt result))))))

;; ============================================================
;; Nested skill discovery (subdirectories)
;; ============================================================

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
