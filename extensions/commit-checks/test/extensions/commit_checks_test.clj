(ns extensions.commit-checks-test
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [extensions.commit-checks :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(defn- temp-dir []
  (.getCanonicalPath (doto (java.io.File/createTempFile "psi-commit-checks" "")
                       (.delete)
                       (.mkdirs))))

(defn- write-config! [workspace-dir cfg]
  (let [f (io/file workspace-dir ".psi" "commit-checks.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str cfg))
    (.getCanonicalPath f)))

(defn- write-lines! [path line-count]
  (.mkdirs (.getParentFile (io/file path)))
  (spit path (str (str/join "\n" (repeat line-count "x")) "\n"))
  path)

(def repo-root
  (.getCanonicalPath (io/file ".")))

(defn- read-bb-init-form []
  (get-in (read-string (slurp (io/file repo-root "bb.edn"))) [:tasks :init]))

(defn- find-top-level-def [form var-symbol]
  (some (fn [node]
          (when (and (seq? node) (= 'def (first node)) (= var-symbol (second node)))
            (nth node 2)))
        (tree-seq coll? seq form)))

(defn- file-length-helper-forms []
  (let [wanted #{'file-length-scan-roots
                 'file-length-legacy-max-lines
                 'file-length-find-roots
                 'file-length-find-args
                 'file-length-violations}]
    (->> (tree-seq coll? seq (read-bb-init-form))
         (filter (fn [node]
                   (and (seq? node)
                        (#{'def 'defn} (first node))
                        (contains? wanted (second node)))))
         vec)))

(defn- legacy-file-length-limits []
  (or (find-top-level-def (read-bb-init-form) 'file-length-legacy-max-lines)
      (throw (ex-info "Unable to find commit-check:file-lengths legacy ratchet map"
                      {:var 'file-length-legacy-max-lines}))))

(defn- run-file-length-check []
  (proc/shell {:dir repo-root
               :continue true
               :out :string
               :err :string}
              "bb" "commit-check:file-lengths"))

(defn- delete-file-if-exists! [path]
  (let [f (io/file repo-root path)]
    (when (.exists f)
      (io/delete-file f))))

(defn- delete-empty-parents! [path stop-path]
  (let [stop-file (.getCanonicalFile (io/file repo-root stop-path))]
    (loop [dir (.getParentFile (.getCanonicalFile (io/file repo-root path)))]
      (when (and dir
                 (str/starts-with? (.getPath dir) (.getPath stop-file))
                 (empty? (seq (.list dir))))
        (io/delete-file dir)
        (recur (.getParentFile dir))))))

(defn- with-temporary-oversized-file! [path f]
  (let [file (io/file repo-root path)]
    (try
      (write-lines! file 801)
      (f)
      (finally
        (delete-file-if-exists! path)))))

(defn- with-temporary-oversized-file-and-cleanup! [path cleanup-root f]
  (try
    (with-temporary-oversized-file! path f)
    (finally
      (delete-empty-parents! path cleanup-root))))

(defn- with-temporary-growth! [path f]
  (let [file     (io/file repo-root path)
        original (slurp file)]
    (try
      (spit file (str original "x\n"))
      (f)
      (finally
        (spit file original)))))

(defn- combined-output [{:keys [out err]}]
  (str out err))

(deftest file-length-check-helpers-handle-sparse-repo-shapes-test
  ;; Verifies the shared implementation used by the real bb.edn task skips
  ;; absent optional scan roots and treats empty/matchless scanned roots as no
  ;; violations. This protects sparse checkout/subtree shapes without relying
  ;; on the full current repository layout.
  (let [forms       (file-length-helper-forms)
        eval-ns-sym (gensym "psi.commit-checks-test.bb-eval")
        eval-ns     (create-ns eval-ns-sym)]
    (binding [*ns* eval-ns]
      (refer 'clojure.core)
      (eval '(require '[clojure.java.io :as io]))
      (eval '(require '[clojure.string :as str]))
      (doseq [form forms]
        (eval form))
      (let [workspace      (io/file (temp-dir))
            find-roots     (ns-resolve eval-ns-sym 'file-length-find-roots)
            find-args      (ns-resolve eval-ns-sym 'file-length-find-args)
            violations     (ns-resolve eval-ns-sym 'file-length-violations)
            scan-roots-var (ns-resolve eval-ns-sym 'file-length-scan-roots)]
        (doseq [root ["components" "bases" "extensions"]]
          (alter-var-root scan-roots-var
                          (constantly [(.getPath (io/file workspace root))]))
          (is (= [] (find-roots)) root)
          (is (= [] (violations "")) root)
          (.mkdirs (io/file workspace root "docs"))
          (is (= [(.getPath (io/file workspace root))]
                 (vec (find-roots))) root)
          (let [result (apply proc/shell
                              {:continue true :out :string :err :string}
                              "find"
                              (find-args))]
            (is (zero? (:exit result)) (str root ": " (combined-output result)))
            (is (= "" (:out result)) root)
            (is (= [] (violations (:out result))) root)))))))

(deftest file-length-check-scans-extensions-with-real-task-test
  ;; Verifies the real bb.edn commit-check:file-lengths task scans extensions/
  ;; and reports a controlled oversized test file with the default 800 limit.
  (let [offender "extensions/commit-checks/test/extensions/too_long_real_task_probe.clj"]
    (with-temporary-oversized-file!
      offender
      (fn []
        (let [result (run-file-length-check)
              output (combined-output result)]
          (is (pos? (:exit result)))
          (is (str/includes? output offender))
          (is (str/includes? output "800 limit")))))))

(deftest file-length-check-still-scans-components-and-bases-with-real-task-test
  ;; Verifies the real bb.edn task still scans the original components/ and
  ;; bases/ src/test roots after the extensions/ widening.
  (doseq [[root file] {"components" "components/commit_check_file_lengths_probe/src/probe/core.clj"
                       "bases" "bases/commit_check_file_lengths_probe/test/probe/base_test.clj"}]
    (with-temporary-oversized-file-and-cleanup!
      file
      (str root "/commit_check_file_lengths_probe")
      (fn []
        (let [result (run-file-length-check)
              output (combined-output result)]
          (is (pos? (:exit result)) root)
          (is (str/includes? output file))
          (is (str/includes? output "800 limit")))))))

(deftest file-length-check-enforces-real-legacy-ratchets-test
  ;; Verifies every real legacy ratchet path in bb.edn passes at its recorded
  ;; line count and fails when it grows beyond its path-specific limit.
  (let [baseline (run-file-length-check)]
    (is (zero? (:exit baseline)) (combined-output baseline)))
  (doseq [[path limit] (legacy-file-length-limits)]
    (with-temporary-growth!
      path
      (fn []
        (let [result (run-file-length-check)
              output (combined-output result)]
          (is (pos? (:exit result)) path)
          (is (str/includes? output path))
          (is (str/includes? output (str limit " limit")))))))
  (let [restored (run-file-length-check)]
    (is (zero? (:exit restored)) (combined-output restored))))

(deftest init-registers-handler-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})]
    (sut/init api)
    (is (= 1 (count (get-in @state [:handlers "git_commit_created"]))))))

(deftest missing-config-does-nothing-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc"})
      (is (= [] (:messages @state))))))

(deftest all-success-does-not-send-prompt-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "ok" :cmd ["bash" "-lc" "exit 0"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc"})
      (is (= [] (:messages @state))))))

(deftest failures-send-one-prompt-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "bad-1" :cmd ["bash" "-lc" "echo bad1 && exit 1"]}
                               {:id "ok" :cmd ["bash" "-lc" "echo ok && exit 0"]}
                               {:id "bad-2" :cmd ["bash" "-lc" "echo bad2 1>&2 && exit 2"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [messages (:messages @state)
            prompt   (first (filter #(= "extension-prompt" (:custom-type %)) messages))
            notice   (first (filter #(= "commit-checks" (:custom-type %)) messages))]
        (is (some? prompt))
        (is (some? notice))
        (is (.contains (str (:content prompt)) "workspace-dir: "))
        (is (.contains (str (:content prompt)) "session-id: s1"))
        (is (.contains (str (:content prompt)) "commit: abc123"))
        (is (.contains (str (:content prompt)) "## bad-1"))
        (is (.contains (str (:content prompt)) "## bad-2"))
        (is (not (.contains (str (:content prompt)) "## ok")))
        (is (= "Please inspect these failures and make the minimal necessary fixes."
               (last (str/split-lines (:content prompt)))))))))

(deftest failure-footers-are-rendered-per-failing-command-test
  ;; Verifies configured instructions remain attached to their failed command,
  ;; while absent, empty, and successful commands add no footer text.
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)
        global-footer "Please inspect these failures and make the minimal necessary fixes."
        lint-footer "Fix lint output."
        test-footer "Fix test output."]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "lint"
                                :cmd ["bash" "-lc" "echo lint-output && exit 1"]
                                :footer lint-footer}
                               {:id "no-footer"
                                :cmd ["bash" "-lc" "echo no-footer-output && exit 1"]}
                               {:id "empty-footer"
                                :cmd ["bash" "-lc" "echo empty-footer-output && exit 1"]
                                :footer ""}
                               {:id "test"
                                :cmd ["bash" "-lc" "echo test-output && exit 1"]
                                :footer test-footer}
                               {:id "success"
                                :cmd ["bash" "-lc" "echo success-output && exit 0"]
                                :footer "Successful checks must not appear."}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [prompt (->> (:messages @state)
                        (filter #(= "extension-prompt" (:custom-type %)))
                        first
                        :content)
            lint-index (.indexOf prompt lint-footer)
            test-index (.indexOf prompt test-footer)
            global-index (.indexOf prompt global-footer)]
        (is (string? prompt))
        (is (< (.indexOf prompt "lint-output") lint-index (.indexOf prompt "## no-footer")))
        (is (< (.indexOf prompt "test-output") test-index global-index))
        (is (neg? (.indexOf prompt "Successful checks must not appear.")))
        (is (neg? (.indexOf prompt "## success")))
        (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote global-footer)) prompt))))
        (is (= global-footer (subs prompt global-index)))))))

(deftest timed-out-command-footer-precedes-global-trailer-test
  ;; Timeout failures follow the same command-local footer path as exits.
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)
        timeout-ms 50
        timeout-output (str "Command timed out after " timeout-ms "ms")
        command-footer "Inspect and fix the timed-out check."
        global-footer "Please inspect these failures and make the minimal necessary fixes."]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "slow"
                                :cmd ["bash" "-lc" "sleep 1"]
                                :timeout-ms timeout-ms
                                :footer command-footer}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [prompt (->> (:messages @state)
                        (filter #(= "extension-prompt" (:custom-type %)))
                        first
                        :content)
            timeout-index (.indexOf prompt timeout-output)
            footer-index (.indexOf prompt command-footer)
            global-index (.indexOf prompt global-footer)]
        (is (string? prompt))
        (is (< timeout-index footer-index global-index))
        (is (= global-footer (subs prompt global-index)))))))

(deftest handler-prefers-workspace-dir-over-cwd-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)
        wrong-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :commands [{:id "pwd-check" :cmd ["bash" "-lc" "pwd && exit 1"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1"
                :workspace-dir workspace-dir
                :cwd wrong-dir
                :head "abc123"})
      (let [prompt (first (filter #(= "extension-prompt" (:custom-type %)) (:messages @state)))]
        (is (some? prompt))
        (is (.contains (str (:content prompt)) (str "workspace-dir: " workspace-dir)))
        (is (.contains (str (:content prompt)) workspace-dir))
        (is (not (.contains (str (:content prompt)) (str "workspace-dir: " wrong-dir))))))))

(deftest prompt-output-is-truncated-test
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/commit_checks.clj"})
        workspace-dir (temp-dir)]
    (write-config! workspace-dir
                   {:enabled true
                    :max-output-chars 20
                    :commands [{:id "long" :cmd ["bash" "-lc" "printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' 1>&2; exit 1"]}]})
    (sut/init api)
    (let [handler (first (get-in @state [:handlers "git_commit_created"]))]
      (handler {:session-id "s1" :workspace-dir workspace-dir :head "abc123"})
      (let [prompt (first (filter #(= "extension-prompt" (:custom-type %)) (:messages @state)))]
        (is (some? prompt))
        (is (.contains (str (:content prompt)) "[output truncated to 20 chars]"))))))
