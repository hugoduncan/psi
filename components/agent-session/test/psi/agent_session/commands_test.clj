(ns psi.agent-session.commands-test
  (:require
   [psi.agent-session.test-support :as test-support]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.extensions :as ext]
   [psi.agent-core.core :as agent]
   [psi.ai.model-registry :as model-registry]
   [psi.memory.core :as memory]
   [psi.memory.store :as store]
   [psi.query.core :as query]))

;; ── Test helper ─────────────────────────────────────────────
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- make-test-ctx
  "Create a minimal session context for testing commands.
   Returns [ctx session-id]."
  ([] (make-test-ctx {}))
  ([opts]
   (create-session-context
    {:session-defaults (merge {:model {:provider "anthropic"
                                       :id       "test-model"
                                       :reasoning false}
                               :system-prompt "test prompt"}
                              opts)
     :cwd (test-support/temp-cwd)
     :persist? false})))

(def ^:private test-ai-model
  {:provider :anthropic :id "test-model" :name "Test"})

(def ^:private cmd-opts
  {:oauth-ctx nil
   :ai-model test-ai-model
   :supports-session-tree? true})

(defn- with-ready-memory-ctx
  [ctx]
  (assoc ctx :memory-ctx
         (memory/create-context {:state-overrides {:status :ready}})))

(defn- with-unready-memory-ctx
  [ctx]
  (assoc ctx :memory-ctx
         (memory/create-context {:state-overrides {:status :initializing}})))

;; ── dispatch tests ──────────────────────────────────────────

(deftest dispatch-quit-test
  (let [[ctx session-id] (make-test-ctx)]
    (is (= {:type :quit} (commands/dispatch-in ctx session-id "/quit" cmd-opts)))
    (is (= {:type :quit} (commands/dispatch-in ctx session-id "/exit" cmd-opts)))))

(deftest dispatch-new-session-test
  (let [[ctx session-id] (make-test-ctx)
        current-id session-id
        sd-old     (session/new-session-in! ctx current-id {})
        ctx        ctx
        old-id     (:session-id sd-old)
        result     (commands/dispatch-in ctx old-id "/new" cmd-opts)]
    (is (= :new-session (:type result)))
    (is (string? (:message result)))
    (is (not= old-id (get-in result [:rehydrate :session-id])))))

(deftest dispatch-new-session-uses-callback-when-provided-test
  (let [[ctx session-id] (make-test-ctx)
        called?    (atom 0)
        result     (commands/dispatch-in ctx
                                         session-id
                                         "/new"
                                         (assoc cmd-opts
                                                :on-new-session!
                                                (fn [_source-session-id]
                                                  (swap! called? inc)
                                                  {:messages [{:role :assistant :text "startup"}]
                                                   :tool-calls {"call-1" {:name "read"}}
                                                   :tool-order ["call-1"]})))]
    (is (= :new-session (:type result)))
    (is (= 1 @called?))
    (is (= [{:role :assistant :text "startup"}]
           (get-in result [:rehydrate :messages])))
    (is (= ["call-1"]
           (get-in result [:rehydrate :tool-order])))))

(deftest dispatch-resume-test
  (let [[ctx session-id] (make-test-ctx)]
    (is (= {:type :resume} (commands/dispatch-in ctx session-id "/resume" cmd-opts)))))

(deftest dispatch-tree-open-test
  (let [[ctx session-id] (make-test-ctx)]
    (is (= {:type :tree-open} (commands/dispatch-in ctx session-id "/tree" cmd-opts)))))

(deftest dispatch-tree-not-supported-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/tree" (assoc cmd-opts :supports-session-tree? false))]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "only available in TUI mode"))))

(deftest dispatch-tree-switch-by-id-test
  (let [[ctx session-id] (make-test-ctx)
        sd         (session/new-session-in! ctx session-id {})
        ctx        ctx
        active-id  (:session-id sd)
        result     (commands/dispatch-in ctx active-id (str "/tree " active-id) cmd-opts)]
    (is (string? active-id))
    (is (not= :tree-open (:type result)))))

(deftest dispatch-tree-active-session-id-is-noop-test
  (let [[ctx session-id] (make-test-ctx)
        active-id session-id
        result    (commands/dispatch-in ctx active-id (str "/tree " active-id) cmd-opts)]
    (is (string? active-id))
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Already active session"))))

(deftest dispatch-tree-name-session-test
  (let [[ctx session-id] (make-test-ctx)
        result (commands/dispatch-in ctx session-id (str "/tree name " session-id " Focus on prompt lifecycle") cmd-opts)]
    (is (= :tree-rename (:type result)))
    (is (= session-id (:session-id result)))
    (is (= "Focus on prompt lifecycle" (:session-name result)))
    (is (= "Focus on prompt lifecycle" (:session-name (ss/get-session-data-in ctx session-id))))))

(deftest dispatch-tree-name-session-usage-test
  (let [[ctx session-id] (make-test-ctx)
        result (commands/dispatch-in ctx session-id "/tree name" cmd-opts)]
    (is (= :text (:type result)))
    (is (= "Usage: /tree name <session-id|prefix> <name>" (:message result)))))

(deftest dispatch-status-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/status" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Session status"))
    (is (str/includes? (:message result) "Phase"))))

(deftest dispatch-history-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/history" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Message history"))
    (is (str/includes? (:message result) "(empty)"))))

(deftest dispatch-help-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/help" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "/quit"))
    (is (str/includes? (:message result) "/new"))
    (is (str/includes? (:message result) "/login")))
  (testing "/? is an alias for /help"
    (let [[ctx session-id] (make-test-ctx)]
      (is (= :text (:type (commands/dispatch-in ctx session-id "/?" cmd-opts)))))))

(deftest dispatch-prompts-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/prompts" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Prompt Templates"))))

(deftest dispatch-skills-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/skills" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Skills"))))

(deftest dispatch-worktree-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/worktree" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Git worktrees"))
    (is (str/includes? (:message result) "worktrees:"))))

(deftest dispatch-background-jobs-commands-test
  (let [[ctx session-id] (make-test-ctx)
        thread-id session-id]
    (dispatch/dispatch! ctx :session/update-background-jobs-state
                        {:update-fn (fn [store]
                                      (:state (bg-jobs/start-background-job
                                               store
                                               {:tool-call-id "tc-cmd-j1"
                                                :thread-id thread-id
                                                :tool-name "delegate"
                                                :job-id "job-cmd-j1"})))}
                        {:origin :core})

    (let [jobs-result (commands/dispatch-in ctx thread-id "/jobs" cmd-opts)]
      (is (= :text (:type jobs-result)))
      (is (str/includes? (:message jobs-result) "Background jobs"))
      (is (str/includes? (:message jobs-result) "job-cmd-j1")))

    (let [jobs-filtered (commands/dispatch-in ctx thread-id "/jobs running" cmd-opts)]
      (is (= :text (:type jobs-filtered)))
      (is (str/includes? (:message jobs-filtered) "job-cmd-j1")))

    (let [jobs-invalid (commands/dispatch-in ctx thread-id "/jobs nope" cmd-opts)]
      (is (= :text (:type jobs-invalid)))
      (is (= "Usage: /jobs [status ...]" (:message jobs-invalid))))

    (let [inspect-result (commands/dispatch-in ctx thread-id "/job job-cmd-j1" cmd-opts)]
      (is (= :text (:type inspect-result)))
      (is (str/includes? (:message inspect-result) "Background job"))
      (is (str/includes? (:message inspect-result) "job-cmd-j1")))

    (let [inspect-usage (commands/dispatch-in ctx thread-id "/job" cmd-opts)]
      (is (= :text (:type inspect-usage)))
      (is (= "Usage: /job <job-id>" (:message inspect-usage))))

    (let [cancel-result (commands/dispatch-in ctx thread-id "/cancel-job job-cmd-j1" cmd-opts)
          job           (bg-rt/inspect-background-job-in! ctx thread-id "job-cmd-j1")]
      (is (= :text (:type cancel-result)))
      (is (str/includes? (:message cancel-result) "Cancellation requested"))
      (is (= :pending-cancel (:status job))))

    (let [_ (dispatch/dispatch! ctx :scheduler/create
                                {:session-id thread-id
                                 :schedule-id "sch-cmd-queued"
                                 :label "queued"
                                 :message "queued"
                                 :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                                 :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                                 :delay-ms 1000}
                                {:origin :core})
          _ (swap! (:state* ctx) (ss/session-update thread-id #(assoc % :is-streaming true)))
          _ (dispatch/dispatch! ctx :scheduler/fired
                                {:session-id thread-id
                                 :schedule-id "sch-cmd-queued"}
                                {:origin :core})
          jobs-result (commands/dispatch-in ctx thread-id "/jobs running" cmd-opts)]
      (is (= :text (:type jobs-result)))
      (is (str/includes? (:message jobs-result) "schedule/sch-cmd-queued")))

    (let [cancel-usage (commands/dispatch-in ctx thread-id "/cancel-job" cmd-opts)]
      (is (= :text (:type cancel-usage)))
      (is (= "Usage: /cancel-job <job-id>" (:message cancel-usage))))))

(deftest dispatch-scheduled-background-job-cancel-command-test
  (let [[ctx session-id] (make-test-ctx)
        _ (dispatch/dispatch! ctx :scheduler/create
                              {:session-id session-id
                               :schedule-id "sch-cancel-1"
                               :label "cancel-me"
                               :message "cancel me later"
                               :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                               :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                               :delay-ms 1000}
                              {:origin :core})
        cancel-result (commands/dispatch-in ctx session-id "/cancel-job schedule/sch-cancel-1" cmd-opts)
        result (session/query-in ctx session-id
                                 [{:psi.scheduler/schedules
                                   [:psi.scheduler/schedule-id
                                    :psi.scheduler/status]}])
        schedule (first (:psi.scheduler/schedules result))]
    (is (= :text (:type cancel-result)))
    (is (str/includes? (:message cancel-result) "Cancellation requested"))
    (is (= "sch-cancel-1" (:psi.scheduler/schedule-id schedule)))
    (is (= :cancelled (:psi.scheduler/status schedule)))))

(deftest dispatch-model-no-arg-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/model" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Current model:"))))

(deftest workflow-send-event-tracked-job-visible-via-commands-test
  (testing "workflow send-event tracked job is visible via /jobs and /job"
    (let [[ctx session-id] (make-test-ctx)
          thread-id session-id
          qctx      (query/create-query-context)
          chart     (chart/statechart {:id :cmd-wf}
                                      (ele/state {:id :idle}
                                                 (ele/transition {:event :workflow/start :target :running}))
                                      (ele/state {:id :running})
                                      (ele/final {:id :done}))
          mutate    (fn [op params]
                      (get (query/query-in qctx
                                           {:psi/agent-session-ctx ctx}
                                           [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                       (not (contains? params :session-id))
                                                       (assoc :session-id thread-id)))])
                           op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/cmd"
               :type     :cmdwf
               :chart    chart})

      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/cmd"
               :type     :cmdwf
               :id       "wf-cmd"
               :auto-start? false})

      (let [send-r (mutate 'psi.extension.workflow/send-event
                           {:ext-path "/ext/cmd"
                            :id       "wf-cmd"
                            :event    :workflow/start
                            :track-background-job? true
                            :data     {:tool-call-id "tc-cmd-send-1"}})
            job-id (:psi.extension.background-job/id send-r)]
        (is (string? job-id))

        (let [jobs-result (commands/dispatch-in ctx thread-id "/jobs running" cmd-opts)]
          (is (= :text (:type jobs-result)))
          (is (str/includes? (:message jobs-result) job-id)))

        (let [inspect-result (commands/dispatch-in ctx thread-id (str "/job " job-id) cmd-opts)]
          (is (= :text (:type inspect-result)))
          (is (str/includes? (:message inspect-result) job-id)))

        (let [listed (bg-rt/list-background-jobs-in! ctx thread-id)]
          (is (some #(= job-id (:job-id %)) listed)))))))

(deftest dispatch-model-set-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/model openai gpt-5.3-codex" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "✓ Model set to"))
    (is (= "openai" (get-in (ss/get-session-data-in ctx session-id) [:model :provider])))
    (is (= "gpt-5.3-codex" (get-in (ss/get-session-data-in ctx session-id) [:model :id])))))

(deftest dispatch-model-invalid-arity-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/model openai" cmd-opts)]
    (is (= :text (:type result)))
    (is (= "Usage: /model OR /model <provider> <model-id>" (:message result)))))

(deftest dispatch-model-unknown-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/model openai no-such-model" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Unknown model:"))))

(deftest dispatch-thinking-no-arg-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/thinking" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Current thinking level:"))))

(deftest dispatch-thinking-set-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/thinking high" cmd-opts)]
    (is (= :text (:type result)))
    ;; test model in fixture has reasoning false, so level clamps to :off
    (is (str/includes? (:message result) "✓ Thinking level set to off"))
    (is (= :off (:thinking-level (ss/get-session-data-in ctx session-id))))))

(deftest dispatch-thinking-unknown-level-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/thinking turbo" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Unknown thinking level: turbo"))
    (is (str/includes? (:message result) "Allowed:"))))

(deftest dispatch-thinking-invalid-arity-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/thinking high extra" cmd-opts)]
    (is (= :text (:type result)))
    (is (= "Usage: /thinking OR /thinking <level>" (:message result)))))

(deftest dispatch-remember-without-memory-ctx-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/remember" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Memory context not configured"))))

(deftest dispatch-remember-accepted-test
  (let [[ctx session-id] (make-test-ctx)
        ctx          (with-ready-memory-ctx ctx)
        before-count (count (:records (memory/get-state-in (:memory-ctx ctx))))
        result       (commands/dispatch-in ctx session-id "/remember verify runtime" cmd-opts)
        after-state  (memory/get-state-in (:memory-ctx ctx))
        after-count  (count (:records after-state))
        rec          (last (:records after-state))
        prov         (:provenance rec)
        telemetry    (session/query-in ctx
                                       [:psi.memory.remember/captures
                                        :psi.memory.remember/last-capture-at])]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remembered"))
    (is (str/includes? (:message result) "record-id:"))
    (is (= 1 (- after-count before-count)) "exactly one remember record per invocation")
    (is (= "verify runtime" (:content rec)))
    (is (= :remember (get-in rec [:provenance :source])))
    (is (string? (:sessionId prov)))
    (is (= (:cwd ctx) (:cwd prov)))
    (is (contains? prov :gitBranch))
    (is (some #(= (:record-id rec) (:record-id %))
              (:psi.memory.remember/captures telemetry))
        "captured remember record should be visible in remember telemetry captures")
    (is (= (:timestamp rec) (:psi.memory.remember/last-capture-at telemetry)))))

(deftest dispatch-remember-blocked-when-memory-not-ready-test
  (let [[ctx session-id] (make-test-ctx)
        ctx        (with-unready-memory-ctx ctx)
        result     (commands/dispatch-in ctx session-id "/remember check live readiness" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remember blocked"))
    (is (str/includes? (:message result) "memory_capture_prerequisites_not_ready"))))

(deftest dispatch-remember-store-write-failure-surfaces-fallback-test
  (let [[ctx session-id] (make-test-ctx)
        ctx         (with-ready-memory-ctx ctx)
        status-atom (atom :ready)
        provider    (reify store/StoreProvider
                      (provider-id [_] "failing-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _] this)
                      (close-provider! [this] this)
                      (provider-status [_] @status-atom)
                      (provider-health [_]
                        {:status :healthy
                         :checked-at (java.time.Instant/now)
                         :details nil})
                      (provider-write! [_ _ _]
                        {:ok? false :error :boom :message "write failed"})
                      (provider-query! [_ _] {:ok? true :results []})
                      (provider-load-state [_] {:ok? true}))
        _ (memory/register-store-provider-in! (:memory-ctx ctx) provider)
        _ (memory/select-store-provider-in! (:memory-ctx ctx) "failing-store")
        result (commands/dispatch-in ctx session-id "/remember provider outage path" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remembered with store fallback"))
    (is (str/includes? (:message result) "store-error: boom"))
    (is (str/includes? (:message result) "provider: failing-store"))))

(deftest dispatch-not-a-command-test
  (testing "plain text returns nil"
    (let [[ctx session-id] (make-test-ctx)]
      (is (nil? (commands/dispatch-in ctx session-id "hello world" cmd-opts)))))
  (testing "skill invocation returns nil (handled by agent)"
    (let [[ctx session-id] (make-test-ctx)]
      (is (nil? (commands/dispatch-in ctx session-id "/skill:foo" cmd-opts)))))
  (testing "prompt template returns nil (handled by agent)"
    (let [[ctx session-id] (make-test-ctx)]
      (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
             assoc :prompt-templates [{:name "my-template"
                                       :description "Template"
                                       :content "Expanded template body"
                                       :source :project}])
      (is (nil? (commands/dispatch-in ctx session-id "/my-template" cmd-opts)))))
  (testing "unknown slash input resolves as unknown"
    (let [[ctx session-id] (make-test-ctx)]
      (is (= {:kind :unknown}
             (commands/slash-resolution-in ctx session-id "/no-such-command" cmd-opts))))))

(deftest slash-resolution-prefers-commands-over-templates-test
  (let [[ctx session-id] (make-test-ctx)]
    (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
           assoc :prompt-templates [{:name "history"
                                     :description "Shadow history"
                                     :content "Template history"
                                     :source :project}
                                    {:name "runtime-template"
                                     :description "Runtime template"
                                     :content "Expanded runtime template"
                                     :source :project}])
    (testing "built-in command wins over same-name template"
      (let [resolution (commands/slash-resolution-in ctx session-id "/history" cmd-opts)]
        (is (= :command (:kind resolution)))
        (is (= :text (get-in resolution [:result :type])))
        (is (str/includes? (get-in resolution [:result :message]) "Message history"))))
    (testing "builtin command catalog stays aligned for precedence checks"
      (is (contains? (commands/loaded-command-names-in ctx session-id) "history")))
    (testing "loaded runtime template resolves as template fallback"
      (let [resolution (commands/slash-resolution-in ctx session-id "/runtime-template")]
        (is (= :template (:kind resolution)))
        (is (= "runtime-template"
               (get-in resolution [:template-match :source-template])))
        (is (= "Expanded runtime template"
               (get-in resolution [:template-match :content])))))))

(deftest dispatch-login-no-oauth-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/login" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :login-error (:type result)))))

(deftest dispatch-logout-no-oauth-test
  (let [[ctx session-id] (make-test-ctx)
        result     (commands/dispatch-in ctx session-id "/logout" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "not available"))))

(deftest dispatch-extension-cmd-test
  (let [[ctx session-id] (make-test-ctx)
        reg        (:extension-registry ctx)
        called     (atom nil)
        handler    (fn [args] (reset! called args))
        _          (ext/register-extension-in! reg "test-ext")
        _          (ext/register-command-in! reg
                                             "test-ext"
                                             {:name "test-cmd"
                                              :description "A test command"
                                              :handler handler})
        result     (commands/dispatch-in ctx session-id "/test-cmd some args" cmd-opts)]
    (is (= :extension-cmd (:type result)))
    (is (= "test-cmd" (:name result)))
    (is (= "some args" (:args result)))
    (is (fn? (:handler result)))))

;; ── format-* tests ──────────────────────────────────────────

(deftest format-status-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-status ctx session-id)]
    (is (str/includes? s "Phase"))
    (is (str/includes? s "idle"))
    (is (str/includes? s "Roots"))
    (is (str/includes? s "agent-session-ctx"))))

(deftest format-status-includes-effective-reasoning-effort-test
  (let [[ctx session-id] (make-test-ctx {:model {:provider "openai"
                                                 :id "gpt-5.3-codex"
                                                 :reasoning true}
                                         :thinking-level :high})
        s          (commands/format-status ctx session-id)]
    (is (str/includes? s "thinking high"))))

(deftest format-worktree-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-worktree ctx session-id)]
    (is (str/includes? s "Git worktrees"))
    (is (str/includes? s "cwd"))
    (is (str/includes? s "worktrees:"))))

(deftest format-history-empty-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-history ctx session-id)]
    (is (str/includes? s "(empty)"))))

(deftest format-history-with-messages-test
  (let [[ctx session-id] (make-test-ctx)]
    (agent/append-message-in! (ss/agent-ctx-in ctx session-id)
                              {:role "user" :content [{:type :text :text "hello"}]})
    (let [s (commands/format-history ctx session-id)]
      (is (str/includes? s "[user]"))
      (is (str/includes? s "hello")))))

(deftest format-help-includes-all-commands-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-help ctx session-id)]
    (doseq [cmd ["/quit" "/status" "/history" "/new" "/resume" "/tree"
                 "/login" "/logout" "/remember" "/worktree"
                 "/reload-models"
                 "/jobs" "/job" "/cancel-job"
                 "/help" "/prompts" "/skills"]]
      (is (str/includes? s cmd) (str "help should mention " cmd)))
    (is (str/includes? s "~/.psi/agent/models.edn"))
    (is (str/includes? s ".psi/models.edn"))))

(deftest format-prompts-none-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-prompts ctx session-id)]
    (is (str/includes? s "(none discovered)"))))

(deftest format-skills-none-test
  (let [[ctx session-id] (make-test-ctx)
        s          (commands/format-skills ctx session-id)]
    (is (str/includes? s "(none discovered)"))))

;; ── /reload-models ──────────────────────────────────────────

(deftest reload-models-command-test
  (testing "/reload-models returns :text result with count"
    (let [[ctx session-id] (make-test-ctx)
          result (commands/dispatch-in ctx session-id "/reload-models" cmd-opts)]
      (is (= :text (:type result)))
      (is (str/includes? (:message result) "Models reloaded"))
      (is (str/includes? (:message result) "count"))))

  (testing "/reload-models picks up a new project models.edn"
    (let [cwd (test-support/temp-cwd)
          _   (.mkdirs (java.io.File. (str cwd "/.psi")))
          _   (spit (str cwd "/.psi/models.edn")
                    (pr-str {:providers
                             {"local" {:base-url "http://localhost:11434/v1"
                                       :api      :openai-completions
                                       :models   [{:id   "test-llm"
                                                   :name "Test LLM"}]}}}))
          [ctx session-id] (create-session-context {:cwd cwd :persist? false})
          result (commands/dispatch-in ctx session-id "/reload-models" cmd-opts)]
      (is (= :text (:type result)))
      (is (str/includes? (:message result) "Models reloaded"))
      (is (some? (model-registry/find-model :local "test-llm")))
      ;; cleanup: reset registry to built-ins only
      (model-registry/init! {:user-models-path    nil
                             :project-models-path nil}))))

(deftest reload-extension-installs-command-test
  (testing "/reload-extension-installs returns :text result with apply summary"
    (let [[ctx session-id] (make-test-ctx)]
      (with-redefs [session/reload-extension-installs-in!
                    (fn [_ctx _sid]
                      {:install-state
                       {:psi.extensions/effective
                        {:entries-by-lib
                         {'foo/local {:status :loaded}
                          'bar/remote {:status :restart-required}}}
                        :psi.extensions/diagnostics
                        [{:severity :info :category :restart-required :message "restart required"}]
                        :psi.extensions/last-apply
                        {:status :restart-required
                         :restart-required? true
                         :summary "restart required for remote deps"}}})]
        (let [result (commands/dispatch-in ctx session-id "/reload-extension-installs" cmd-opts)]
          (is (= :text (:type result)))
          (is (str/includes? (:message result) "Extension installs reloaded"))
          (is (str/includes? (:message result) "restart-required"))
          (is (str/includes? (:message result) "diagnostics")))))))

