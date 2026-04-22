(ns psi.agent-session.resolvers-test
  "Behavior and integration tests for agent-session resolvers.

   Scope:
   - concrete resolver behavior from session root
   - joins and bridge behavior into adjacent domains
   - session-specific integrations and regressions

   Non-scope:
   - graph discovery/introspection contract for :psi.graph/* and
     root-queryable advertisement; see graph_surface_test.clj

   Each test asserts that a direct EQL query against a fresh session context
   returns a well-typed value for the target attribute — no resolver error."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.test-support :as test-support]
   [psi.ai.model-registry :as model-registry]
   [psi.query.core :as query]))

;; ── helpers ─────────────────────────────────────────────
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- q
  "Run EQL query against a fresh session context."
  [eql]
  (let [[ctx session-id] (test-support/create-test-session)]
    (session/query-in ctx session-id eql)))

(defn- q-in
  "Run EQL query against explicit session context CTX."
  [ctx eql]
  (let [session-id (some-> (ss/list-context-sessions-in ctx) first :session-id)]
    (session/query-in ctx session-id eql)))

(defmacro with-user-dir
  "Temporarily set java user.dir while evaluating body."
  [dir & body]
  `(let [orig# (System/getProperty "user.dir")]
     (try
       (System/setProperty "user.dir" (str ~dir))
       ~@body
       (finally
         (System/setProperty "user.dir" orig#)))))

;; ── :psi.agent-session/messages-count ───────────────────

(deftest messages-count-resolver-test
  (testing "messages-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/messages-count])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (zero? (:psi.agent-session/messages-count result))
          "fresh session has no messages"))))

;; ── :psi.agent-session/ai-call-count ────────────────────

(deftest ai-call-count-resolver-test
  (testing "ai-call-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/ai-call-count])]
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (zero? (:psi.agent-session/ai-call-count result))
          "fresh session has no AI calls"))))

;; ── :psi.agent-session/tool-call-count / executed-tool-count ─────────

(deftest tool-call-count-resolver-test
  (testing "tool-call-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/tool-call-count])]
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (zero? (:psi.agent-session/tool-call-count result))
          "fresh session has no tool calls"))))

(deftest executed-tool-count-resolver-test
  (testing "executed-tool-count is an integer for a fresh session"
    (let [result (q [:psi.agent-session/executed-tool-count])]
      (is (integer? (:psi.agent-session/executed-tool-count result)))
      (is (zero? (:psi.agent-session/executed-tool-count result))
          "fresh session has no executed tools")))

  (testing "executed-tool-count follows canonical lifecycle summaries rather than transcript tool results"
    (let [[ctx session-id] (test-support/create-test-session)]
      (ss/update-state-value-in! ctx (ss/state-path :journal session-id) into
                                 [{:kind :message
                                   :data {:message {:role "assistant"
                                                    :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}
                                                              {:type :tool-call :id "call-2" :name "bash" :arguments "{}"}]}}}
                                  {:kind :message
                                   :data {:message {:role "toolResult"
                                                    :tool-call-id "call-1"
                                                    :tool-name "read"
                                                    :content [{:type :text :text "done"}]
                                                    :is-error false}}}])
      (ss/update-state-value-in! ctx (ss/state-path :tool-lifecycle-events session-id) into
                                 [{:event-kind :tool-result :tool-id "call-1" :tool-name "read"}
                                  {:event-kind :tool-result :tool-id "call-2" :tool-name "bash"}])
      (let [result (session/query-in ctx session-id [:psi.agent-session/tool-call-count
                                                     :psi.agent-session/executed-tool-count])]
        (is (= 1 (:psi.agent-session/tool-call-count result)))
        (is (= 2 (:psi.agent-session/executed-tool-count result)))))))

;; ── :psi.agent-session/start-time ───────────────────────

(deftest start-time-resolver-test
  (testing "start-time is an Instant"
    (let [result (q [:psi.agent-session/start-time])]
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))))

  (testing "start-time is before or equal to current-time"
    (let [result (q [:psi.agent-session/start-time
                     :psi.agent-session/current-time])
          start  (:psi.agent-session/start-time result)
          now    (:psi.agent-session/current-time result)]
      (is (not (.isAfter ^java.time.Instant start ^java.time.Instant now))
          "start-time should not be after current-time"))))

;; ── :psi.agent-session/current-time ─────────────────────

(deftest current-time-resolver-test
  (testing "current-time is an Instant"
    (let [result (q [:psi.agent-session/current-time])]
      (is (instance? java.time.Instant (:psi.agent-session/current-time result)))))

  (testing "current-time is recent (within 60 seconds)"
    (let [result (q [:psi.agent-session/current-time])
          now    (java.time.Instant/now)
          ct     (:psi.agent-session/current-time result)
          diff   (Math/abs (- (.toEpochMilli now) (.toEpochMilli ^java.time.Instant ct)))]
      (is (< diff 60000) "current-time should be within 60s of system clock"))))

;; ── Combined query (mirrors the failing psi-tool pattern) ──

(deftest combined-telemetry-query-test
  (testing "all canonical telemetry attrs succeed in one query"
    (let [result (q [:psi.agent-session/messages-count
                     :psi.agent-session/ai-call-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/executed-tool-count
                     :psi.agent-session/ui-type
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (integer? (:psi.agent-session/executed-tool-count result)))
      (is (integer? (:psi.agent-session/executed-tool-count result)))
      (is (contains? #{:console :tui :emacs} (:psi.agent-session/ui-type result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))

;; ── Mixed with existing attrs (regression) ──────────────

(deftest mixed-attrs-query-test
  (testing "new attrs compose with existing stable attrs"
    (let [result (q [:psi.agent-session/phase
                     :psi.agent-session/model
                     :psi.agent-session/ui-type
                     :psi.agent-session/session-id
                     :psi.agent-session/messages-count
                     :psi.agent-session/ai-call-count
                     :psi.agent-session/tool-call-count
                     :psi.agent-session/executed-tool-count
                     :psi.agent-session/start-time
                     :psi.agent-session/current-time])]
      (is (keyword? (:psi.agent-session/phase result)))
      (is (contains? #{:console :tui :emacs} (:psi.agent-session/ui-type result)))
      (is (integer? (:psi.agent-session/messages-count result)))
      (is (integer? (:psi.agent-session/ai-call-count result)))
      (is (integer? (:psi.agent-session/tool-call-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result))))))

;; ── Model selector bridge attrs ──────────────────────────

(deftest multi-session-context-eql-process-and-persisted-test
  (testing "context session attrs expose process sessions and persisted session list remains queryable"
    (let [cwd                (str (System/getProperty "java.io.tmpdir") "/psi-resolvers-context-" (java.util.UUID/randomUUID))
          _                  (.mkdirs (java.io.File. cwd))
          [ctx _]            (create-session-context {:cwd cwd})
          sd-1               (session/new-session-in! ctx nil {})
          sid-1              (:session-id sd-1)
          path-1             (:session-file sd-1)
          _                  (ss/journal-append-in! ctx sid-1
                                                    (persist/message-entry {:role "user"
                                                                            :content [{:type :text :text "alpha"}]
                                                                            :timestamp (java.time.Instant/parse "2026-03-16T10:47:00Z")}))
          _                  (persist/flush-journal! (java.io.File. path-1)
                                                     sid-1
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/thinking-level-entry :off)
                                                      (persist/session-info-entry "alpha")])
          sd-2               (session/new-session-in! ctx sid-1 {})
          sid-2              (:session-id sd-2)
          path-2             (:session-file sd-2)
          _                  (ss/journal-append-in! ctx sid-2
                                                    (persist/message-entry {:role "user"
                                                                            :content [{:type :text :text "beta"}]
                                                                            :timestamp (java.time.Instant/parse "2026-03-16T10:48:00Z")}))
          _                  (persist/flush-journal! (java.io.File. path-2)
                                                     sid-2
                                                     cwd
                                                     nil
                                                     nil
                                                     [(persist/thinking-level-entry :off)
                                                      (persist/session-info-entry "beta")])
          process-result     (q-in ctx [:psi.agent-session/context-session-count
                                        {:psi.agent-session/context-sessions
                                         [:psi.session-info/id
                                          :psi.session-info/path
                                          :psi.session-info/worktree-path
                                          :psi.session-info/name
                                          :psi.session-info/display-name
                                          :psi.session-info/created]}])
          persisted-result   (q-in ctx [{:psi.session/list
                                         [:psi.session-info/id
                                          :psi.session-info/path
                                          :psi.session-info/worktree-path
                                          :psi.session-info/name
                                          :psi.session-info/message-count]}])
          context-sessions   (:psi.agent-session/context-sessions process-result)
          persisted          (:psi.session/list persisted-result)]
      (is (<= 2 (:psi.agent-session/context-session-count process-result)))
      (is (some #(= sid-1 (:psi.session-info/id %)) context-sessions))
      (is (some #(= sid-2 (:psi.session-info/id %)) context-sessions))
      (is (some #(= path-1 (:psi.session-info/path %)) context-sessions))
      (is (some #(= path-2 (:psi.session-info/path %)) context-sessions))
      (is (some #(= "alpha" (:psi.session-info/display-name %)) context-sessions))
      (is (some #(= "beta" (:psi.session-info/display-name %)) context-sessions))
      (is (every? #(= cwd (:psi.session-info/worktree-path %)) context-sessions))
      (is (every? #(= cwd (:psi.session-info/worktree-path %)) context-sessions))
      (is (every? #(instance? java.time.Instant (:psi.session-info/created %)) context-sessions))

      (is (vector? persisted))
      (is (some #(= sid-1 (:psi.session-info/id %)) persisted))
      (is (some #(= sid-2 (:psi.session-info/id %)) persisted))
      (is (some #(= "alpha" (:psi.session-info/name %)) persisted))
      (is (some #(= "beta" (:psi.session-info/name %)) persisted))
      (is (every? #(= cwd (:psi.session-info/worktree-path %)) persisted))
      (is (every? #(= cwd (:psi.session-info/worktree-path %)) persisted))
      (is (every? integer? (map :psi.session-info/message-count persisted))))))

(deftest model-catalog-resolver-test
  (testing "model-catalog is queryable with deterministic model entries"
    (let [result (q [:psi.agent-session/model-catalog])
          catalog (:psi.agent-session/model-catalog result)]
      (is (vector? catalog))
      (is (seq catalog))
      (is (every? map? catalog))
      (is (every? string? (keep :provider catalog)))
      (is (every? string? (keep :id catalog)))
      (is (every? string? (keep :name catalog)))
      (is (= (sort-by (juxt :provider :id) catalog) catalog)
          "catalog should be sorted by provider then model id"))))

(deftest nrepl-runtime-resolver-test
  (testing "runtime nREPL attrs resolve nil when runtime has no nREPL server"
    (let [result (q [:psi.runtime/nrepl-host
                     :psi.runtime/nrepl-port
                     :psi.runtime/nrepl-endpoint])]
      (is (contains? result :psi.runtime/nrepl-host))
      (is (contains? result :psi.runtime/nrepl-port))
      (is (contains? result :psi.runtime/nrepl-endpoint))
      (is (nil? (:psi.runtime/nrepl-host result)))
      (is (nil? (:psi.runtime/nrepl-port result)))
      (is (nil? (:psi.runtime/nrepl-endpoint result)))))

  (testing "runtime nREPL attrs reflect effective runtime atom values"
    (let [runtime-atom (atom {:host "localhost"
                              :port 7888
                              :endpoint "localhost:7888"})
          [ctx _]      (create-session-context {:persist? false
                                                :nrepl-runtime-atom runtime-atom})
          result       (q-in ctx [:psi.runtime/nrepl-host
                                  :psi.runtime/nrepl-port
                                  :psi.runtime/nrepl-endpoint])]
      (is (= "localhost" (:psi.runtime/nrepl-host result)))
      (is (= 7888 (:psi.runtime/nrepl-port result)))
      (is (= "localhost:7888" (:psi.runtime/nrepl-endpoint result)))))

  (testing "runtime nREPL attrs are discoverable in graph root-queryable attrs and edges"
    (let [result    (q [:psi.graph/root-queryable-attrs :psi.graph/edges])
          root-attrs (set (:psi.graph/root-queryable-attrs result))
          edge-attrs (set (keep :attribute (:psi.graph/edges result)))]
      (is (contains? root-attrs :psi.runtime/nrepl-host))
      (is (contains? root-attrs :psi.runtime/nrepl-port))
      (is (contains? root-attrs :psi.runtime/nrepl-endpoint))
      (is (contains? edge-attrs :psi.runtime/nrepl-host))
      (is (contains? edge-attrs :psi.runtime/nrepl-port))
      (is (contains? edge-attrs :psi.runtime/nrepl-endpoint)))))

(deftest authenticated-providers-resolver-test
  (testing "resolver returns empty vector when oauth context is absent"
    (with-redefs [model-registry/all-models-seq (fn [] [])
                  model-registry/get-auth (fn [_] nil)]
      (let [result (q [:psi.agent-session/authenticated-providers])]
        (is (= [] (:psi.agent-session/authenticated-providers result))))))

  (testing "resolver reports providers with configured auth"
    (with-redefs [model-registry/all-models-seq (fn [] [])
                  model-registry/get-auth (fn [_] nil)]
      (let [oauth-ctx (oauth/create-null-context
                       {:credentials {:anthropic {:type :oauth
                                                  :access "test-access"
                                                  :refresh "test-refresh"
                                                  :expires (+ (System/currentTimeMillis) 3600000)}}})
            [ctx _]  (create-session-context {:persist? false :oauth-ctx oauth-ctx})
            result   (q-in ctx [:psi.agent-session/authenticated-providers])
            providers (:psi.agent-session/authenticated-providers result)]
        (is (vector? providers))
        (is (= ["anthropic"] providers))
        (is (= ["anthropic"]
               (get-in (ss/get-state-value-in ctx (ss/state-path :oauth))
                       [:authenticated-providers])))))))

;; ── Git history bridge (cwd -> :git/context) ─────────────

(deftest git-history-status-query-test
  (testing "git.repo/status is queryable from session root via bridge resolver"
    (let [result (q [:git.repo/status
                     :git.repo/current-commit
                     :git.repo/has-changes])]
      (is (contains? #{:clean :modified :staged :error}
                     (:git.repo/status result)))
      (is (or (nil? (:git.repo/current-commit result))
              (string? (:git.repo/current-commit result))))
      (is (boolean? (:git.repo/has-changes result))))))

(deftest git-history-commits-query-test
  (testing "git.repo/commits returns commit entities via in-session eql"
    (let [result  (q [:git.repo/commits
                      :git.repo/has-history])
          commits (:git.repo/commits result)]
      (is (vector? commits))
      (is (boolean? (:git.repo/has-history result)))
      (when (seq commits)
        (is (string? (:git.commit/sha (first commits))))
        (is (string? (:git.commit/subject (first commits))))))))

(deftest git-worktree-query-test
  (testing "git.worktree attrs are queryable from session root via git-context bridge"
    (let [result (q [:git.worktree/inside-repo?
                     :git.worktree/list
                     :git.worktree/current
                     :git.worktree/count])]
      (is (boolean? (:git.worktree/inside-repo? result)))
      (is (vector? (:git.worktree/list result)))
      (is (integer? (:git.worktree/count result)))
      (when (:git.worktree/inside-repo? result)
        (is (map? (:git.worktree/current result)))))))

(deftest session-git-worktree-query-test
  (testing "session root resolves git worktree + default-branch attrs directly"
    (let [result (q [:git.worktree/list
                     :git.worktree/current
                     :git.worktree/count
                     :git.branch/default-branch])]
      (is (vector? (:git.worktree/list result)))
      (is (integer? (:git.worktree/count result)))
      (is (map? (:git.branch/default-branch result)))
      (is (string? (get-in result [:git.branch/default-branch :branch])))
      (when (pos? (:git.worktree/count result))
        (is (map? (:git.worktree/current result))))))

  (testing ":psi.agent-session/worktree-path is canonical"
    (let [[ctx _] (create-session-context {:cwd "/repo/main"
                                           :session-defaults {:worktree-path "/repo/feature-x"}
                                           :persist? false})
          result (q-in ctx [:psi.agent-session/worktree-path])]
      (is (= "/repo/feature-x" (:psi.agent-session/worktree-path result))))))

;; ── Background jobs introspection ───────────────────────

(deftest background-jobs-resolver-test
  (testing "background job attrs resolve from session root and include nested job entities"
    (let [[ctx session-id] (test-support/create-test-session)
          thread-id     session-id
          _         (dispatch/dispatch! ctx :session/update-background-jobs-state
                                        {:update-fn (fn [store]
                                                      (:state (bg-jobs/start-background-job
                                                               store
                                                               {:tool-call-id "tc-bg-1"
                                                                :thread-id    thread-id
                                                                :tool-name    "delegate"
                                                                :job-id       "job-bg-1"
                                                                :job-kind     :workflow
                                                                :workflow-ext-path "extensions/workflow_loader.clj"
                                                                :workflow-id  "planner"})))}
                                        {:origin :core})
          result    (session/query-in ctx session-id
                                      [:psi.agent-session/background-job-count
                                       :psi.agent-session/background-job-statuses
                                       {:psi.agent-session/background-jobs
                                        [:psi.background-job/id
                                         :psi.background-job/thread-id
                                         :psi.background-job/tool-call-id
                                         :psi.background-job/tool-name
                                         :psi.background-job/job-kind
                                         :psi.background-job/status
                                         :psi.background-job/is-terminal
                                         :psi.background-job/is-non-terminal]}])
          jobs      (:psi.agent-session/background-jobs result)
          job       (first jobs)]
      (is (= 1 (:psi.agent-session/background-job-count result)))
      (is (= [:running :pending-cancel :completed :failed :cancelled :timed-out]
             (:psi.agent-session/background-job-statuses result)))
      (is (vector? jobs))
      (is (= 1 (count jobs)))
      (is (= "job-bg-1" (:psi.background-job/id job)))
      (is (= thread-id (:psi.background-job/thread-id job)))
      (is (= "tc-bg-1" (:psi.background-job/tool-call-id job)))
      (is (= "delegate" (:psi.background-job/tool-name job)))
      (is (= :workflow (:psi.background-job/job-kind job)))
      (is (= :running (:psi.background-job/status job)))
      (is (false? (:psi.background-job/is-terminal job)))
      (is (true? (:psi.background-job/is-non-terminal job))))))

(deftest scheduler-resolver-test
  (testing "scheduler attrs resolve from session root and background-job projection includes scheduled prompts"
    (let [[ctx session-id] (test-support/create-test-session)
          _ (dispatch/dispatch! ctx :scheduler/create
                                {:session-id session-id
                                 :schedule-id "sch-1"
                                 :label "check-build"
                                 :message "check build"
                                 :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                                 :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                                 :delay-ms 1000}
                                {:origin :core})
          result (session/query-in ctx session-id
                                   [:psi.scheduler/pending-count
                                    {:psi.scheduler/schedules
                                     [:psi.scheduler/schedule-id
                                      :psi.scheduler/label
                                      :psi.scheduler/message
                                      :psi.scheduler/status]}
                                    {:psi.agent-session/background-jobs
                                     [:psi.background-job/id
                                      :psi.background-job/tool-name
                                      :psi.background-job/job-kind
                                      :psi.background-job/status]}])
          schedules (:psi.scheduler/schedules result)
          jobs (:psi.agent-session/background-jobs result)
          schedule (first schedules)
          job (first (filter #(= "schedule/sch-1" (:psi.background-job/id %)) jobs))]
      (is (= 1 (:psi.scheduler/pending-count result)))
      (is (= 1 (count schedules)))
      (is (= "sch-1" (:psi.scheduler/schedule-id schedule)))
      (is (= "check-build" (:psi.scheduler/label schedule)))
      (is (= :pending (:psi.scheduler/status schedule)))
      (is (= :scheduled-prompt (:psi.background-job/job-kind job)))
      (is (= :running (:psi.background-job/status job)))
      (is (= "check-build" (:psi.background-job/tool-name job)))))

  (testing "scheduler supports entity-seeded single schedule lookup"
    (let [[ctx session-id] (test-support/create-test-session)
          _ (dispatch/dispatch! ctx :scheduler/create
                                {:session-id session-id
                                 :schedule-id "sch-one"
                                 :label "one"
                                 :message "wake"
                                 :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                                 :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                                 :delay-ms 1000}
                                {:origin :core})
          result (session/query-in ctx
                                   [:psi.scheduler/schedule-id
                                    :psi.scheduler/label
                                    :psi.scheduler/message
                                    :psi.scheduler/status]
                                   {:psi.agent-session/session-id session-id
                                    :psi.scheduler/schedule-id "sch-one"})]
      (is (= "sch-one" (:psi.scheduler/schedule-id result)))
      (is (= "one" (:psi.scheduler/label result)))
      (is (= "wake" (:psi.scheduler/message result)))
      (is (= :pending (:psi.scheduler/status result))))))

(deftest register-resolvers-in-includes-history-resolvers-test
  (testing "register-resolvers-in! includes history resolvers so worktree attrs are resolvable
            (regression: extension query-fn uses isolated qctx via register-resolvers-in!)"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx    (query/create-query-context)
          _      (session/register-resolvers-in! qctx false)
          _      (session/register-mutations-in! qctx mutations/all-mutations true)
          result (query/query-in qctx {:psi/agent-session-ctx ctx
                                       :psi.agent-session/session-id session-id}
                                 [:git.worktree/list
                                  :git.worktree/current
                                  :git.worktree/count
                                  :git.branch/default-branch])]
      (is (contains? result :git.worktree/list)
          "git.worktree/list resolvable via isolated qctx (requires history resolvers)")
      (is (contains? result :git.worktree/current)
          "git.worktree/current resolvable via isolated qctx (requires history resolvers)")
      (is (integer? (:git.worktree/count result))
          "git.worktree/count is an integer via isolated qctx")
      (is (contains? result :git.branch/default-branch)
          "git.branch/default-branch resolvable via isolated qctx"))))


