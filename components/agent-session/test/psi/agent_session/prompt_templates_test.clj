(ns psi.agent-session.prompt-templates-test
  "Tests for prompt template discovery, parsing, argument expansion,
   invocation, and EQL introspection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.core :as session-core]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

;; ============================================================
;; Test helpers — temp directory with .md files
;; ============================================================

(defn- make-temp-dir
  "Create a temporary directory with a unique name."
  [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- write-template!
  "Write a .md file into `dir` with `name`.md and `content`."
  [dir template-name content]
  (spit (io/file dir (str template-name ".md")) content))

(defn- cleanup-dir!
  "Delete all files in `dir` then delete `dir` itself."
  [dir]
  (doseq [f (.listFiles dir)] (.delete f))
  (.delete dir))

(defn with-temp-templates*
  "Create a temp dir, write template files, call (f dir), clean up.
   `templates` is {\"name\" \"content\" ...}."
  [templates f]
  (let [dir (make-temp-dir "psi-prompt-test")]
    (doseq [[n c] templates]
      (write-template! dir n c))
    (try (f dir)
         (finally (cleanup-dir! dir)))))

;; ============================================================
;; Frontmatter Extraction
;; ============================================================

(deftest extract-frontmatter-test
  (testing "parses YAML frontmatter between --- fences"
    (let [raw "---\ndescription: A test template\n---\nHello $1"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (= "A test template" (:description frontmatter)))
      (is (= "Hello $1" body))))

  (testing "handles quoted values in frontmatter"
    (let [raw "---\ndescription: \"Hello world\"\n---\nBody text"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (= "Hello world" (:description frontmatter)))
      (is (= "Body text" body))))

  (testing "handles multiple frontmatter keys"
    (let [raw "---\ndescription: desc\nauthor: ψ\n---\nBody"
          {:keys [frontmatter]} (pt/extract-frontmatter raw)]
      (is (= "desc" (:description frontmatter)))
      (is (= "ψ" (:author frontmatter)))))

  (testing "no frontmatter — entire text is body"
    (let [raw "Just some text"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (nil? frontmatter))
      (is (= "Just some text" body))))

  (testing "--- without closing fence — entire text is body"
    (let [raw "---\ndescription: no close\nStill body"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (nil? frontmatter))
      (is (str/includes? body "Still body"))))

  (testing "empty frontmatter"
    (let [raw "---\n---\nBody only"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (= {} frontmatter))
      (is (= "Body only" body))))

  (testing "leading whitespace before ---"
    (let [raw "  ---\ndescription: with spaces\n---\nBody"
          {:keys [frontmatter body]} (pt/extract-frontmatter raw)]
      (is (= "with spaces" (:description frontmatter)))
      (is (= "Body" body)))))

;; ============================================================
;; First Non-Empty Line
;; ============================================================

(deftest first-non-empty-line-test
  (testing "returns first non-blank line"
    (is (= "Hello" (pt/first-non-empty-line "\n\nHello\nWorld"))))

  (testing "returns empty string for blank input"
    (is (= "" (pt/first-non-empty-line "\n\n  \n"))))

  (testing "returns first line when no blanks"
    (is (= "First" (pt/first-non-empty-line "First\nSecond")))))

;; ============================================================
;; Template File Parsing
;; ============================================================

(deftest parse-template-file-test
  (testing "parses template with frontmatter"
    (with-temp-templates*
      {"greet" "---\ndescription: Greet someone\n---\nHello $1!"}
      (fn [dir]
        (let [tpl (pt/parse-template-file (str dir "/greet.md") :user)]
          (is (= "greet" (:name tpl)))
          (is (= "Greet someone" (:description tpl)))
          (is (= "Hello $1!" (:content tpl)))
          (is (= :user (:source tpl)))
          (is (str/ends-with? (:file-path tpl) "greet.md"))))))

  (testing "fallback description from first line when no frontmatter"
    (with-temp-templates*
      {"simple" "Generate a $1 component"}
      (fn [dir]
        (let [tpl (pt/parse-template-file (str dir "/simple.md") :project)]
          (is (= "simple" (:name tpl)))
          (is (= "Generate a $1 component" (:description tpl)))
          (is (= "Generate a $1 component" (:content tpl)))
          (is (= :project (:source tpl)))))))

  (testing "returns nil for non-existent file"
    (is (nil? (pt/parse-template-file "/nonexistent/path.md" :user)))))

;; ============================================================
;; Placeholder Analysis
;; ============================================================

(deftest placeholder-analysis-test
  (testing "has-placeholders? detects $N"
    (is (pt/has-placeholders? "Hello $1"))
    (is (pt/has-placeholders? "Use $@ for all")))

  (testing "has-placeholders? false for no placeholders"
    (is (not (pt/has-placeholders? "No placeholders here")))
    (is (not (pt/has-placeholders? "")))
    (is (not (pt/has-placeholders? nil))))

  (testing "placeholder-count counts distinct $N"
    (is (= 0 (pt/placeholder-count "No placeholders")))
    (is (= 1 (pt/placeholder-count "Hello $1")))
    (is (= 2 (pt/placeholder-count "$1 and $2")))
    (is (= 1 (pt/placeholder-count "$1 $1 $1")))
    (is (= 3 (pt/placeholder-count "$1 $2 $3 plus $@")))))

;; ============================================================
;; Shell-like Tokenization
;; ============================================================

(deftest tokenize-args-test
  (testing "simple words"
    (is (= ["Button" "click" "handler"]
           (pt/tokenize-args "Button click handler"))))

  (testing "quoted strings"
    (is (= ["Button" "click handler"]
           (pt/tokenize-args "Button \"click handler\""))))

  (testing "multiple quoted strings"
    (is (= ["one" "two three" "four"]
           (pt/tokenize-args "one \"two three\" four"))))

  (testing "escaped quotes inside quoted string"
    (is (= ["say \"hello\""]
           (pt/tokenize-args "\"say \\\"hello\\\"\""))))

  (testing "empty/blank input"
    (is (nil? (pt/tokenize-args "")))
    (is (nil? (pt/tokenize-args "   ")))
    (is (nil? (pt/tokenize-args nil))))

  (testing "single word"
    (is (= ["hello"] (pt/tokenize-args "hello"))))

  (testing "extra whitespace between words"
    (is (= ["a" "b" "c"] (pt/tokenize-args "  a   b   c  "))))

  (testing "empty quoted string"
    (is (= [""] (pt/tokenize-args "\"\"")))))

;; ============================================================
;; Placeholder Expansion
;; ============================================================

(deftest expand-placeholders-test
  (testing "positional $1 $2"
    (is (= "Hello Alice and Bob"
           (pt/expand-placeholders "Hello $1 and $2" ["Alice" "Bob"]))))

  (testing "missing args produce empty string"
    (is (= "Hello  and "
           (pt/expand-placeholders "Hello $1 and $2" []))))

  (testing "$@ joins all args"
    (is (= "args: one two three"
           (pt/expand-placeholders "args: $@" ["one" "two" "three"]))))

  (testing "$ARGUMENTS is alias for $@"
    (is (= "args: one two three"
           (pt/expand-placeholders "args: $ARGUMENTS" ["one" "two" "three"]))))

  (testing "${@:N} slices from Nth"
    (is (= "rest: two three"
           (pt/expand-placeholders "rest: ${@:2}" ["one" "two" "three"]))))

  (testing "${@:N:L} takes L from Nth"
    (is (= "slice: two three"
           (pt/expand-placeholders "slice: ${@:2:2}" ["one" "two" "three" "four"]))))

  (testing "${@:N:L} with insufficient args"
    (is (= "slice: three"
           (pt/expand-placeholders "slice: ${@:3:5}" ["one" "two" "three"]))))

  (testing "mixed placeholders — $@ includes ALL args"
    (is (= "Create Button with click handler using Button click handler one two three"
           (pt/expand-placeholders
            "Create $1 with $2 using $@"
            ["Button" "click handler" "one" "two" "three"]))))

  (testing "mixed placeholders — ${@:N} for rest-of-args"
    (is (= "Create Button with click handler using one two three"
           (pt/expand-placeholders
            "Create $1 with $2 using ${@:3}"
            ["Button" "click handler" "one" "two" "three"]))))

  (testing "nil args treated as empty"
    (is (= "Hello  world"
           (pt/expand-placeholders "Hello $1 world" nil))))

  (testing "no placeholders — content unchanged"
    (is (= "No placeholders here"
           (pt/expand-placeholders "No placeholders here" ["ignored"]))))

  (testing "$10 and above work correctly"
    (is (= "ten: TEN one: ONE"
           (pt/expand-placeholders
            "ten: $10 one: $1"
            ["ONE" "2" "3" "4" "5" "6" "7" "8" "9" "TEN"])))))

;; ============================================================
;; Discovery
;; ============================================================

(deftest discover-template-files-test
  (testing "finds .md files in directory"
    (with-temp-templates*
      {"alpha" "A content" "beta" "B content"}
      (fn [dir]
        (let [files (pt/discover-template-files (str dir) :user)]
          (is (= 2 (count files)))
          (is (every? #(= :user (:source %)) files))))))

  (testing "returns nil for non-existent directory"
    (is (nil? (pt/discover-template-files "/nonexistent/dir" :user))))

  (testing "ignores non-.md files"
    (with-temp-templates*
      {"valid" "content"}
      (fn [dir]
        (spit (io/file dir "ignored.txt") "not a template")
        (let [files (pt/discover-template-files (str dir) :user)]
          (is (= 1 (count files))))))))

(deftest discover-templates-test
  (testing "discovers from global and project dirs"
    (with-temp-templates*
      {"global-tpl" "Global template"}
      (fn [global-dir]
        (with-temp-templates*
          {"project-tpl" "Project template"}
          (fn [project-dir]
            (let [templates (pt/discover-templates
                             {:global-prompts-dir  (str global-dir)
                              :project-prompts-dir (str project-dir)})]
              (is (= 2 (count templates)))
              (is (some #(= "global-tpl" (:name %)) templates))
              (is (some #(= "project-tpl" (:name %)) templates))))))))

  (testing "first-discovered wins on name collision"
    (with-temp-templates*
      {"shared" "Global version"}
      (fn [global-dir]
        (with-temp-templates*
          {"shared" "Project version"}
          (fn [project-dir]
            (let [templates (pt/discover-templates
                             {:global-prompts-dir  (str global-dir)
                              :project-prompts-dir (str project-dir)})
                  shared    (pt/find-template templates "shared")]
              (is (= 1 (count templates)))
              (is (= "Global version" (:content shared)))
              (is (= :user (:source shared)))))))))

  (testing "extra-paths are included"
    (with-temp-templates*
      {}
      (fn [global-dir]
        (with-temp-templates*
          {"extra" "Extra template"}
          (fn [extra-dir]
            (let [templates (pt/discover-templates
                             {:global-prompts-dir  (str global-dir)
                              :project-prompts-dir "/nonexistent"
                              :extra-paths         [(str extra-dir "/extra.md")]})]
              (is (= 1 (count templates)))
              (is (= :cli (:source (first templates))))))))))

  (testing "disabled flag returns empty"
    (with-temp-templates*
      {"tpl" "content"}
      (fn [dir]
        (let [templates (pt/discover-templates
                         {:global-prompts-dir (str dir) :disabled true})]
          (is (empty? templates)))))))

;; ============================================================
;; Template Lookup
;; ============================================================

(deftest find-template-test
  (let [templates [{:name "foo" :description "Foo" :content "Foo content" :source :user :file-path "/foo.md"}
                   {:name "bar" :description "Bar" :content "Bar content" :source :project :file-path "/bar.md"}]]

    (testing "finds by name"
      (is (= "Foo" (:description (pt/find-template templates "foo")))))

    (testing "returns nil for unknown name"
      (is (nil? (pt/find-template templates "baz"))))))

;; ============================================================
;; Command Parsing
;; ============================================================

(deftest parse-command-test
  (testing "parses /name args"
    (is (= {:command-name "greet" :args-text "Alice Bob"}
           (pt/parse-command "/greet Alice Bob"))))

  (testing "parses /name with no args"
    (is (= {:command-name "help" :args-text ""}
           (pt/parse-command "/help"))))

  (testing "returns nil for non-command text"
    (is (nil? (pt/parse-command "just text")))
    (is (nil? (pt/parse-command "")))
    (is (nil? (pt/parse-command nil)))))

;; ============================================================
;; Invocation
;; ============================================================

(deftest invoke-template-test
  (let [templates [{:name "greet"
                    :description "Greet someone"
                    :content "Hello $1, welcome to $2!"
                    :source :user
                    :file-path "/greet.md"}
                   {:name "list"
                    :description "List all args"
                    :content "Items: $@"
                    :source :project
                    :file-path "/list.md"}]]

    (testing "expands template with positional args"
      (let [result (pt/invoke-template templates #{} "/greet Alice Wonderland")]
        (is (= "Hello Alice, welcome to Wonderland!" (:content result)))
        (is (= "greet" (:source-template result)))))

    (testing "expands template with $@"
      (let [result (pt/invoke-template templates #{} "/list a b c")]
        (is (= "Items: a b c" (:content result)))
        (is (= "list" (:source-template result)))))

    (testing "returns nil for unknown template"
      (is (nil? (pt/invoke-template templates #{} "/unknown arg"))))

    (testing "commands take priority over templates"
      (is (nil? (pt/invoke-template templates #{"greet"} "/greet Alice"))))

    (testing "returns nil for non-command text"
      (is (nil? (pt/invoke-template templates #{} "regular text"))))

    (testing "template with no args"
      (let [templates [{:name "hello" :description "Hi"
                        :content "Hello world" :source :user :file-path "/hello.md"}]]
        (is (= "Hello world" (:content (pt/invoke-template templates #{} "/hello"))))))))

;; ============================================================
;; Introspection helpers
;; ============================================================

(deftest enrich-template-test
  (testing "adds derived fields"
    (let [tpl {:name "test" :description "Test" :content "Hello $1 and $2 with $@"
               :source :user :file-path "/test.md"}
          enriched (pt/enrich-template tpl)]
      (is (true? (:has-placeholders enriched)))
      (is (= 2 (:placeholder-count enriched)))
      (is (= "test" (:name enriched)))))

  (testing "no placeholders"
    (let [tpl {:name "plain" :description "Plain" :content "No vars here"
               :source :user :file-path "/plain.md"}
          enriched (pt/enrich-template tpl)]
      (is (false? (:has-placeholders enriched)))
      (is (= 0 (:placeholder-count enriched))))))

(deftest template-summary-test
  (testing "summarizes templates"
    (let [templates [{:name "a" :description "A" :content "$1" :source :user :file-path "/a.md"}
                     {:name "b" :description "B" :content "plain" :source :project :file-path "/b.md"}]
          summary (pt/template-summary templates)]
      (is (= 2 (:template-count summary)))
      (is (= 2 (count (:templates summary))))
      (is (true? (:has-placeholders (first (:templates summary)))))
      (is (false? (:has-placeholders (second (:templates summary))))))))

(deftest template-names-test
  (testing "returns name vector"
    (let [templates [{:name "x" :description "X" :content "" :source :user :file-path "/x.md"}
                     {:name "y" :description "Y" :content "" :source :user :file-path "/y.md"}]]
      (is (= ["x" "y"] (pt/template-names templates))))))

(deftest templates-by-source-test
  (testing "groups by source"
    (let [templates [{:name "a" :description "A" :content "" :source :user :file-path "/a.md"}
                     {:name "b" :description "B" :content "" :source :project :file-path "/b.md"}
                     {:name "c" :description "C" :content "" :source :user :file-path "/c.md"}]
          grouped (pt/templates-by-source templates)]
      (is (= 2 (count (:user grouped))))
      (is (= 1 (count (:project grouped)))))))

;; ============================================================
;; EQL Introspection (resolvers)
;; ============================================================

(deftest prompt-template-eql-introspection-test
  ;; Use an isolated session context with templates loaded into session data
  (let [templates [{:name "greet"
                    :description "Greet someone"
                    :content "Hello $1!"
                    :source :user
                    :file-path "/greet.md"}
                   {:name "review"
                    :description "Code review"
                    :content "Review the following: $@"
                    :source :project
                    :file-path "/review.md"}]
        ctx     (session-core/create-context
                 {:session-defaults {:prompt-templates templates}})
        sd      (session-core/new-session-in! ctx nil {})
        session-id (:session-id sd)]

    (testing "query template count via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.prompt-template/count])]
        (is (= 2 (:psi.prompt-template/count result)))))

    (testing "query template names via EQL"
      (let [result (session-core/query-in ctx session-id [:psi.prompt-template/names])]
        (is (= ["greet" "review"] (:psi.prompt-template/names result)))))

    (testing "query template summary via EQL"
      (let [result  (session-core/query-in ctx session-id [:psi.prompt-template/summary])
            summary (:psi.prompt-template/summary result)]
        (is (= 2 (:template-count summary)))
        (is (= 2 (count (:templates summary))))))

    (testing "query templates by source via EQL"
      (let [result   (session-core/query-in ctx session-id [:psi.prompt-template/by-source])
            grouped  (:psi.prompt-template/by-source result)]
        (is (= 1 (count (:user grouped))))
        (is (= 1 (count (:project grouped))))))))

(deftest prompt-template-detail-eql-test
  ;; Build a Pathom env with prompt template resolvers
  ;; and seed it with the session ctx + template name
  (let [templates [{:name "greet"
                    :description "Greet someone"
                    :content "Hello $1 and $2!"
                    :source :user
                    :file-path "/greet.md"}]
        ctx (session-core/create-context
             {:session-defaults {:prompt-templates templates}})
        sd  (session-core/new-session-in! ctx nil {})
        env (pci/register resolvers/all-resolvers)
        result (p.eql/process env
                              {:psi/agent-session-ctx ctx
                               :psi.agent-session/session-id (:session-id sd)
                               :psi.prompt-template/name "greet"}
                              [:psi.prompt-template/detail])
        detail (:psi.prompt-template/detail result)]
    (testing "detail includes enriched fields"
      (is (= "greet" (:name detail)))
      (is (= "Greet someone" (:description detail)))
      (is (true? (:has-placeholders detail)))
      (is (= 2 (:placeholder-count detail))))

    (testing "detail for unknown template is nil"
      (let [r (p.eql/process env
                             {:psi/agent-session-ctx ctx
                              :psi.agent-session/session-id (:session-id sd)
                              :psi.prompt-template/name "unknown"}
                             [:psi.prompt-template/detail])]
        (is (nil? (:psi.prompt-template/detail r)))))))

;; ============================================================
;; Integration: discovery + invocation end-to-end
;; ============================================================

(deftest end-to-end-discovery-and-invocation-test
  (testing "discover templates, then invoke one"
    (with-temp-templates*
      {"component" "---\ndescription: Create a component\n---\nCreate a $1 component with $2 styling"}
      (fn [dir]
        (let [templates (pt/discover-templates
                         {:global-prompts-dir  (str dir)
                          :project-prompts-dir "/nonexistent"})
              result    (pt/invoke-template templates #{} "/component Button dark")]
          (is (= "Create a Button component with dark styling" (:content result)))
          (is (= "component" (:source-template result))))))))
