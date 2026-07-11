(ns extensions.context-manager-friction-analysis-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/friction-helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (reset! context-manager/friction-in-flight-session-ids #{})
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

(deftest mixed-issue-and-duplicate-test
  (testing "test-shaper follow-up: a single run whose helper output contains
            one well-formed ISSUE block plus one DUPLICATE line creates the
            issue's task AND emits the duplicate diagnostic — the two
            coexist (orchestration-level analog of
            parse-friction-output-mixed-test)"
    (let [logged (atom [])
          mixed-output (str issue-output
                            "\nDUPLICATE: flaky-lint ~ 001-flaky-lint\n")
          result (context-manager/friction-analysis
                  {:log #(swap! logged conj %)}
                  {:session-id "s1"}
                  (collaborators
                   {:run-helper (fn [_opts]
                                  {:child-session-id "h1" :text mixed-output})}))]
      (is (= :success (:status result)))
      (is (= ["042-slow-tests"] (:created-task-ids result))
          "the new ISSUE block still creates its task")
      (is (= [{:slug "flaky-lint" :existing-id "001-flaky-lint"}]
             (:duplicate-diagnostics result))
          "the DUPLICATE line still surfaces as a diagnostic")
      (is (some #(re-find #"duplicate" %) @logged)
          "the dedup log line still fires alongside task creation"))))

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

(deftest blank-history-no-op-test
  (testing "empty/blank history -> no-op, no run-helper, no task"
    (doseq [empty-history [nil "" "   "]]
      (let [ran-helper (atom false)
            created    (atom false)
            result (context-manager/friction-analysis
                    {} {:session-id "s1"}
                    (collaborators
                     {:fetch-history (fn [_sid] empty-history)
                      :run-helper    (fn [_opts] (reset! ran-helper true)
                                       {:child-session-id "h1" :text issue-output})
                      :create-task!  (fn [_root _issue] (reset! created true) "042-x")}))]
        (is (= :no-op (:status result)))
        (is (= "no history" (:diagnostic result)))
        (is (false? @ran-helper))
        (is (false? @created))))))

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

(deftest other-known-workflow-step-session-excluded-test
  (testing "a workflow-runtime step-attempt child session is excluded by naming convention"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s3"}
                  (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                            :session-name "workflow builder attempt"})}))]
      (is (= :no-op (:status result))))))

(deftest other-known-auto-session-name-session-excluded-test
  (testing "the auto-session-name extension's helper child session is excluded by name"
    (let [result (context-manager/friction-analysis
                  {} {:session-id "s4"}
                  (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                            :session-name "auto-session-name"})}))]
      (is (= :no-op (:status result))))))

(def ^:private two-issue-output
  (str "ISSUE: a | A\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n\n"
       "ISSUE: b | B\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n"))

(deftest cap-applied-test
  (testing "3 detected issues yield only 2 created tasks (per-run cap)"
    (let [three-issues (str "ISSUE: a | A\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n\n"
                            "ISSUE: b | B\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n\n"
                            "ISSUE: c | C\nFRICTION: f\nEVIDENCE: e\nSUGGESTION: s\n")
          created (atom [])
          logged  (atom [])
          result (context-manager/friction-analysis
                  {:log #(swap! logged conj %)} {:session-id "s1"}
                  (collaborators
                   {:run-helper (fn [_opts] {:child-session-id "h1" :text three-issues})
                    :create-task! (fn [_root issue]
                                    (swap! created conj (:slug issue))
                                    (str "0-" (:slug issue)))}))]
      (is (= :success (:status result)))
      (is (= 2 (count (:created-task-ids result))))
      (is (= ["a" "b"] @created))
      (is (= 1 (:dropped-count result)))
      (is (some #(re-find #"dropped by per-run cap" %) @logged)
          "test-shaper follow-up: the per-run-cap dropped-issues diagnostic log fires")))
  (testing "test-shaper follow-up: no dropped-cap log line fires when issues <= cap"
    (let [logged (atom [])
          result (context-manager/friction-analysis
                  {:log #(swap! logged conj %)} {:session-id "s1"}
                  (collaborators
                   {:run-helper (fn [_opts] {:child-session-id "h1" :text two-issue-output})
                    :create-task! (fn [_root issue] (str "0-" (:slug issue)))}))]
      (is (= :success (:status result)))
      (is (= 0 (:dropped-count result)))
      (is (not-any? #(re-find #"dropped by per-run cap" %) @logged)
          "no cap diagnostic when the issue count is within the cap"))))

(deftest create-task-partial-failure-still-completes-test
  (testing "test-shaper follow-up: on the success branch, an issue whose
            create-task! returns nil or throws is silently dropped from
            :created-task-ids while sibling issues still succeed — analysis
            still completes :success with only the successful id (no nil, no
            throw)"
    (doseq [failing-create [(fn [_root _issue] nil)
                            (fn [_root _issue] (throw (ex-info "boom" {})))]]
      (let [;; first issue "a" succeeds, second issue "b" fails (nil/throw)
            result (context-manager/friction-analysis
                    {} {:session-id "s1"}
                    (collaborators
                     {:run-helper (fn [_opts] {:child-session-id "h1"
                                               :text two-issue-output})
                      :create-task! (fn [root issue]
                                      (if (= "a" (:slug issue))
                                        "042-a"
                                        (failing-create root issue)))}))]
        (is (= :success (:status result)))
        (is (= ["042-a"] (:created-task-ids result))
            "only the successful id is recorded; no nil, no thrown escape")))))

(deftest list-tasks-degradation-still-detects-test
  (testing "test-shaper follow-up: on the success branch, a list-tasks that
            throws or returns nil yields empty dedup lists — analysis still
            reaches run-helper and creates the detected task (dedup
            unavailable → detect without dedup, don't abort)"
    (doseq [degraded-list [(fn [_root] (throw (ex-info "boom" {})))
                           (fn [_root] nil)]]
      (let [ran-helper (atom false)
            result (context-manager/friction-analysis
                    {} {:session-id "s1"}
                    (collaborators
                     {:list-tasks degraded-list
                      :run-helper (fn [_opts]
                                    (reset! ran-helper true)
                                    {:child-session-id "h1" :text issue-output})}))]
        (is (true? @ran-helper) "run-helper still reached despite degraded list-tasks")
        (is (= :success (:status result)))
        (is (= ["042-slow-tests"] (:created-task-ids result))
            "detected task still created without dedup context")))))

(deftest concurrent-run-same-session-guarded-test
  (testing "round-4 review follow-up: a second run for a session already
            in-flight is skipped (not raced), so two overlapping runs on
            the same session can't both independently detect + create a
            task for the same issue"
    (let [entered (promise)
          release (promise)
          in-flight-collaborators
          (collaborators
           {:run-helper (fn [_opts]
                          (deliver entered true)
                          (deref release 2000 ::timeout)
                          {:child-session-id "h1" :text issue-output})})
          fut (future (context-manager/friction-analysis
                       {} {:session-id "s1"} in-flight-collaborators))]
      (is (= true (deref entered 2000 :timeout))
          "first run reached its (blocking) helper run")
      (let [second (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
        (is (= :no-op (:status second)))
        (is (= "analysis already in flight for this session" (:diagnostic second))))
      (deliver release true)
      (is (= :success (:status (deref fut 2000 :timeout)))
          "the first run still completes normally once unblocked"))))

(deftest truly-concurrent-runs-same-session-atomic-claim-test
  (testing "round-6 review follow-up: two calls for the same session-id
            started with no ordering handshake (no wait for the first to
            reach a blocking point) can never both claim the in-flight
            slot — the claim is a single atomic swap-vals!, not a
            separate contains? read + swap! conj"
    (dotimes [_ 20]
      (reset! context-manager/friction-in-flight-session-ids #{})
      (let [start   (promise)
            results (atom [])
            latch   (java.util.concurrent.CountDownLatch. 2)
            run     (fn []
                      (.countDown latch)
                      (deref start 2000 ::timeout)
                      (let [result (context-manager/friction-analysis
                                    {} {:session-id "s1"}
                                    (collaborators
                                     {:run-helper (fn [_opts]
                                                    ;; widen the window between
                                                    ;; the claim and completion
                                                    (Thread/sleep 5)
                                                    {:child-session-id "h1" :text issue-output})}))]
                        (swap! results conj result)))
            fut1    (future (run))
            fut2    (future (run))]
        (.await latch)
        (deliver start true)
        (let [r1 (deref fut1 2000 ::timeout)
              r2 (deref fut2 2000 ::timeout)]
          (is (not= ::timeout r1))
          (is (not= ::timeout r2)))
        (let [statuses (mapv :status @results)]
          (is (= 2 (count statuses)))
          (is (= 1 (count (filter #(= :success %) statuses)))
              (str "exactly one run should proceed to completion, got " statuses))
          (is (= 1 (count (filter #(= :no-op %) statuses)))
              (str "exactly one run should be turned away as already in-flight, got " statuses)))))))

(deftest sequential-runs-same-session-not-blocked-test
  (testing "the in-flight guard doesn't leak across runs: a session can be
            analyzed again once its prior run has finished"
    (let [first-result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))
          second-result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
      (is (= :success (:status first-result)))
      (is (= :success (:status second-result))))))

(deftest in-flight-claim-released-on-non-success-first-run-test
  (testing "test-shaper round-3 follow-up: the in-flight claim is released
            on every exit path of the first run — a no-op or a throwing
            first run must not permanently wedge the session; a second
            run for the same session-id still reaches :success. Mirrors
            sequential-runs-same-session-not-blocked-test's success-case
            leak contract, applied to the no-op/throw exits the `finally`
            exists to cover."
    (testing "first run no-ops (no worktree) → claim released → second run succeeds"
      (let [first-result  (context-manager/friction-analysis
                           {} {:session-id "s1"}
                           (collaborators {:session-info (fn [_sid] {:worktree-root nil})}))
            second-result (context-manager/friction-analysis
                           {} {:session-id "s1"} (collaborators {}))]
        (is (= :no-op (:status first-result)))
        (is (= "no worktree" (:diagnostic first-result)))
        (is (= :success (:status second-result))
            "second run is not turned away as already-in-flight")
        (is (not (contains? @context-manager/friction-in-flight-session-ids "s1"))
            "the in-flight slot is empty after both runs")))
    (testing "first run throws (session-info throws) → claim still released → second run succeeds"
      (reset! context-manager/friction-in-flight-session-ids #{})
      (let [first-result  (context-manager/friction-analysis
                           {} {:session-id "s1"}
                           (collaborators {:session-info (fn [_sid] (throw (ex-info "boom" {})))}))
            second-result (context-manager/friction-analysis
                           {} {:session-id "s1"} (collaborators {}))]
        (is (= :no-op (:status first-result))
            "the throw is swallowed to a no-op, not propagated")
        (is (= :success (:status second-result))
            "second run is not turned away as already-in-flight")
        (is (not (contains? @context-manager/friction-in-flight-session-ids "s1"))
            "the in-flight slot is empty after both runs")))))

(deftest exclusion-no-op-branch-diagnostic-pinned-test
  (testing "test-shaper round-3 follow-up: the known-helper exclusion path
            (evaluated first in run-analysis's cond) pins its specific
            :diagnostic \"known helper/infra session excluded\", not just
            that some no-op fired — so a regression that mis-routed an
            excluded session into a different no-op branch (or that let an
            analyzable session fall into exclusion) would be caught. Mirrors
            missing-local-model-no-op-test/missing-worktree-no-op-test's
            specific-diagnostic contract, applied across the distinct
            exclusion sources."
    (testing "own tracked helper session"
      (swap! context-manager/friction-helper-session-ids conj "s1")
      (let [result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
        (is (= :no-op (:status result)))
        (is (= "known helper/infra session excluded" (:diagnostic result)))))
    (testing "entity-resolution tracked helper session"
      (swap! context-manager/entity-resolution-helper-session-ids conj "s1")
      (let [result (context-manager/friction-analysis {} {:session-id "s1"} (collaborators {}))]
        (is (= :no-op (:status result)))
        (is (= "known helper/infra session excluded" (:diagnostic result)))))
    (testing "known helper session by literal name"
      (let [result (context-manager/friction-analysis
                    {} {:session-id "s2"}
                    (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                              :session-name "entity-resolution"})}))]
        (is (= :no-op (:status result)))
        (is (= "known helper/infra session excluded" (:diagnostic result)))))
    (testing "workflow step-attempt child session (workflow-step-session? path)"
      (let [result (context-manager/friction-analysis
                    {} {:session-id "s3"}
                    (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                              :session-name "workflow builder attempt"})}))]
        (is (= :no-op (:status result)))
        (is (= "known helper/infra session excluded" (:diagnostic result)))))
    (testing "auto-session-name helper child session"
      (let [result (context-manager/friction-analysis
                    {} {:session-id "s4"}
                    (collaborators {:session-info (fn [_sid] {:worktree-root "/repo"
                                                              :session-name "auto-session-name"})}))]
        (is (= :no-op (:status result)))
        (is (= "known helper/infra session excluded" (:diagnostic result)))))))

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
