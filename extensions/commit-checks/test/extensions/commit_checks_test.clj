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

(defn- mkdirs! [path]
  (.mkdirs (io/file path))
  path)

(defn- write-lines! [path line-count]
  (.mkdirs (.getParentFile (io/file path)))
  (spit path (str (str/join "\n" (repeat line-count "x")) "\n"))
  path)

(defn- write-bb-edn! [workspace-dir]
  (spit (io/file workspace-dir "bb.edn")
        (pr-str
         {:tasks
          {'commit-check:file-lengths
           {:doc "Fail when src/ or test/ files under components/, bases/, or extensions/ exceed 800 lines; recorded legacy oversized extension files fail if they grow."
            :requires '([babashka.tasks :refer [shell]]
                        [clojure.string :as str])
            :task
            '(let [legacy-max-lines {"extensions/legacy/test/extensions/legacy_test.clj" 974}
                   result (shell {:out :string}
                                 "find" "components" "bases" "extensions" "-type" "f"
                                 "(" "-path" "*/src/*" "-o" "-path" "*/test/*" ")"
                                 "-exec" "wc" "-l" "{}" ";")
                   lines (str/split-lines (:out result))
                   bad (->> lines
                            (keep (fn [line]
                                    (let [[_ n path] (re-matches (re-pattern "\\s*(\\d+)\\s+(.+)") line)
                                          line-count (some-> n Long/parseLong)
                                          limit (get legacy-max-lines path 800)]
                                      (when (and line-count path (> line-count limit))
                                        (str path " (" n " lines, " limit " limit)")))))
                            vec)]
               (when (seq bad)
                 (binding [*out* *err*]
                   (println "Files longer than allowed line limits:")
                   (doseq [line bad]
                     (println line)))
                 (System/exit 1)))}}})))

(defn- run-file-length-check [workspace-dir]
  (proc/shell {:dir workspace-dir
               :continue true
               :out :string
               :err :string}
              "bb" "commit-check:file-lengths"))

(defn- combined-output [{:keys [out err]}]
  (str out err))

(deftest file-length-check-scans-extensions-and-enforces-legacy-ratchet-test
  ;; Verifies that commit-check:file-lengths scans extensions/ and preserves
  ;; legacy ratchet semantics for recorded oversized extension files.
  (let [workspace-dir (temp-dir)
        offender "extensions/sample/test/extensions/too_long_test.clj"
        legacy "extensions/legacy/test/extensions/legacy_test.clj"
        legacy-file (io/file workspace-dir legacy)]
    (write-bb-edn! workspace-dir)
    (doseq [root ["components" "bases" "extensions"]]
      (mkdirs! (io/file workspace-dir root)))
    (write-lines! (io/file workspace-dir offender) 801)
    (write-lines! legacy-file 974)
    (let [result (run-file-length-check workspace-dir)
          output (combined-output result)]
      (is (pos? (:exit result)))
      (is (str/includes? output offender))
      (is (str/includes? output "800 limit"))
      (is (not (str/includes? output legacy))))
    (io/delete-file (io/file workspace-dir offender))
    (let [result (run-file-length-check workspace-dir)]
      (is (zero? (:exit result)) (combined-output result)))
    (write-lines! legacy-file 975)
    (let [result (run-file-length-check workspace-dir)
          output (combined-output result)]
      (is (pos? (:exit result)))
      (is (str/includes? output legacy))
      (is (str/includes? output "974 limit")))))

(deftest file-length-check-still-scans-components-and-bases-test
  ;; Verifies that widening the commit check to extensions/ did not regress the
  ;; original components/ and bases/ src/test scan roots.
  (doseq [[root file] {"components" "components/sample/src/sample/core.clj"
                       "bases" "bases/sample/test/sample/base_test.clj"}]
    (let [workspace-dir (temp-dir)]
      (write-bb-edn! workspace-dir)
      (doseq [root* ["components" "bases" "extensions"]]
        (mkdirs! (io/file workspace-dir root*)))
      (write-lines! (io/file workspace-dir file) 801)
      (let [result (run-file-length-check workspace-dir)
            output (combined-output result)]
        (is (pos? (:exit result)) root)
        (is (str/includes? output file))
        (is (str/includes? output "800 limit"))))))

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
        (is (not (.contains (str (:content prompt)) "## ok")))))))

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
