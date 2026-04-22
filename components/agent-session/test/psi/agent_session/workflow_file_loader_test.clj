(ns psi.agent-session.workflow-file-loader-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-loader :as loader]
   [psi.agent-session.workflow-model :as workflow-model]))

(defn- with-temp-workflow-dir
  "Create a temp directory with workflow files, call f with the dir path, then cleanup."
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

(def planner-md
  (str "---\nname: planner\ndescription: Plans tasks\n---\n"
       "You are a planner."))

(def builder-md
  (str "---\nname: builder\ndescription: Builds code\n---\n"
       "{:tools [\"read\" \"bash\" \"edit\" \"write\"]}\n\n"
       "You are a builder agent."))

(def reviewer-md
  (str "---\nname: reviewer\ndescription: Reviews code\n---\n"
       "You are a reviewer."))

(def chain-md
  (str "---\nname: plan-build-review\ndescription: Plan, build, and review\n---\n"
       "{:steps [{:workflow \"planner\" :prompt \"$INPUT\"}\n"
       "         {:workflow \"builder\" :prompt \"Execute: $INPUT\\nOriginal: $ORIGINAL\"}\n"
       "         {:workflow \"reviewer\" :prompt \"Review: $INPUT\\nOriginal: $ORIGINAL\"}]}\n\n"
       "Coordinate a plan-build-review cycle."))

(def bad-md
  "---\nname: broken\n---\nNo description.")

(deftest scan-directory-test
  (testing "scans directory for .md files and parses them"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "builder.md" builder-md
       "not-a-workflow.txt" "ignored"}
      (fn [dir]
        (let [results (loader/scan-directory dir)]
          (is (= 2 (count results)))
          (is (= #{"planner" "builder"}
                 (set (map :name results))))
          (is (every? :source-path results))))))

  (testing "returns empty for non-existent directory"
    (is (empty? (loader/scan-directory "/tmp/nonexistent-workflow-dir-xyz")))))

(deftest load-workflow-definitions-test
  (testing "loads and compiles workflow files from a project directory"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "builder.md" builder-md}
      (fn [dir]
        ;; Override scanning to use only our temp dir
        (with-redefs [loader/global-workflow-dirs (constantly [])
                      loader/project-workflow-dir (constantly dir)]
          (let [{:keys [definitions errors]} (loader/load-workflow-definitions dir)]
            (is (= #{"planner" "builder"} (set (keys definitions))))
            (is (empty? errors))
            (is (every? workflow-model/valid-workflow-definition?
                        (vals definitions))))))))

  (testing "multi-step definitions compile with step references resolved"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "builder.md" builder-md
       "reviewer.md" reviewer-md
       "plan-build-review.md" chain-md}
      (fn [dir]
        (with-redefs [loader/global-workflow-dirs (constantly [])
                      loader/project-workflow-dir (constantly dir)]
          (let [{:keys [definitions errors]} (loader/load-workflow-definitions dir)]
            (is (= 4 (count definitions)))
            (is (contains? definitions "plan-build-review"))
            (is (= 3 (count (get-in definitions ["plan-build-review" :step-order]))))
            (is (empty? errors)))))))

  (testing "unresolved step references reported as errors"
    (with-temp-workflow-dir
      {"plan-build-review.md" chain-md}
      (fn [dir]
        (with-redefs [loader/global-workflow-dirs (constantly [])
                      loader/project-workflow-dir (constantly dir)]
          (let [{:keys [definitions errors]} (loader/load-workflow-definitions dir)]
            ;; Definition still compiled, but errors reported for missing refs
            (is (= 1 (count definitions)))
            (is (seq errors))
            (is (some #(re-find #"unknown workflow" (:error %)) errors)))))))

  (testing "parse errors collected separately from successful compilations"
    (with-temp-workflow-dir
      {"planner.md" planner-md
       "broken.md" bad-md}
      (fn [dir]
        (with-redefs [loader/global-workflow-dirs (constantly [])
                      loader/project-workflow-dir (constantly dir)]
          (let [{:keys [definitions errors]} (loader/load-workflow-definitions dir)]
            (is (= 1 (count definitions)))
            (is (contains? definitions "planner"))
            (is (= 1 (count errors)))))))))

(deftest directory-precedence-test
  (testing "project definitions override global definitions with same name"
    (let [global-planner "---\nname: planner\ndescription: Global planner\n---\nGlobal."
          project-planner "---\nname: planner\ndescription: Project planner\n---\nProject."]
      (with-temp-workflow-dir
        {"planner.md" global-planner}
        (fn [global-dir]
          (with-temp-workflow-dir
            {"planner.md" project-planner}
            (fn [project-dir]
              (with-redefs [loader/global-workflow-dirs (constantly [global-dir])
                            loader/project-workflow-dir (constantly project-dir)]
                (let [{:keys [definitions]} (loader/load-workflow-definitions project-dir)]
                  (is (= "Project planner" (:summary (get definitions "planner")))))))))))))
