(ns extensions.context-manager-friction-analysis-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/friction-helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(def ^:private issue-output
  (str "ISSUE: slow-tests | Test suite is slow\n"
       "FRICTION: bb test takes minutes\n"
       "EVIDENCE: turn 3 waited 5 minutes for feedback\n"
       "SUGGESTION: add a faster focused test runner\n"))

(defn- collaborators
  [overrides]
  (merge
   {:select-model  (fn [_sid] {:provider :ollama :id "qwen"})
    :run-helper    (fn [_opts] {:child-session-id "helper-1" :text issue-output})
    :fetch-history (fn [_sid] "history excerpt")
    :session-info  (fn [_sid] {:worktree-root "/repo" :session-name "top-level"})
    :list-tasks    (fn [_root] {:open [] :recent-closed []})
    :create-task!  (fn [_root issue] (str "042-" (:slug issue)))}
   overrides))

(deftest issue-creates-task-test
  (testing "a detected issue with no dedup match creates a task"
    (let [created (atom nil)
          result (context-manager/friction-analysis
                  {} {:session-id "s1"}
                  (collaborators {:create-task! (fn [_root issue]
                                                  (reset! created issue)
                                                  "042-slow-tests")}))]
      (is (= :success (:status result)))
      (is (= ["042-slow-tests"] (:created-task-ids result)))
      (is (= "slow-tests" (:slug @created))))))

(deftest duplicate-skipped-test
  (testing "a duplicate issue is skipped and logged, no task created"
    (let [logged (atom [])
          dup-output "DUPLICATE: slow-tests ~ 001-slow-tests\n"
          result (context-manager/friction-analysis
                  {:log #(swap! logged conj %)}
                  {:session-id "s1"}
                  (collaborators {:run-helper (fn [_opts]
                                                {:child-session-id "h1" :text dup-output})}))]
      (is (= :success (:status result)))
      (is (= [] (:created-task-ids result)))
      (is (= [{:slug "slow-tests" :existing-id "001-slow-tests"}]
             (:duplicate-diagnostics result)))
      (is (some #(re-find #"duplicate" %) @logged)))))

(deftest helper-failure-no-op-test
  (testing "a throwing helper run yields no task, no throw"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s1"}
                  (collaborators {:run-helper (fn [_opts] (throw (ex-info "boom" {})))}))]
      (is (= :success (:status result)))
      (is (= [] (:created-task-ids result))))))

(deftest missing-local-model-no-op-test
  (testing "no local model available -> no-op, no task"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s1"}
                  (collaborators {:select-model (fn [_sid] nil)}))]
      (is (= :no-op (:status result)))
      (is (= "no local model" (:diagnostic result))))))

(deftest missing-worktree-no-op-test
  (testing "no worktree available -> no-op, no task"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s1"}
                  (collaborators {:session-info (fn [_sid] {:worktree-root nil})}))]
      (is (= :no-op (:status result)))
      (is (= "no worktree" (:diagnostic result))))))

(deftest own-helper-session-excluded-test
  (testing "the analyzer's own tracked helper session is excluded"
    (swap! context-manager/friction-helper-session-ids conj "s1")
    (let [result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
      (is (= :no-op (:status result))))))

(deftest entity-resolution-helper-session-excluded-test
  (testing "an entity-resolution tracked helper session is excluded"
    (swap! context-manager/entity-resolution-helper-session-ids conj "s1")
    (let [result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
      (is (= :no-op (:status result))))))

(deftest other-known-helper-session-excluded-test
  (testing "a session identifiable by name as a known helper/infra session is excluded"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s2"}
                  (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                            :session-name "entity-resolution"})}))]
      (is (= :no-op (:status result))))))

(deftest cap-applied-test
  (testing "3 detected issues yield only 2 created tasks (per-run cap)"
    (let [three-issues (str "ISSUE: a | A\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n\n"
                            "ISSUE: b | B\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n\n"
                            "ISSUE: c | C\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n")
          created (atom [])
          result (context-manager/friction-analysis
                  {} {:session-id "s1"}
                  (collaborators
                   {:run-helper (fn [_opts] {:child-session-id "h1" :text three-issues})
                    :create-task! (fn [_root issue]
                                    (swap! created conj (:slug issue))
                                    (str "0-" (:slug issue)))}))]
      (is (= :success (:status result)))
      (is (= 2 (count (:created-task-ids result))))
      (is (= ["a" "b"] @created))
      (is (= 1 (:dropped-count result))))))

(deftest all-collaborators-throw-never-throws-test
  (testing "friction-analysis never throws, even when every collaborator throws"
    (let [boom (fn [& _] (throw (ex-info "boom" {})))
          result (context-manager/friction-analysis
                  {:log boom}
                  {:session-id "s1"}
                  {:select-model boom
                   :run-helper boom
                   :fetch-history boom
                   :session-info boom
                   :list-tasks boom
                   :create-task! boom})]
      (is (map? result))
      (is (= :no-op (:status result))))))
