(ns psi.agent-session.tools-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.tools :as tools]))

(deftest eql-query-tool-schema-test
  (testing "eql_query tool schema is well-formed"
    (is (= "eql_query" (:name tools/eql-query-tool)))
    (is (string? (:description tools/eql-query-tool)))
    (is (string? (:parameters tools/eql-query-tool)))))

(deftest eql-query-tool-in-all-schemas-test
  (testing "eql_query appears in all-tool-schemas"
    (is (some #(= "eql_query" (:name %)) tools/all-tool-schemas))))

(deftest search-tools-in-all-schemas-test
  (testing "ls/find/grep appear in all-tool-schemas"
    (is (some #(= "ls" (:name %)) tools/all-tool-schemas))
    (is (some #(= "find" (:name %)) tools/all-tool-schemas))
    (is (some #(= "grep" (:name %)) tools/all-tool-schemas))))

(deftest eql-query-tool-not-in-all-tools-test
  (testing "eql_query is excluded from all-tools (requires context)"
    (is (not (some #(= "eql_query" (:name %)) tools/all-tools)))))

(deftest search-tools-in-all-tools-test
  (testing "ls/find/grep are included in all-tools"
    (is (some #(= "ls" (:name %)) tools/all-tools))
    (is (some #(= "find" (:name %)) tools/all-tools))
    (is (some #(= "grep" (:name %)) tools/all-tools))))

(deftest make-eql-query-tool-valid-query-test
  (testing "valid EQL query returns EDN result"
    (let [query-fn (fn [q]
                     (is (= [:foo/bar] q))
                     {:foo/bar 42})
          tool     (tools/make-eql-query-tool query-fn)
          result   ((:execute tool) {"query" "[:foo/bar]"})]
      (is (false? (:is-error result)))
      (is (= {:foo/bar 42} (read-string (:content result)))))))

(deftest make-eql-query-tool-nested-query-test
  (testing "nested EQL query with joins"
    (let [query-fn (fn [_q]
                     {:psi.agent-session/stats {:total-messages 5}})
          tool     (tools/make-eql-query-tool query-fn)
          result   ((:execute tool)
                    {"query" "[{:psi.agent-session/stats [:total-messages]}]"})]
      (is (false? (:is-error result)))
      (is (string? (:content result))))))

(deftest make-eql-query-tool-invalid-edn-test
  (testing "invalid EDN returns error"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "[not valid edn!!!"})]
      (is (true? (:is-error result)))
      (is (re-find #"EQL query error" (:content result))))))

(deftest make-eql-query-tool-not-a-vector-test
  (testing "non-vector EDN returns error"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" ":just-a-keyword"})]
      (is (true? (:is-error result)))
      (is (re-find #"must be an EDN vector" (:content result))))))

(deftest make-eql-query-tool-query-fn-throws-test
  (testing "query-fn exception is caught and returned as error"
    (let [tool   (tools/make-eql-query-tool
                  (fn [_q] (throw (ex-info "resolver failed" {}))))
          result ((:execute tool) {"query" "[:foo]"})]
      (is (true? (:is-error result)))
      (is (re-find #"resolver failed" (:content result))))))

(deftest make-eql-query-tool-truncation-test
  (testing "truncated eql_query output includes spill path and narrowing guidance"
    (let [tool   (tools/make-eql-query-tool
                  (fn [_q] {:big (apply str (repeat 500 "x"))})
                  {:overrides {"eql_query" {:max-lines 1000 :max-bytes 80}}
                   :tool-call-id "test-call-id"})
          result ((:execute tool) {"query" "[:big]"})
          spill  (get-in result [:details :full-output-path])]
      (is (false? (:is-error result)))
      (is (re-find #"Output truncated" (:content result)))
      (is (re-find #"Use a narrower query" (:content result)))
      (is (string? spill))
      (is (.exists (io/file spill))))))

(deftest make-eql-query-tool-read-eval-disabled-test
  (testing "read-eval is disabled for safety"
    (let [tool   (tools/make-eql-query-tool (fn [_q] {}))
          result ((:execute tool) {"query" "#=(+ 1 2)"})]
      (is (true? (:is-error result))))))

(deftest execute-tool-dispatch-test
  (testing "built-in dispatch handles ls/find/grep"
    (let [tmp (java.io.File/createTempFile "psi-dispatch-test" "")]
      (.delete tmp)
      (.mkdirs tmp)
      (try
        (spit (io/file tmp "a.txt") "alpha")
        (spit (io/file tmp "b.clj") "(ns b)")
        (let [dir         (.getAbsolutePath tmp)
              ls-result   (tools/execute-tool "ls" {"path" dir})
              find-result (tools/execute-tool "find" {"path" dir "pattern" "*.txt"})
              grep-result (tools/execute-tool "grep" {"path" dir "pattern" "alpha"})]
          (is (false? (:is-error ls-result)))
          (is (str/includes? (:content ls-result) "a.txt"))
          (is (false? (:is-error find-result)))
          (is (str/includes? (:content find-result) "a.txt"))
          (is (false? (:is-error grep-result)))
          (is (str/includes? (:content grep-result) "alpha")))
        (finally
          (doseq [file (reverse (file-seq tmp))]
            (.delete file))))))

  (testing "built-in dispatch does not handle eql_query"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
                          (tools/execute-tool "eql_query" {"query" "[:foo]"})))))

(deftest eql-query-tool-integration-test
  (testing "eql_query tool works with a real session context"
    (let [ctx      (session/create-context {:persist? false})
          _        (session/new-session-in! ctx)
          tool     (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
          exec     (:execute tool)
          ;; Query session phase
          result   (exec {"query" "[:psi.agent-session/phase]"})
          parsed   (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :idle (:psi.agent-session/phase parsed)))))

  (testing "eql_query tool returns session-id from live context"
    (let [ctx      (session/create-context {:persist? false})
          _        (session/new-session-in! ctx)
          tool     (tools/make-eql-query-tool (fn [q] (session/query-in ctx q)))
          exec     (:execute tool)
          result   (exec {"query" "[:psi.agent-session/session-id]"})
          parsed   (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (string? (:psi.agent-session/session-id parsed))))))

;;; CWD support tests

(defn- with-temp-dir
  "Create a temp dir, run f with its path, clean up."
  [f]
  (let [tmp (java.io.File/createTempFile "psi-tools-test" "")]
    (.delete tmp)
    (.mkdirs tmp)
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (doseq [file (reverse (file-seq tmp))]
          (.delete file))))))

(deftest execute-read-cwd-test
  (testing "read resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "hello.txt") "world")
        (let [result (tools/execute-read {"path" "hello.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (= "world" (:content result)))))))

  (testing "read with absolute path ignores cwd"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "abs.txt"))]
          (spit abs-path "absolute")
          (let [result (tools/execute-read {"path" abs-path} {:cwd "/nonexistent"})]
            (is (false? (:is-error result)))
            (is (= "absolute" (:content result))))))))

  (testing "read without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "plain.txt"))]
          (spit abs-path "plain")
          (let [result (tools/execute-read {"path" abs-path})]
            (is (false? (:is-error result)))
            (is (= "plain" (:content result)))))))))

(deftest execute-bash-cwd-test
  (testing "bash runs in cwd when provided"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "marker.txt") "found")
        (let [result (tools/execute-bash {"command" "cat marker.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "found"))))))

  (testing "bash without cwd works as before"
    (let [result (tools/execute-bash {"command" "echo hello"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "hello")))))

(deftest execute-edit-cwd-test
  (testing "edit resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "edit-me.txt") "old text here")
        (let [result (tools/execute-edit
                      {"path" "edit-me.txt" "oldText" "old text" "newText" "new text"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully replaced text" (:content result)))
          (is (string? (get-in result [:details :diff])))
          (is (pos-int? (get-in result [:details :first-changed-line])))
          (is (= "new text here" (slurp (io/file dir "edit-me.txt"))))))))

  (testing "edit fuzzy fallback handles smart quotes and trailing whitespace"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "fuzzy.txt") "hello ‘world’   \nnext")
        (let [result (tools/execute-edit
                      {"path" "fuzzy.txt" "oldText" "hello 'world'" "newText" "hello everyone"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (slurp (io/file dir "fuzzy.txt")) "hello everyone"))))))

  (testing "edit preserves UTF-8 BOM"
    (with-temp-dir
      (fn [dir]
        (let [p (io/file dir "bom.txt")]
          (spit p (str "\uFEFF" "before"))
          (let [result (tools/execute-edit
                        {"path" "bom.txt" "oldText" "before" "newText" "after"}
                        {:cwd dir})
                updated (slurp p)]
            (is (false? (:is-error result)))
            (is (str/starts-with? updated "\uFEFF"))
            (is (str/includes? updated "after")))))))

  (testing "edit without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "edit-abs.txt"))]
          (spit abs-path "before")
          (let [result (tools/execute-edit
                        {"path" abs-path "oldText" "before" "newText" "after"})]
            (is (false? (:is-error result)))
            (is (= "after" (slurp abs-path)))))))))

(deftest execute-write-cwd-test
  (testing "write resolves relative path against cwd"
    (with-temp-dir
      (fn [dir]
        (let [result (tools/execute-write
                      {"path" "sub/output.txt" "content" "written"}
                      {:cwd dir})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully wrote 7 bytes" (:content result)))
          (is (= "written" (slurp (io/file dir "sub" "output.txt"))))))))

  (testing "write without cwd works as before"
    (with-temp-dir
      (fn [dir]
        (let [abs-path (.getAbsolutePath (io/file dir "write-abs.txt"))
              result   (tools/execute-write
                        {"path" abs-path "content" "abs-written"})]
          (is (false? (:is-error result)))
          (is (re-find #"Successfully wrote" (:content result)))
          (is (= "abs-written" (slurp abs-path))))))))

(deftest make-tools-with-cwd-test
  (testing "returns four tools scoped to cwd"
    (with-temp-dir
      (fn [dir]
        (let [tools-vec (tools/make-tools-with-cwd dir)]
          (is (= 4 (count tools-vec)))
          (is (= #{"read" "bash" "edit" "write"}
                 (into #{} (map :name) tools-vec)))))))

  (testing "tools execute in the scoped directory"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "scoped.txt") "scoped-content")
        (let [tools-vec  (tools/make-tools-with-cwd dir)
              read-tool  (first (filter #(= "read" (:name %)) tools-vec))
              bash-tool  (first (filter #(= "bash" (:name %)) tools-vec))
              write-tool (first (filter #(= "write" (:name %)) tools-vec))
              edit-tool  (first (filter #(= "edit" (:name %)) tools-vec))]
          ;; read
          (let [r ((:execute read-tool) {"path" "scoped.txt"})]
            (is (false? (:is-error r)))
            (is (= "scoped-content" (:content r))))
          ;; bash
          (let [r ((:execute bash-tool) {"command" "cat scoped.txt"})]
            (is (false? (:is-error r)))
            (is (str/includes? (:content r) "scoped-content")))
          ;; write
          (let [r ((:execute write-tool) {"path" "new.txt" "content" "new-content"})]
            (is (false? (:is-error r)))
            (is (= "new-content" (slurp (io/file dir "new.txt")))))
          ;; edit
          (let [r ((:execute edit-tool) {"path" "new.txt" "oldText" "new-content" "newText" "edited"})]
            (is (false? (:is-error r)))
            (is (= "edited" (slurp (io/file dir "new.txt"))))))))))

(deftest execute-ls-find-grep-test
  (testing "ls lists sorted entries with directory suffix and empty-directory message"
    (with-temp-dir
      (fn [dir]
        (let [empty-res (tools/execute-ls {"path" dir})]
          (is (= "(empty directory)" (:content empty-res))))
        (spit (io/file dir "b.txt") "b")
        (spit (io/file dir "A.txt") "a")
        (.mkdirs (io/file dir "zdir"))
        (let [res (tools/execute-ls {"path" dir})]
          (is (false? (:is-error res)))
          (is (str/includes? (:content res) "A.txt"))
          (is (str/includes? (:content res) "b.txt"))
          (is (str/includes? (:content res) "zdir/"))))))

  (testing "ls applies semantic entry limit before byte truncation"
    (with-temp-dir
      (fn [dir]
        (doseq [i (range 10)]
          (spit (io/file dir (str "f" i ".txt")) "x"))
        (let [limit-res (tools/execute-ls {"path" dir "limit" 3})
              bytes-res (tools/execute-ls {"path" dir "limit" 10}
                                          {:overrides {"ls" {:max-bytes 8}}})]
          (is (= 3 (get-in limit-res [:details :entry-limit-reached])))
          (is (str/includes? (:content limit-res) "limit reached"))
          (is (true? (get-in bytes-res [:details :truncation :truncated])))))))

  (testing "find supports glob pattern, no-results, and result limit metadata"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "a.clj") "(ns a)")
        (spit (io/file dir "b.txt") "hello")
        (spit (io/file dir "c.clj") "(ns c)")
        (let [res       (tools/execute-find {"path" dir "pattern" "*.clj"})
              none-res  (tools/execute-find {"path" dir "pattern" "*.md"})
              limit-res (tools/execute-find {"path" dir "pattern" "*" "limit" 1})]
          (is (false? (:is-error res)))
          (is (str/includes? (:content res) ".clj"))
          (is (= "No files found matching pattern" (:content none-res)))
          (is (= 1 (get-in limit-res [:details :result-limit-reached])))
          (is (str/includes? (:content limit-res) "limit reached"))))))

  (testing "grep supports literal/ignore-case/context, no-match, and truncation metadata"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "g.txt") "Alpha\nBeta\nalpha\n")
        (let [res      (tools/execute-grep {"path" dir "pattern" "alpha" "ignoreCase" true})
              lit-res  (tools/execute-grep {"path" dir "pattern" "Alpha" "literal" true})
              none-res (tools/execute-grep {"path" dir "pattern" "zzz"})
              line-res (tools/execute-grep {"path" dir "pattern" "A"}
                                           {:overrides {"grep" {:max-bytes 30}}})]
          (is (false? (:is-error res)))
          (is (str/includes? (:content res) "alpha"))
          (is (false? (:is-error lit-res)))
          (is (= "No matches found" (:content none-res)))
          (is (true? (get-in line-res [:details :truncation :truncated]))))))))

(deftest make-read-only-tools-with-cwd-test
  (testing "returns read-only/search tools in canonical order"
    (with-temp-dir
      (fn [dir]
        (let [tools-vec (tools/make-read-only-tools-with-cwd dir)]
          (is (= ["read" "grep" "find" "ls"] (mapv :name tools-vec)))))))

  (testing "read-only/search tools execute in scoped cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "notes.txt") "hello alpha")
        (let [tools-vec  (tools/make-read-only-tools-with-cwd dir)
              read-tool  (first (filter #(= "read" (:name %)) tools-vec))
              grep-tool  (first (filter #(= "grep" (:name %)) tools-vec))
              find-tool  (first (filter #(= "find" (:name %)) tools-vec))
              ls-tool    (first (filter #(= "ls" (:name %)) tools-vec))]
          (is (str/includes? (:content ((:execute read-tool) {"path" "notes.txt"})) "hello alpha"))
          (is (str/includes? (:content ((:execute grep-tool) {"path" "." "pattern" "alpha"})) "alpha"))
          (is (str/includes? (:content ((:execute find-tool) {"path" "." "pattern" "*.txt"})) "notes.txt"))
          (is (str/includes? (:content ((:execute ls-tool) {"path" "."})) "notes.txt")))))))
