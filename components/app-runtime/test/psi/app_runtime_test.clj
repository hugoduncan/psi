(ns psi.app-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.transcript]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.introspection.core :as introspection]
   [psi.memory.runtime :as memory-runtime]
   #_[psi.tui.app :as tui-app]))

(deftest select-login-provider-test
  (let [providers [{:id :anthropic :name "Anthropic"}
                   {:id :openai :name "OpenAI"}]]

    (testing "defaults to active model provider when no explicit provider arg"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai nil)]
        (is (nil? error))
        (is (= :openai (:id provider)))))

    (testing "explicit provider arg overrides active provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "anthropic")]
        (is (nil? error))
        (is (= :anthropic (:id provider)))))

    (testing "returns clear error for unknown explicit provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "not-a-provider")]
        (is (nil? provider))
        (is (str/includes? error "Unknown OAuth provider"))
        (is (str/includes? error "anthropic"))
        (is (str/includes? error "openai"))))))

(deftest select-login-provider-missing-active-provider-test
  (testing "does not silently fall back to another provider"
    (let [providers [{:id :anthropic :name "Anthropic"}]
          {:keys [provider error]}
          (commands/select-login-provider providers :openai nil)]
      (is (nil? provider))
      (is (str/includes? error "not available for model provider openai"))
      (is (str/includes? error "OPENAI_API_KEY"))
      (is (str/includes? error "anthropic")))))

(defn- with-main-bootstrap-stubs
  [f]
  (with-redefs [psi.app-runtime/resolve-model
                (fn [_]
                  {:provider           :anthropic
                   :id                 "test-model"
                   :name               "Test Model"
                   :supports-reasoning false
                   :context-window     200000})
                oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                sys-prompt/build-system-prompt (fn [_] "")
                ext/discover-extension-paths (fn [& _] [])
                bootstrap/bootstrap-in!
                (fn [_ctx _session-id _]
                  {:extension-errors [] :extension-loaded-count 0})]
    (f)))

(deftest create-runtime-session-context-creates-single-initial-session-test
  (with-main-bootstrap-stubs
    (fn []
      (let [{:keys [ctx session-id]} (app-runtime/create-runtime-session-context
                                      {:provider           :anthropic
                                       :id                 "test-model"
                                       :name               "Test Model"
                                       :supports-reasoning false
                                       :context-window     200000}
                                      {:ui-type :emacs})
            sessions (ss/list-context-sessions-in ctx)]
        (is (= 1 (count sessions)))
        (is (= session-id (:session-id (first sessions))))))))

(deftest start-tui-runtime-extension-command-after-new-targets-new-session-test
  (let [orig-state @app-runtime/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (let [{:keys [ctx session-id]} (app-runtime/create-runtime-session-context
                                          {:provider           :anthropic
                                           :id                 "test-model"
                                           :name               "Test Model"
                                           :supports-reasoning false
                                           :context-window     200000}
                                          {:ui-type :tui})
                reg                    (:extension-registry ctx)
                ext-path               "/ext/which-session"
                runtime-fns*           (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
                api                    (ext/create-extension-api reg ext-path runtime-fns*)
                _                      (ext/register-extension-in! reg ext-path)
                _                      ((:register-command api)
                                        "which-session"
                                        {:description "Append implicit extension session id"
                                         :handler     (fn [_args]
                                                        ((:append-message api)
                                                         "assistant"
                                                         (:psi.agent-session/session-id
                                                          ((:query api) [:psi.agent-session/session-id]))))})
                tui-opts*              (atom nil)
                tui-start!             (fn [_run-agent-fn opts]
                                         (reset! tui-opts* opts)
                                         :ok)]
            (with-redefs [app-runtime/create-runtime-session-context
                          (fn [_ai-model opts]
                            {:ctx ctx
                             :oauth-ctx nil
                             :cwd (or (:cwd opts) (System/getProperty "user.dir"))
                             :session-id session-id})
                          app-runtime/bootstrap-runtime-session!
                          (fn [_ctx sid _ai-model _opts]
                            {:ctx _ctx
                             :templates []
                             :skills []
                             :startup-rehydrate ((resolve 'psi.app-runtime/startup-rehydrate-from-current-session!) ctx sid nil {:provider :anthropic :id "test-model" :name "Test Model" :supports-reasoning false})})]
              (is (= :ok (app-runtime/start-tui-runtime! tui-start! :ignored)))
              (let [dispatch-fn   (:dispatch-fn @tui-opts*)
                    query-fn      (:query-fn @tui-opts*)
                    before-id     (:psi.agent-session/session-id (query-fn [:psi.agent-session/session-id]))
                    new-result    (dispatch-fn "/new")
                    after-new-id  (:session-id (:rehydrate new-result))
                    ext-result    (dispatch-fn "/which-session")]
                (is (= :new-session (:type new-result)))
                (is (string? after-new-id))
                (is (not= before-id after-new-id))
                (is (= :extension-cmd (:type ext-result)))
                ((:handler ext-result) (:args ext-result))
                (is (= after-new-id
                       (last (->> (persist/all-entries-in ctx after-new-id)
                                  (filter #(= :message (:kind %)))
                                  (map #(get-in % [:data :message :content 0 :text])))))
                    "extension command implicit query must follow the active session after /new"))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest run-session-initializes-session-file-test
  (let [orig-state @app-runtime/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [clojure.core/read-line (let [calls (atom 0)]
                                                 (fn []
                                                   (if (= 1 (swap! calls inc))
                                                     "/quit"
                                                     nil)))]
            (app-runtime/run-session :ignored)
            (let [ctx        (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                  sd         (ss/get-session-data-in ctx session-id)]
              (is (some? ctx))
              (is (string? (:session-file sd)))
              (is (= :console (:ui-type sd)))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest run-session-journals-command-inputs-test
  (let [orig-state @app-runtime/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [clojure.core/read-line (let [calls (atom 0)]
                                                 (fn []
                                                   (case (swap! calls inc)
                                                     1 "/history"
                                                     2 "/quit"
                                                     nil)))]
            (app-runtime/run-session :ignored)
            (let [ctx        (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                  msg-texts  (->> (persist/all-entries-in ctx session-id)
                                  (filter #(= :message (:kind %)))
                                  (map #(get-in % [:data :message :content 0 :text]))
                                  set)]
              (is (contains? msg-texts "/history"))
              (is (contains? msg-texts "/quit"))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest start-tui-runtime-passes-current-session-file-test
  (let [orig-state @app-runtime/session-state
        captured   (atom nil)]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (let [mock-tui-start! (fn [_run-agent-fn opts]
                                  (reset! captured opts)
                                  :ok)]
            (is (= :ok (app-runtime/start-tui-runtime! mock-tui-start! :ignored {} {})))
            (is (string? (:current-session-file @captured)))
            (is (fn? (:dispatch-fn @captured)))
            (is (fn? (:on-interrupt-fn! @captured)))
            (let [ctx (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)]
              (is (= :tui (:ui-type (ss/get-session-data-in ctx session-id))))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest start-tui-runtime-journals-command-input-test
  (let [orig-state @app-runtime/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (let [mock-tui-start! (fn [_run-agent-fn opts]
                                  ((:dispatch-fn opts) "/history")
                                  :ok)]
            (is (= :ok (app-runtime/start-tui-runtime! mock-tui-start! :ignored {} {})))
            (let [ctx        (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                  msg-texts  (->> (persist/all-entries-in ctx session-id)
                                  (filter #(= :message (:kind %)))
                                  (map #(get-in % [:data :message :content 0 :text]))
                                  set)]
              (is (contains? msg-texts "/history"))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest run-session-routes-cli-prompt-through-prompt-lifecycle-test
  (let [orig-state @app-runtime/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (dispatch/clear-event-log!)
          (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                        (fn [_ai-ctx _ctx sid prepared _progress-queue]
                          {:execution-result/turn-id (:prepared-request/id prepared)
                           :execution-result/session-id sid
                           :execution-result/prepared-request-id (:prepared-request/id prepared)
                           :execution-result/assistant-message {:role "assistant"
                                                                :content [{:type :text :text "hello from lifecycle"}]
                                                                :stop-reason :stop
                                                                :timestamp (java.time.Instant/now)}
                           :execution-result/turn-outcome :turn.outcome/stop
                           :execution-result/tool-calls []
                           :execution-result/stop-reason :stop})
                        clojure.core/read-line (let [calls (atom 0)]
                                                 (fn []
                                                   (case (swap! calls inc)
                                                     1 "hello"
                                                     2 "/quit"
                                                     nil)))]
            (app-runtime/run-session :ignored)
            (let [ctx        (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                  entries    (dispatch/event-log-entries)
                  roles      (->> (persist/all-entries-in ctx session-id)
                                  (filter #(= :message (:kind %)))
                                  (map #(get-in % [:data :message :role]))
                                  vec)]
              (is (some #(= :session/prompt-submit (:event-type %)) entries))
              (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
              (is (some #(= :session/prompt-record-response (:event-type %)) entries))
              (is (some #(= :session/prompt-finish (:event-type %)) entries))
              (is (= ["user" "assistant" "user"] roles))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest submit-prompt-in-runs-git-head-sync-after-successful-turn-test
  (let [orig-state @app-runtime/session-state
        sync-calls (atom [])]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (dispatch/clear-event-log!)
          (with-redefs [clojure.core/read-line
                        (let [calls (atom 0)]
                          (fn []
                            (case (swap! calls inc)
                              1 "/quit"
                              nil)))
                        runtime/safe-maybe-sync-on-git-head-change!
                        (fn [_ctx sid]
                          (swap! sync-calls conj sid)
                          {:ok? true})
                        psi.agent-session.prompt-runtime/execute-prepared-request!
                        (fn [_ai-ctx _ctx sid prepared _progress-queue]
                          {:execution-result/turn-id (:prepared-request/id prepared)
                           :execution-result/session-id sid
                           :execution-result/prepared-request-id (:prepared-request/id prepared)
                           :execution-result/assistant-message {:role "assistant"
                                                                :content [{:type :text :text "hello from app-runtime sync"}]
                                                                :stop-reason :stop
                                                                :timestamp (java.time.Instant/now)}
                           :execution-result/turn-outcome :turn.outcome/stop
                           :execution-result/tool-calls []
                           :execution-result/stop-reason :stop})]
            (app-runtime/run-session :ignored)
            (let [ctx (:ctx @app-runtime/session-state)
                  session-id (-> @app-runtime/session-state
                                 :ctx
                                 ss/list-context-sessions-in
                                 first
                                 :session-id)]
              (#'app-runtime/submit-prompt-in!
               ctx
               session-id
               (:ai-model @app-runtime/session-state)
               "hello"
               nil
               {:sync-on-git-head-change? true})
              (is (= [session-id] @sync-calls)
                  "submit-prompt-in! runs git-head sync once after a successful prompt turn")))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest start-tui-runtime-routes-agent-prompts-through-prompt-lifecycle-test
  (let [orig-state @app-runtime/session-state
        queued     (atom nil)]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (dispatch/clear-event-log!)
          (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                        (fn [_ai-ctx _ctx sid prepared progress-queue]
                          (reset! queued progress-queue)
                          {:execution-result/turn-id (:prepared-request/id prepared)
                           :execution-result/session-id sid
                           :execution-result/prepared-request-id (:prepared-request/id prepared)
                           :execution-result/assistant-message {:role "assistant"
                                                                :content [{:type :text :text "hello from tui lifecycle"}]
                                                                :stop-reason :stop
                                                                :timestamp (java.time.Instant/now)}
                           :execution-result/turn-outcome :turn.outcome/stop
                           :execution-result/tool-calls []
                           :execution-result/stop-reason :stop})]
            (let [result (app-runtime/start-tui-runtime!
                          (fn [run-agent-fn _opts]
                            (let [queue (java.util.concurrent.LinkedBlockingQueue.)]
                              (run-agent-fn "hello from tui" queue)
                              (.poll queue 2000 java.util.concurrent.TimeUnit/MILLISECONDS)))
                          :ignored {} {})
                  ctx     (:ctx @app-runtime/session-state)
                  sid     (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                  entries (dispatch/event-log-entries)
                  roles   (->> (persist/all-entries-in ctx sid)
                               (filter #(= :message (:kind %)))
                               (map #(get-in % [:data :message :role]))
                               vec)]
              (is (= :done (:kind result)))
              (is (= "assistant" (get-in result [:result :role])))
              (is (= "hello from tui lifecycle"
                     (get-in result [:result :content 0 :text])))
              (is (instance? java.util.concurrent.LinkedBlockingQueue @queued))
              (is (some #(= :session/prompt-submit (:event-type %)) entries))
              (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
              (is (some #(= :session/prompt-record-response (:event-type %)) entries))
              (is (some #(= :session/prompt-finish (:event-type %)) entries))
              (is (= ["user" "assistant"] roles))))))
      (finally
        (reset! app-runtime/session-state orig-state)))))

(deftest agent-messages->tui-resume-state-rehydrates-tool-rows-test
  (let [messages [{:role "user"
                   :content [{:type :text :text "read file"}]}
                  {:role "assistant"
                   :content [{:type :text :text "Sure"}
                             {:type :tool-call :id "call-1" :name "read"
                              :arguments "{\"path\":\"a.txt\"}"}]}
                  {:role "toolResult"
                   :tool-call-id "call-1"
                   :tool-name "read"
                   :content [{:type :text :text "hello"}
                             {:type :image :mime-type "image/png" :data "<base64>"}]
                   :details {:full-output-path "/tmp/all.log"}
                   :is-error false}
                  {:role "assistant"
                   :content [{:type :text :text "done"}]}]
        {:keys [messages tool-calls tool-order]}
        (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
    ;; tool-call block emits a :tool message before the assistant text summary
    (is (= [{:role :user :text "read file"}
            {:role :tool :tool-id "call-1"}
            {:role :assistant :text "Sure"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-1"] tool-order))
    (is (= "read" (get-in tool-calls ["call-1" :name])))
    (is (= :success (get-in tool-calls ["call-1" :status])))
    (is (= "hello" (get-in tool-calls ["call-1" :result])))
    (is (= {:full-output-path "/tmp/all.log"}
           (get-in tool-calls ["call-1" :details])))))

(deftest agent-messages->tui-resume-state-supports-structured-content-test
  (let [messages [{:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "planning"}
                                      {:kind :tool-call :id "call-2" :name "read" :input {:path "README.md"}}]}}
                  {:role "toolResult"
                   :tool-call-id "call-2"
                   :tool-name "read"
                   :content [{:type :text :text "ok"}]
                   :is-error false}
                  {:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "done"}]}}]
        {:keys [messages tool-calls tool-order]}
        (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
    ;; tool-call block emits a :tool message before the assistant text summary
    (is (= [{:role :tool :tool-id "call-2"}
            {:role :assistant :text "planning"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-2"] tool-order))
    (is (= "read" (get-in tool-calls ["call-2" :name])))
    (is (= "{:path \"README.md\"}"
           (get-in tool-calls ["call-2" :args])))))

(deftest agent-messages->tui-resume-state-rehydrates-thinking-blocks-test
  (testing "thinking blocks in assistant content are emitted as :thinking messages before the assistant reply"
    (let [messages [{:role "user"
                     :content [{:type :text :text "explain recursion"}]}
                    {:role "assistant"
                     :content [{:type :thinking :text "Let me think about this carefully."}
                               {:type :text :text "Recursion is when a function calls itself."}]}]
          {:keys [messages]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      (is (= [{:role :user :text "explain recursion"}
              {:role :thinking :text "Let me think about this carefully."}
              {:role :assistant :text "Recursion is when a function calls itself."}]
             messages)))))

(deftest agent-messages->tui-resume-state-thinking-before-tool-in-block-order-test
  (testing "thinking and tool-call blocks are emitted in block order; assistant text follows"
    (let [messages [{:role "assistant"
                     :content [{:type :thinking :text "Plan A"}
                               {:type :tool-call :id "call-3" :name "bash"
                                :arguments "{\"cmd\":\"ls\"}"}
                               {:type :thinking :text "Plan B"}
                               {:type :text :text "Done."}]}
                    {:role "toolResult"
                     :tool-call-id "call-3"
                     :tool-name "bash"
                     :content [{:type :text :text "file1 file2"}]
                     :is-error false}]
          {:keys [messages tool-order]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      ;; thinking A, :tool message, thinking B, then assistant text — in block order
      (is (= {:role :thinking :text "Plan A"} (nth messages 0)))
      (is (= {:role :tool :tool-id "call-3"} (nth messages 1)))
      (is (= {:role :thinking :text "Plan B"} (nth messages 2)))
      (is (= {:role :assistant :text "Done."} (nth messages 3)))
      (is (= ["call-3"] tool-order)))))

(deftest agent-messages->tui-resume-state-structured-content-with-thinking-test
  (testing "structured content map with thinking blocks is rehydrated correctly"
    (let [messages [{:role "assistant"
                     :content {:kind :structured
                               :blocks [{:kind :thinking :text "Structured thinking."}
                                        {:kind :text :text "Structured answer."}]}}]
          {:keys [messages]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      (is (= [{:role :thinking :text "Structured thinking."}
              {:role :assistant :text "Structured answer."}]
             messages)))))

;; moved to psi.main
#_(deftest memory-runtime-opts-from-args-test
    (is (= {:store-provider "in-memory"
            :auto-store-fallback? false
            :history-commit-limit 99
            :retention-snapshots 12
            :retention-deltas 34}
           (#'app-runtime/memory-runtime-opts-from-args
            ["--memory-store" "in-memory"
             "--memory-store-fallback" "off"
             "--memory-history-limit" "99"
             "--memory-retention-snapshots" "12"
             "--memory-retention-deltas" "34"])))
    (is (= {}
           (#'app-runtime/memory-runtime-opts-from-args
            ["--memory-history-limit" "not-a-number"
             "--memory-store-fallback" "maybe"]))))

;; moved to psi.main
#_(deftest session-runtime-config-from-args-test
    (testing "CLI flag sets timeout"
      (is (= {:llm-stream-idle-timeout-ms 90000}
             (#'app-runtime/session-runtime-config-from-args
              ["--llm-idle-timeout-ms" "90000"]))))

    (testing "env var is used when CLI flag is absent"
      (with-redefs [app-runtime/llm-idle-timeout-ms-from-env (fn [] 42000)]
        (is (= {:llm-stream-idle-timeout-ms 42000}
               (#'app-runtime/session-runtime-config-from-args [])))))

    (testing "CLI flag wins over env var"
      (with-redefs [app-runtime/llm-idle-timeout-ms-from-env (fn [] 42000)]
        (is (= {:llm-stream-idle-timeout-ms 90000}
               (#'app-runtime/session-runtime-config-from-args
                ["--llm-idle-timeout-ms" "90000"])))))

    (testing "invalid CLI value does not fall back to env"
      (with-redefs [app-runtime/llm-idle-timeout-ms-from-env (fn [] 42000)]
        (is (= {}
               (#'app-runtime/session-runtime-config-from-args
                ["--llm-idle-timeout-ms" "not-a-number"]))))))

;; moved to psi.main
#_(deftest rpc-trace-file-from-args-test
    (is (= "/tmp/rpc-trace.ndedn"
           (#'app-runtime/rpc-trace-file-from-args
            ["--rpc-trace-file" "/tmp/rpc-trace.ndedn"])))
    (is (nil? (#'app-runtime/rpc-trace-file-from-args
               ["--rpc-trace-file" "   "])))
    (is (nil? (#'app-runtime/rpc-trace-file-from-args []))))

(deftest bootstrap-runtime-session-initial-context-index-has-single-session-test
  (with-redefs [oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                sys-prompt/build-system-prompt (fn [_] "")
                ext/discover-extension-paths (fn [& _] [])
                introspection/register-resolvers! (fn [] nil)
                memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                bootstrap/bootstrap-in!
                (fn [_ctx _session-id _]
                  {:extension-errors [] :extension-loaded-count 0})]
    (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                         {:provider :anthropic
                          :id "test-model"
                          :name "Test Model"
                          :supports-reasoning false}
                         {})
          session-id (-> (ss/list-context-sessions-in ctx) first :session-id)
          sd         (ss/get-session-data-in ctx session-id)
          sessions   (ss/get-sessions-map-in ctx)]
      (is (= 1 (count sessions)))
      (is (= session-id (:session-id sd)))
      (is (= [session-id] (vec (keys sessions)))))))

(deftest bootstrap-runtime-session-passes-memory-runtime-opts-to-sync-test
  (let [captured (atom nil)]
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [opts]
                                                      (reset! captured opts)
                                                      {:ok? true})
                  bootstrap/bootstrap-in!
                  (fn [_ctx _session-id _]
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "test-model"
                            :name "Test Model"
                            :supports-reasoning false}
                           {:memory-runtime-opts {:store-provider "in-memory"
                                                  :retention-snapshots 22
                                                  :retention-deltas 44}
                            :session-config {:llm-stream-idle-timeout-ms 54321}})]
        (is (= "in-memory" (:store-provider @captured)))
        (is (= 22 (:retention-snapshots @captured)))
        (is (= 44 (:retention-deltas @captured)))
        (is (string? (:cwd @captured)))
        (is (= 54321 (get-in ctx [:config :llm-stream-idle-timeout-ms])))))))

(deftest bootstrap-runtime-session-enriches-system-prompt-with-capabilities-test
  (with-redefs [oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                ext/discover-extension-paths (fn [& _] [])
                introspection/register-resolvers! (fn [] nil)
                memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                bootstrap/bootstrap-in!
                (fn [_ctx _session-id _]
                  {:extension-errors [] :extension-loaded-count 0})]
    (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                         {:provider :anthropic
                          :id "test-model"
                          :name "Test Model"
                          :supports-reasoning false}
                         {})
          sid    (-> (ss/list-context-sessions-in ctx) first :session-id)
          prompt (:psi.agent-session/system-prompt
                  (session/query-in ctx sid [:psi.agent-session/system-prompt]))]
      ;; Lambda mode is default — graph capabilities appear after lambda graph discovery
      (is (str/includes? prompt "λ graph(eql)."))
      (is (str/includes? prompt "- agent-session (ops=")))))

(deftest bootstrap-runtime-session-wires-nrepl-runtime-atom-test
  (let [orig @app-runtime/nrepl-runtime]
    (try
      (reset! app-runtime/nrepl-runtime {:host "localhost"
                                         :port 8999
                                         :endpoint "localhost:8999"})
      (with-redefs [oauth/create-context (fn [] nil)
                    pt/discover-templates (fn [] [])
                    skills/discover-skills (fn [] {:skills [] :diagnostics []})
                    sys-prompt/discover-context-files (fn [_] [])
                    sys-prompt/build-system-prompt (fn [_] "")
                    ext/discover-extension-paths (fn [& _] [])
                    introspection/register-resolvers! (fn [] nil)
                    memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                    bootstrap/bootstrap-in!
                    (fn [_ctx _session-id _]
                      {:extension-errors [] :extension-loaded-count 0})]
        (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                             {:provider :anthropic
                              :id "test-model"
                              :name "Test Model"
                              :supports-reasoning false}
                             {})
              result (session/query-in ctx [:psi.runtime/nrepl-host
                                            :psi.runtime/nrepl-port
                                            :psi.runtime/nrepl-endpoint])]
          (is (= "localhost" (:psi.runtime/nrepl-host result)))
          (is (= 8999 (:psi.runtime/nrepl-port result)))
          (is (= "localhost:8999" (:psi.runtime/nrepl-endpoint result)))))
      (finally
        (reset! app-runtime/nrepl-runtime orig)))))

(deftest nrepl-runtime-eql-reflects-live-start-stop-test
  (let [orig-runtime @app-runtime/nrepl-runtime
        orig-user-dir (System/getProperty "user.dir")
        tmp-dir-file (java.io.File.
                      (str (System/getProperty "java.io.tmpdir")
                           "/psi-main-nrepl-runtime-"
                           (java.util.UUID/randomUUID)))
        _ (.mkdirs tmp-dir-file)
        tmp-dir (.getAbsolutePath tmp-dir-file)]
    (try
      (System/setProperty "user.dir" tmp-dir)
      (let [srv (#'app-runtime/start-nrepl! 0)
            cwd (System/getProperty "user.dir")]
        (try
          (is (pos-int? (:port srv)))
          (let [ctx    (session/create-context {:persist? false
                                                :cwd cwd
                                                :nrepl-runtime-atom app-runtime/nrepl-runtime})
                _      (session/new-session-in! ctx nil {})
                result (session/query-in ctx [:psi.runtime/nrepl-host
                                              :psi.runtime/nrepl-port
                                              :psi.runtime/nrepl-endpoint])
                expected-endpoint (str (:psi.runtime/nrepl-host result)
                                       ":"
                                       (:psi.runtime/nrepl-port result))]
            (is (= "localhost" (:psi.runtime/nrepl-host result)))
            (is (integer? (:psi.runtime/nrepl-port result)))
            (is (= (:port srv) (:psi.runtime/nrepl-port result)))
            (is (= expected-endpoint (:psi.runtime/nrepl-endpoint result))))
          (finally
            (#'app-runtime/stop-nrepl! srv))))
      (let [ctx-after-stop    (session/create-context {:persist? false
                                                       :cwd (System/getProperty "user.dir")
                                                       :nrepl-runtime-atom app-runtime/nrepl-runtime})
            _                 (session/new-session-in! ctx-after-stop nil {})
            result-after-stop (session/query-in ctx-after-stop
                                                [:psi.runtime/nrepl-host
                                                 :psi.runtime/nrepl-port
                                                 :psi.runtime/nrepl-endpoint])]
        (is (nil? (:psi.runtime/nrepl-host result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-port result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-endpoint result-after-stop))))
      (finally
        (System/setProperty "user.dir" orig-user-dir)
        (reset! app-runtime/nrepl-runtime orig-runtime)))))

(deftest start-nrepl-redirects-startup-chatter-to-stderr-test
  (let [orig-runtime @app-runtime/nrepl-runtime
        out          (java.io.StringWriter.)
        err          (java.io.StringWriter.)]
    (try
      (with-redefs [requiring-resolve (fn [sym]
                                        (case sym
                                          nrepl.server/start-server
                                          (fn [& {:keys [port]}]
                                            (println "nREPL server started on port" (or port 0))
                                            (.println System/out (str "system-out port " (or port 0)))
                                            {:port (or port 5555)})
                                          nrepl.server/stop-server
                                          (fn [_] nil)))]
        (binding [*out* out
                  *err* err]
          (let [srv (#'app-runtime/start-nrepl! 5555)]
            (is (= 5555 (:port srv)))
            (is (not (str/includes? (str out) "nREPL server started on port")))
            (is (not (str/includes? (str out) "system-out port 5555")))
            (is (str/includes? (str err) "nREPL server started on port"))
            ;; Direct `System/out`/`System/err` writes bypass dynamic *out*/*err*
            ;; binding in this test harness, so only the startup chatter emitted
            ;; through println is asserted here.
            (#'app-runtime/stop-nrepl! srv))))
      (finally
        (reset! app-runtime/nrepl-runtime orig-runtime)))))

(deftest bootstrap-runtime-session-applies-project-preferences-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "openai"
      :model-id "gpt-5.3-codex"
      :thinking-level :high})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                  bootstrap/bootstrap-in!
                  (fn [_ctx _session-id _]
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "claude-sonnet-4-6"
                            :name "Claude Sonnet 4.6"
                            :supports-reasoning true}
                           {:cwd cwd})
            session-id      (-> (ss/list-context-sessions-in ctx) first :session-id)
            sd              (ss/get-session-data-in ctx session-id)]
        (is (= "openai" (get-in sd [:model :provider])))
        (is (= "gpt-5.3-codex" (get-in sd [:model :id])))
        (is (= :high (:thinking-level sd)))))))

(deftest bootstrap-runtime-session-invalid-project-model-falls-back-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "nope"
      :model-id "missing"
      :thinking-level :xhigh})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                  bootstrap/bootstrap-in!
                  (fn [_ctx _session-id _]
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'app-runtime/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "claude-sonnet-4-6"
                            :name "Claude Sonnet 4.6"
                            :supports-reasoning false}
                           {:cwd cwd})
            session-id      (-> (ss/list-context-sessions-in ctx) first :session-id)
            sd              (ss/get-session-data-in ctx session-id)]
        (is (= "anthropic" (get-in sd [:model :provider])))
        (is (= "claude-sonnet-4-6" (get-in sd [:model :id])))
        (is (= :off (:thinking-level sd)))))))
