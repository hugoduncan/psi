(ns psi.app-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]

   [psi.agent-session.core :as session]
   [psi.agent-session.ui-capabilities :as ui-capabilities]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.app-runtime :as app-runtime]
   [psi.app-runtime.transcript]
   [psi.session-persistence.core :as persist]
   [psi.turn-runtime.core :as turn-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.prompt-assets.system-prompt :as sys-prompt]
   [psi.memory.runtime :as memory-runtime]
   [psi.app-runtime.test-support :as app-test-support]
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

(defn- main-bootstrap-stub-bindings
  "Returns bootstrap-stub-bindings merged with resolve-model and
   discover-extension-paths stubs needed by entry-point tests."
  []
  (merge (app-test-support/bootstrap-stub-bindings)
         {#'app-runtime/resolve-model (fn [_] app-test-support/test-ai-model)
          #'ext/discover-extension-paths (fn [& _] [])}))

(deftest create-runtime-session-context-does-not-create-initial-session-test
  (with-redefs-fn (main-bootstrap-stub-bindings)
    (fn []
      (let [{:keys [ctx]} (app-runtime/create-runtime-session-context
                           app-test-support/test-ai-model
                           {:ui-type :emacs
                            :persist? false})
            sessions (ss/list-context-sessions-in ctx)]
        (is (empty? sessions))))))

(deftest create-runtime-session-context-can-suppress-default-tui-ui-provider-test
  (with-redefs-fn (main-bootstrap-stub-bindings)
    (fn []
      (let [{:keys [ctx]} (app-runtime/create-runtime-session-context
                           app-test-support/test-ai-model
                           {:ui-type :tui
                            :persist? false
                            :install-default-ui-capability-provider? false})]
        (is (nil? (ui-capabilities/provider ctx)))
        (is (= {:psi.ui/type nil
                :psi.ui/available? false
                :psi.ui/capabilities []
                :psi.ui/actions []
                :psi.ui/make-visible-action
                (ui-capabilities/unavailable-make-visible-action
                 ui-capabilities/no-provider-reason
                 "No UI capability provider is installed.")
                :psi.ui/diagnostic nil}
               (session/query-in ctx nil ui-capabilities/ui-attrs)))))))

(deftest build-startup-plan-does-not-require-live-session-test
  (with-redefs-fn (main-bootstrap-stub-bindings)
    (fn []
      (let [{:keys [ctx cwd]} (app-runtime/create-runtime-session-context
                               app-test-support/test-ai-model
                               {:ui-type :emacs
                                :persist? false})
            plan (#'app-runtime/build-startup-plan ctx {:cwd cwd})]
        (is (map? plan))
        (is (= cwd (:cwd plan)))
        (is (= [] (ss/list-context-sessions-in ctx)))
        (is (contains? plan :templates))
        (is (contains? plan :skills))
        (is (contains? plan :base-tools))))))

(deftest bootstrap-runtime-session-creates-initial-session-after-startup-plan-test
  (with-redefs-fn (main-bootstrap-stub-bindings)
    (fn []
      (let [{:keys [ctx cwd]} (app-runtime/create-runtime-session-context
                               app-test-support/test-ai-model
                               {:ui-type :emacs
                                :persist? false})
            calls             (atom [])
            startup-plan      {:cwd cwd :diagnostics []}]
        (with-redefs [app-runtime/build-startup-plan
                      (fn [ctx* opts]
                        (swap! calls conj {:step :build-startup-plan
                                           :sessions (count (ss/list-context-sessions-in ctx*))
                                           :cwd (:cwd opts)})
                        startup-plan)
                      app-runtime/create-initial-startup-session!
                      (fn [ctx*]
                        (swap! calls conj {:step :create-initial-startup-session!
                                           :sessions (count (ss/list-context-sessions-in ctx*))})
                        (:session-id (session/new-session-in! ctx* nil {})))
                      app-runtime/adopt-startup-plan-into-session!
                      (fn [ctx* session-id _ai-model startup-plan* _opts]
                        (swap! calls conj {:step :adopt-startup-plan-into-session!
                                           :sessions (count (ss/list-context-sessions-in ctx*))
                                           :session-id session-id
                                           :startup-plan startup-plan*})
                        {:ctx ctx*
                         :session-id session-id
                         :startup-plan startup-plan*})]
          (let [result (app-runtime/bootstrap-runtime-session!
                        ctx
                        app-test-support/test-ai-model
                        {:cwd cwd})
                steps  (mapv :step @calls)]
            (is (= [:build-startup-plan
                    :create-initial-startup-session!
                    :adopt-startup-plan-into-session!]
                   steps))
            (is (= 0 (:sessions (first @calls)))
                "startup-plan assembly must run before any live session exists")
            (is (= 0 (:sessions (second @calls)))
                "initial-session creation point must be reached before any session exists")
            (is (= 1 (:sessions (nth @calls 2)))
                "startup-plan adoption should see the created initial session")
            (is (= startup-plan (:startup-plan result)))
            (is (= 1 (count (ss/list-context-sessions-in ctx))))))))))

(deftest start-tui-runtime-installs-and-clears-tui-ui-provider-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
        (fn []
          (let [bootstrap-query* (atom nil)
                started-query*   (atom nil)
                after-return-ctx* (atom nil)
                tui-start!       (fn [_run-agent-fn opts]
                                   (let [query-fn (:query-fn opts)]
                                     (reset! started-query* (query-fn ui-capabilities/ui-attrs))
                                     :ok))]
            (with-redefs [app-runtime/bootstrap-runtime-session!
                          (fn [ctx _ai-model _opts]
                            (reset! bootstrap-query* (session/query-in ctx nil ui-capabilities/ui-attrs))
                            (let [sid (:session-id (session/new-session-in! ctx nil {}))]
                              {:ctx ctx
                               :session-id sid
                               :templates []
                               :skills []
                               :startup-rehydrate {}}))]
              (is (= :ok (app-runtime/start-tui-runtime! tui-start! :ignored {} {} {:session-root nil})))
              (reset! after-return-ctx* (:ctx @app-runtime/session-state))
              (is (= ui-capabilities/no-provider-reason
                     (get-in @bootstrap-query* [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))
                  "bootstrap runs before the TUI frontend installs its active provider")
              (is (= {:psi.ui/type :tui
                      :psi.ui/available? true
                      :psi.ui/capabilities []
                      :psi.ui/actions []
                      :psi.ui/make-visible-action
                      (ui-capabilities/unavailable-make-visible-action
                       ui-capabilities/unsupported-capability-reason
                       "The attached UI does not support making itself visible.")
                      :psi.ui/diagnostic nil}
                     @started-query*)
                  "TUI frontend queries see an attached-but-unsupported provider")
              (is (nil? (ui-capabilities/provider @after-return-ctx*))
                  "TUI provider is cleared when the frontend exits"))))))))

(deftest start-tui-runtime-clears-tui-ui-provider-when-frontend-throws-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
        (fn []
          (let [started-query*  (atom nil)
                after-throw-ctx* (atom nil)
                tui-start!      (fn [_run-agent-fn opts]
                                  (let [query-fn (:query-fn opts)]
                                    (reset! started-query* (query-fn ui-capabilities/ui-attrs))
                                    (throw (ex-info "frontend failed" {}))))]
            (with-redefs [app-runtime/bootstrap-runtime-session!
                          (fn [ctx _ai-model _opts]
                            (let [sid (:session-id (session/new-session-in! ctx nil {}))]
                              {:ctx ctx
                               :session-id sid
                               :templates []
                               :skills []
                               :startup-rehydrate {}}))]
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"frontend failed"
                   (app-runtime/start-tui-runtime! tui-start! :ignored {} {} {:session-root nil})))
              (reset! after-throw-ctx* (:ctx @app-runtime/session-state))
              (is (= ui-capabilities/unsupported-capability-reason
                     (get-in @started-query* [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))
                  "TUI frontend observes the attached provider before the exceptional exit")
              (is (nil? (ui-capabilities/provider @after-throw-ctx*))
                  "TUI provider is cleared when the frontend throws")
              (is (= ui-capabilities/no-provider-reason
                     (get-in (session/query-in @after-throw-ctx* nil ui-capabilities/ui-attrs)
                             [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))
                  "post-throw queries cannot observe stale attached TUI advertisements"))))))))

(deftest start-tui-runtime-extension-command-after-new-targets-new-session-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
        (fn []
          (let [{:keys [ctx]} (app-runtime/create-runtime-session-context
                               app-test-support/test-ai-model
                               {:ui-type :tui
                                :persist? false})
                session-id             (:session-id (session/new-session-in! ctx nil {}))
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
                             :cwd (or (:cwd opts) (System/getProperty "user.dir"))})
                          app-runtime/bootstrap-runtime-session!
                          (fn [_ctx _ai-model _opts]
                            (let [sid (or (:session-id _opts) session-id)]
                              {:ctx _ctx
                               :session-id sid
                               :templates []
                               :skills []
                               :startup-rehydrate (#'app-runtime/startup-rehydrate-from-current-session! ctx sid nil app-test-support/test-ai-model)}))]
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
                    "extension command implicit query must follow the active session after /new")))))))))

(deftest run-session-starts-non-persisting-console-session-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
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
              (is (nil? (:session-file sd)))
              (is (= :console (:ui-type sd))))))))))

(deftest run-session-journals-command-inputs-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
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
              (is (contains? msg-texts "/quit")))))))))

(deftest start-tui-runtime-passes-current-session-file-test
  (let [captured (atom nil)]
    (test-support/with-temp-session-root
      (fn [session-root]
        (app-test-support/with-session-state-restore
          (fn []
            (with-redefs-fn (main-bootstrap-stub-bindings)
              (fn []
                (let [mock-tui-start! (fn [_run-agent-fn opts]
                                        (reset! captured opts)
                                        :ok)]
                  (is (= :ok (app-runtime/start-tui-runtime! mock-tui-start! :ignored {} {} {:session-root session-root})))
                  (is (string? (:current-session-file @captured))
                      "persisted TUI startup should pass the current session file to the frontend")
                  (is (fn? (:dispatch-fn @captured)))
                  (is (fn? (:on-interrupt-fn! @captured)))
                  (let [ctx (:ctx @app-runtime/session-state)
                        session-id (-> @app-runtime/session-state :ctx ss/list-context-sessions-in first :session-id)
                        session-file (:session-file (ss/get-session-data-in ctx session-id))]
                    (is (= :tui (:ui-type (ss/get-session-data-in ctx session-id))))
                    (is (= session-file (:current-session-file @captured)))
                    (is (.startsWith session-file session-root)
                        (str "expected persisted TUI session-file under isolated session-root\n"
                             "session-root: " session-root "\n"
                             "session-file: " session-file))))))))))))

(deftest start-tui-runtime-journals-command-input-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
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
              (is (contains? msg-texts "/history")))))))))

(deftest run-session-routes-cli-prompt-through-prompt-lifecycle-test
  (app-test-support/with-session-state-restore
    (fn []
      (with-redefs-fn (main-bootstrap-stub-bindings)
        (fn []
          (kernel/clear-event-log!)
          (with-redefs [psi.turn-runtime.core/execute-prepared-request!
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
                  entries    (kernel/event-log-entries)
                  roles      (->> (persist/all-entries-in ctx session-id)
                                  (filter #(= :message (:kind %)))
                                  (map #(get-in % [:data :message :role]))
                                  vec)]
              (is (some #(= :session/prompt-submit (:event-type %)) entries))
              (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
              (is (some #(= :session/prompt-record-response (:event-type %)) entries))
              (is (some #(= :session/prompt-finish (:event-type %)) entries))
              (is (= ["user" "assistant" "user"] roles)))))))))

(deftest submit-prompt-in-runs-git-head-sync-after-successful-turn-test
  (let [sync-calls (atom [])]
    (app-test-support/with-session-state-restore
      (fn []
        (with-redefs-fn (main-bootstrap-stub-bindings)
          (fn []
            (kernel/clear-event-log!)
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
                          psi.turn-runtime.core/execute-prepared-request!
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
                    sync-calls-before (count @sync-calls)
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
                (is (= [session-id] (subvec (vec @sync-calls) sync-calls-before))
                    "submit-prompt-in! runs git-head sync once after a successful prompt turn")))))))))

(deftest start-tui-runtime-routes-agent-prompts-through-prompt-lifecycle-test
  (let [queued (atom nil)]
    (app-test-support/with-session-state-restore
      (fn []
        (with-redefs-fn (main-bootstrap-stub-bindings)
          (fn []
            (kernel/clear-event-log!)
            (with-redefs [psi.turn-runtime.core/execute-prepared-request!
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
                    entries (kernel/event-log-entries)
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
                (is (= ["user" "assistant"] roles))))))))))

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
;; moved to psi.main
;; moved to psi.main
(deftest bootstrap-runtime-session-initial-context-index-has-single-session-test
  (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                         {#'ext/discover-extension-paths (fn [& _] [])})
    (fn []
      (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                           app-test-support/test-ai-model
                           {:persist? false})
            session-id (-> (ss/list-context-sessions-in ctx) first :session-id)
            sd         (ss/get-session-data-in ctx session-id)
            sessions   (ss/get-sessions-map-in ctx)]
        (is (= 1 (count sessions)))
        (is (= session-id (:session-id sd)))
        (is (= [session-id] (vec (keys sessions))))))))

(deftest bootstrap-runtime-session-passes-memory-runtime-opts-to-sync-test
  (let [captured (atom nil)]
    (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                           {#'ext/discover-extension-paths (fn [& _] [])
                            #'memory-runtime/sync-memory-layer! (fn [opts]
                                                                  (reset! captured opts)
                                                                  {:ok? true})})
      (fn []
        (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                             app-test-support/test-ai-model
                             {:persist? false
                              :memory-runtime-opts {:store-provider "in-memory"
                                                    :retention-snapshots 22
                                                    :retention-deltas 44}
                              :session-config {:llm-stream-idle-timeout-ms 54321}})]
          (is (= "in-memory" (:store-provider @captured)))
          (is (= 22 (:retention-snapshots @captured)))
          (is (= 44 (:retention-deltas @captured)))
          (is (string? (:cwd @captured)))
          (is (= 54321 (get-in ctx [:config :llm-stream-idle-timeout-ms]))))))))

(deftest bootstrap-runtime-session-enriches-system-prompt-with-capabilities-test
  (with-redefs-fn (merge (dissoc (app-test-support/bootstrap-stub-bindings)
                                 #'sys-prompt/build-system-prompt)
                         {#'ext/discover-extension-paths (fn [& _] [])})
    (fn []
      (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                           app-test-support/test-ai-model
                           {:persist? false})
            sid    (-> (ss/list-context-sessions-in ctx) first :session-id)
            prompt (:psi.agent-session/system-prompt
                    (session/query-in ctx sid [:psi.agent-session/system-prompt]))]
        ;; Lambda mode is default — graph capabilities appear after lambda graph discovery
        (is (str/includes? prompt "λ graph(eql)."))
        (is (str/includes? prompt "- agent-session (ops="))))))

(deftest bootstrap-runtime-session-wires-nrepl-runtime-atom-test
  (let [orig @app-runtime/nrepl-runtime]
    (try
      (reset! app-runtime/nrepl-runtime {:host "localhost"
                                         :port 8999
                                         :endpoint "localhost:8999"})
      (with-redefs-fn (merge (app-test-support/bootstrap-stub-bindings)
                             {#'ext/discover-extension-paths (fn [& _] [])})
        (fn []
          (let [{:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                               app-test-support/test-ai-model
                               {:persist? false})
                result (session/query-in ctx [:psi.runtime/nrepl-host
                                              :psi.runtime/nrepl-port
                                              :psi.runtime/nrepl-endpoint])]
            (is (= "localhost" (:psi.runtime/nrepl-host result)))
            (is (= 8999 (:psi.runtime/nrepl-port result)))
            (is (= "localhost:8999" (:psi.runtime/nrepl-endpoint result))))))
      (finally
        (reset! app-runtime/nrepl-runtime orig)))))

(deftest bootstrap-runtime-session-intentional-persisting-test-root-is-forwarded-test
  (test-support/with-temp-session-root
    (fn [session-root]
      (with-redefs-fn (main-bootstrap-stub-bindings)
        (fn []
          (let [cwd (test-support/temp-cwd)
                {:keys [ctx]} (app-test-support/bootstrap-fresh-session!
                               app-test-support/test-ai-model
                               {:cwd cwd :persist? true :session-root session-root})
                session-id   (-> (ss/list-context-sessions-in ctx) first :session-id)
                session-file (:session-file (ss/get-session-data-in ctx session-id))]
            (is (string? session-file) "intentional persisted bootstrap should allocate a session file")
            (is (.startsWith session-file session-root)
                (str "expected persisted bootstrap session-file under isolated session-root\n"
                     "session-root: " session-root "\n"
                     "session-file: " session-file))))))))

;; ---------------------------------------------------------------------------
;; maybe-install-nullable-execution-mode
;; ---------------------------------------------------------------------------

(deftest maybe-install-nullable-execution-mode-passthrough-when-absent-test
  (testing "returns ctx unchanged when env var is nil"
    (with-redefs [app-runtime/nullable-execution-mode (fn [] nil)]
      (let [ctx {:some-key "value"}
            result (#'app-runtime/maybe-install-nullable-execution-mode ctx)]
        (is (= ctx result))
        (is (not (contains? result :execute-prepared-request-fn)))))))

(deftest maybe-install-nullable-execution-mode-installs-stub-when-deterministic-test
  (testing "installs :execute-prepared-request-fn when mode is deterministic"
    (with-redefs [app-runtime/nullable-execution-mode (fn [] "deterministic")]
      (let [ctx {:some-key "value"}
            result (#'app-runtime/maybe-install-nullable-execution-mode ctx)]
        (is (contains? result :execute-prepared-request-fn))
        (is (fn? (:execute-prepared-request-fn result)))
        (is (= "value" (:some-key result)))))))

(deftest maybe-install-nullable-execution-mode-stub-echoes-user-text-test
  (testing "stub executor echoes user text back as assistant response with correct shape"
    (with-redefs [app-runtime/nullable-execution-mode (fn [] "deterministic")]
      (let [ctx (#'app-runtime/maybe-install-nullable-execution-mode {})
            stub (:execute-prepared-request-fn ctx)
            prepared {:prepared-request/id "turn-42"
                      :prepared-request/user-message
                      {:content [{:type :text :text "hello world"}]}}
            result (stub nil nil "session-1" prepared nil)]
        (is (= "turn-42" (:execution-result/turn-id result)))
        (is (= "session-1" (:execution-result/session-id result)))
        (is (= "assistant" (get-in result [:execution-result/assistant-message :role])))
        (is (= "hello world"
               (get-in result [:execution-result/assistant-message :content 0 :text])))
        (is (= :stop (get-in result [:execution-result/assistant-message :stop-reason])))
        (is (inst? (get-in result [:execution-result/assistant-message :timestamp])))
        (is (= :turn.outcome/stop (:execution-result/turn-outcome result)))
        (is (= [] (:execution-result/tool-calls result)))
        (is (= :stop (:execution-result/stop-reason result)))))))

(deftest maybe-install-nullable-execution-mode-stub-generates-turn-id-when-absent-test
  (testing "stub generates a UUID turn-id when prepared-request has no :prepared-request/id"
    (with-redefs [app-runtime/nullable-execution-mode (fn [] "deterministic")]
      (let [ctx (#'app-runtime/maybe-install-nullable-execution-mode {})
            stub (:execute-prepared-request-fn ctx)
            prepared {:prepared-request/user-message
                      {:content [{:type :text :text "no id"}]}}
            result (stub nil nil "s1" prepared nil)]
        (is (string? (:execution-result/turn-id result)))
        (is (parse-uuid (:execution-result/turn-id result)))))))

(deftest maybe-install-nullable-execution-mode-stub-empty-text-when-no-user-message-test
  (testing "stub falls back to empty string when user message text is missing"
    (with-redefs [app-runtime/nullable-execution-mode (fn [] "deterministic")]
      (let [ctx (#'app-runtime/maybe-install-nullable-execution-mode {})
            stub (:execute-prepared-request-fn ctx)
            prepared {:prepared-request/id "turn-99"}
            result (stub nil nil "s2" prepared nil)]
        (is (= "" (get-in result [:execution-result/assistant-message :content 0 :text])))))))



