(ns psi.prompt-assets.system-prompt-test
  "Tests for system prompt assembly and context file discovery."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.prompt-assets.system-prompt :as sys-prompt]))

;; ============================================================
;; Test helpers
;; ============================================================

(defn- make-temp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- cleanup-dir! [dir]
  (when (.isDirectory dir)
    (doseq [f (.listFiles dir)]
      (cleanup-dir! f)))
  (.delete dir))

;; ============================================================
;; Context file discovery
;; ============================================================

(deftest discover-context-files-test
  (testing "finds AGENTS.md in cwd"
    (let [dir (make-temp-dir "psi-ctx-test")]
      (spit (io/file dir "AGENTS.md") "# Project Context")
      (try
        (let [files (sys-prompt/discover-context-files (str dir))]
          (is (= 1 (count files)))
          (is (str/includes? (:content (first files)) "# Project Context")))
        (finally (cleanup-dir! dir)))))

  (testing "prefers AGENTS.md over CLAUDE.md"
    (let [dir (make-temp-dir "psi-ctx-prefer-test")]
      (spit (io/file dir "AGENTS.md") "AGENTS content")
      (spit (io/file dir "CLAUDE.md") "CLAUDE content")
      (try
        (let [files (sys-prompt/discover-context-files (str dir))]
          ;; Should find AGENTS.md (checked first)
          (is (= 1 (count files)))
          (is (str/includes? (:content (first files)) "AGENTS content")))
        (finally (cleanup-dir! dir)))))

  (testing "returns empty for dir without context files"
    (let [dir (make-temp-dir "psi-ctx-empty-test")]
      (try
        (is (empty? (sys-prompt/discover-context-files (str dir))))
        (finally (cleanup-dir! dir))))))

;; ============================================================
;; System prompt assembly
;; ============================================================

;;; Lambda mode tests

(deftest build-system-prompt-lambda-mode-test
  ;; Lambda mode is the default prompt mode.
  (testing "build-system-prompt in lambda mode"
    (testing "is default when no :prompt-mode specified"
      (let [prompt (sys-prompt/build-system-prompt {:cwd "/test/dir"})]
        (is (str/includes? prompt "λ engage(nucleus).")
            "nucleus prelude present")
        (is (str/includes? prompt "λ identity(ψ)")
            "lambda identity present")
        (is (not (str/includes? prompt "You are ψ (Psi)"))
            "prose identity absent")))

    (testing "includes non-empty lambda-compiled sections"
      (let [prompt (sys-prompt/build-system-prompt {:cwd "/test/dir"})]
        (is (str/includes? prompt "λ engage(nucleus)."))
        (is (str/includes? prompt "λ identity(ψ)"))
        (is (str/includes? prompt "λ tools."))
        (is (str/includes? prompt "λ graph(eql)."))
        (is (not (str/includes? prompt "λ guide.")))))

    (testing "includes lambda tool descriptions"
      (let [prompt (sys-prompt/build-system-prompt {:cwd "/test/dir"})]
        (is (str/includes? prompt "read → λpath. content(path)"))
        (is (str/includes? prompt "bash → λcommand. shell(command)"))
        (is (str/includes? prompt "edit → λ{path oldText newText}. find_exact(path, oldText) → replace(path, oldText, newText)"))
        (is (str/includes? prompt "write → λ{path content}. create(path,content) ∨ overwrite(path, content)"))
        (is (str/includes? prompt "psi-tool → λaction. runtime(query ∨ eval[ψ,in-process] ∨ reload-code ∨ project-repl[worktree,nrepl]) → {graph ∨ value ∨ reload-report ∨ project-repl-report}"))))

    (testing "built-in tool falls back to prose when no lambda description"
      (let [prompt (sys-prompt/build-system-prompt
                    {:cwd "/test"
                     :tool-defs [{:name "read"
                                  :description "Read prose only"}]})]
        (is (str/includes? prompt "read → Read prose only"))))

    (testing "includes graph capabilities data"
      (let [prompt (sys-prompt/build-system-prompt
                    {:graph-capabilities [{:domain :agent-session
                                           :operation-count 10
                                           :resolver-count 8
                                           :mutation-count 2}]})]
        (is (str/includes? prompt "agent-session (ops=10, resolvers=8, mutations=2)"))))

    (testing "uses custom prelude when override provided"
      (let [prompt (sys-prompt/build-system-prompt
                    {:cwd "/test"
                     :nucleus-prelude-override "λ custom.prelude"})]
        (is (str/includes? prompt "λ custom.prelude"))
        (is (not (str/includes? prompt "λ engage(nucleus).")))))

    (testing "extension tool falls back to prose when no lambda description"
      (let [prompt (sys-prompt/build-system-prompt
                    {:cwd "/test"
                     :tool-defs [{:name "my-ext-tool"
                                  :description "Prose desc"}]})]
        (is (str/includes? prompt "my-ext-tool → Prose desc"))))

    (testing "extension tool uses lambda description when available"
      (let [prompt (sys-prompt/build-system-prompt
                    {:cwd "/test"
                     :tool-defs [{:name "my-ext-tool"
                                  :description "Prose desc"
                                  :lambda-description "λt. ext(t)"}]})]
        (is (str/includes? prompt "my-ext-tool → λt. ext(t)"))
        (is (not (str/includes? prompt "Prose desc")))))

    (testing "renders skills in lambda notation"
      (let [skills [{:name "test-skill" :description "A test skill"
                     :file-path "/skills/test-skill/SKILL.md"
                     :base-dir "/skills/test-skill"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt {:skills skills})]
        (is (str/includes? prompt "λ skills. match(task, description)"))
        (is (str/includes? prompt "test-skill → A test skill @"))
        (is (not (str/includes? prompt "<available_skills>")))))

    (testing "uses lambda frontmatter when available"
      (let [skills [{:name "my-skill" :description "Prose desc"
                     :lambda-description "λs. compile(prompt)"
                     :file-path "/s/SKILL.md" :base-dir "/s"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt {:skills skills})]
        (is (str/includes? prompt "my-skill → λs. compile(prompt) @"))
        (is (not (str/includes? prompt "Prose desc")))))

    (testing "is deterministic given the same inputs"
      (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
            opts    {:cwd "/test" :session-instant instant}
            p1      (sys-prompt/build-system-prompt opts)
            p2      (sys-prompt/build-system-prompt opts)]
        (is (= p1 p2))))))

;;; Prose mode tests

(deftest build-system-prompt-prose-mode-test
  ;; Prose mode preserves the original natural-language prompt.
  (testing "build-system-prompt in prose mode"
    (testing "includes prose identity and tools"
      (let [prompt (sys-prompt/build-system-prompt {:cwd "/test/dir" :prompt-mode :prose})]
        (is (str/includes? prompt "You are ψ (Psi)"))
        (is (str/includes? prompt "Available tools:"))
        (is (str/includes? prompt "read: Read the contents of a file. Returns the file text."))
        (is (str/includes? prompt "psi-tool: Execute a live psi runtime operation. Canonical requests use `action` with one of:"))
        (is (str/includes? prompt "Guidelines:"))
        (is (str/includes? prompt "Capability graph (EQL discovery):"))
        (is (not (str/includes? prompt "λ engage(nucleus).")))))

    (testing "includes graph capabilities"
      (let [prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose
                     :graph-capabilities [{:domain :agent-session
                                           :operation-count 10
                                           :resolver-count 8
                                           :mutation-count 2}
                                          {:domain :ai
                                           :operation-count 4
                                           :resolver-count 4
                                           :mutation-count 0}]})]
        (is (str/includes? prompt "Current capabilities (from :psi.graph/capabilities):"))
        (is (str/includes? prompt "- agent-session (ops=10, resolvers=8, mutations=2)"))
        (is (str/includes? prompt "- ai (ops=4, resolvers=4, mutations=0)"))))

    (testing "includes action-based psi-tool examples in prose graph guidance"
      (let [prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose})]
        (is (str/includes? prompt "psi-tool(action: \"query\", query: \"[:psi.graph/root-seeds]\")"))
        (is (str/includes? prompt "psi-tool(action: \"eval\", ns: \"clojure.core\", form: \"(+ 1 2)\") = in-process ψ eval in an already loaded namespace; psi-tool(action: \"project-repl\", op: \"eval\", code: \"(+ 1 2)\") = managed project nREPL eval for the target worktree."))
        (is (str/includes? prompt "psi self-reload is worktree-authoritative"))
        (is (str/includes? prompt "psi-tool(action: \"query\", query: \"[:psi.agent-session/worktree-path]\")"))
        (is (str/includes? prompt "start small: reload one already loaded namespace"))
        (is (str/includes? prompt "warning-only mismatch diagnostics"))
        (is (str/includes? prompt "do not retarget reload to some other checkout"))
        (is (str/includes? prompt "psi-tool(action: \"reload-code\", namespaces: [\"psi.agent-session.tools\"])"))
        (is (str/includes? prompt "psi-tool(action: \"project-repl\", op: \"status\")"))
        (is (str/includes? prompt "psi-tool(action: \"project-repl\", op: \"eval\", code: \"(+ 1 2)\")"))
        (is (str/includes? prompt "psi.runtime-session/list"))
        (is (str/includes? prompt "psi.graph/resolver-index"))
        (is (str/includes? prompt "psi.graph/attr-index"))
        (is (str/includes? prompt "psi.resolver/sym"))))

    (testing "excludes graph discovery when psi-tool not available"
      (let [prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose
                     :tool-defs [{:name "read" :description "Read"}
                                 {:name "bash" :description "Bash"}
                                 {:name "edit" :description "Edit"}
                                 {:name "write" :description "Write"}]
                     :selected-tools ["read" "bash" "edit" "write"]})]
        (is (not (str/includes? prompt "Capability graph (EQL discovery):")))))

    (testing "renders extension tool descriptions from provided tool defs"
      (let [prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose
                     :tool-defs [{:name "read" :description "Read the contents of a file. Returns the file text."}
                                 {:name "edit-clj"
                                  :description "Replace text in a file. old-string is matched by structural equality (whitespace does not matter); old-string and new-string must each be one complete, parseable form."}]})]
        (is (str/includes? prompt "edit-clj: Replace text in a file."))))))

;;; Mode-independent tests

(deftest build-system-prompt-shared-test
  ;; Behaviour shared between lambda and prose modes.
  (testing "build-system-prompt (mode-independent)"
    (testing "includes session creation date"
      (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
            prompt  (sys-prompt/build-system-prompt {:session-instant instant})]
        (is (str/includes? prompt "Session start date:"))
        (is (str/includes? prompt "January 15, 2026"))
        ;; time portion should NOT be present
        (is (not (re-find #"^Session start.*at.*$" prompt)) prompt)))

    (testing "includes context files"
      (let [prompt (sys-prompt/build-system-prompt
                    {:context-files [{:path "/project/AGENTS.md"
                                      :content "# My Project\nInstructions here"}]})]
        (is (str/includes? prompt "# Project Context"))
        (is (str/includes? prompt "Instructions here"))))

    (testing "includes skills section when read tool available"
      (let [skills [{:name "test-skill" :description "A test skill"
                     :file-path "/skills/test-skill/SKILL.md"
                     :base-dir "/skills/test-skill"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt {:skills skills})]
        (is (str/includes? prompt "test-skill"))
        (is (str/includes? prompt "A test skill"))))

    (testing "excludes skills when read tool not available"
      (let [skills [{:name "test-skill" :description "A test skill"
                     :file-path "/skills/test-skill/SKILL.md"
                     :base-dir "/skills/test-skill"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt
                    {:skills skills
                     :tool-defs [{:name "bash" :description "Bash"}
                                 {:name "edit" :description "Edit"}
                                 {:name "write" :description "Write"}]
                     :selected-tools ["bash" "edit" "write"]})]
        (is (not (str/includes? prompt "<available_skills>")))))

    (testing "custom prompt replaces default in both modes"
      (doseq [mode [:lambda :prose]]
        (let [prompt (sys-prompt/build-system-prompt
                      {:prompt-mode mode
                       :custom-prompt "Custom system prompt."})]
          (is (str/starts-with? prompt "Custom system prompt."))
          (is (not (str/includes? prompt "λ engage(nucleus).")))
          (is (not (str/includes? prompt "Available tools:"))))))

    (testing "append prompt is added"
      (let [prompt (sys-prompt/build-system-prompt
                    {:append-prompt "Extra instructions."})]
        (is (str/includes? prompt "Extra instructions."))))

    (testing "can omit preamble and runtime metadata"
      (let [prompt (sys-prompt/build-system-prompt
                    {:include-preamble? false
                     :include-runtime-metadata? false
                     :append-prompt "Helper prompt."})]
        (is (= "Helper prompt." prompt))))

    (testing "can omit context files while preserving appended prompt"
      (let [prompt (sys-prompt/build-system-prompt
                    {:include-preamble? false
                     :include-runtime-metadata? false
                     :include-context-files? false
                     :context-files [{:path "/AGENTS.md" :content "Context text"}]
                     :append-prompt "Helper prompt."})]
        (is (= "Helper prompt." prompt))))

    (testing "does not include working-directory metadata"
      (let [prompt (sys-prompt/build-system-prompt
                    {:cwd "/tmp/worktree-demo"})]
        (is (not (str/includes? prompt "Current working directory:")))
        (is (not (str/includes? prompt "Current worktree directory:")))))

    (testing "runtime metadata follows prompt contributions which follow context files"
      (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
            prompt   (sys-prompt/build-system-prompt
                      {:cwd "/tmp/demo"
                       :context-files [{:path "/A.md" :content "Ctx"}]
                       :append-prompt "# Extension Prompt Contributions"
                       :session-instant instant})
            ctx-idx       (.indexOf prompt "# Project Context")
            contrib-idx   (.indexOf prompt "# Extension Prompt Contributions")
            date-idx      (.indexOf prompt "Session start date:")]
        (is (pos? ctx-idx))
        (is (pos? contrib-idx) prompt)
        (is (pos? date-idx))
        (is (> contrib-idx ctx-idx) "contributions after context")
        (is (> date-idx contrib-idx) "date after contributions")))

    (testing "skills appear before context files"
      (let [prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose
                     :context-files [{:path "/A.md" :content "Ctx"}]
                     :skills [{:name "s" :description "S"
                               :file-path "/s/SKILL.md" :base-dir "/s"
                               :source :user :disable-model-invocation false}]})
            skills-idx  (.indexOf prompt "<available_skills>")
            ctx-idx     (.indexOf prompt "# Project Context")]
        (is (pos? skills-idx))
        (is (pos? ctx-idx))
        (is (< skills-idx ctx-idx) "skills before context")))

    (testing "external content is identical between modes"
      (let [instant (java.time.Instant/parse "2026-01-15T10:30:00Z")
            shared  {:cwd "/test" :session-instant instant
                     :context-files [{:path "/A.md" :content "Ctx"}]
                     :skills [{:name "s" :description "S"
                               :file-path "/s/SKILL.md" :base-dir "/s"
                               :source :user :disable-model-invocation false}]}
            lambda-p (sys-prompt/build-system-prompt (assoc shared :prompt-mode :lambda))
            prose-p  (sys-prompt/build-system-prompt (assoc shared :prompt-mode :prose))
            extract  (fn [p marker]
                       (let [idx (.indexOf p marker)]
                         (when (pos? idx) (subs p idx))))]
        ;; Context, skills, and metadata tail should match
        (is (= (extract lambda-p "# Project Context")
               (extract prose-p "# Project Context")))))))

(deftest build-system-prompt-custom-with-extras-test
  (testing "custom prompt with skills and context"
    (testing "in lambda mode (default)"
      (let [skills [{:name "my-skill" :description "My skill"
                     :file-path "/s/SKILL.md" :base-dir "/s"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt
                    {:custom-prompt "Custom base."
                     :context-files [{:path "/AGENTS.md" :content "Context text"}]
                     :skills skills})]
        (is (str/starts-with? prompt "Custom base."))
        (is (str/includes? prompt "Context text"))
        (is (str/includes? prompt "λ skills. match(task, description)"))))
    (testing "in prose mode"
      (let [skills [{:name "my-skill" :description "My skill"
                     :file-path "/s/SKILL.md" :base-dir "/s"
                     :source :user :disable-model-invocation false}]
            prompt (sys-prompt/build-system-prompt
                    {:prompt-mode :prose
                     :custom-prompt "Custom base."
                     :context-files [{:path "/AGENTS.md" :content "Context text"}]
                     :skills skills})]
        (is (str/starts-with? prompt "Custom base."))
        (is (str/includes? prompt "Context text"))
        (is (str/includes? prompt "<available_skills>"))))))

(deftest system-prompt-blocks-test
  ;; system-prompt-blocks returns a single cacheable block
  ;; since the entire prompt is now stable (time + cwd frozen)
  (testing "system-prompt-blocks"
    (testing "returns single cached block when caching enabled"
      (let [blocks (sys-prompt/system-prompt-blocks "test prompt" true)]
        (is (= 1 (count blocks)))
        (is (= "test prompt" (:text (first blocks))))
        (is (= {:type :ephemeral} (:cache-control (first blocks))))))
    (testing "returns single uncached block when caching disabled"
      (let [blocks (sys-prompt/system-prompt-blocks "test prompt" false)]
        (is (= 1 (count blocks)))
        (is (= "test prompt" (:text (first blocks))))
        (is (nil? (:cache-control (first blocks))))))
    (testing "returns nil for empty prompt"
      (is (nil? (sys-prompt/system-prompt-blocks "" false)))
      (is (nil? (sys-prompt/system-prompt-blocks nil false))))))

(deftest system-prompt-introspectable-test
  (testing "assembled prompt is a string that can be stored and queried"
    (let [prompt (sys-prompt/build-system-prompt
                  {:skills [{:name "s1" :description "Skill one"
                             :file-path "/s1/SKILL.md" :base-dir "/s1"
                             :source :user :disable-model-invocation false}]})]
      (is (string? prompt))
      (is (pos? (count prompt)))
      ;; The prompt contains the skills section for introspection
      (is (str/includes? prompt "s1")))))
