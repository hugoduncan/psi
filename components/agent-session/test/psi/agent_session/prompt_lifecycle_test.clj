(ns psi.agent-session.prompt-lifecycle-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.prompt-chain]
   [psi.agent-session.prompt-request]
   [psi.turn-runtime.core]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.turn]
   [psi.state-kernel.dispatch :as kernel]
   [psi.session-persistence.core]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [clojure.java.io :as io]
   [psi.ai.providers.anthropic]
   [psi.ai.providers.openai]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- journal-messages
  [ctx session-id]
  (let [journal (ss/get-state-value-in ctx (ss/state-path :journal session-id))]
    (->> journal
         (filter #(= :message (:kind %)))
         (mapv #(get-in % [:data :message])))))

(defn- delete-tree! [path]
  (when path
    (doseq [f (reverse (file-seq (io/file path)))]
      (.delete ^java.io.File f))))

(defmacro with-temp-dir
  [[sym prefix] & body]
  `(let [~sym (str (java.nio.file.Files/createTempDirectory ~prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
     ~sym
     (try
       ~@body
       (finally
         (delete-tree! ~sym)))))

(deftest submit-synthetic-user-prompt-enters-canonical-prompt-lifecycle-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "scheduled hello"}]
                  :timestamp (java.time.Instant/now)
                  :source :scheduled
                  :schedule-id "sch-test"
                  :label "wake"}]
    (kernel/clear-event-log!)
    (let [result (session/dispatch-in! ctx :session/submit-synthetic-user-prompt
                                       {:session-id session-id
                                        :user-msg user-msg}
                                       {:origin :core})
          effects (->> (kernel/handler-entry :session/submit-synthetic-user-prompt)
                       :fn
                       (#(% ctx {:session-id session-id :user-msg user-msg}))
                       :effects)]
      (is (map? result))
      (is (= true (:submitted? result)))
      (is (= user-msg (:user-msg result)))
      (is (= 3 (count effects)))
      (is (= [:session/prompt-submit :session/prompt :session/prompt-prepare-request]
             (mapv :event-type effects))))))

(deftest prompt-submit-handler-adds-tail-repair-effect-before-user-message-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg {:role "assistant"
                       :content [{:type :tool-call :id "tc-tail" :name "bash" :arguments "{}"}]
                       :stop-reason :tool_use
                       :timestamp #inst "2026-05-14T13:28:43.762-00:00"}
        user-msg {:role "user"
                  :content [{:type :text :text "status?"}]
                  :timestamp (java.time.Instant/now)}]
    (session/dispatch-in! ctx :session/append-journal-entry
                          {:session-id session-id
                           :entry (psi.session-persistence.core/message-entry assistant-msg)}
                          {:origin :core})
    (let [handler-result ((:fn (kernel/handler-entry :session/prompt-submit))
                          ctx
                          {:session-id session-id
                           :user-msg user-msg})
          effects (:effects handler-result)]
      (is (= 2 (count effects)))
      (is (= :runtime/dispatch-event (-> effects first :effect/type)))
      (is (= "toolResult" (get-in (first effects) [:event-data :entry :data :message :role])))
      (is (= "tc-tail" (get-in (first effects) [:event-data :entry :data :message :tool-call-id])))
      (is (= :runtime/dispatch-event (-> effects second :effect/type)))
      (is (= "user" (get-in (second effects) [:event-data :entry :data :message :role])))
      (is (= 1 (get-in handler-result [:return :repaired-tool-result-count]))))))

(deftest prompt-record-response-appends-assistant-once-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :text :text "done"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}]
    (kernel/clear-event-log!)
    (session/dispatch-in! ctx :session/prompt-record-response
                          {:session-id session-id
                           :execution-result execution-result}
                          {:origin :core})
    (let [msgs (journal-messages ctx session-id)]
      (is (= 1 (count msgs)))
      (is (= "assistant" (:role (first msgs))))
      (is (= "done" (get-in (first msgs) [:content 0 :text]))))))

(deftest prompt-record-response-routes-tool-use-to-continue-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :tool-call :id "tc-1" :name "read" :arguments "{}"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-tool"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/tool-use
                          :execution-result/tool-calls [{:id "tc-1" :name "read" :arguments "{}"}]
                          :execution-result/stop-reason :stop}]
    (kernel/clear-event-log!)
    (with-redefs [psi.agent-session.prompt-chain/run-prompt-tools! (fn [_ctx _sid _res _pq]
                                                                     {:continued? true :tool-call-count 1})
                  psi.agent-session.prompt-request/build-prepared-request (fn [_ctx sid {:keys [turn-id]}]
                                                                            {:prepared-request/id turn-id
                                                                             :prepared-request/session-id sid
                                                                             :prepared-request/system-prompt "sys"
                                                                             :prepared-request/messages []
                                                                             :prepared-request/tools []
                                                                             :prepared-request/session-snapshot {:cache-breakpoints #{}}
                                                                             :prepared-request/model {:provider "stub" :id "stub"}
                                                                             :prepared-request/ai-options {}
                                                                             :prepared-request/provider-conversation {:system-prompt "sys"
                                                                                                                      :messages []
                                                                                                                      :tools []}})
                  psi.turn-runtime.core/execute-prepared-request! (fn [_ai-ctx _ctx sid prepared _pq]
                                                                    {:execution-result/turn-id (:prepared-request/id prepared)
                                                                     :execution-result/session-id sid
                                                                     :execution-result/assistant-message {:role "assistant"
                                                                                                          :content [{:type :text :text "after tool"}]
                                                                                                          :stop-reason :stop
                                                                                                          :timestamp (java.time.Instant/now)}
                                                                     :execution-result/turn-outcome :turn.outcome/stop
                                                                     :execution-result/tool-calls []
                                                                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-record-response
                            {:session-id session-id
                             :execution-result execution-result}
                            {:origin :core})
      (let [entries (kernel/event-log-entries)]
        (is (some #(= :session/prompt-continue (:event-type %)) entries))
        (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
        (is (some #(= :session/prompt-record-response (:event-type %)) entries))
        (let [msgs (journal-messages ctx session-id)]
          (is (= 2 (count msgs)))
          (is (= "assistant" (:role (first msgs))))
          (is (= "assistant" (:role (second msgs)))))))))

(deftest prompt-in-end-to-end-updates-prompt-lifecycle-summaries-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (kernel/clear-event-log!)
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "hello back"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/prompt-in! ctx session-id "hello"))
    (let [result (session/query-in ctx session-id [:psi.agent-session/last-prepared-turn-id
                                                   :psi.agent-session/last-prepared-message-count
                                                   :psi.agent-session/last-execution-turn-id
                                                   :psi.agent-session/last-execution-turn-outcome
                                                   :psi.agent-session/last-execution-stop-reason])
          entries (kernel/event-log-entries)
          msgs    (journal-messages ctx session-id)]
      (is (string? (:psi.agent-session/last-prepared-turn-id result)))
      (is (number? (:psi.agent-session/last-prepared-message-count result)))
      (is (= (:psi.agent-session/last-prepared-turn-id result)
             (:psi.agent-session/last-execution-turn-id result)))
      (is (= :turn.outcome/stop (:psi.agent-session/last-execution-turn-outcome result)))
      (is (= :stop (:psi.agent-session/last-execution-stop-reason result)))
      (is (some #(= :session/prompt-submit (:event-type %)) entries))
      (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
      (is (some #(= :session/prompt-record-response (:event-type %)) entries))
      (is (some #(= :session/prompt-finish (:event-type %)) entries))
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (is (false? (:is-streaming (ss/get-session-data-in ctx session-id))))
      (is (= ["user" "assistant"] (mapv :role msgs))))))

(deftest prompt-in-runs-git-head-sync-after-turn-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        sync-calls       (atom [])]
    (kernel/clear-event-log!)
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [_ctx sid]
                    (swap! sync-calls conj sid)
                    {:ok? true})
                  psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "hello back"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/prompt-in! ctx session-id "hello")
      (is (= [session-id] @sync-calls)
          "prompt-in! should run git-head sync after a normal prompt turn"))))

(deftest stranded-streaming-session-recovers-on-next-prompt-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :core})
    (session/dispatch-in! ctx :on-streaming-entered {:session-id session-id} {:origin :statechart})
    (is (= :streaming (ss/sc-phase-in ctx session-id)))
    (is (true? (:is-streaming (ss/get-session-data-in ctx session-id))))
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "recovered"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/prompt-in! ctx session-id "hello after stall"))
    (is (= :idle (ss/sc-phase-in ctx session-id)))
    (is (false? (:is-streaming (ss/get-session-data-in ctx session-id))))
    (is (= ["user" "assistant"] (mapv :role (journal-messages ctx session-id))))))

(deftest queue-while-streaming-recovers-stranded-session-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :core})
    (session/dispatch-in! ctx :on-streaming-entered {:session-id session-id} {:origin :statechart})
    (let [result (session/queue-while-streaming-in! ctx session-id "nudge" :steer)]
      (is (= false (:accepted? result)))
      (is (= :not-streaming (:behavior result)))
      (is (true? (:recovered? result)))
      (is (= :idle (ss/sc-phase-in ctx session-id)))
      (is (= [] (:steering-messages (ss/get-session-data-in ctx session-id)))))))

(deftest abort-records-interrupted-tool-results-with-reason-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        agent-ctx        (ss/agent-ctx-in ctx session-id)
        appended*        (atom [])]
    (swap! (:data-atom agent-ctx) assoc :pending-tool-calls #{"tc-abort"})
    (with-redefs [psi.agent-session.dispatch/dispatch!
                  (let [orig psi.agent-session.dispatch/dispatch!]
                    (fn [ctx event-type event-data opts]
                      (when (= :session/tool-agent-record-result event-type)
                        (swap! appended* conj (:tool-result-msg event-data)))
                      (orig ctx event-type event-data opts)))]
      (session/abort-in! ctx session-id))
    (is (= 1 (count @appended*)))
    (is (= "tc-abort" (:tool-call-id (first @appended*))))
    (is (true? (:is-error (first @appended*))))
    (is (= :user-abort (get-in (first @appended*) [:details :interruption :reason])))
    (is (str/includes? (get-in (first @appended*) [:content 0 :text]) "Reason: user-abort."))))

(deftest deferred-interrupt-records-interrupted-tool-results-with-reason-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        agent-ctx        (ss/agent-ctx-in ctx session-id)
        appended*        (atom [])]
    (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :core})
    (session/dispatch-in! ctx :on-streaming-entered {:session-id session-id} {:origin :statechart})
    (swap! (:data-atom agent-ctx) assoc :pending-tool-calls #{"tc-interrupt"})
    (session/request-interrupt-in! ctx session-id)
    (with-redefs [psi.agent-session.dispatch/dispatch!
                  (let [orig psi.agent-session.dispatch/dispatch!]
                    (fn [ctx event-type event-data opts]
                      (when (= :session/tool-agent-record-result event-type)
                        (swap! appended* conj (:tool-result-msg event-data)))
                      (orig ctx event-type event-data opts)))]
      (session/dispatch-in! ctx :on-agent-done {:session-id session-id} {:origin :statechart}))
    (is (= 1 (count @appended*)))
    (is (= "tc-interrupt" (:tool-call-id (first @appended*))))
    (is (true? (:is-error (first @appended*))))
    (is (= :deferred-interrupt (get-in (first @appended*) [:details :interruption :reason])))
    (is (str/includes? (get-in (first @appended*) [:content 0 :text]) "Reason: deferred-interrupt."))))

(deftest abort-cancels-active-prompt-runtime-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        started (promise)
        release (promise)
        progress-q (java.util.concurrent.LinkedBlockingQueue.)]
    (with-redefs [psi.turn-runtime.core/do-stream!
                  (fn [_ai-ctx _conv _model _opts consume-fn]
                    {:future
                     (future
                       (consume-fn {:type :start})
                       (deliver started true)
                       @release
                       (consume-fn {:type :text-delta :content-index 0 :delta "late"})
                       (consume-fn {:type :done :reason :stop}))})]
      (let [runner (future
                     (session/prompt-in! ctx session-id "hello" nil {:progress-queue progress-q}))]
        (is (= true (deref started 1000 ::timeout)))
        (testing "session enters streaming before abort"
          (is (= :streaming (ss/sc-phase-in ctx session-id))))
        (session/abort-in! ctx session-id)
        (deliver release true)
        (let [result (deref runner 1000 ::timeout)
              assistant (session/last-assistant-message-in ctx session-id)
              entries (kernel/event-log-entries)
              execution-summary (get-in (ss/get-session-data-in ctx session-id)
                                        [:last-execution-result-summary :stop-reason])]
          (is (not= ::timeout result))
          (is (= :idle (ss/sc-phase-in ctx session-id)))
          (is (false? (:is-streaming (ss/get-session-data-in ctx session-id))))
          ;; prompt-prepare-request currently returns the prepared-request scaffold,
          ;; while execution proceeds via the effect path.
          (is (map? result))
          (is (contains? result :prepared-request))
          (is (= :aborted execution-summary))
          (is (= :aborted (:stop-reason assistant)))
          (is (= "Aborted" (:error-message assistant)))
          (is (some #(= :session/abort (:event-type %)) entries))
          (is (some #(= :session/prompt-record-response (:event-type %)) entries)))))))

(deftest build-prepared-request-surfaces-developer-and-contribution-layers-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/bootstrap-prompt-state
                          {:session-id session-id
                           :system-prompt "sys"
                           :developer-prompt "dev"
                           :developer-prompt-source :explicit}
                          {:origin :core})
    (session/dispatch-in! ctx :session/register-prompt-contribution
                          {:session-id session-id
                           :ext-path "/ext/a"
                           :id "c1"
                           :contribution {:content "Hint A" :priority 10 :enabled true}}
                          {:origin :core})
    (let [prepared (psi.agent-session.prompt-request/build-prepared-request
                    ctx session-id {:turn-id "t1"
                                    :user-message {:role "user"
                                                   :content [{:type :text :text "hello"}]}})
          layers   (:prepared-request/prompt-layers prepared)
          kinds    (mapv :id layers)]
      (is (= [:system/base :system/developer :system/contributions] kinds))
      (is (= "sys" (get-in prepared [:prepared-request/prompt-layers 0 :content])))
      (is (= "dev" (get-in prepared [:prepared-request/prompt-layers 1 :content])))
      (is (= :explicit (get-in prepared [:prepared-request/prompt-layers 1 :source])))
      (is (= "Hint A" (get-in prepared [:prepared-request/prompt-layers 2 :content])))
      (is (= "sys\n\ndev\n\n# Extension Prompt Contributions\n\n<prompt_contribution id=\"c1\" ext_path=\"/ext/a\">\nHint A\n</prompt_contribution>"
             (:prepared-request/system-prompt prepared)))
      (is (= "dev" (get-in prepared [:prepared-request/session-snapshot :developer-prompt])))
      (is (= :explicit (get-in prepared [:prepared-request/session-snapshot :developer-prompt-source]))))))

(deftest build-prepared-request-reassembles-effective-system-prompt-from-base-and-contributions-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/bootstrap-prompt-state
                          {:session-id session-id
                           :system-prompt "base"}
                          {:origin :core})
    (session/dispatch-in! ctx :session/register-prompt-contribution
                          {:session-id session-id
                           :ext-path "/ext/a"
                           :id "c2"
                           :contribution {:content "Hint B" :priority 20 :enabled true}}
                          {:origin :core})
    ;; Simulate stale cached :system-prompt state: request preparation should
    ;; rebuild from canonical base prompt + contribution layers instead.
    (test-support/update-state! ctx :session-data assoc :system-prompt "stale")
    (let [prepared (psi.agent-session.prompt-request/build-prepared-request
                    ctx session-id {:turn-id "t2"
                                    :user-message {:role "user"
                                                   :content [{:type :text :text "hello"}]}})]
      (is (= "base\n\n# Extension Prompt Contributions\n\n<prompt_contribution id=\"c2\" ext_path=\"/ext/a\">\nHint B\n</prompt_contribution>"
             (:prepared-request/system-prompt prepared)))
      (is (= (:prepared-request/system-prompt prepared)
             (get-in prepared [:prepared-request/provider-conversation :system-prompt]))))))

(deftest build-prepared-request-allows-explicit-runtime-model-override-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        runtime-model    {:provider "stub" :id "override-model" :context-window 1234}
        prepared         (psi.agent-session.prompt-request/build-prepared-request
                          ctx session-id {:turn-id "t-override"
                                          :user-message {:role "user"
                                                         :content [{:type :text :text "hello"}]}
                                          :runtime-model runtime-model})]
    (is (= runtime-model (:prepared-request/model prepared)))))

(deftest build-prepared-request-expands-skill-invocation-into-user-message-test
  (let [dir (str (java.nio.file.Files/createTempDirectory "psi-skill-expand"
                                                          (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [skill-file (str dir "/demo/SKILL.md")
            _          (.mkdirs (java.io.File. (str dir "/demo")))
            _          (spit skill-file "---\nname: demo\ndescription: Demo skill\n---\n# Skill Body\nUse this skill carefully.")
            skill      {:name "demo"
                        :description "Demo skill"
                        :file-path skill-file
                        :base-dir (str dir "/demo")
                        :source :path
                        :disable-model-invocation false}
            [ctx session-id] (create-session-context {:persist? false
                                                      :session-defaults {:skills [skill]}})
            prepared   (psi.agent-session.prompt-request/build-prepared-request
                        ctx session-id {:turn-id "t-skill"
                                        :commands []
                                        :user-message {:role "user"
                                                       :content [{:type :text :text "/skill:demo apply this"}]}})]
        (is (= :skill (get-in prepared [:prepared-request/input-expansion :kind])))
        (is (= "demo" (get-in prepared [:prepared-request/input-expansion :name])))
        (is (str/includes? (get-in prepared [:prepared-request/user-message :content 0 :text]) "<skill name=\"demo\""))
        (is (str/includes? (get-in prepared [:prepared-request/user-message :content 0 :text]) "# Skill Body"))
        (is (str/includes? (get-in prepared [:prepared-request/user-message :content 0 :text]) "apply this")))
      (finally
        (delete-tree! dir)))))

(deftest build-prepared-request-expands-template-invocation-into-user-message-test
  (let [[ctx session-id] (create-session-context
                          {:persist? false
                           :session-defaults {:prompt-templates [{:name "summarize"
                                                                  :description "Summarize text"
                                                                  :content "Summarize: $@"
                                                                  :source :path
                                                                  :file-path "/tmp/summarize.md"}]}})
        prepared (psi.agent-session.prompt-request/build-prepared-request
                  ctx session-id {:turn-id "t-template"
                                  :commands []
                                  :user-message {:role "user"
                                                 :content [{:type :text :text "/summarize hello world"}]}})]
    (is (= :template (get-in prepared [:prepared-request/input-expansion :kind])))
    (is (= "summarize" (get-in prepared [:prepared-request/input-expansion :name])))
    (is (= "Summarize: hello world"
           (get-in prepared [:prepared-request/user-message :content 0 :text])))))

(deftest queued-steering-is-injected-into-continuation-prepared-request-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg        {:role "user"
                         :content [{:type :text :text "hi"}]
                         :timestamp (java.time.Instant/now)}
        assistant-msg   {:role "assistant"
                         :content [{:type :tool-call :id "tc-1" :name "read" :arguments "{}"}]
                         :stop-reason :stop
                         :timestamp (java.time.Instant/now)}
        tool-result-msg {:role "toolResult"
                         :tool-call-id "tc-1"
                         :tool-name "read"
                         :content [{:type :text :text "file body"}]
                         :timestamp (java.time.Instant/now)}]
    (session/dispatch-in! ctx :session/bootstrap-prompt-state
                          {:session-id session-id
                           :system-prompt "sys"}
                          {:origin :core})
    (doseq [message [user-msg assistant-msg tool-result-msg]]
      (session/dispatch-in! ctx :session/append-journal-entry
                            {:session-id session-id
                             :entry (psi.session-persistence.core/message-entry message)}
                            {:origin :core}))
    (session/dispatch-in! ctx :session/enqueue-steering-message
                          {:session-id session-id
                           :text "Please be brief."}
                          {:origin :core})
    (let [prepared            (psi.agent-session.prompt-request/build-prepared-request
                               ctx session-id {:turn-id "turn-2"
                                               :user-message nil})
          provider-conv       (:prepared-request/provider-conversation prepared)
          openai-messages     (#'psi.ai.providers.openai/transform-messages provider-conv)
          anthropic-messages  (#'psi.ai.providers.anthropic/transform-messages provider-conv)]
      (is (= [{:role "user" :content "Please be brief."}]
             (take-last 1 openai-messages)))
      (is (= {:role "assistant"
              :tool_calls [{:id "tc-1"
                            :type "function"
                            :function {:name "read"
                                       :arguments "{}"}}]}
             (second openai-messages)))
      (is (= {:role "tool"
              :tool_call_id "tc-1"
              :content "file body"}
             (nth openai-messages 2)))
      (is (= [{:role "user"
               :content [{:type "text" :text "Please be brief." :cache_control {:type "ephemeral"}}]}]
             (take-last 1 anthropic-messages)))
      (is (= {:role "assistant"
              :content [{:type "tool_use"
                         :id "tc-1"
                         :name "read"
                         :input {}}]}
             (second anthropic-messages)))
      (is (= {:role "user"
              :content [{:type "tool_result"
                         :tool_use_id "tc-1"
                         :content "file body"}]}
             (nth anthropic-messages 2))))))

(deftest prompt-execution-result-returns-terminal-result-after-tool-continuation-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        tool-call-result {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message {:role "assistant"
                                                               :content [{:type :tool-call
                                                                          :id "tc-1"
                                                                          :name "read"
                                                                          :arguments "{}"}]
                                                               :stop-reason :stop
                                                               :timestamp (java.time.Instant/now)}
                          :execution-result/turn-outcome :turn.outcome/tool-use
                          :execution-result/tool-calls [{:id "tc-1" :name "read" :arguments "{}"}]
                          :execution-result/stop-reason :stop}
        terminal-result  {:execution-result/turn-id "turn-2"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message {:role "assistant"
                                                               :content [{:type :text :text "final answer"}]
                                                               :stop-reason :stop
                                                               :timestamp (java.time.Instant/now)}
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}
        execution-count* (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _progress-queue]
                    (swap! execution-count* inc)
                    (is (= session-id sid))
                    (if (= 1 @execution-count*)
                      (do
                        (is (= "hello"
                               (get-in prepared [:prepared-request/user-message :content 0 :text])))
                        tool-call-result)
                      (do
                        (is (= 2 @execution-count*))
                        (is (nil? (:prepared-request/user-message prepared)))
                        terminal-result)))
                  psi.agent-session.prompt-chain/run-prompt-tools!
                  (fn [ctx sid _execution-result _progress-queue]
                    (session/dispatch-in! ctx :session/tool-record-result
                                          {:session-id sid
                                           :shaped-result {:result-message {:role "toolResult"
                                                                            :tool-call-id "tc-1"
                                                                            :tool-name "read"
                                                                            :content [{:type :text :text "file body"}]
                                                                            :timestamp (java.time.Instant/now)}}}
                                          {:origin :core})
                    {:continued? true :tool-call-count 1})]
      (let [result (psi.agent-session.turn/prompt-execution-result-in! ctx session-id "hello")
            assistant-msg (session/last-assistant-message-in ctx session-id)]
        (is (= 2 @execution-count*))
        (is (= :turn.outcome/stop (:execution-result/turn-outcome result)))
        (is (= "final answer"
               (get-in result [:execution-result/assistant-message :content 0 :text])))
        (is (= "final answer"
               (get-in assistant-msg [:content 0 :text])))))))

(deftest prompt-prepare-request-consumes-queued-steering-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/enqueue-steering-message
                          {:session-id session-id
                           :text "Please be brief."}
                          {:origin :core})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-prepare-request
                            {:session-id session-id
                             :turn-id "turn-steer"
                             :user-msg nil}
                            {:origin :core}))
    (is (= [] (:steering-messages (ss/get-session-data-in ctx session-id))))))

(deftest prompt-finish-dispatches-extension-turn-finished-event-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        seen             (atom [])
        assistant-msg    {:role "assistant"
                          :content [{:type :text :text "done"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        terminal-result  {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}]
    (kernel/clear-event-log!)
    (let [reg (:extension-registry ctx)]
      (psi.agent-session.extensions/register-extension-in! reg "/ext/auto-session-name")
      (psi.agent-session.extensions/register-handler-in! reg "/ext/auto-session-name" "session_turn_finished"
                                                         (fn [event]
                                                           (swap! seen conj event)
                                                           nil)))
    (session/dispatch-in! ctx :session/prompt-finish
                          {:session-id session-id
                           :turn-id "turn-1"
                           :terminal-result terminal-result}
                          {:origin :core})
    (is (= [{:session-id session-id
             :turn-id "turn-1"
             :assistant-message assistant-msg}]
           @seen))))

(deftest prompt-finish-triggers-follow-up-next-run-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :text :text "done"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        terminal-result  {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}]
    (session/dispatch-in! ctx :session/enqueue-follow-up-message
                          {:session-id session-id
                           :text "next question"}
                          {:origin :core})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    (is (= "next question"
                           (get-in prepared [:prepared-request/user-message :content 0 :text])))
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "followed up"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-finish
                            {:session-id session-id
                             :turn-id "turn-1"
                             :terminal-result terminal-result}
                            {:origin :core}))
    (let [msgs (journal-messages ctx session-id)]
      (is (= ["user" "assistant"] (mapv :role msgs)))
      (is (= "next question" (get-in (first msgs) [:content 0 :text])))
      (is (= [] (:follow-up-messages (ss/get-session-data-in ctx session-id)))))))

(deftest prompt-finish-chains-follow-ups-in-one-at-a-time-batches-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        assistant-msg    {:role "assistant"
                          :content [{:type :text :text "done"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        terminal-result  {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}
        seen-prompts     (atom [])]
    (session/dispatch-in! ctx :session/enqueue-follow-up-message
                          {:session-id session-id :text "q1"}
                          {:origin :core})
    (session/dispatch-in! ctx :session/enqueue-follow-up-message
                          {:session-id session-id :text "q2"}
                          {:origin :core})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    (swap! seen-prompts conj (get-in prepared [:prepared-request/user-message :content 0 :text]))
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-finish
                            {:session-id session-id
                             :turn-id "turn-1"
                             :terminal-result terminal-result}
                            {:origin :core}))
    (is (= ["q1" "q2"] @seen-prompts))
    (is (= [] (:follow-up-messages (ss/get-session-data-in ctx session-id))))))

(deftest prompt-execution-result-retryable-error-enters-retrying-and-schedules-retry-test
  (testing "canonical prompt lifecycle should schedule retry/backoff for retryable provider errors"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :provider-retry-sleep? false})
          reg             (:extension-registry ctx)
          seen            (atom [])
          attempts        (atom 0)]
      (kernel/clear-event-log!)
      (ext/register-extension-in! reg "/ext/provider-telemetry")
      (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_retry_scheduled" #(swap! seen conj %))
      (with-redefs [psi.turn-runtime.core/execute-live-turn!
                    (fn [_ai-ctx _ctx _sid {:keys [turn-id ai-model]}]
                      (let [attempt (swap! attempts inc)]
                        {:turn-id turn-id
                         :model ai-model
                         :ai-options {}
                         :turn-ctx nil
                         :assistant-message (if (= 1 attempt)
                                              {:role "assistant"
                                               :content [{:type :error :text "Connection reset by peer"}]
                                               :stop-reason :error
                                               :error-message "Connection reset by peer"
                                               :timestamp (java.time.Instant/now)}
                                              {:role "assistant"
                                               :content [{:type :text :text "recovered"}]
                                               :stop-reason :stop
                                               :timestamp (java.time.Instant/now)})}))]
        (let [result (psi.agent-session.turn/prompt-execution-result-in! ctx session-id "trigger transient connection error")]
          (is (= :stop (:execution-result/stop-reason result)))))
      (is (= ["provider_retry_scheduled"] (mapv :type @seen)))
      (is (= 2 @attempts))
      (is (empty? (filter (fn [entry]
                            (some #(= :runtime/agent-start-loop (:effect/type %))
                                  (concat (:declared-effects entry)
                                          (:applied-effects entry))))
                          (kernel/event-log-entries)))))))

(deftest prompt-lifecycle-terminal-provider-error-emits-one-finished-telemetry-event-test
  ;; Terminal provider failures are already finalized at the provider boundary;
  ;; prompt finish must not duplicate the compatibility terminal telemetry event.
  (let [[ctx session-id] (create-session-context {:persist? false})
        reg             (:extension-registry ctx)
        seen            (atom [])]
    (ext/register-extension-in! reg "/ext/provider-telemetry")
    (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_finished" #(swap! seen conj %))
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [_ai-ctx _ctx _sid {:keys [turn-id ai-model]}]
                    {:turn-id turn-id
                     :model ai-model
                     :ai-options {}
                     :turn-ctx nil
                     :assistant-message {:role "assistant"
                                         :content [{:type :error :text "bad request"}]
                                         :stop-reason :error
                                         :error-message "bad request"
                                         :http-status 400
                                         :timestamp (java.time.Instant/now)}})]
      (let [result (psi.agent-session.turn/prompt-execution-result-in! ctx session-id "trigger terminal provider error")]
        (is (= :error (:execution-result/stop-reason result)))
        (is (= :non-retryable (get-in result [:execution-result/retry-outcome :failure-reason])))))
    (is (= 1 (count @seen)))
    (is (= ["provider_request_finished"] (mapv :type @seen)))
    (is (= [:failed] (mapv :status @seen)))
    (is (= [true] (mapv :final? @seen)))
    (is (= [:non-retryable] (mapv :failure-reason @seen)))))

(deftest prompt-provider-retry-after-tool-result-does-not-rerun-tool-test
  ;; Provider-boundary retry for a request containing recorded tool results
  ;; retries only that prepared provider request; it does not execute tools again.
  (let [[ctx session-id] (create-session-context {:persist? false
                                                  :provider-retry-sleep? false})
        provider-attempts* (atom 0)
        tool-runs*         (atom 0)]
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [_ai-ctx _ctx _sid {:keys [turn-id ai-model]}]
                    (let [attempt (swap! provider-attempts* inc)]
                      {:turn-id turn-id
                       :model ai-model
                       :ai-options {}
                       :turn-ctx nil
                       :assistant-message (case attempt
                                            1 {:role "assistant"
                                               :content [{:type :tool-call
                                                          :id "tc-1"
                                                          :name "read"
                                                          :arguments "{}"}]
                                               :stop-reason :stop
                                               :timestamp (java.time.Instant/now)}
                                            2 {:role "assistant"
                                               :content [{:type :error :text "Connection reset by peer"}]
                                               :stop-reason :error
                                               :error-message "Connection reset by peer"
                                               :timestamp (java.time.Instant/now)}
                                            {:role "assistant"
                                             :content [{:type :text :text "final answer"}]
                                             :stop-reason :stop
                                             :timestamp (java.time.Instant/now)})}))
                  psi.agent-session.prompt-chain/run-prompt-tools!
                  (fn [ctx sid _execution-result _progress-queue]
                    (swap! tool-runs* inc)
                    (session/dispatch-in! ctx :session/tool-record-result
                                          {:session-id sid
                                           :shaped-result {:result-message {:role "toolResult"
                                                                            :tool-call-id "tc-1"
                                                                            :tool-name "read"
                                                                            :content [{:type :text :text "file body"}]
                                                                            :timestamp (java.time.Instant/now)}}}
                                          {:origin :core})
                    {:continued? true :tool-call-count 1})]
      (let [result (psi.agent-session.turn/prompt-execution-result-in! ctx session-id "read a file")]
        (is (= :stop (:execution-result/stop-reason result)))
        (is (= "final answer"
               (get-in result [:execution-result/assistant-message :content 0 :text])))))
    (is (= 3 @provider-attempts*))
    (is (= 1 @tool-runs*))
    (is (= ["provider_request_started" "provider_request_finished"
            "provider_request_started" "provider_request_finished"
            "provider_retry_scheduled" "provider_request_started"
            "provider_request_finished"]
           (mapv :type (get-in @(:state* ctx)
                               [:agent-session :sessions session-id :telemetry :provider-events]))))))
