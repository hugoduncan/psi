(ns psi.agent-session.query-graph-test
  "Tests for global query graph registration, workflow mutations,
  tool mutations, bootstrap, and child session infrastructure."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.history.git :as history-git]
   [psi.query.core :as query]
   [psi.recursion.core :as recursion])
  (:import
   (java.io File)))
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Global query graph registration ─────────────────────────────────────────

(deftest register-resolvers-in!-test
  (testing "register-resolvers-in! wires agent-session into an isolated query ctx"
    (let [qctx (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      ;; Agent-session resolvers should now be in the isolated graph
      (let [syms (query/resolver-syms-in qctx)]
        (is (contains? syms 'psi.agent-session.resolvers.session/agent-session-identity)
            "identity resolver should be registered")
        (is (contains? syms 'psi.agent-session.resolvers.session/agent-session-phase)
            "phase resolver should be registered"))))

  (testing "register-resolvers-in! enables EQL queries via the isolated ctx"
    (let [[session-ctx _] (create-session-context)
          qctx            (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      (let [session-id (->> (ss/list-context-sessions-in session-ctx) first :session-id)
            result     (query/query-in qctx
                                       {:psi/agent-session-ctx session-ctx
                                        :psi.agent-session/session-id session-id}
                                       [:psi.agent-session/session-id
                                        :psi.agent-session/phase])]
        (is (string? (:psi.agent-session/session-id result)))
        (is (= :idle (:psi.agent-session/phase result)))))))

(deftest register-mutations-in!-includes-history-mutations-test
  (testing "register-mutations-in! wires history git mutations into isolated query ctx"
    (let [git-ctx     (history-git/create-null-context)
          [ctx _]     (create-session-context {:cwd (:repo-dir git-ctx) :persist? false})
          qctx        (query/create-query-context)]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [syms (query/mutation-syms-in qctx)
            wt-path (str (.getParentFile (File. (:repo-dir git-ctx)))
                         File/separator
                         "ext-mutation-worktree-"
                         (java.util.UUID/randomUUID))
            result (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx
                                         :git/context (history-git/create-context (:repo-dir git-ctx))}
                                        [(list 'git.worktree/add!
                                               {:psi/agent-session-ctx ctx
                                                :git/context (history-git/create-context (:repo-dir git-ctx))
                                                :input {:path wt-path
                                                        :branch "ext-mutation-worktree"}})])
                        'git.worktree/add!)]
        (is (contains? syms 'git.worktree/add!))
        (is (true? (:success result))))))

  (testing "isolated extension mutation path can attach a worktree to an existing branch"
    (let [git-ctx       (history-git/create-null-context)
          repo-dir      (:repo-dir git-ctx)
          parent-dir    (.getParentFile (File. repo-dir))
          suffix        (str (java.util.UUID/randomUUID))
          branch-name   (str "fix-repeated-thinking-output-" suffix)
          source-path   (str parent-dir File/separator "branch-source-" suffix)
          worktree-path (str parent-dir File/separator branch-name)
          _             (history-git/worktree-add git-ctx {:path source-path
                                                           :branch branch-name})
          _             (history-git/worktree-remove git-ctx {:path source-path})
          [ctx _]       (create-session-context {:cwd repo-dir :persist? false})
          qctx          (query/create-query-context)]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [attach (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx
                                         :git/context (history-git/create-context repo-dir)}
                                        [(list 'git.worktree/add!
                                               {:psi/agent-session-ctx ctx
                                                :git/context (history-git/create-context repo-dir)
                                                :input {:path worktree-path
                                                        :branch branch-name
                                                        :create-branch false}})])
                        'git.worktree/add!)]
        (is (true? (:success attach)) (pr-str attach))
        (is (= branch-name (:branch attach)) (pr-str attach))))))

(deftest rpc-trace-mutation-and-resolver-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        qctx       (query/create-query-context)
        mutate     (fn [op params]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx}
                                          [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                      (not (contains? params :session-id))
                                                      (assoc :session-id session-id)))])
                          op))]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)

    (is (contains? (query/mutation-syms-in qctx) 'psi.extension/set-rpc-trace))
    (is (= {:psi.agent-session/rpc-trace-enabled false
            :psi.agent-session/rpc-trace-file nil}
           (session/query-in ctx session-id [:psi.agent-session/rpc-trace-enabled
                                             :psi.agent-session/rpc-trace-file])))

    (let [r1 (mutate 'psi.extension/set-rpc-trace
                     {:session-id session-id
                      :enabled true
                      :file "/tmp/psi-rpc-trace-from-mutation.ndedn"})]
      (is (= true (:psi.agent-session/rpc-trace-enabled r1)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r1)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? true
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (dissoc (:event-data entry) :session-id)))))

    (let [r2 (mutate 'psi.extension/set-rpc-trace {:session-id session-id :enabled false})]
      (is (= false (:psi.agent-session/rpc-trace-enabled r2)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r2)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? false
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (dissoc (:event-data entry) :session-id)))))))

(deftest session-query-in-exposes-recursion-attrs-test
  (testing "session/query-in can read recursion attrs from live session recursion-ctx"
    (let [rctx (recursion/create-context)
          _    (recursion/register-hooks-in! rctx)
          [ctx _] (create-session-context {:recursion-ctx rctx})
          result  (session/query-in ctx
                                    [:psi.recursion/status
                                     :psi.recursion/paused?
                                     :psi.recursion/current-cycle
                                     :psi.recursion/hooks])]
      (is (= :idle (:psi.recursion/status result)))
      (is (false? (:psi.recursion/paused? result)))
      (is (nil? (:psi.recursion/current-cycle result)))
      (is (vector? (:psi.recursion/hooks result)))
      (is (pos? (count (:psi.recursion/hooks result)))))))

(deftest workflow-background-job-terminal-injection-test
  (testing "workflow completion emits background-job-terminal assistant message"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-bg-test}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}
                                                              (ele/script {:expr (fn [_ data]
                                                                                   [{:op :assign
                                                                                     :data {:result (get-in data [:_event :data :result])}}])})))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx
                                         :psi.agent-session/session-id session-id}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/bg"
               :type     :bg-simple
               :chart    chart})
      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/bg"
               :type     :bg-simple
               :id       "wf-bg-1"
               :auto-start? false})
      (mutate 'psi.extension.workflow/send-event
              {:ext-path "/ext/bg"
               :id       "wf-bg-1"
               :event    :workflow/start
               :track-background-job? true
               :data     {:tool-call-id "tc-wf-bg-1"}})
      (mutate 'psi.extension.workflow/send-event
              {:ext-path "/ext/bg"
               :id       "wf-bg-1"
               :event    :workflow/finish
               :data     {:result {:ok true}}})

      ;; Turn boundary / idle checkpoint triggers background terminal injection path.
      (ext-rt/set-extension-run-fn-in! ctx session-id (fn [_text _source] nil))
      (Thread/sleep 30)

      (let [assistant-msgs (->> (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))
                                (filter #(= "assistant" (:role %)))
                                vec)
            injected (some #(when (= "background-job-terminal" (:custom-type %)) %) assistant-msgs)
            injected-text (get-in injected [:content 0 :text])]
        (is (map? injected) (str "assistant messages: " assistant-msgs))
        (is (string? injected-text))
        (is (re-find #"workflow-id" injected-text))))))

(deftest workflow-mutations-and-resolvers-test
  (testing "workflow mutation ops and resolver attrs are wired"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-test}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}
                                                              (ele/script {:expr (fn [_ data]
                                                                                   [{:op :assign
                                                                                     :data {:result (get-in data [:_event :data :result])}}])})))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (let [ctx* ctx]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx*
                                           :psi.agent-session/session-id session-id}
                                          [(list op (cond-> (assoc params :psi/agent-session-ctx ctx*)
                                                      (not (contains? params :session-id))
                                                      (assoc :session-id session-id)))])
                          op)))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [r1 (mutate 'psi.extension.workflow/register-type
                       {:ext-path "/ext/a"
                        :type     :simple
                        :chart    chart})
            r2 (mutate 'psi.extension.workflow/create
                       {:ext-path "/ext/a"
                        :type     :simple
                        :id       "w1"
                        :auto-start? false})
            r3 (mutate 'psi.extension.workflow/send-event
                       {:ext-path "/ext/a"
                        :id       "w1"
                        :event    :workflow/start})
            r4 (mutate 'psi.extension.workflow/send-event
                       {:ext-path "/ext/a"
                        :id       "w1"
                        :event    :workflow/finish
                        :data     {:result 99}})
            q  (session/query-in ctx
                                 [:psi.extension.workflow/count
                                  :psi.extension.workflow/running-count
                                  {:psi.extension/workflows
                                   [:psi.extension/path
                                    :psi.extension.workflow/id
                                    :psi.extension.workflow/phase
                                    :psi.extension.workflow/result]}])]
        (is (true? (:psi.extension.workflow.type/registered? r1)))
        (is (true? (:psi.extension.workflow/created? r2)))
        (is (true? (:psi.extension.workflow/event-accepted? r3)))
        (is (true? (:psi.extension.workflow/event-accepted? r4)))
        (is (= 1 (:psi.extension.workflow/count q)))
        (is (= 0 (:psi.extension.workflow/running-count q)))
        (is (= [{:psi.extension/path "/ext/a"
                 :psi.extension.workflow/id "w1"
                 :psi.extension.workflow/phase :done
                 :psi.extension.workflow/result 99}]
               (:psi.extension/workflows q)))))))

(deftest send-workflow-event-track-background-job-gated-test
  (testing "send-event tracks background jobs only when track-background-job? is true"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-track-gate}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          thread-id session-id]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/gate"
               :type     :gate
               :chart    chart})
      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/gate"
               :type     :gate
               :id       "wg"
               :auto-start? false})

      (let [jobs-before (bg-rt/list-background-jobs-in! ctx thread-id)
            no-track    (mutate 'psi.extension.workflow/send-event
                                {:ext-path "/ext/gate"
                                 :id       "wg"
                                 :event    :workflow/start
                                 :data     {:tool-call-id "tc-gate-1"}})
            jobs-a      (bg-rt/list-background-jobs-in! ctx thread-id)]
        (is (true? (:psi.extension.workflow/event-accepted? no-track)))
        (is (nil? (:psi.extension.background-job/id no-track)))
        (is (= (count jobs-before) (count jobs-a))))

      (let [tracked (mutate 'psi.extension.workflow/send-event
                            {:ext-path "/ext/gate"
                             :id       "wg"
                             :event    :workflow/finish
                             :track-background-job? true
                             :data     {:tool-call-id "tc-gate-2"}})
            jobs-b  (bg-rt/list-background-jobs-in! ctx
                                                    thread-id
                                                    [:running :pending-cancel :completed :failed :cancelled :timed-out])]
        (is (true? (:psi.extension.workflow/event-accepted? tracked)))
        (is (string? (:psi.extension.background-job/id tracked)))
        (is (some #(= (:psi.extension.background-job/id tracked) (:job-id %)) jobs-b))))))

(deftest workflow-query-and-background-job-vertical-slice-test
  (testing "workflow public data and background-job projection are queryable and coherent"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-vertical-slice}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [registered (mutate 'psi.extension.workflow/register-type
                               {:ext-path "/ext/slice"
                                :type :mcp-slice
                                :chart chart
                                :start-event :workflow/start
                                :initial-data-fn (fn [_]
                                                   {:run/id "slice-1"
                                                    :run/current-state :done
                                                    :run/progress {:message "finished"}})
                                :public-data-fn (fn [data]
                                                  {:run/id (:run/id data)
                                                   :run/current-state (:run/current-state data)
                                                   :run/progress (:run/progress data)
                                                   :run/display {:top-line (str "✓ slice " (:run/id data))
                                                                 :detail-line (str "  " (get-in data [:run/progress :message]))}})})
            created (mutate 'psi.extension.workflow/create
                            {:ext-path "/ext/slice"
                             :type :mcp-slice
                             :id "slice-1"
                             :track-background-job? true
                             :input {:tool-call-id "tc-slice-1"}})
            _       (bg-rt/reconcile-workflow-background-jobs-in! ctx)
            resp    (session/query-in ctx session-id
                                      [{:psi.extension/workflows
                                        [:psi.extension/path
                                         :psi.extension.workflow/id
                                         :psi.extension.workflow/phase
                                         :psi.extension.workflow/data]}
                                       {:psi.agent-session/background-jobs
                                        [:psi.background-job/workflow-ext-path
                                         :psi.background-job/workflow-id
                                         :psi.background-job/status
                                         :psi.background-job/is-terminal]}])
            wf      (first (:psi.extension/workflows resp))
            job     (first (:psi.agent-session/background-jobs resp))]
        (is (true? (:psi.extension.workflow.type/registered? registered)))
        (is (true? (:psi.extension.workflow/created? created)))
        (is (= "/ext/slice" (:psi.extension/path wf)))
        (is (= "slice-1" (:psi.extension.workflow/id wf)))
        (is (= :done (:psi.extension.workflow/phase wf)))
        (is (= :done (get-in wf [:psi.extension.workflow/data :run/current-state])))
        (is (= {:message "finished"}
               (get-in wf [:psi.extension.workflow/data :run/progress])))
        (is (= {:top-line "✓ slice slice-1"
                :detail-line "  finished"}
               (get-in wf [:psi.extension.workflow/data :run/display])))
        (is (= "/ext/slice" (:psi.background-job/workflow-ext-path job)))
        (is (= "slice-1" (:psi.background-job/workflow-id job)))
        (is (true? (:psi.background-job/is-terminal job)))
        (is (contains? #{:completed :failed} (:psi.background-job/status job)))))))

