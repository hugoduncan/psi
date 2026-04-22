(ns psi.agent-session.prompt-lifecycle-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions]
   [psi.agent-session.prompt-chain]
   [psi.agent-session.prompt-request]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.session-state :as ss]
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
    (dispatch/clear-event-log!)
    (let [result (dispatch/dispatch! ctx :session/submit-synthetic-user-prompt
                                     {:session-id session-id
                                      :user-msg user-msg}
                                     {:origin :core})
          effects (->> (dispatch/handler-entry :session/submit-synthetic-user-prompt)
                       :fn
                       (#(% ctx {:session-id session-id :user-msg user-msg}))
                       :effects)]
      (is (map? result))
      (is (= true (:submitted? result)))
      (is (= user-msg (:user-msg result)))
      (is (= 3 (count effects)))
      (is (= [:session/prompt-submit :session/prompt :session/prompt-prepare-request]
             (mapv :event-type effects))))))

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
    (dispatch/clear-event-log!)
    (dispatch/dispatch! ctx :session/prompt-record-response
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
    (dispatch/clear-event-log!)
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
                  psi.agent-session.prompt-runtime/execute-prepared-request! (fn [_ai-ctx _ctx sid prepared _pq]
                                                                               {:execution-result/turn-id (:prepared-request/id prepared)
                                                                                :execution-result/session-id sid
                                                                                :execution-result/assistant-message {:role "assistant"
                                                                                                                     :content [{:type :text :text "after tool"}]
                                                                                                                     :stop-reason :stop
                                                                                                                     :timestamp (java.time.Instant/now)}
                                                                                :execution-result/turn-outcome :turn.outcome/stop
                                                                                :execution-result/tool-calls []
                                                                                :execution-result/stop-reason :stop})]
      (dispatch/dispatch! ctx :session/prompt-record-response
                          {:session-id session-id
                           :execution-result execution-result}
                          {:origin :core})
      (let [entries (dispatch/event-log-entries)]
        (is (some #(= :session/prompt-continue (:event-type %)) entries))
        (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
        (is (some #(= :session/prompt-record-response (:event-type %)) entries))
        (let [msgs (journal-messages ctx session-id)]
          (is (= 2 (count msgs)))
          (is (= "assistant" (:role (first msgs))))
          (is (= "assistant" (:role (second msgs)))))))))

(deftest prompt-in-end-to-end-updates-prompt-lifecycle-summaries-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (dispatch/clear-event-log!)
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
          entries (dispatch/event-log-entries)
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
    (dispatch/clear-event-log!)
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [_ctx sid]
                    (swap! sync-calls conj sid)
                    {:ok? true})
                  psi.agent-session.prompt-runtime/execute-prepared-request!
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

(deftest abort-cancels-active-prompt-runtime-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        started (promise)
        release (promise)
        progress-q (java.util.concurrent.LinkedBlockingQueue.)]
    (with-redefs [psi.agent-session.prompt-runtime/do-stream!
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
              entries (dispatch/event-log-entries)
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
    (dispatch/dispatch! ctx :session/bootstrap-prompt-state
                        {:session-id session-id
                         :system-prompt "sys"
                         :developer-prompt "dev"
                         :developer-prompt-source :explicit}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/register-prompt-contribution
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
    (dispatch/dispatch! ctx :session/bootstrap-prompt-state
                        {:session-id session-id
                         :system-prompt "base"}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/register-prompt-contribution
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
        assistant-msg    {:role "assistant"
                          :content [{:type :tool-call :id "tc-1" :name "read" :arguments "{}"}]
                          :stop-reason :stop
                          :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-1"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/tool-use
                          :execution-result/tool-calls [{:id "tc-1" :name "read" :arguments "{}"}]
                          :execution-result/stop-reason :stop}
        tool-result-msg  {:role "toolResult"
                          :tool-call-id "tc-1"
                          :tool-name "read"
                          :content [{:type :text :text "file body"}]
                          :timestamp (java.time.Instant/now)}]
    (dispatch/dispatch! ctx :session/bootstrap-prompt-state
                        {:session-id session-id
                         :system-prompt "sys"}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/prompt-submit
                        {:session-id session-id
                         :user-msg {:role "user"
                                    :content [{:type :text :text "hi"}]
                                    :timestamp (java.time.Instant/now)}}
                        {:origin :core})
    (with-redefs [psi.agent-session.prompt-chain/run-prompt-tools! (fn [_ctx _sid _res _pq]
                                                                     {:continued? true :tool-call-count 1})]
      (dispatch/dispatch! ctx :session/prompt-record-response
                          {:session-id session-id
                           :execution-result execution-result}
                          {:origin :core}))
    (dispatch/dispatch! ctx :session/tool-record-result
                        {:session-id session-id
                         :shaped-result {:result-message tool-result-msg}}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/enqueue-steering-message
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

(deftest prompt-prepare-request-consumes-queued-steering-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (dispatch/dispatch! ctx :session/enqueue-steering-message
                        {:session-id session-id
                         :text "Please be brief."}
                        {:origin :core})
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
      (dispatch/dispatch! ctx :session/prompt-prepare-request
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
    (dispatch/clear-event-log!)
    (let [reg (:extension-registry ctx)]
      (psi.agent-session.extensions/register-extension-in! reg "/ext/auto-session-name")
      (psi.agent-session.extensions/register-handler-in! reg "/ext/auto-session-name" "session_turn_finished"
                                                         (fn [event]
                                                           (swap! seen conj event)
                                                           nil)))
    (dispatch/dispatch! ctx :session/prompt-finish
                        {:session-id session-id
                         :turn-id "turn-1"
                         :terminal-result terminal-result}
                        {:origin :core})
    (is (= [{:session-id session-id
             :turn-id "turn-1"}]
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
    (dispatch/dispatch! ctx :session/enqueue-follow-up-message
                        {:session-id session-id
                         :text "next question"}
                        {:origin :core})
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
      (dispatch/dispatch! ctx :session/prompt-finish
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
    (dispatch/dispatch! ctx :session/enqueue-follow-up-message
                        {:session-id session-id :text "q1"}
                        {:origin :core})
    (dispatch/dispatch! ctx :session/enqueue-follow-up-message
                        {:session-id session-id :text "q2"}
                        {:origin :core})
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
      (dispatch/dispatch! ctx :session/prompt-finish
                          {:session-id session-id
                           :turn-id "turn-1"
                           :terminal-result terminal-result}
                          {:origin :core}))
    (is (= ["q1" "q2"] @seen-prompts))
    (is (= [] (:follow-up-messages (ss/get-session-data-in ctx session-id))))))
