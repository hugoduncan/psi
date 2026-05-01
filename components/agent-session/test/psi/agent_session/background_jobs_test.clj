(ns psi.agent-session.background-jobs-test
  "Scaffold tests for background tool jobs contract.

  Source matrix:
  - doc/background-tool-jobs-test-matrix.md

  Each test id (N/E/B) intentionally starts as a concrete stub and should be
  replaced with runtime-backed assertions as implementation lands."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.background-jobs :as bj]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.workflows :as wf]
   [psi.query.core :as query]))

(defn- start-job!
  [store tool-call-id thread-id tool-name job-id]
  (bj/start-background-job-in! store {:tool-call-id tool-call-id
                                      :thread-id thread-id
                                      :tool-name tool-name
                                      :job-id job-id}))

(defn- mark-terminal!
  [store params]
  (bj/mark-terminal-in! store params))

(defn- request-cancel!
  [store params]
  (bj/request-cancel-in! store params))

(defn- pending-background-terminal-message-count
  [ctx session-id]
  (->> (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))
       (filter #(= "assistant" (:role %)))
       (filter #(= "background-job-terminal" (:custom-type %)))
       count))

;; ---------------------------------------------------------------------------
;; Nominal (N)
;; ---------------------------------------------------------------------------

(deftest n1-synchronous-tool-completion-test
  (testing "N1: synchronous completion returns no job-id and creates no background job"
    (let [store  (bj/create-store)
          result (bj/record-synchronous-result-in!
                  store
                  {:tool-call-id "tc-sync-1"
                   :thread-id "thread-a"
                   :tool-name "bash"
                   :result {:content "ok" :is-error false}})]
      (is (= :synchronous (:mode result)))
      (is (nil? (:job-id result)))
      (is (nil? (:status result)))
      (is (= {:content "ok" :is-error false} (:result result)))
      (is (empty? (vals (:jobs-by-id @store)))))))

(deftest n2-background-start-response-test
  (testing "N2: background start returns job-id/running and creates one job record"
    (let [store  (bj/create-store)
          result (bj/start-background-job-in!
                  store
                  {:tool-call-id "tc-bg-1"
                   :thread-id "thread-a"
                   :tool-name "delegate"
                   :job-id "job-1"})
          job    (bj/get-job-in store "job-1")]
      (is (= :background (:mode result)))
      (is (= "job-1" (:job-id result)))
      (is (= :running (:status result)))
      (is (nil? (:result result)))
      (is (= "thread-a" (:thread-id job)))
      (is (= "tc-bg-1" (:tool-call-id job)))
      (is (= "delegate" (:tool-name job)))
      (is (= :running (:status job)))
      (is (false? (:terminal-message-emitted job))))))

(deftest n3-terminal-status-transition-test
  (testing "N3: terminal outcome updates status/completed-at/payload"
    (let [store (bj/create-store)
          _     (start-job! store "tc-bg-2" "thread-a" "delegate" "job-2")
          job   (bj/mark-terminal-in!
                 store
                 {:job-id "job-2"
                  :outcome :completed
                  :payload {:value 42}})]
      (is (= :completed (:status job)))
      (is (= {:value 42} (:terminal-payload job)))
      (is (instance? java.time.Instant (:completed-at job))))))

(deftest n4-single-terminal-injection-test
  (testing "N4: one synthetic assistant message emitted per terminal job"
    (let [store (bj/create-store)
          _     (start-job! store "tc-n4" "thread-a" "tool-z" "job-n4")
          _     (mark-terminal! store {:job-id "job-n4"
                                       :outcome :completed
                                       :payload {:ok true}})
          pending-before (bj/pending-terminal-jobs-in store "thread-a")
          _     (bj/mark-terminal-message-emitted-in! store {:job-id "job-n4"})
          pending-after  (bj/pending-terminal-jobs-in store "thread-a")
          job            (bj/get-job-in store "job-n4")]
      (is (= ["job-n4"] (mapv :job-id pending-before)))
      (is (empty? pending-after))
      (is (true? (:terminal-message-emitted job)))
      (is (instance? java.time.Instant (:terminal-message-emitted-at job))))))

(deftest n4b-suppressed-terminal-message-test
  (testing "N4b: suppressed terminal messages do not remain pending for transcript emission"
    (let [store (bj/create-store)
          _     (start-job! store "tc-n4b" "thread-a" "tool-z" "job-n4b")
          _     (mark-terminal! store {:job-id "job-n4b"
                                       :outcome :completed
                                       :payload {:ok true}
                                       :suppress-terminal-message? true})
          pending (bj/pending-terminal-jobs-in store "thread-a")
          job     (bj/get-job-in store "job-n4b")]
      (is (empty? pending))
      (is (true? (:terminal-message-emitted job)))
      (is (instance? java.time.Instant (:terminal-message-emitted-at job))))))

(deftest n5-ordered-multi-job-injection-test
  (testing "N5: terminal injections emitted in completion-time order"
    (let [store (bj/create-store)
          _     (start-job! store "tc-n5-a" "thread-a" "tool-z" "job-n5-a")
          _     (Thread/sleep 2)
          _     (start-job! store "tc-n5-b" "thread-a" "tool-z" "job-n5-b")
          _     (mark-terminal! store {:job-id "job-n5-a" :outcome :completed :payload {:n 1}})
          _     (Thread/sleep 2)
          _     (mark-terminal! store {:job-id "job-n5-b" :outcome :completed :payload {:n 2}})
          pending (bj/pending-terminal-jobs-in store "thread-a")]
      (is (= ["job-n5-a" "job-n5-b"] (mapv :job-id pending))))))

(deftest n6-cancel-request-user-and-agent-test
  (testing "N6: both user and agent can request cancellation"
    (let [store (bj/create-store)
          _     (start-job! store "tc-n6-user" "thread-a" "tool-z" "job-n6-user")
          _     (start-job! store "tc-n6-agent" "thread-a" "tool-z" "job-n6-agent")
          user-cancelled  (request-cancel! store {:thread-id "thread-a"
                                                  :job-id "job-n6-user"
                                                  :requested-by "user"})
          agent-cancelled (request-cancel! store {:thread-id "thread-a"
                                                  :job-id "job-n6-agent"
                                                  :requested-by :agent})]
      (is (= :pending-cancel (:status user-cancelled)))
      (is (= :pending-cancel (:status agent-cancelled)))
      (is (instance? java.time.Instant (:cancel-requested-at user-cancelled)))
      (is (instance? java.time.Instant (:cancel-requested-at agent-cancelled))))))

(deftest n7-oversize-payload-path-test
  (testing "N7: oversized terminal payload spills to temp file reference"
    (let [store   (bj/create-store)
          payload {:blob (apply str (repeat 200 "x"))}
          _       (start-job! store "tc-n7" "thread-a" "tool-z" "job-n7")
          _       (mark-terminal! store {:job-id "job-n7"
                                         :outcome :completed
                                         :payload payload})
          payload-edn (pr-str payload)
          policy      {:max-lines 1000 :max-bytes 40}
          truncation  (tool-output/head-truncate payload-edn policy)]
      (is (true? (:truncated truncation)))
      (try
        (let [spill-path (tool-output/persist-truncated-output! "tool-z" "job-n7" payload-edn)
              _          (bj/set-terminal-payload-file-in! store {:job-id "job-n7" :path spill-path})
              content    (str (:content truncation)
                              "\n\nTerminal payload exceeded output limits. See temp file: "
                              spill-path)
              job        (bj/get-job-in store "job-n7")]
          (is (= spill-path (:terminal-payload-file job)))
          (is (.exists (java.io.File. spill-path)))
          (is (= payload-edn (slurp spill-path)))
          (is (re-find #"Terminal payload exceeded output limits\. See temp file:" content))
          (is (str/includes? content spill-path)))
        (finally
          (tool-output/cleanup-temp-store!))))))

(deftest n8-default-list-behavior-test
  (testing "N8: default list returns non-terminal statuses only"
    (let [store (bj/create-store)
          _     (start-job! store "tc-bg-3" "thread-a" "tool-x" "job-3")
          _     (start-job! store "tc-bg-4" "thread-a" "tool-x" "job-4")
          _     (start-job! store "tc-bg-5" "thread-a" "tool-x" "job-5")
          _     (request-cancel! store {:thread-id "thread-a" :job-id "job-4"})
          _     (mark-terminal! store {:job-id "job-5" :outcome :failed :payload {:error "boom"}})
          listed (bj/list-jobs-in store "thread-a")
          ids    (mapv :job-id listed)
          statuses (set (map :status listed))]
      (is (= ["job-3" "job-4"] ids))
      (is (= #{:running :pending-cancel} statuses)))))

(deftest n9-explicit-status-filter-test
  (testing "N9: explicit status filter narrows list results"
    (let [store (bj/create-store)
          _     (start-job! store "tc-n9-a" "thread-a" "tool-z" "job-n9-running")
          _     (start-job! store "tc-n9-b" "thread-a" "tool-z" "job-n9-cancel")
          _     (start-job! store "tc-n9-c" "thread-a" "tool-z" "job-n9-failed")
          _     (request-cancel! store {:thread-id "thread-a" :job-id "job-n9-cancel"})
          _     (mark-terminal! store {:job-id "job-n9-failed"
                                       :outcome :failed
                                       :payload {:error "x"}})
          listed (bj/list-jobs-in store "thread-a" [:failed])]
      (is (= ["job-n9-failed"] (mapv :job-id listed)))
      (is (= #{:failed} (set (map :status listed)))))))

(deftest n10-inspect-in-thread-test
  (testing "N10: inspect returns job details in originating thread"
    (let [store (bj/create-store)
          _     (start-job! store "tc-bg-6" "thread-a" "tool-y" "job-6")
          job   (bj/inspect-job-in store {:thread-id "thread-a" :job-id "job-6"})]
      (is (= "job-6" (:job-id job)))
      (is (= "thread-a" (:thread-id job)))
      (is (= "tc-bg-6" (:tool-call-id job)))
      (is (= :running (:status job))))))

;; ---------------------------------------------------------------------------
;; Edge (E)
;; ---------------------------------------------------------------------------

(deftest e1-second-background-job-same-tool-call-rejected-test
  (testing "E1: second background job for same tool_call_id is rejected"
    (let [store (bj/create-store)]
      (start-job! store "tc-edge-1" "thread-a" "tool-z" "job-edge-1")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Tool invocation already resolved"
           (start-job! store "tc-edge-1" "thread-a" "tool-z" "job-edge-2"))))))

(deftest e2-duplicate-job-id-collision-rejected-test
  (testing "E2: duplicate job-id collision is rejected"
    (let [store (bj/create-store)]
      (start-job! store "tc-edge-2a" "thread-a" "tool-z" "job-edge-dup")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Duplicate job-id"
           (start-job! store "tc-edge-2b" "thread-a" "tool-z" "job-edge-dup"))))))

(deftest e3-non-terminal-updates-do-not-inject-test
  (testing "E3: non-terminal updates do not enqueue synthetic assistant message"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e3" "thread-a" "tool-z" "job-e3")
          pending (bj/pending-terminal-jobs-in store "thread-a")]
      (is (empty? pending))
      (is (= :running (:status (bj/get-job-in store "job-e3")))))))

(deftest e4-duplicate-emit-attempt-idempotent-test
  (testing "E4: duplicate terminal emit attempt does not create second message"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e4" "thread-a" "tool-z" "job-e4")
          _     (mark-terminal! store {:job-id "job-e4" :outcome :completed :payload {:ok true}})
          _     (bj/mark-terminal-message-emitted-in! store {:job-id "job-e4"})
          _     (bj/mark-terminal-message-emitted-in! store {:job-id "job-e4"})
          pending (bj/pending-terminal-jobs-in store "thread-a")]
      (is (empty? pending))
      (is (true? (:terminal-message-emitted (bj/get-job-in store "job-e4")))))))

(deftest e5-best-effort-cancel-may-still-complete-test
  (testing "E5: cancellation remains best-effort and job may still complete"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e5" "thread-a" "tool-z" "job-e5")
          _     (request-cancel! store {:thread-id "thread-a" :job-id "job-e5"})
          done  (mark-terminal! store {:job-id "job-e5"
                                       :outcome :completed
                                       :payload {:ok true}})]
      (is (= :completed (:status done)))
      (is (= {:ok true} (:terminal-payload done)))
      (is (instance? java.time.Instant (:cancel-requested-at done))))))

(deftest e6-completion-wins-cancel-race-test
  (testing "E6: completion wins if execution already finished"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e6" "thread-a" "tool-z" "job-e6")
          _     (mark-terminal! store {:job-id "job-e6"
                                       :outcome :completed
                                       :payload {:ok true}})
          after-cancel (request-cancel! store {:thread-id "thread-a" :job-id "job-e6"})]
      (is (= :completed (:status after-cancel)))
      (is (= {:ok true} (:terminal-payload after-cancel)))
      (is (nil? (:cancel-requested-at after-cancel))))))

(deftest e7-idle-completion-wakes-turn-boundary-test
  (testing "E7: idle terminal outcome requests next turn boundary"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          thread-id session-id]
      (dispatch/dispatch! ctx :session/update-background-jobs-state
                          {:update-fn (fn [store]
                                        (-> store
                                            (-> (bj/start-background-job {:tool-call-id "tc-e7"
                                                                          :thread-id thread-id
                                                                          :tool-name "workflow/test"
                                                                          :job-id "job-e7"})
                                                :state)
                                            (bj/mark-terminal {:job-id "job-e7"
                                                               :outcome :completed
                                                               :payload {:ok true}})))}
                          {:origin :core})
      (is (= 0 (pending-background-terminal-message-count ctx thread-id)))
      (ext-rt/set-extension-run-fn-in! ctx thread-id (fn [_ _] nil))
      (Thread/sleep 30)
      (is (= 1 (pending-background-terminal-message-count ctx thread-id)))
      (is (true? (:terminal-message-emitted (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-e7")))))))

(deftest e8-cross-thread-list-cancel-isolation-test
  (testing "E8: cross-thread list/cancel isolation is enforced"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e8-a" "thread-a" "tool-z" "job-e8-a")
          _     (start-job! store "tc-e8-b" "thread-b" "tool-z" "job-e8-b")
          list-a (bj/list-jobs-in store "thread-a")
          list-b (bj/list-jobs-in store "thread-b")]
      (is (= ["job-e8-a"] (mapv :job-id list-a)))
      (is (= ["job-e8-b"] (mapv :job-id list-b)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Job not found in this thread"
           (request-cancel! store {:thread-id "thread-b" :job-id "job-e8-a"}))))))

(deftest e9-inspect-outside-thread-rejected-test
  (testing "E9: inspect outside origin thread returns canonical not-found error"
    (let [store (bj/create-store)]
      (start-job! store "tc-edge-9" "thread-a" "tool-z" "job-edge-9")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Job not found in this thread"
           (bj/inspect-job-in store {:thread-id "thread-b" :job-id "job-edge-9"}))))))

(deftest e10-payload-near-limit-formatting-test
  (testing "E10: payload near limits chooses inline vs file branch correctly"
    (let [inline-payload {:blob (apply str (repeat 10 "x"))}
          over-payload   {:blob (apply str (repeat 200 "x"))}
          policy         {:max-lines 1000 :max-bytes 40}
          inline-trunc   (tool-output/head-truncate (pr-str inline-payload) policy)
          over-trunc     (tool-output/head-truncate (pr-str over-payload) policy)]
      (is (false? (:truncated inline-trunc)))
      (is (true? (:truncated over-trunc)))
      (is (string? (:content inline-trunc)))
      (is (string? (:content over-trunc)))
      (is (not (re-find #"Terminal payload exceeded output limits" (:content inline-trunc))))
      (try
        (let [spill-path (tool-output/persist-truncated-output! "tool-z" "job-e10" (pr-str over-payload))
              over-content (str (:content over-trunc)
                                "\n\nTerminal payload exceeded output limits. See temp file: "
                                spill-path)]
          (is (re-find #"Terminal payload exceeded output limits\. See temp file:" over-content))
          (is (str/includes? over-content spill-path))
          (is (.exists (java.io.File. spill-path))))
        (finally
          (tool-output/cleanup-temp-store!))))))

(deftest e11-manual-retry-request-rejected-test
  (testing "E11: manual retry is not supported"
    (let [store (bj/create-store)]
      (start-job! store "tc-edge-11" "thread-a" "tool-z" "job-edge-11")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Manual retry is not supported for background jobs"
           (bj/retry-job-in! store {:thread-id "thread-a" :job-id "job-edge-11"}))))))

(deftest e12-process-restart-drops-jobs-test
  (testing "E12: process restart reinitializes in-memory registry"
    (let [store (bj/create-store)
          _     (start-job! store "tc-e12-a" "thread-a" "tool-z" "job-e12-a")
          _     (start-job! store "tc-e12-b" "thread-b" "tool-z" "job-e12-b")]
      (is (= 2 (count (:jobs-by-id @store))))
      (is (= true (:reinitialized? (bj/process-restarted-in! store))))
      (is (empty? (:jobs-by-id @store)))
      (is (empty? (:tool-call->job-id @store)))
      (is (empty? (:tool-call->mode @store))))))

(deftest e13-retryable-llm-http-errors-are-internal-test
  (testing "E13: internal retryable LLM HTTP errors do not trigger external injection"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          thread-id session-id]
      (dispatch/dispatch! ctx :session/update-background-jobs-state
                          {:update-fn (fn [store]
                                        (:state (bj/start-background-job
                                                 store
                                                 {:tool-call-id "tc-e13"
                                                  :thread-id thread-id
                                                  :tool-name "workflow/test"
                                                  :job-id "job-e13"})))}
                          {:origin :core})
      ;; Simulate internal retryable error handling path: job remains running and no terminal outcome marked.
      (is (= :running (:status (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-e13"))))
      (ext-rt/set-extension-run-fn-in! ctx thread-id (fn [_ _] nil))
      (Thread/sleep 30)
      (is (= :running (:status (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-e13"))))
      (is (= 0 (pending-background-terminal-message-count ctx thread-id)))
      (is (empty? (bj/pending-terminal-jobs-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) thread-id))))))

;; ---------------------------------------------------------------------------
;; Boundary (B)
;; ---------------------------------------------------------------------------

(deftest b1-sync-async-boundary-exclusive-mode-test
  (testing "B1: invocation chooses exactly one mode (sync or background)"
    (let [store (bj/create-store)
          sync-result (bj/record-synchronous-result-in!
                       store
                       {:tool-call-id "tc-b1-sync"
                        :thread-id "thread-a"
                        :tool-name "tool-z"
                        :result {:ok true}})]
      (is (= :synchronous (:mode sync-result)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Tool invocation already resolved"
           (start-job! store "tc-b1-sync" "thread-a" "tool-z" "job-b1-sync"))))

    (let [store2 (bj/create-store)
          bg-result (start-job! store2 "tc-b1-bg" "thread-a" "tool-z" "job-b1-bg")]
      (is (= :background (:mode bg-result)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Tool invocation already resolved"
           (bj/record-synchronous-result-in!
            store2
            {:tool-call-id "tc-b1-bg"
             :thread-id "thread-a"
             :tool-name "tool-z"
             :result {:ok true}}))))))

(deftest b2-global-job-id-uniqueness-at-scale-test
  (testing "B2: high-volume creation preserves global job-id uniqueness"
    (let [store (bj/create-store)
          n     200]
      (doseq [i (range n)]
        (bj/start-background-job-in!
         store
         {:tool-call-id (str "tc-b2-" i)
          :thread-id "thread-scale"
          :tool-name "delegate"
          :job-id nil}))
      (let [jobs (vals (:jobs-by-id @store))
            ids  (map :job-id jobs)]
        (is (= n (count jobs)))
        (is (= n (count (set ids)))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Duplicate job-id"
           (start-job! store "tc-b2-collision" "thread-scale" "delegate"
                       (-> (vals (:jobs-by-id @store)) first :job-id)))))))

(deftest b3-same-timestamp-ordering-tie-test
  (testing "B3: deterministic tie handling when completion timestamps match"
    (let [store (bj/create-store)
          _     (start-job! store "tc-b3-a" "thread-a" "tool-z" "job-b3-a")
          _     (start-job! store "tc-b3-b" "thread-a" "tool-z" "job-b3-b")
          _     (mark-terminal! store {:job-id "job-b3-a" :outcome :completed :payload {:n 1}})
          _     (mark-terminal! store {:job-id "job-b3-b" :outcome :completed :payload {:n 2}})
          completed-at (-> (bj/get-job-in store "job-b3-a") :completed-at)
          _     (swap! store assoc-in [:jobs-by-id "job-b3-b" :completed-at] completed-at)
          pending (bj/pending-terminal-jobs-in store "thread-a")]
      (is (= ["job-b3-a" "job-b3-b"] (mapv :job-id pending)))
      ;; Repeated reads preserve deterministic order.
      (is (= ["job-b3-a" "job-b3-b"] (mapv :job-id (bj/pending-terminal-jobs-in store "thread-a")))))))

(deftest b4-at-most-once-under-concurrent-emitters-test
  (testing "B4: concurrent emit attempts still produce one terminal message"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          thread-id session-id
          _     (dispatch/dispatch! ctx :session/update-background-jobs-state
                                    {:update-fn (fn [store]
                                                  (-> store
                                                      (-> (bj/start-background-job {:tool-call-id "tc-b4"
                                                                                    :thread-id thread-id
                                                                                    :tool-name "tool-z"
                                                                                    :job-id "job-b4"})
                                                          :state)
                                                      (bj/mark-terminal {:job-id "job-b4" :outcome :completed :payload {:ok true}})))}
                                    {:origin :core})
          f1    (future (ext-rt/set-extension-run-fn-in! ctx thread-id (fn [_ _] nil)) true)
          f2    (future (ext-rt/set-extension-run-fn-in! ctx thread-id (fn [_ _] nil)) true)
          _     @f1
          _     @f2
          pending (bj/pending-terminal-jobs-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) thread-id)]
      (is (= 1 (pending-background-terminal-message-count ctx thread-id)))
      (is (empty? pending))
      (is (true? (:terminal-message-emitted (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-b4")))))))

(deftest b5-payload-size-boundary-test
  (testing "B5: max-bytes/max-lines boundary behavior is correct"
    (let [at-limit (apply str (repeat 20 "x"))
          over-limit (apply str (repeat 21 "x"))
          at-trunc (tool-output/head-truncate at-limit {:max-lines 1000 :max-bytes 20})
          over-trunc (tool-output/head-truncate over-limit {:max-lines 1000 :max-bytes 20})]
      (is (false? (:truncated at-trunc)))
      (is (= at-limit (:content at-trunc)))
      (is (true? (:truncated over-trunc)))
      (is (zero? (:output-bytes over-trunc)))
      (is (true? (:first-line-exceeds-limit over-trunc))))))

(deftest b6-retention-at-limit-test
  (testing "B6: exactly 20 terminal jobs retains all without eviction"
    (let [store (bj/create-store)]
      (doseq [i (range 20)]
        (let [job-id (str "job-b6-" i)]
          (start-job! store (str "tc-b6-" i) "thread-a" "tool-z" job-id)
          (mark-terminal! store {:job-id job-id
                                 :outcome :completed
                                 :terminal-history-max-per-thread 20
                                 :payload {:i i}})))
      (let [terminal (->> (vals (:jobs-by-id @store))
                          (filter #(= "thread-a" (:thread-id %)))
                          (filter #(bj/terminal-status? (:status %))))]
        (is (= 20 (count terminal)))))))

(deftest b7-retention-overflow-evicts-oldest-terminal-test
  (testing "B7: overflow evicts oldest terminal by completion time"
    (let [store (bj/create-store)]
      (doseq [i (range 21)]
        (let [job-id (str "job-b7-" i)]
          (start-job! store (str "tc-b7-" i) "thread-a" "tool-z" job-id)
          (mark-terminal! store {:job-id job-id
                                 :outcome :completed
                                 :terminal-history-max-per-thread 20
                                 :payload {:i i}})
          (Thread/sleep 1)))
      (let [ids (set (keys (:jobs-by-id @store)))]
        (is (= 20 (count ids)))
        (is (not (contains? ids "job-b7-0")))
        (is (contains? ids "job-b7-20"))))))

(deftest b8-retention-preserves-non-terminal-test
  (testing "B8: non-terminal jobs are preserved during terminal eviction"
    (let [store (bj/create-store)
          _     (start-job! store "tc-b8-running" "thread-a" "tool-z" "job-b8-running")]
      (doseq [i (range 21)]
        (let [job-id (str "job-b8-term-" i)]
          (start-job! store (str "tc-b8-term-" i) "thread-a" "tool-z" job-id)
          (mark-terminal! store {:job-id job-id
                                 :outcome :completed
                                 :terminal-history-max-per-thread 20
                                 :payload {:i i}})
          (Thread/sleep 1)))
      (let [running (bj/get-job-in store "job-b8-running")
            terminal (->> (vals (:jobs-by-id @store))
                          (filter #(= "thread-a" (:thread-id %)))
                          (filter #(bj/terminal-status? (:status %))))]
        (is (= :running (:status running)))
        (is (= 20 (count terminal)))))))

;; ---------------------------------------------------------------------------
;; Regression: explicit extension notification triggers workflow-job terminal detection
;; ---------------------------------------------------------------------------

(def ^:private instant-done-chart
  "Minimal statechart that transitions immediately to :done on :workflow/start."
  (chart/statechart {:id :instant-done-chart}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :done}
                                               (ele/script
                                                {:expr (fn [_ _]
                                                         [{:op :assign
                                                           :data {:workflow/result "ok"}}])})))
                    (ele/final {:id :done})))

(deftest notify-triggers-workflow-job-terminal-detection-test
  (testing "notify mutation marks workflow-backed background jobs terminal"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          ext-path  "/test/notify-regression.clj"
          wf-id     "wf-sm-1"
          thread-id session-id
          reg       (:workflow-registry ctx)
          qctx      (query/create-query-context)
          _         (session/register-resolvers-in! qctx false)
          _         (session/register-mutations-in! qctx mutations/all-mutations true)
          mutate    (fn [op params]
                      (get (query/query-in qctx
                                           {:psi/agent-session-ctx ctx}
                                           [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                       (not (contains? params :session-id))
                                                       (assoc :session-id thread-id)))])
                           op))]
      ;; Register workflow type and create/start a workflow instance
      (wf/register-type-in! reg ext-path {:type :instant-done :chart instant-done-chart})
      (wf/ensure-pump! reg)
      (wf/create-workflow-in! reg ext-path {:type :instant-done :id wf-id :auto-start? true})
      ;; Wait for the statechart to reach :done
      (loop [i 0]
        (let [w (wf/workflow-in reg ext-path wf-id)]
          (when (and (< i 200) (not (:done? w)))
            (Thread/sleep 10)
            (recur (inc i)))))
      (is (true? (:done? (wf/workflow-in reg ext-path wf-id)))
          "workflow should be done before we register the background job")
      ;; Register a background job that is linked to the now-done workflow
      (dispatch/dispatch! ctx :session/update-background-jobs-state
                          {:update-fn (fn [store]
                                        (:state (bj/start-background-job
                                                 store
                                                 {:tool-call-id      "tc-sm-regression"
                                                  :thread-id         thread-id
                                                  :tool-name         "workflow/instant-done"
                                                  :job-id            "job-sm-1"
                                                  :job-kind          :workflow
                                                  :workflow-ext-path ext-path
                                                  :workflow-id       wf-id})))}
                          {:origin :core})
      (is (= :running (:status (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-sm-1")))
          "job should start as running before notify is called")
      ;; Invoke notify via the real Pathom mutation surface — this exercises the fix
      (mutate 'psi.extension/notify
              {:role "assistant" :content "chain result" :custom-type "chain-result"})
      ;; The job should now be terminal (completed) — the fix under test
      (let [job (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-sm-1")]
        (is (bj/terminal-status? (:status job))
            "background job should be terminal after notify")
        (is (= :completed (:status job))))
      (wf/shutdown-in! reg))))

(def ^:private delayed-done-chart
  "Statechart that reaches :done asynchronously shortly after start."
  (chart/statechart {:id :delayed-done-chart}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :running}))
                    (ele/state {:id :running}
                               (ele/invoke {:id :finish
                                            :type :future
                                            :params (fn [_ _] {})
                                            :src (fn [_]
                                                   (Thread/sleep 40)
                                                   {:ok true})})
                               (ele/transition {:event :done.invoke.finish :target :done}))
                    (ele/final {:id :done})))

(deftest notify-terminal-detection-handles-workflow-completion-race-test
  (testing "notify eventually marks job terminal when workflow completes just after message"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          ext-path  "/test/notify-race.clj"
          wf-id     "wf-sm-race"
          thread-id session-id
          reg       (:workflow-registry ctx)
          qctx      (query/create-query-context)
          _         (session/register-resolvers-in! qctx false)
          _         (session/register-mutations-in! qctx mutations/all-mutations true)
          mutate    (fn [op params]
                      (get (query/query-in qctx
                                           {:psi/agent-session-ctx ctx}
                                           [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                       (not (contains? params :session-id))
                                                       (assoc :session-id thread-id)))])
                           op))]
      (wf/register-type-in! reg ext-path {:type :delayed-done :chart delayed-done-chart})
      (wf/ensure-pump! reg)
      (wf/create-workflow-in! reg ext-path {:type :delayed-done :id wf-id :auto-start? true})
      (dispatch/dispatch! ctx :session/update-background-jobs-state
                          {:update-fn (fn [store]
                                        (:state (bj/start-background-job
                                                 store
                                                 {:tool-call-id      "tc-sm-race"
                                                  :thread-id         thread-id
                                                  :tool-name         "workflow/delayed-done"
                                                  :job-id            "job-sm-race"
                                                  :job-kind          :workflow
                                                  :workflow-ext-path ext-path
                                                  :workflow-id       wf-id})))}
                          {:origin :core})
      ;; Fire notify before workflow reaches :done.
      (mutate 'psi.extension/notify
              {:role "assistant" :content "chain result" :custom-type "chain-result"})
      ;; Poll for eventual terminal transition (covers race between message inject and wf completion).
      (loop [i 0]
        (let [job (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs))
                                 "job-sm-race")]
          (if (or (>= i 80) (bj/terminal-status? (:status job)))
            (is (= :completed (:status job)))
            (do
              (Thread/sleep 10)
              (recur (inc i))))))
      (wf/shutdown-in! reg))))

(deftest background-job-resolver-self-heals-stale-workflow-status-test
  (testing "background-job resolver reconciles stale workflow-backed running jobs"
    (let [[ctx session-id] (test-support/make-session-ctx {})
          ext-path  "/test/resolver-reconcile.clj"
          wf-id     "wf-resolve-1"
          thread-id session-id
          reg       (:workflow-registry ctx)]
      (wf/register-type-in! reg ext-path {:type :instant-done :chart instant-done-chart})
      (wf/ensure-pump! reg)
      (wf/create-workflow-in! reg ext-path {:type :instant-done :id wf-id :auto-start? true})
      ;; Wait workflow done
      (loop [i 0]
        (let [w (wf/workflow-in reg ext-path wf-id)]
          (when (and (< i 200) (not (:done? w)))
            (Thread/sleep 10)
            (recur (inc i)))))
      ;; Seed stale running job linked to done workflow
      (dispatch/dispatch! ctx :session/update-background-jobs-state
                          {:update-fn (fn [store]
                                        (:state (bj/start-background-job
                                                 store
                                                 {:tool-call-id      "tc-resolve-1"
                                                  :thread-id         thread-id
                                                  :tool-name         "workflow/instant-done"
                                                  :job-id            "job-resolve-1"
                                                  :job-kind          :workflow
                                                  :workflow-ext-path ext-path
                                                  :workflow-id       wf-id})))}
                          {:origin :core})
      (is (= :running (:status (bj/get-job-in (ss/get-state-value-in ctx (ss/state-path :background-jobs)) "job-resolve-1"))))
      ;; Querying background-jobs should reconcile and expose terminal status.
      (let [resp (session/query-in ctx session-id [:psi.agent-session/background-jobs])
            jobs (:psi.agent-session/background-jobs resp)
            job  (first (filter #(= "job-resolve-1" (:psi.background-job/id %)) jobs))]
        (is (= :completed (:psi.background-job/status job))))
      (wf/shutdown-in! reg))))
