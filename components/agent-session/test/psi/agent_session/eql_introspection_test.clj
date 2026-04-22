(ns psi.agent-session.eql-introspection-test
  "Tests for EQL query-in introspection: session attrs, tool output,
  tool-call attempts, tool lifecycle events, provider captures, and
  API error diagnostics."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- retarget
  [ctx _sid-or-sd]
  ctx)
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- inject-messages!
  "Append a sequence of message maps into agent-core for testing."
  [ctx session-id msgs]
  (let [agent-ctx (ss/agent-ctx-in ctx session-id)]
    (doseq [m msgs]
      (agent-core/append-message-in! agent-ctx m))))

(defn- make-user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- make-assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :stop-reason :stop :timestamp (java.time.Instant/now)})

(defn- make-tool-result-msg [tool-id tool-name result]
  {:role "toolResult" :tool-call-id tool-id :tool-name tool-name
   :content [{:type :text :text result}] :is-error false
   :timestamp (java.time.Instant/now)})

;; ── EQL introspection ───────────────────────────────────────────────────────

(deftest eql-introspection-test
  (testing "query-in resolves :psi.agent-session/session-id"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.agent-session/session-id])]
      (is (string? (:psi.agent-session/session-id result)))))

  (testing "query-in resolves phase"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.agent-session/phase
                                                   :psi.agent-session/is-idle])]
      (is (= :idle (:psi.agent-session/phase result)))
      (is (true? (:psi.agent-session/is-idle result)))))

  (testing "query-in resolves model after set-model-in!"
    (let [[ctx session-id] (create-session-context)
          model {:provider "x" :id "y" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [result (session/query-in ctx session-id [:psi.agent-session/model])]
        (is (= model (:psi.agent-session/model result))))))

  (testing "set-system-prompt-in! updates session + agent-core prompt state"
    (let [[ctx session-id] (create-session-context)
          prompt     "graph-aware system prompt"
          _          (dispatch/dispatch! ctx :session/set-system-prompt {:session-id session-id :prompt prompt} {:origin :core})
          result     (session/query-in ctx session-id [:psi.agent-session/system-prompt])]
      (is (= prompt (:psi.agent-session/system-prompt result)))
      (is (= prompt (:system-prompt (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))))))

  (testing "new-session-in! preserves stable prompt (bootstrap cwd frozen at context creation)"
    (let [[ctx session-id] (create-session-context {:cwd "/tmp/main"})
          base      (str "Prompt body"
                         "\nCurrent date and time: Friday, March 13, 2026 at 11:00:00 am GMT-04:00"
                         "\nCurrent working directory: /tmp/main"
                         "\nCurrent worktree directory: /tmp/main")]
      (test-support/update-state! ctx :session-data merge {:worktree-path "/tmp/main"
                                                           :base-system-prompt base
                                                           :system-prompt base})
      (let [sd                 (session/new-session-in! ctx session-id {:worktree-path "/tmp/worktree"})
            session-id         (:session-id sd)
            ctx                (retarget ctx sd)
            sd                 (ss/get-session-data-in ctx session-id)]
        ;; Prompt cwd is frozen at context creation — does not change with worktree
        (is (str/includes? (:system-prompt sd) "Current working directory: /tmp/main"))
        (is (= (:base-system-prompt sd) (:system-prompt sd))))))

  (testing "query-in resolves graph capabilities via agent-session bridge"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.graph/capabilities])]
      (is (vector? (:psi.graph/capabilities result)))
      (is (seq (:psi.graph/capabilities result)))
      (is (some #(= :agent-session (:domain %))
                (:psi.graph/capabilities result)))))

  (testing "query-in supports explicit non-active session targeting via extra entity"
    (let [[ctx active-session-id] (create-session-context)
          child-sd                (session/new-session-in! ctx active-session-id {:session-name "child helper"})
          child-session-id        (:session-id child-sd)
          _                       (dispatch/dispatch! ctx :session/set-model {:session-id child-session-id
                                                                              :model {:provider "local" :id "gemma-3" :reasoning false}}
                                                      {:origin :core})
          result                  (session/query-in ctx
                                                    [:psi.agent-session/session-id
                                                     :psi.agent-session/session-name
                                                     :psi.agent-session/model-provider
                                                     :psi.agent-session/model-id]
                                                    {:psi.agent-session/session-id child-session-id})]
      (is (= child-session-id (:psi.agent-session/session-id result)))
      (is (= "child helper" (:psi.agent-session/session-name result)))
      (is (= "local" (:psi.agent-session/model-provider result)))
      (is (= "gemma-3" (:psi.agent-session/model-id result)))))

  (testing "query-in resolves developer prompt"
    (let [[ctx session-id] (create-session-context {:session-defaults {:developer-prompt "dev layer"
                                                                       :developer-prompt-source :explicit}})
          result (session/query-in ctx session-id [:psi.agent-session/developer-prompt
                                                   :psi.agent-session/developer-prompt-source
                                                   :psi.agent-session/prompt-layers])]
      (is (= "dev layer" (:psi.agent-session/developer-prompt result)))
      (is (= :explicit (:psi.agent-session/developer-prompt-source result)))
      (is (= {:base-system-prompt ""
              :system-prompt ""
              :developer-prompt "dev layer"
              :developer-prompt-source :explicit
              :prompt-contributions []}
             (:psi.agent-session/prompt-layers result)))))

  (testing "query-in omits fallback developer prompt when none is configured"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.agent-session/developer-prompt
                                                   :psi.agent-session/developer-prompt-source
                                                   :psi.agent-session/prompt-layers])]
      (is (nil? (:psi.agent-session/developer-prompt result)))
      (is (nil? (:psi.agent-session/developer-prompt-source result)))
      (is (= {:base-system-prompt ""
              :system-prompt ""
              :developer-prompt nil
              :developer-prompt-source nil
              :prompt-contributions []}
             (:psi.agent-session/prompt-layers result)))))

  (testing "query-in resolves prompt lifecycle summaries"
    (let [[ctx session-id] (create-session-context)
          prepared-at (java.time.Instant/now)
          recorded-at (java.time.Instant/now)]
      (test-support/update-state! ctx :session-data assoc
                                  :last-prepared-request-summary {:turn-id "t-1"
                                                                  :message-count 2
                                                                  :tool-count 1
                                                                  :system-prompt-chars 42
                                                                  :input-expansion {:kind :skill :name "demo"}
                                                                  :prepared-at prepared-at}
                                  :last-execution-result-summary {:turn-id "t-1"
                                                                  :turn-outcome :turn.outcome/stop
                                                                  :stop-reason :stop
                                                                  :tool-call-count 1
                                                                  :recorded-at recorded-at})
      (let [result (session/query-in ctx session-id [:psi.agent-session/last-prepared-request-summary
                                                     :psi.agent-session/last-prepared-turn-id
                                                     :psi.agent-session/last-prepared-message-count
                                                     :psi.agent-session/last-prepared-tool-count
                                                     :psi.agent-session/last-prepared-system-prompt-chars
                                                     :psi.agent-session/last-prepared-input-expansion
                                                     :psi.agent-session/last-prepared-at
                                                     :psi.agent-session/last-execution-result-summary
                                                     :psi.agent-session/last-execution-turn-id
                                                     :psi.agent-session/last-execution-turn-outcome
                                                     :psi.agent-session/last-execution-stop-reason
                                                     :psi.agent-session/last-execution-tool-call-count
                                                     :psi.agent-session/last-execution-recorded-at])]
        (is (= {:turn-id "t-1"
                :message-count 2
                :tool-count 1
                :system-prompt-chars 42
                :input-expansion {:kind :skill :name "demo"}
                :prepared-at prepared-at}
               (:psi.agent-session/last-prepared-request-summary result)))
        (is (= "t-1" (:psi.agent-session/last-prepared-turn-id result)))
        (is (= 2 (:psi.agent-session/last-prepared-message-count result)))
        (is (= 1 (:psi.agent-session/last-prepared-tool-count result)))
        (is (= 42 (:psi.agent-session/last-prepared-system-prompt-chars result)))
        (is (= {:kind :skill :name "demo"}
               (:psi.agent-session/last-prepared-input-expansion result)))
        (is (= prepared-at (:psi.agent-session/last-prepared-at result)))
        (is (= {:turn-id "t-1"
                :turn-outcome :turn.outcome/stop
                :stop-reason :stop
                :tool-call-count 1
                :recorded-at recorded-at}
               (:psi.agent-session/last-execution-result-summary result)))
        (is (= "t-1" (:psi.agent-session/last-execution-turn-id result)))
        (is (= :turn.outcome/stop (:psi.agent-session/last-execution-turn-outcome result)))
        (is (= :stop (:psi.agent-session/last-execution-stop-reason result)))
        (is (= 1 (:psi.agent-session/last-execution-tool-call-count result)))
        (is (= recorded-at (:psi.agent-session/last-execution-recorded-at result))))))

  (testing "query-in resolves context fraction"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:session-id session-id :tokens 4000 :window 10000} {:origin :core})
      (let [result (session/query-in ctx session-id [:psi.agent-session/context-fraction])]
        (is (= 0.4 (:psi.agent-session/context-fraction result))))))

  (testing "query-in resolves extension-summary"
    (let [[ctx session-id] (create-session-context)]
      (ext/register-extension-in! (:extension-registry ctx) "/ext/a")
      (let [result (session/query-in ctx session-id [:psi.agent-session/extension-summary])]
        (is (= 1 (get-in result [:psi.agent-session/extension-summary :extension-count]))))))

  (testing "query-in resolves prompt contribution attrs"
    (let [[ctx session-id] (create-session-context)]
      (dispatch/dispatch! ctx :session/register-prompt-contribution
                          {:session-id session-id :ext-path "/ext/a" :id "c1"
                           :contribution {:content "Hint" :priority 10 :enabled true}}
                          {:origin :core})
      (let [result (session/query-in ctx session-id [:psi.agent-session/base-system-prompt
                                                     :psi.agent-session/prompt-contributions
                                                     :psi.extension/prompt-contribution-count])]
        (is (= "" (:psi.agent-session/base-system-prompt result)))
        (is (= 1 (count (:psi.agent-session/prompt-contributions result))))
        (is (= 1 (:psi.extension/prompt-contribution-count result))))))

  (testing "query-in resolves stats"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.agent-session/stats])]
      (is (map? (:psi.agent-session/stats result)))
      (is (contains? (:psi.agent-session/stats result) :session-id))))

  (testing "query-in resolves canonical telemetry attrs directly"
    (let [[ctx session-id] (create-session-context)]
      (doseq [attr [:psi.agent-session/messages-count
                    :psi.agent-session/ai-call-count
                    :psi.agent-session/tool-call-count
                    :psi.agent-session/executed-tool-count
                    :psi.agent-session/start-time
                    :psi.agent-session/current-time]]
        (let [result (session/query-in ctx session-id [attr])]
          (is (contains? result attr))
          (is (not (contains? result :com.wsscode.pathom3.connect.runner/attribute-errors))))))

    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id [:psi.agent-session/messages-count
                                                   :psi.agent-session/ai-call-count
                                                   :psi.agent-session/tool-call-count
                                                   :psi.agent-session/executed-tool-count
                                                   :psi.agent-session/start-time
                                                   :psi.agent-session/current-time])]
      (is (int? (:psi.agent-session/messages-count result)))
      (is (int? (:psi.agent-session/ai-call-count result)))
      (is (int? (:psi.agent-session/tool-call-count result)))
      (is (int? (:psi.agent-session/executed-tool-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result)))
      (is (<= 0 (:psi.agent-session/messages-count result)))
      (is (<= 0 (:psi.agent-session/ai-call-count result)))
      (is (<= 0 (:psi.agent-session/tool-call-count result)))
      (is (<= 0 (:psi.agent-session/executed-tool-count result)))))

  (testing "query-in resolves usage aggregates from journal assistant usage"
    (let [[ctx session-id] (create-session-context)
          assistant-1 {:role "assistant"
                       :content [{:type :text :text "a"}]
                       :usage {:input-tokens 1000
                               :output-tokens 200
                               :cache-read-tokens 50
                               :cache-write-tokens 10
                               :cost {:total 0.123}}
                       :timestamp (java.time.Instant/now)}
          assistant-2 {:role "assistant"
                       :content [{:type :text :text "b"}]
                       :usage {:input 500
                               :output 100
                               :cache-read 0
                               :cache-write 0
                               :cost {:total 0.050}}
                       :timestamp (java.time.Instant/now)}]
      (test-support/update-state! ctx :journal
                                  into
                                  [(persist/message-entry assistant-1)
                                   (persist/message-entry assistant-2)])
      (let [result (session/query-in ctx session-id [:psi.agent-session/usage-input
                                                     :psi.agent-session/usage-output
                                                     :psi.agent-session/usage-cache-read
                                                     :psi.agent-session/usage-cache-write
                                                     :psi.agent-session/usage-cost-total])]
        (is (= 1500 (:psi.agent-session/usage-input result)))
        (is (= 300 (:psi.agent-session/usage-output result)))
        (is (= 50 (:psi.agent-session/usage-cache-read result)))
        (is (= 10 (:psi.agent-session/usage-cache-write result)))
        (is (< (Math/abs (- 0.173 (double (:psi.agent-session/usage-cost-total result))))
               1.0e-9)))))

  (testing "query-in resolves model provider/id/reasoning attrs"
    (let [[ctx session-id] (create-session-context)
          model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}]
      (dispatch/dispatch! ctx :session/set-model {:session-id session-id :model model} {:origin :core})
      (let [result (session/query-in ctx session-id [:psi.agent-session/model-provider
                                                     :psi.agent-session/model-id
                                                     :psi.agent-session/model-reasoning
                                                     :psi.agent-session/effective-reasoning-effort
                                                     :psi.agent-session/worktree-path
                                                     :psi.agent-session/git-branch])]
        (is (= "openai" (:psi.agent-session/model-provider result)))
        (is (= "gpt-5.3-codex" (:psi.agent-session/model-id result)))
        (is (true? (:psi.agent-session/model-reasoning result)))
        (is (nil? (:psi.agent-session/effective-reasoning-effort result)))
        (is (string? (:psi.agent-session/worktree-path result)))
        (is (contains? result :psi.agent-session/git-branch)))))

  (testing "query-in resolves tool summary"
    (let [[ctx session-id] (create-session-context)
          tool {:name "foo" :label "Foo" :description "Bar" :parameters "{}"}]
      (agent-core/set-tools-in! (ss/agent-ctx-in ctx session-id) [tool])
      (let [summary (session/query-in ctx session-id [:psi.tool/count :psi.tool/names :psi.tool/summary])]
        (is (= 1 (:psi.tool/count summary)))
        (is (= ["foo"] (:psi.tool/names summary)))
        (is (= "foo" (get-in summary [:psi.tool/summary :tools 0 :name]))))))

  (testing "session-worktree-path-in prefers session worktree-path over bootstrap context cwd"
    (let [[ctx session-id] (create-session-context {:cwd "/repo/main"
                                                    :session-defaults {:worktree-path "/repo/feature-x"}})]
      (is (= "/repo/feature-x" (ss/session-worktree-path-in ctx session-id)))
      (dispatch/dispatch! ctx :session/set-worktree-path {:session-id session-id :worktree-path "/repo/feature-y"} {:origin :core})
      (is (= "/repo/feature-y" (ss/session-worktree-path-in ctx session-id)))))

  (testing "query-in resolves effective reasoning effort for reasoning models"
    (let [[ctx session-id] (create-session-context {:session-defaults {:model {:provider "openai"
                                                                               :id "gpt-5.3-codex"
                                                                               :reasoning true}
                                                                       :thinking-level :high}})
          result (session/query-in ctx session-id [:psi.agent-session/model-reasoning
                                                   :psi.agent-session/effective-reasoning-effort])]
      (is (true? (:psi.agent-session/model-reasoning result)))
      (is (= "high" (:psi.agent-session/effective-reasoning-effort result))))))

(deftest tool-output-eql-introspection-test
  (testing "query-in resolves tool-output policy defaults and overrides"
    (let [[ctx session-id] (create-session-context {:session-defaults
                                                    {:tool-output-overrides
                                                     {"bash" {:max-lines 77 :max-bytes 2048}}}})
          result (session/query-in ctx session-id [:psi.tool-output/default-max-lines
                                                   :psi.tool-output/default-max-bytes
                                                   :psi.tool-output/overrides])]
      (is (= 1000 (:psi.tool-output/default-max-lines result)))
      (is (= 51200 (:psi.tool-output/default-max-bytes result)))
      (is (= {"bash" {:max-lines 77 :max-bytes 2048}}
             (:psi.tool-output/overrides result)))))

  (testing "query-in resolves tool-output calls and aggregate stats"
    (let [[ctx session-id] (create-session-context)]
      (test-support/set-state! ctx :tool-output-stats
                               {:calls [{:tool-call-id "call-1"
                                         :tool-name "bash"
                                         :timestamp (java.time.Instant/now)
                                         :limit-hit true
                                         :truncated-by :bytes
                                         :effective-max-lines 1000
                                         :effective-max-bytes 51200
                                         :output-bytes 120
                                         :context-bytes-added 64}]
                                :aggregates {:total-context-bytes 64
                                             :by-tool {"bash" 64}
                                             :limit-hits-by-tool {"bash" 1}}})
      (let [result (session/query-in ctx session-id [{:psi.tool-output/calls
                                                      [:psi.tool-output.call/tool-call-id
                                                       :psi.tool-output.call/tool-name
                                                       :psi.tool-output.call/limit-hit?
                                                       :psi.tool-output.call/truncated-by
                                                       :psi.tool-output.call/effective-max-lines
                                                       :psi.tool-output.call/effective-max-bytes
                                                       :psi.tool-output.call/output-bytes
                                                       :psi.tool-output.call/context-bytes-added]}
                                                     :psi.tool-output/stats])
            calls  (:psi.tool-output/calls result)
            first-call (first calls)
            stats  (:psi.tool-output/stats result)]
        (is (= 1 (count calls)))
        (is (= "call-1" (:psi.tool-output.call/tool-call-id first-call)))
        (is (= "bash" (:psi.tool-output.call/tool-name first-call)))
        (is (true? (:psi.tool-output.call/limit-hit? first-call)))
        (is (= :bytes (:psi.tool-output.call/truncated-by first-call)))
        (is (= 1000 (:psi.tool-output.call/effective-max-lines first-call)))
        (is (= 51200 (:psi.tool-output.call/effective-max-bytes first-call)))
        (is (= 120 (:psi.tool-output.call/output-bytes first-call)))
        (is (= 64 (:psi.tool-output.call/context-bytes-added first-call)))
        (is (= {:total-context-bytes 64
                :by-tool {"bash" 64}
                :limit-hits-by-tool {"bash" 1}}
               stats))))))

(deftest tool-call-attempts-eql-introspection-test
  (testing "query-in resolves tool-call attempts and unmatched counts"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)
          t3  (.plusMillis t0 30)
          t4  (.plusMillis t0 40)]
      (test-support/update-state! ctx :tool-call-attempts into
                                  [{:turn-id "turn-1"
                                    :timestamp t0
                                    :event-kind :toolcall-start
                                    :content-index 0
                                    :id "call-1"
                                    :name "bash"}
                                   {:turn-id "turn-1"
                                    :timestamp t1
                                    :event-kind :toolcall-delta
                                    :content-index 0
                                    :delta "{\"command\":\"ls\"}"}
                                   {:turn-id "turn-1"
                                    :timestamp t2
                                    :event-kind :toolcall-end
                                    :content-index 0}
                                   {:turn-id "turn-2"
                                    :timestamp t3
                                    :event-kind :toolcall-start
                                    :content-index 0
                                    :id "call-2"
                                    :name "read"}
                                   {:turn-id "turn-2"
                                    :timestamp t4
                                    :event-kind :toolcall-end
                                    :content-index 0}])

      ;; Only call-1 has a committed toolResult; call-2 is unmatched.
      (inject-messages! ctx session-id [(make-tool-result-msg "call-1" "bash" "ok")])

      (let [r        (session/query-in ctx session-id
                                       [:psi.agent-session/tool-call-attempt-count
                                        :psi.agent-session/tool-call-attempt-unmatched-count
                                        {:psi.agent-session/tool-call-attempts
                                         [:psi.tool-call-attempt/id
                                          :psi.tool-call-attempt/name
                                          :psi.tool-call-attempt/status
                                          :psi.tool-call-attempt/delta-count
                                          :psi.tool-call-attempt/argument-bytes
                                          :psi.tool-call-attempt/result-recorded?]}])
            attempts (:psi.agent-session/tool-call-attempts r)
            by-id    (into {} (map (juxt :psi.tool-call-attempt/id identity)) attempts)
            call-1   (get by-id "call-1")
            call-2   (get by-id "call-2")]
        (is (= 2 (:psi.agent-session/tool-call-attempt-count r)))
        (is (= 1 (:psi.agent-session/tool-call-attempt-unmatched-count r)))

        (is (= :result-recorded (:psi.tool-call-attempt/status call-1)))
        (is (true? (:psi.tool-call-attempt/result-recorded? call-1)))
        (is (= 1 (:psi.tool-call-attempt/delta-count call-1)))
        (is (pos? (:psi.tool-call-attempt/argument-bytes call-1)))

        (is (= :ended (:psi.tool-call-attempt/status call-2)))
        (is (false? (:psi.tool-call-attempt/result-recorded? call-2)))))))

(deftest tool-call-count-vs-lifecycle-summary-count-test
  (testing "tool-call-count is transcript-based while lifecycle-summary-count is canonical lifecycle-based"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)]
      (ss/journal-append-in! ctx session-id
                             {:kind :message
                              :data {:message {:role "toolResult"
                                               :tool-call-id "call-journal-1"
                                               :tool-name "read"
                                               :content [{:type :text :text "ok"}]
                                               :is-error false}}})
      (test-support/update-state! ctx :tool-lifecycle-events into
                                  [{:event-kind :tool-start
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :timestamp t0}
                                   {:event-kind :tool-executing
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :arguments "{}"
                                    :parsed-args {}
                                    :timestamp t1}
                                   {:event-kind :tool-result
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :result-text "done"
                                    :is-error false
                                    :timestamp t2}
                                   {:event-kind :tool-start
                                    :tool-id "call-life-2"
                                    :tool-name "read"
                                    :timestamp t1}])
      (let [r (session/query-in ctx session-id
                                [:psi.agent-session/tool-call-count
                                 :psi.agent-session/executed-tool-count
                                 :psi.agent-session/tool-lifecycle-summary-count])]
        (is (= 1 (:psi.agent-session/tool-call-count r)))
        (is (= 2 (:psi.agent-session/executed-tool-count r)))
        (is (= 2 (:psi.agent-session/tool-lifecycle-summary-count r)))))))

(deftest tool-lifecycle-events-eql-introspection-test
  (testing "query-in resolves canonical tool lifecycle events as a distinct surface"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)]
      (test-support/update-state! ctx :tool-lifecycle-events into
                                  [{:event-kind :tool-start
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :timestamp t0}
                                   {:event-kind :tool-executing
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :arguments "{}"
                                    :parsed-args {}
                                    :timestamp t1}
                                   {:event-kind :tool-result
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :result-text "ok"
                                    :is-error false
                                    :timestamp t2}])
      (let [r (session/query-in ctx session-id
                                [:psi.agent-session/tool-lifecycle-event-count
                                 :psi.agent-session/tool-lifecycle-summary-count
                                 {:psi.agent-session/tool-lifecycle-events
                                  [:psi.tool-lifecycle/event-kind
                                   :psi.tool-lifecycle/tool-id
                                   :psi.tool-lifecycle/tool-name
                                   :psi.tool-lifecycle/result-text
                                   :psi.tool-lifecycle/is-error
                                   :psi.tool-lifecycle/arguments
                                   :psi.tool-lifecycle/parsed-args]}
                                 {:psi.agent-session/tool-lifecycle-summaries
                                  [:psi.tool-lifecycle.summary/tool-id
                                   :psi.tool-lifecycle.summary/tool-name
                                   :psi.tool-lifecycle.summary/event-count
                                   :psi.tool-lifecycle.summary/last-event-kind
                                   :psi.tool-lifecycle.summary/completed?
                                   :psi.tool-lifecycle.summary/is-error
                                   :psi.tool-lifecycle.summary/result-text
                                   :psi.tool-lifecycle.summary/arguments
                                   :psi.tool-lifecycle.summary/parsed-args]}])
            events    (:psi.agent-session/tool-lifecycle-events r)
            summaries (:psi.agent-session/tool-lifecycle-summaries r)
            summary   (first summaries)]
        (is (= 3 (:psi.agent-session/tool-lifecycle-event-count r)))
        (is (= 1 (:psi.agent-session/tool-lifecycle-summary-count r)))
        (is (= [:tool-start :tool-executing :tool-result]
               (mapv :psi.tool-lifecycle/event-kind events)))
        (is (= "call-1" (:psi.tool-lifecycle/tool-id (first events))))
        (is (= "read" (:psi.tool-lifecycle/tool-name (first events))))
        (is (= "{}" (:psi.tool-lifecycle/arguments (second events))))
        (is (= {} (:psi.tool-lifecycle/parsed-args (second events))))
        (is (= "ok" (:psi.tool-lifecycle/result-text (last events))))
        (is (false? (:psi.tool-lifecycle/is-error (last events))))
        (is (= "call-1" (:psi.tool-lifecycle.summary/tool-id summary)))
        (is (= "read" (:psi.tool-lifecycle.summary/tool-name summary)))
        (is (= 3 (:psi.tool-lifecycle.summary/event-count summary)))
        (is (= :tool-result (:psi.tool-lifecycle.summary/last-event-kind summary)))
        (is (true? (:psi.tool-lifecycle.summary/completed? summary)))
        (is (false? (:psi.tool-lifecycle.summary/is-error summary)))
        (is (= "ok" (:psi.tool-lifecycle.summary/result-text summary)))
        (is (= "{}" (:psi.tool-lifecycle.summary/arguments summary)))
        (is (= {} (:psi.tool-lifecycle.summary/parsed-args summary)))

        (let [qctx (query/create-query-context)
              _    (session/register-resolvers-in! qctx false)
              lookup (query/query-in qctx
                                     {:psi/agent-session-ctx ctx
                                      :psi.agent-session/session-id session-id
                                      :psi.agent-session/lookup-tool-id "call-1"}
                                     [{:psi.agent-session/tool-lifecycle-summary-for-tool-id
                                       [:psi.tool-lifecycle.summary/tool-id
                                        :psi.tool-lifecycle.summary/tool-name
                                        :psi.tool-lifecycle.summary/event-count
                                        :psi.tool-lifecycle.summary/last-event-kind
                                        :psi.tool-lifecycle.summary/completed?
                                        :psi.tool-lifecycle.summary/result-text
                                        :psi.tool-lifecycle.summary/arguments
                                        :psi.tool-lifecycle.summary/parsed-args]}])
              looked-up (:psi.agent-session/tool-lifecycle-summary-for-tool-id lookup)]
          (is (= "call-1" (:psi.tool-lifecycle.summary/tool-id looked-up)))
          (is (= "read" (:psi.tool-lifecycle.summary/tool-name looked-up)))
          (is (= 3 (:psi.tool-lifecycle.summary/event-count looked-up)))
          (is (= :tool-result (:psi.tool-lifecycle.summary/last-event-kind looked-up)))
          (is (true? (:psi.tool-lifecycle.summary/completed? looked-up)))
          (is (= "ok" (:psi.tool-lifecycle.summary/result-text looked-up)))
          (is (= "{}" (:psi.tool-lifecycle.summary/arguments looked-up)))
          (is (= {} (:psi.tool-lifecycle.summary/parsed-args looked-up))))))))

(deftest provider-capture-eql-introspection-test
  (testing "query-in resolves provider request/reply captures"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 25)
          t2  (.plusMillis t0 50)]
      (test-support/update-state! ctx :provider-requests
                                  conj
                                  {:provider :openai
                                   :api :openai-codex-responses
                                   :url "https://chatgpt.com/backend-api/codex/responses"
                                   :turn-id "turn-123"
                                   :timestamp t0
                                   :request {:headers {"Authorization" "Bearer ***REDACTED*** (len=99)"}
                                             :body {:model "gpt-5.3-codex" :tool_choice "auto"}}})
      (test-support/update-state! ctx :provider-replies
                                  into
                                  [{:provider :openai
                                    :api :openai-codex-responses
                                    :url "https://chatgpt.com/backend-api/codex/responses"
                                    :turn-id "turn-123"
                                    :timestamp t1
                                    :event {:type "response.completed"
                                            :response {:status "completed"}}}
                                   {:provider :anthropic
                                    :api :anthropic-messages
                                    :url "https://api.anthropic.com/v1/messages"
                                    :turn-id "turn-ant-1"
                                    :timestamp t2
                                    :event {:type :error
                                            :error-message "Error (status 400) [request-id req_ant_1]"
                                            :http-status 400}}])
      (test-support/update-state! ctx :provider-error-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-1"
                                   :timestamp t2
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_1]"
                                           :http-status 400}})

      (let [r (session/query-in ctx session-id
                                [:psi.agent-session/provider-request-count
                                 :psi.agent-session/provider-reply-count
                                 {:psi.agent-session/provider-last-request
                                  [:psi.provider-request/provider
                                   :psi.provider-request/api
                                   :psi.provider-request/turn-id
                                   :psi.provider-request/body]}
                                 {:psi.agent-session/provider-last-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-last-error-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-error-replies
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}])]
        (is (= 1 (:psi.agent-session/provider-request-count r)))
        (is (= 2 (:psi.agent-session/provider-reply-count r)))

        (is (= :openai
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/provider])))
        (is (= :openai-codex-responses
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/api])))
        (is (= "turn-123"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/turn-id])))
        (is (= "gpt-5.3-codex"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/body
                          :model])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= 1 (count (:psi.agent-session/provider-error-replies r))))
        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-error-replies 0
                          :psi.provider-reply/provider])))))

    (testing "provider capture lookup by turn-id resolves exact request and reply"
      (let [[ctx session-id] (create-session-context)
            t0  (java.time.Instant/now)
            t1  (.plusMillis t0 25)]
        (test-support/update-state! ctx :provider-requests
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t0
                                     :request {:headers {"x-test" "1"}
                                               :body {:model "claude-sonnet-4-6"
                                                      :messages [{:role "user" :content [{:type "text" :text "hi"}]}]}}})
        (test-support/update-state! ctx :provider-replies
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t1
                                     :event {:type :error
                                             :error-message "Error (status 400) [request-id req_lookup]"
                                             :http-status 400}})

        (let [req ((resolve 'psi.agent-session.resolvers.telemetry/provider-request-by-turn-id)
                   {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                    :psi/agent-session-ctx ctx
                    :psi.agent-session/session-id session-id})
              reply ((resolve 'psi.agent-session.resolvers.telemetry/provider-reply-by-turn-id)
                     {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                      :psi/agent-session-ctx ctx
                      :psi.agent-session/session-id session-id})]
          (is (= :anthropic
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/provider])))
          (is (= :anthropic-messages
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/api])))
          (is (= "claude-sonnet-4-6"
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/body
                              :model])))
          (is (= :error
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :type])))
          (is (= 400
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :http-status]))))))))

(deftest current-request-shape-test
  (testing "current shape reflects all messages"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hello")
                                        (make-assistant-msg "world")])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/request-shape
                                  [:psi.request-shape/message-count
                                   :psi.request-shape/estimated-tokens
                                   :psi.request-shape/context-window
                                   :psi.request-shape/alternation-valid?]}])
            shape (:psi.agent-session/request-shape r)]
        (is (= 2 (:psi.request-shape/message-count shape)))
        (is (pos? (:psi.request-shape/estimated-tokens shape)))
        (is (= 200000 (:psi.request-shape/context-window shape)))
        (is (true? (:psi.request-shape/alternation-valid? shape))))))

  (testing "headroom decreases as context grows"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "a")])
      (let [r1 (session/query-in ctx session-id
                                 [{:psi.agent-session/request-shape
                                   [:psi.request-shape/headroom-tokens]}])
            h1 (-> r1 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
        ;; Add more messages
        (inject-messages! ctx session-id [(make-assistant-msg (apply str (repeat 1000 "x")))
                                          (make-user-msg "b")])
        (let [r2 (session/query-in ctx session-id
                                   [{:psi.agent-session/request-shape
                                     [:psi.request-shape/headroom-tokens]}])
              h2 (-> r2 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
          (is (< h2 h1)))))))
