(ns psi.workflow-loader.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.core :as loader]))

(defn- with-temp-workflow-dir
  [files f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wf-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (doseq [[filename content] files]
        (spit (io/file dir filename) content))
      (f (.getAbsolutePath dir))
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn- with-project-loader-result
  [files f]
  (with-temp-workflow-dir
    files
    (fn [dir]
      (with-redefs [loader/global-workflow-dirs (constantly [])
                    loader/project-workflow-dir (constantly dir)]
        (f dir (loader/load-workflow-definitions dir))))))

(def planner-md
  "---\nname: planner\ndescription: Plans tasks\ntools:\n  - read\n---\nYou are a planner.")

(def builder-md
  "---\nname: builder\ndescription: Builds code\ntools:\n  - read\n  - bash\n---\nYou are a builder.")

(def plan-build-edn
  "{:name \"plan-build\"\n :description \"Plan and build without review\"\n :definition-id \"plan-build\"\n :steps [{:name \"plan\"\n          :type :session\n          :contributions [{:type :template\n                           :text \"{{input}}\"\n                           :vars {\"input\" {:from :workflow-input :path [:input]}}}]}]}")

(deftest scan-directory-test
  (testing "scans directory for .md and .edn workflow files"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "plan-build.edn" plan-build-edn
       "ignored.txt" "nope"}
      (fn [dir]
        (let [results (loader/scan-directory dir)]
          (is (= 2 (count results)))
          (is (= #{:md :edn} (set (map :file-kind results))))))))

  (testing "returns empty for non-existent directory"
    (is (empty? (loader/scan-directory "/tmp/nonexistent-workflow-dir-xyz")))))

(deftest load-workflow-definitions-test
  (testing "loads markdown single-step and edn multi-step workflows from a project directory"
    (with-project-loader-result
      {"planner.md" planner-md
       "plan-build.edn" plan-build-edn}
      (fn [_dir {:keys [definitions errors warnings]}]
        (is (= #{"planner" "plan-build"} (set (keys definitions))))
        (is (empty? errors))
        (is (empty? warnings))
        (is (= 1 (count (get-in definitions ["planner" :steps]))))
        (is (= :session (get-in definitions ["planner" :steps 0 :type])))
        (is (= :session (get-in definitions ["plan-build" :steps 0 :type]))))))

  (testing "same-kind duplicate names warn and later root wins"
    (with-temp-workflow-dir
      {"planner.md" "---\nname: planner\ndescription: Global planner\n---\nGlobal planner."}
      (fn [global-dir]
        (with-temp-workflow-dir
          {"planner.md" "---\nname: planner\ndescription: Project planner\n---\nProject planner."}
          (fn [project-dir]
            (with-redefs [loader/global-workflow-dirs (constantly [global-dir])
                          loader/project-workflow-dir (constantly project-dir)]
              (let [{:keys [definitions errors warnings]} (loader/load-workflow-definitions project-dir)]
                (is (empty? errors))
                (is (= "Project planner" (:summary (get definitions "planner"))))
                (is (= 1 (count warnings)))
                (is (re-find #"Duplicate workflow name `planner` for `\.md` files"
                             (:message (first warnings)))))))))))

  (testing "mixed-kind duplicate names fail clearly and do not load either definition"
    (with-project-loader-result
      {"planner.md" planner-md
       "planner.edn" "{:name \"planner\" :description \"Plans tasks as EDN\" :definition-id \"planner\" :steps [{:name \"step\" :type :session :contributions [{:type :template :text \"hi\" :vars {}}]}]}"}
      (fn [_dir {:keys [definitions errors]}]
        (is (empty? definitions))
        (is (= 1 (count errors)))
        (is (= "planner" (:name (first errors))))
        (is (re-find #"defined by both `\.md` and `\.edn` files"
                     (:error (first errors))))))))