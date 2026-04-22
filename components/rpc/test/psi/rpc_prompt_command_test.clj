(ns psi.rpc-prompt-command-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime :as prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.state-accessors :as sa]
   [psi.memory.core :as memory]
   [psi.memory.store :as store]
   [psi.rpc :as rpc]
   [psi.rpc-test-support :as support]))

(deftest rpc-prompt-honors-explicit-session-id-test
  (testing "prompt worker uses explicit :session-id routing, even when focus points elsewhere"
    (let [[ctx _a-sid] (support/create-session-context)
          z-sd         (session/new-session-in! ctx _a-sid {})
          z-sid        (:session-id z-sd)
          captured     (atom nil)
          state        (atom {:transport {:ready? true
                                          :pending {}}
                              :connection {:focus-session-id _a-sid}
                              :workers {}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :execute-prepared-request-fn (fn [_ai-ctx _ctx sid _prepared-request _opts]
                                                             (reset! captured sid)
                                                             (support/ok-execution-result sid [{:type :text :text "ok"}]))})
          handler (support/make-handler ctx state)
          input    (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                        (format "{:id \"p1\" :kind :request :op \"prompt\" :params {:session-id \"%s\" :message \"hi\"}}\n" z-sid))]
      (support/run-loop input handler state 300)
      (dotimes [_ 20]
        (when-not @captured
          (Thread/sleep 50)))
      (is (= z-sid @captured)))))

(deftest rpc-prompt-slash-dispatch-gate-test
  (testing "when commands/dispatch-in returns non-nil, execute-prepared-request-fn is NOT called"
    (let [[ctx _]      (support/create-session-context)
          loop-called? (atom false)
          state        (atom {:transport {:ready? true :pending {}}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                             (reset! loop-called? true)
                                                             (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input        (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                            "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                            "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "history output"})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line]
                                   (try (edn/read-string line)
                                        (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?)
              "execute-prepared-request-fn must NOT be called when dispatch returns a command result")
          (is (some? msg-evt)
              "assistant/message event must be emitted for the command result")
          (is (= "assistant"
                 (get-in msg-evt [:data :role]))
              "assistant/message role must be \"assistant\"")
          (is (some #(str/includes? (get % :text "") "history output")
                    (get-in msg-evt [:data :content]))
              "assistant/message content must include command output text")))))

  (testing "when commands/dispatch-in returns nil, execute-prepared-request-fn IS called"
    (let [[ctx _]      (support/create-session-context)
          loop-called? (atom false)
          state        (atom {:transport {:ready? true :pending {}}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                             (reset! loop-called? true)
                                                             (support/ok-execution-result session-id [{:type :text :text "ok"}]))})
          handler (support/make-handler ctx state)
          input        (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                            "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"plain text\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)]
        (support/run-loop input handler state 250)
        (is (true? @loop-called?)
            "execute-prepared-request-fn must be called when dispatch returns nil")))))

(deftest rpc-prompt-expands-skill-input-during-request-preparation-test
  (testing "non-command /skill prompt is expanded during request preparation"
    (let [skill-file   (java.io.File/createTempFile "psi-rpc-skill-" ".md")
          _           (.deleteOnExit skill-file)
          _           (spit skill-file "# Skill Body\nUse this carefully.")
          skill       {:name "demo"
                       :description "Demo skill"
                       :file-path (.getAbsolutePath skill-file)
                       :base-dir (.getParent skill-file)
                       :source :path
                       :disable-model-invocation false}
          [ctx _]     (support/create-session-context {:session-defaults {:skills [skill]}})
          captured    (atom nil)
          bridge      (fn [_ai-ctx _ctx session-id prepared _pq]
                        ;; Capture the user message text from the prepared request.
                        ;; Provider-format messages have {:role :user :content {:kind :text :text "..."}}
                        (let [msgs (:prepared-request/messages prepared)
                              user-msg (first (filter #(= :user (:role %)) msgs))
                              content  (:content user-msg)
                              txt      (cond
                                         (string? content) content
                                         (map? content)    (:text content)
                                         (vector? content) (-> content first :text))]
                          (reset! captured txt))
                        (support/assistant-msg->execution-result session-id
                                                                 {:role "assistant"
                                                                  :content [{:type :text :text "ok"}]
                                                                  :stop-reason :stop}))
          ctx*        (assoc ctx :execute-prepared-request-fn bridge)
          state       (atom {:transport {:ready? true :pending {}}
                             :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          handler     (rpc/make-session-request-handler ctx* (select-keys @state [:rpc-ai-model]))
          input       (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                           "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/skill:demo apply this\"}}\n")]
      (support/run-loop input handler state 250)
      (is (string? @captured))
      (when (string? @captured)
        (is (str/includes? @captured "<skill name=\"demo\""))
        (is (str/includes? @captured "# Skill Body"))
        (is (str/includes? @captured "apply this"))
        (is (not= "/skill:demo apply this" @captured))))))

(deftest rpc-prompt-passes-resolved-api-key-to-agent-loop-test
  (testing "non-command prompt forwards runtime-resolved api-key through prepared request"
    (let [[ctx _]    (support/create-session-context)
          captured   (atom nil)
          state      (atom {:transport {:ready? true :pending {}}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}})
          handler (support/make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"plain text\"}}\n")]
      (with-redefs [runtime/resolve-api-key-in (fn [_ctx _session-id _model] "test-api-key")
                    commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)
                    prompt-runtime/execute-prepared-request!
                    (fn [_ai-ctx _ctx session-id prepared _pq]
                      (reset! captured (:prepared-request/ai-options prepared))
                      {:execution-result/turn-id (:prepared-request/id prepared)
                       :execution-result/session-id session-id
                       :execution-result/assistant-message {:role "assistant"}
                       :content [{:type :text :text "ok"}]
                       :stop-reason :stop
                       :timestamp (java.time.Instant/now)
                       :execution-result/turn-outcome :turn.outcome/stop
                       :execution-result/tool-calls []
                       :execution-result/stop-reason :stop})]
        (support/run-loop input handler state 250)
        (is (= "test-api-key" (:api-key @captured)))))))

(deftest rpc-prompt-handle-command-result-types-test
  (testing "text-command-emits-assistant-message with session/updated and footer/updated"
    (let [[ctx _]    (support/create-session-context)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"session/updated\" \"footer/updated\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "<history output>"})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              topics  (set (map :event events))
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted")
          (is (some #(str/includes? (get % :text "") "<history output>")
                    (get-in msg-evt [:data :content]))
              "assistant/message must contain command output")
          (is (contains? topics "session/updated") "session/updated must be emitted")
          (is (contains? topics "footer/updated") "footer/updated must be emitted")))))

  (testing "extension-cmd-executes-handler and captures stdout"
    (let [[ctx _]    (support/create-session-context)
          loop-called? (atom false)
          received-args (atom nil)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                       (reset! loop-called? true)
                                                       (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/ext-cmd\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type    :extension-cmd
                                            :name    "test-cmd"
                                            :args    "some args"
                                            :handler (fn [args]
                                                       (reset! received-args args)
                                                       (println "ext output"))})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?) "agent loop must NOT be called for extension-cmd")
          (is (= "some args" @received-args)
              "extension handler must receive raw args string")
          (is (some? msg-evt) "assistant/message must be emitted")
          (is (some #(str/includes? (get % :text "") "ext output")
                    (get-in msg-evt [:data :content]))
              "stdout from extension handler must appear in assistant/message")))))

  (testing "extension-cmd-handler-error-surfaced deterministically"
    (let [[ctx _]    (support/create-session-context)
          loop-called? (atom false)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                       (reset! loop-called? true)
                                                       (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/ext-err\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type    :extension-cmd
                                            :name    "err-cmd"
                                            :args    ""
                                            :handler (fn [_] (throw (ex-info "boom" {})))})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (false? @loop-called?) "agent loop must NOT be called on handler error")
          (is (some? msg-evt) "assistant/message must be emitted on error")
          (is (some #(str/includes? (get % :text "") "[extension command error:")
                    (get-in msg-evt [:data :content]))
              "error message must be surfaced in assistant/message")))))

  (testing "login-start-emits-url-text"
    (let [[ctx _]    (support/create-session-context)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type     :login-start
                                            :provider {:name "Anthropic"}
                                            :url      "https://example.com/auth"})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for login-start")
          (is (some #(str/includes? (get % :text "") "https://example.com/auth")
                    (get-in msg-evt [:data :content]))
              "URL must appear in assistant/message content")))))

  (testing "login-start manual flow uses pending-code path and shared oauth completion"
    (let [[ctx _]      (support/create-session-context {:oauth-ctx {:mode :test}})
          loop-called? (atom false)
          dispatches   (atom [])
          completions  (atom [])
          state        (atom {:transport {:ready? true :pending {}}
                              :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                              :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                             (reset! loop-called? true)
                                                             (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)]
      (with-redefs [commands/dispatch-in
                    (fn [_ctx _session-id text _opts]
                      (swap! dispatches conj text)
                      (when (= text "/login")
                        {:type                 :login-start
                         :provider             {:id :anthropic :name "Anthropic"}
                         :url                  "https://example.com/auth"
                         :login-state          {:verifier "v1"}
                         :uses-callback-server false}))
                    oauth/complete-login!
                    (fn [_oauth-ctx provider-id input login-state]
                      (swap! completions conj {:provider-id provider-id
                                               :input input
                                               :login-state login-state})
                      {:type :oauth :access "tok" :refresh "ref" :expires (+ (System/currentTimeMillis) 60000)})]
        (support/run-loop "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n"
                          handler state 250)
        (is (some? (:pending-login (sa/oauth-projection-in ctx)))
            "manual login-start should set canonical pending-login state")

        (support/run-loop "{:id \"p2\" :kind :request :op \"prompt\" :params {:message \"auth-code-123\"}}\n"
                          handler state 250)

        (is (= [{:provider-id :anthropic
                 :input "auth-code-123"
                 :login-state {:verifier "v1"}}]
               @completions)
            "pending auth code prompt should complete login via oauth/complete-login!")
        (is (nil? (:pending-login (sa/oauth-projection-in ctx)))
            "pending-login should clear from canonical oauth state after completion attempt")
        (is (= ["/login"] @dispatches)
            "second prompt must bypass commands/dispatch-in while login is pending")
        (is (false? @loop-called?)
            "agent loop must not run during manual login completion"))))

  (testing "login-start callback flow auto-completes with nil input"
    (let [[ctx _]      (support/create-session-context {:oauth-ctx {:mode :test}})
          loop-called? (atom false)
          completions  (atom [])
          state        (atom {:transport {:ready? true :pending {}}
                              :rpc-ai-model {:provider "openai" :id "stub" :supports-reasoning true}
                              :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts]
                                                             (reset! loop-called? true)
                                                             (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)]
      (with-redefs [commands/dispatch-in
                    (fn [_ctx _session-id text _opts]
                      (when (= text "/login")
                        {:type                 :login-start
                         :provider             {:id :openai :name "OpenAI"}
                         :url                  "https://example.com/openai"
                         :login-state          {:state "s1"}
                         :uses-callback-server true}))
                    oauth/complete-login!
                    (fn [_oauth-ctx provider-id input login-state]
                      (swap! completions conj {:provider-id provider-id
                                               :input input
                                               :login-state login-state})
                      {:type :oauth :access "tok" :refresh "ref" :expires (+ (System/currentTimeMillis) 60000)})]
        (let [{:keys [out-lines]} (support/run-loop "{:id \"p3\" :kind :request :op \"prompt\" :params {:message \"/login\"}}\n"
                                                    handler state 800)
              events (->> out-lines support/parse-frames (filter #(= :event (:kind %)))
                          (filter #(= "assistant/message" (:event %))))
              texts  (mapcat #(map :text (get-in % [:data :content])) events)]
          (is (= [{:provider-id :openai
                   :input nil
                   :login-state {:state "s1"}}]
                 @completions)
              "callback login-start should complete with nil input and wait for callback server")
          (is (nil? (:pending-login (sa/oauth-projection-in ctx)))
              "callback flow should not leave canonical pending-login state")
          (is (false? @loop-called?)
              "agent loop must not run during callback login completion")
          (is (some #(str/includes? % "Waiting for browser callback") texts)
              "callback flow should emit waiting status text")
          (is (some #(str/includes? % "Logged in to OpenAI") texts)
              "callback flow should emit completion text")))))

  (testing "quit-emits-fallback-text"
    (let [[ctx _]    (support/create-session-context)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/quit\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :quit})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for quit")
          (is (some #(str/includes? (get % :text "") "not supported over RPC prompt")
                    (get-in msg-evt [:data :content]))
              "fallback text must appear in assistant/message content")))))

  (testing "resume-emits-fallback-text"
    (let [[ctx _]    (support/create-session-context)
          state  (atom {:transport {:ready? true :pending {}}
                        :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                        :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/resume\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :resume})]
        (let [{:keys [out-lines]} (support/run-loop input handler state 250)
              frames  (->> out-lines
                           (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                           vec)
              events  (filter #(= :event (:kind %)) frames)
              msg-evt (some #(when (= "assistant/message" (:event %)) %) events)]
          (is (some? msg-evt) "assistant/message must be emitted for resume")
          (is (some #(str/includes? (get % :text "") "not supported over RPC prompt")
                    (get-in msg-evt [:data :content]))
              "fallback text must appear in assistant/message content")))))

  (testing "remember accepted path emits confirmation and writes exactly one record"
    (let [[ctx* _] (support/create-session-context)
          ctx     (assoc ctx*
                         :memory-ctx
                         (memory/create-context {:state-overrides {:status :ready}}))
          state   (atom {:transport {:ready? true :pending {}}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          before-count (count (:records (memory/get-state-in (:memory-ctx ctx))))
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember rpc-accepted\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)
          after-state (memory/get-state-in (:memory-ctx ctx))
          after-count (count (:records after-state))
          rec (last (:records after-state))
          prov (:provenance rec)
          telemetry (session/query-in ctx
                                      [:psi.memory.remember/captures
                                       :psi.memory.remember/last-capture-at])]
      (is (some #(str/includes? % "Remembered") texts))
      (is (= 1 (- after-count before-count)) "exactly one remember record per RPC invocation")
      (is (= "rpc-accepted" (:content rec)))
      (is (= :remember (get-in rec [:provenance :source])))
      (is (string? (:sessionId prov)))
      (is (= (:cwd ctx) (:cwd prov)))
      (is (contains? prov :gitBranch))
      (is (some #(= (:record-id rec) (:record-id %))
                (:psi.memory.remember/captures telemetry))
          "captured remember record should be visible in remember telemetry captures")
      (is (= (:timestamp rec) (:psi.memory.remember/last-capture-at telemetry)))))

  (testing "remember emits canonical blocked error when memory is not ready"
    (let [[ctx* _] (support/create-session-context)
          ctx     (assoc ctx*
                         :memory-ctx
                         (memory/create-context
                          {:state-overrides {:status :initializing}}))
          state   (atom {:transport {:ready? true :pending {}}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember blocked-path\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)]
      (is (some #(str/includes? % "Remember blocked") texts))
      (is (some #(str/includes? % "memory_capture_prerequisites_not_ready") texts))))

  (testing "remember emits fallback warning when active store write fails"
    (let [[ctx* _]    (support/create-session-context)
          ctx         (assoc ctx*
                             :memory-ctx
                             (memory/create-context {:state-overrides {:status :ready}}))
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
          state   (atom {:transport {:ready? true :pending {}}
                         :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                         :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\"]}}\n"
                       "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/remember provider-outage\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state 300)
          frames   (->> out-lines
                        (keep (fn [line] (try (edn/read-string line) (catch Throwable _ nil))))
                        vec)
          events   (filter #(= :event (:kind %)) frames)
          msg-evts (filter #(= "assistant/message" (:event %)) events)
          texts    (mapcat #(map :text (get-in % [:data :content])) msg-evts)]
      (is (some #(str/includes? % "Remembered with store fallback") texts))
      (is (some #(str/includes? % "store-error: boom") texts))
      (is (some #(str/includes? % "provider: failing-store") texts)))))

(deftest rpc-prompt-slash-command-journaled-test
  (testing "slash command user message is journaled even when dispatch matches (not only on agent-loop path)"
    (let [[ctx session-id] (support/create-session-context)
          state      (atom {:transport {:ready? true :pending {}}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                            :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id))})
          handler (support/make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"/history\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts]
                                           {:type :text :message "history output"})]
        (support/run-loop input handler state 250)
        (let [journal-entries (persist/all-entries-in ctx session-id)
              msg-entries     (filterv #(= :message (:kind %)) journal-entries)
              user-msg        (some #(when (= "user" (get-in % [:data :message :role])) %) msg-entries)]
          (is (seq msg-entries)
              "journal must contain at least one :message entry after slash command")
          (is (some? user-msg)
              "journal must contain the user message for the slash command submission")
          (is (= "/history"
                 (get-in user-msg [:data :message :content 0 :text]))
              "journaled user message must contain the slash command text"))))))

(deftest rpc-prompt-plain-text-journaled-test
  (testing "plain text prompt user message is journaled on agent-loop path"
    (let [[ctx session-id] (support/create-session-context)
          state      (atom {:transport {:ready? true :pending {}}
                            :rpc-ai-model {:provider "anthropic" :id "stub" :supports-reasoning true}
                            :execute-prepared-request-fn (fn [_ai-ctx _ctx session-id _prepared-request _opts] (support/ok-execution-result session-id [{:type :text :text "ok"}]))})
          handler (support/make-handler ctx state)
          input      (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                          "{:id \"p1\" :kind :request :op \"prompt\" :params {:message \"tell me a joke\"}}\n")]
      (with-redefs [commands/dispatch-in (fn [_ctx _session-id _text _opts] nil)]
        (support/run-loop input handler state 250)
        (let [journal-entries (persist/all-entries-in ctx session-id)
              msg-entries     (filterv #(= :message (:kind %)) journal-entries)
              user-msg        (some #(when (= "user" (get-in % [:data :message :role])) %) msg-entries)]
          (is (some? user-msg)
              "journal must contain the user message for plain text prompt")
          (is (= "tell me a joke"
                 (get-in user-msg [:data :message :content 0 :text]))
              "journaled user message must contain the prompt text"))))))
