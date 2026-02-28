(ns psi.agent-session.bash-tool-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.tools :as tools]))

(defn- with-temp-dir
  "Create a temp dir, run f with its path, clean up."
  [f]
  (let [tmp (java.io.File/createTempFile "psi-bash-test" "")]
    (.delete tmp)
    (.mkdirs tmp)
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (doseq [file (reverse (file-seq tmp))]
          (.delete file))))))

;;; Normal execution

(deftest execute-bash-normal-test
  (testing "simple command returns output"
    (let [result (tools/execute-bash {"command" "echo hello"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "hello"))
      (is (nil? (:details result)))))

  (testing "command with no output returns (no output)"
    (let [result (tools/execute-bash {"command" "true"})]
      (is (false? (:is-error result)))
      (is (= "(no output)" (:content result)))))

  (testing "stderr is merged into output"
    (let [result (tools/execute-bash {"command" "echo out; echo err >&2"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "out"))
      (is (str/includes? (:content result) "err")))))

;;; Non-zero exit

(deftest execute-bash-non-zero-exit-test
  (testing "non-zero exit sets is-error and includes exit code"
    (let [result (tools/execute-bash {"command" "exit 42"})]
      (is (true? (:is-error result)))
      (is (str/includes? (:content result) "Command exited with code 42"))))

  (testing "non-zero exit with output includes both"
    (let [result (tools/execute-bash {"command" "echo before-fail; exit 1"})]
      (is (true? (:is-error result)))
      (is (str/includes? (:content result) "Command exited with code 1"))
      (is (str/includes? (:content result) "before-fail")))))

;;; Tail truncation

(deftest execute-bash-truncation-test
  (testing "output within limits has no truncation details"
    (let [result (tools/execute-bash {"command" "echo short"} {:overrides {}})]
      (is (false? (:is-error result)))
      (is (nil? (:details result)))))

  (testing "output exceeding line limit is tail-truncated with spill file"
    ;; Use a very small policy to force truncation
    (let [result (tools/execute-bash
                  {"command" "seq 1 100"}
                  {:overrides {"bash" {:max-lines 5 :max-bytes 25600}}})]
      (is (false? (:is-error result)))
      ;; Should have truncation details
      (is (some? (:details result)))
      (is (true? (get-in result [:details :truncation :truncated])))
      (is (= 100 (get-in result [:details :truncation :total-lines])))
      (is (<= (get-in result [:details :truncation :output-lines]) 5))
      ;; Should have full output path
      (let [spill-path (get-in result [:details :full-output-path])]
        (is (some? spill-path))
        (is (.exists (io/file spill-path)))
        ;; Full output file should contain all 100 lines
        (let [full-content (slurp spill-path)]
          (is (= 100 (count (str/split-lines full-content))))))
      ;; Content should mention truncation and path
      (is (str/includes? (:content result) "truncated"))
      (is (str/includes? (:content result) "100 total lines"))
      (is (str/includes? (:content result) "Full output:"))))

  (testing "output exceeding byte limit is tail-truncated"
    (let [result (tools/execute-bash
                  {"command" "seq 1 1000"}
                  {:overrides {"bash" {:max-lines 10000 :max-bytes 50}}})]
      (is (false? (:is-error result)))
      (is (some? (:details result)))
      (is (true? (get-in result [:details :truncation :truncated])))
      (is (= :bytes (get-in result [:details :truncation :truncated-by]))))))

;;; Timeout

(deftest execute-bash-timeout-test
  (testing "command that exceeds timeout returns error"
    (let [result (tools/execute-bash {"command" "sleep 10" "timeout" 1})]
      (is (true? (:is-error result)))
      (is (str/includes? (:content result) "Command timed out after 1 seconds"))
      (is (nil? (:details result)))))

  (testing "command that finishes before timeout succeeds"
    (let [result (tools/execute-bash {"command" "echo fast" "timeout" 5})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "fast")))))

;;; CWD support

(deftest execute-bash-cwd-test
  (testing "bash runs in cwd when provided"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "marker.txt") "found-it")
        (let [result (tools/execute-bash {"command" "cat marker.txt"} {:cwd dir})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "found-it"))))))

  (testing "bash without cwd works as before"
    (let [result (tools/execute-bash {"command" "echo hello"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "hello")))))

;;; Command prefix

(deftest execute-bash-command-prefix-test
  (testing "command prefix is prepended to command"
    (let [result (tools/execute-bash
                  {"command" "echo $MY_VAR"}
                  {:command-prefix "export MY_VAR=hello"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "hello"))))

  (testing "command without prefix works normally"
    (let [result (tools/execute-bash {"command" "echo no-prefix"})]
      (is (false? (:is-error result)))
      (is (str/includes? (:content result) "no-prefix")))))

;;; Tool call ID for spill files

(deftest execute-bash-tool-call-id-test
  (testing "tool-call-id is used in spill file name"
    (let [result (tools/execute-bash
                  {"command" "seq 1 100"}
                  {:overrides {"bash" {:max-lines 5}}
                   :tool-call-id "test-call-123"})]
      (when-let [path (get-in result [:details :full-output-path])]
        (is (str/includes? path "bash-test-call-123"))))))

;;; Result shape

(deftest execute-bash-result-shape-test
  (testing "non-truncated result has correct shape"
    (let [result (tools/execute-bash {"command" "echo ok"})]
      (is (contains? result :content))
      (is (contains? result :is-error))
      (is (false? (:is-error result)))
      ;; details is nil when no truncation
      (is (nil? (:details result)))))

  (testing "truncated result has correct shape"
    (let [result (tools/execute-bash
                  {"command" "seq 1 50"}
                  {:overrides {"bash" {:max-lines 3}}})]
      (is (contains? result :content))
      (is (contains? result :is-error))
      (is (contains? result :details))
      (let [details (:details result)]
        (is (contains? details :truncation))
        (is (contains? details :full-output-path))
        (let [trunc (:truncation details)]
          (is (contains? trunc :truncated))
          (is (contains? trunc :truncated-by))
          (is (contains? trunc :total-lines))
          (is (contains? trunc :total-bytes))
          (is (contains? trunc :output-lines))
          (is (contains? trunc :output-bytes))
          (is (contains? trunc :max-lines))
          (is (contains? trunc :max-bytes))
          (is (contains? trunc :last-line-partial)))))))

;;; Abort mechanism

(deftest abort-bash-test
  (testing "abort-bash! returns false when no process running"
    (is (not (tools/abort-bash!)))))
