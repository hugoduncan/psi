(ns psi.agent-session.tools-cwd-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tools :as tools]))

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
          (is (= "edit" (get-in result [:meta :tool-name])))
          (is (= [{:type "file/edit"
                   :path (.getPath (io/file dir "edit-me.txt"))
                   :worktree-path dir
                   :first-changed-line (get-in result [:details :first-changed-line])}]
                 (:effects result)))
          (is (= [] (:enrichments result)))
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
          (let [r ((:execute read-tool) {"path" "scoped.txt"})]
            (is (false? (:is-error r)))
            (is (= "scoped-content" (:content r))))
          (let [r ((:execute bash-tool) {"command" "cat scoped.txt"})]
            (is (false? (:is-error r)))
            (is (str/includes? (:content r) "scoped-content")))
          (let [r ((:execute write-tool) {"path" "new.txt" "content" "new-content"})]
            (is (false? (:is-error r)))
            (is (= "new-content" (slurp (io/file dir "new.txt")))))
          (let [r ((:execute edit-tool) {"path" "new.txt" "oldText" "new-content" "newText" "edited"})]
            (is (false? (:is-error r)))
            (is (= "edited" (slurp (io/file dir "new.txt"))))))))))

(deftest make-read-only-tools-with-cwd-test
  (testing "returns read-only tools in canonical order"
    (with-temp-dir
      (fn [dir]
        (let [tools-vec (tools/make-read-only-tools-with-cwd dir)]
          (is (= ["read"] (mapv :name tools-vec)))))))

  (testing "read-only tools execute in scoped cwd"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "notes.txt") "hello alpha")
        (let [tools-vec (tools/make-read-only-tools-with-cwd dir)
              read-tool (first (filter #(= "read" (:name %)) tools-vec))]
          (is (str/includes? (:content ((:execute read-tool) {"path" "notes.txt"})) "hello alpha")))))))
