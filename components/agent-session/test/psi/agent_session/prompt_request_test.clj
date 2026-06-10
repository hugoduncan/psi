(ns psi.agent-session.prompt-request-test
  "Tests for prompt-request auth injection with custom providers."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.session-persistence.core :as persist]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.turn-runtime.conversation :as conversation]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (model-registry/init! {})))))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(defn- session-data-for [provider-kw model-id]
  {:model {:provider provider-kw :id model-id}
   :thinking-level :off})

;; ── Auth injection tests ─────────────────────────────────────────────────────

(deftest custom-provider-api-key-injected-test
  (let [path (write-temp-models!
              {:version   1
               :providers {"local"
                           {:base-url "http://localhost:8080/v1"
                            :api      :openai-completions
                            :auth     {:api-key "my-local-key"}
                            :models   [{:id "test-model"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "custom provider api-key injected into options"
        (let [opts (prompt-request/session->request-options
                    {} ;; empty ctx
                    (session-data-for :local "test-model")
                    {})]
          (is (= "my-local-key" (:api-key opts)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest auth-header-disabled-test
  (let [path (write-temp-models!
              {:version   1
               :providers {"ollama"
                           {:base-url "http://localhost:11434/v1"
                            :api      :openai-completions
                            :auth     {:api-key "ollama" :auth-header? false}
                            :models   [{:id "llama"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "auth-header? false sets :no-auth-header and omits api-key for keyword provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :ollama "llama")
                    {})]
          (is (true? (:no-auth-header opts)))
          ;; api-key should NOT be injected because auth-header? is false
          (is (nil? (:api-key opts)))))

      (testing "auth-header? false sets :no-auth-header and omits api-key for live session string provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model {:provider "ollama" :id "llama"}
                     :thinking-level :off}
                    {})]
          (is (true? (:no-auth-header opts)))
          (is (nil? (:api-key opts)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest custom-headers-propagated-test
  (let [path (write-temp-models!
              {:version   1
               :providers {"custom"
                           {:base-url "http://example.com/v1"
                            :api      :openai-completions
                            :auth     {:api-key      "key123"
                                       :headers      {"X-Custom" "value"
                                                      "X-Project" "psi"}}
                            :models   [{:id "model-a"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "custom headers merged into options for keyword provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :custom "model-a")
                    {})]
          (is (= {"X-Custom" "value" "X-Project" "psi"}
                 (:headers opts)))
          (is (= "key123" (:api-key opts)))))

      (testing "custom headers merged into options for live session string provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model {:provider "custom" :id "model-a"}
                     :thinking-level :off}
                    {})]
          (is (= {"X-Custom" "value" "X-Project" "psi"}
                 (:headers opts)))
          (is (= "key123" (:api-key opts)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest runtime-opts-api-key-takes-priority-test
  (let [path (write-temp-models!
              {:version   1
               :providers {"local"
                           {:base-url "http://localhost:8080/v1"
                            :api      :openai-completions
                            :auth     {:api-key "registry-key"}
                            :models   [{:id "test-model"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "explicit runtime-opts api-key wins over registry"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :local "test-model")
                    {:api-key "explicit-key"})]
          (is (= "explicit-key" (:api-key opts)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest custom-anthropic-compatible-provider-api-key-injected-test
  (let [path (write-temp-models!
              {:version   1
               :providers {"minimax"
                           {:base-url "https://api.minimax.io/anthropic"
                            :api      :anthropic-messages
                            :auth     {:api-key "minimax-inline-key"}
                            :models   [{:id "MiniMax-M2.7"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "custom anthropic-compatible provider injects provider-scoped api-key for keyword provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :minimax "MiniMax-M2.7")
                    {})]
          (is (= "minimax-inline-key" (:api-key opts)))
          (is (nil? (:no-auth-header opts)))
          (is (nil? (:headers opts)))))

      (testing "custom anthropic-compatible provider injects provider-scoped api-key for live session string provider identity"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model {:provider "minimax" :id "MiniMax-M2.7"}
                     :thinking-level :off}
                    {})]
          (is (= "minimax-inline-key" (:api-key opts)))
          (is (nil? (:no-auth-header opts)))
          (is (nil? (:headers opts)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest built-in-provider-no-auth-injection-test
  (model-registry/init! {})

  (testing "built-in provider without registry auth gets no injection"
    (let [opts (prompt-request/session->request-options
                {}
                (session-data-for :anthropic "claude-sonnet-4-6")
                {})]
      ;; No api-key from registry (only from oauth/env in real runtime)
      (is (nil? (:api-key opts)))
      (is (nil? (:no-auth-header opts)))
      (is (nil? (:headers opts))))))

(deftest journal->provider-messages-repairs-dangling-tool-use-test
  (testing "missing tool result is repaired with synthetic error toolResult before later messages"
    (let [assistant {:role "assistant"
                     :content [{:type :text :text "working"}
                               {:type :tool-call :id "call-1" :name "bash" :arguments "{}"}]
                     :timestamp #inst "2026-05-14T13:28:43.762-00:00"}
          later-user {:role "user"
                      :content [{:type :text :text "status?"}]}
          messages (prompt-request/journal->provider-messages
                    [(persist/message-entry assistant)
                     (persist/message-entry later-user)])]
      (is (= ["assistant" "toolResult" "user"] (mapv :role messages)))
      (is (= "call-1" (:tool-call-id (second messages))))
      (is (true? (:is-error (second messages))))
      (is (= "Tool execution interrupted before completion."
             (get-in (second messages) [:content 0 :text])))))

  (testing "existing contiguous tool result is preserved and no synthetic repair is added"
    (let [assistant {:role "assistant"
                     :content [{:type :tool-call :id "call-1" :name "bash" :arguments "{}"}]
                     :timestamp #inst "2026-05-14T13:28:43.762-00:00"}
          result    {:role "toolResult"
                     :tool-call-id "call-1"
                     :tool-name "bash"
                     :content [{:type :text :text "done"}]}
          later-user {:role "user"
                      :content [{:type :text :text "status?"}]}
          messages  (prompt-request/journal->provider-messages
                     [(persist/message-entry assistant)
                      (persist/message-entry result)
                      (persist/message-entry later-user)])]
      (is (= ["assistant" "toolResult" "user"] (mapv :role messages)))
      (is (= "done" (get-in (second messages) [:content 0 :text]))))))

(deftest journal->provider-messages-projects-mid-system-test
  ;; Mid-system journal entries project to inline system provider messages and
  ;; metadata entries between user and mid-system are ignored by provider projection.
  (testing "journal->provider-messages-projects-mid-system"
    (let [user-entry {:kind :message
                      :data {:message {:role "user"
                                       :content [{:type :text :text "question"}]}}}
          metadata-entry {:kind :thinking-level
                          :data {:thinking-level :high}}
          mid-system-entry {:kind :mid-system
                            :data {:text "Use short answers." :source :test}}
          messages (prompt-request/journal->provider-messages
                    [user-entry metadata-entry mid-system-entry])]
      (is (= ["user" "system"] (mapv :role messages)))
      (is (= [{:type :text :text "Use short answers."}]
             (:content (second messages)))))))

(deftest replace-current-user-message-preserves-pending-mid-system-tail-test
  ;; Prepared-turn assembly replaces the current user even when a pending
  ;; mid-system message is attached after it.
  (testing "replace-current-user-message-preserves-pending-mid-system-tail"
    (let [base-messages [{:role "assistant"
                          :content [{:type :text :text "previous"}]}
                         {:role "user"
                          :content [{:type :text :text "raw"}]}
                         {:role "system"
                          :content [{:type :text :text "Use short answers."}]}]
          expanded-user {:role "user"
                         :content [{:type :text :text "expanded"}]}
          messages (#'prompt-request/replace-current-user-message
                    base-messages
                    expanded-user)]
      (is (= ["assistant" "user" "system"] (mapv :role messages)))
      (is (= "expanded" (get-in messages [1 :content 0 :text])))
      (is (= "Use short answers." (get-in messages [2 :content 0 :text]))))))

(deftest tail-dangling-tool-result-repairs-test
  (testing "returns repair for dangling trailing assistant tool call"
    (let [assistant {:role "assistant"
                     :content [{:type :tool-call :id "call-tail" :name "bash" :arguments "{}"}]
                     :timestamp #inst "2026-05-14T13:28:43.762-00:00"}
          repairs   (prompt-request/tail-dangling-tool-result-repairs [assistant])]
      (is (= 1 (count repairs)))
      (is (= "call-tail" (:tool-call-id (first repairs))))
      (is (true? (:is-error (first repairs))))))

  (testing "returns no repair when trailing tool result already exists"
    (let [assistant {:role "assistant"
                     :content [{:type :tool-call :id "call-tail" :name "bash" :arguments "{}"}]
                     :timestamp #inst "2026-05-14T13:28:43.762-00:00"}
          result    {:role "toolResult"
                     :tool-call-id "call-tail"
                     :tool-name "bash"
                     :content [{:type :text :text "done"}]}
          repairs   (prompt-request/tail-dangling-tool-result-repairs [assistant result])]
      (is (= [] repairs)))))

(deftest build-prepared-request-expands-skill-invocation-into-user-message-test
  (testing "skill expansion resolves from root-registry-backed session skill ids"
    (let [skill {:name "lambda-compiler"
                 :description "Compile lambda expressions"
                 :file-path "components/agent-session/test/psi/agent_session/prompt_request_test.clj"
                 :base-dir "/tmp"
                 :source :project
                 :disable-model-invocation false}
          session-id "sid-1"
          state {:agent-session {:sessions {session-id {:data {:session-id session-id
                                                               :skill-ids ["lambda-compiler"]
                                                               :messages []
                                                               :thinking-level :off
                                                               :model {:provider :openai :id "gpt-4.1"}}}}}}
          state* (atom (:root-state (skill-storage/set-skills-in-root-state state session-id [skill])))
          ctx {:state* state*}
          prepared (prompt-request/build-prepared-request
                    ctx
                    session-id
                    {:user-message {:role "user"
                                    :content [{:type :text :text "/skill:lambda-compiler"}]}
                     :runtime-opts {}})]
      (is (= :skill (get-in prepared [:prepared-request/input-expansion :kind])))
      (is (= "lambda-compiler" (get-in prepared [:prepared-request/input-expansion :name])))
      (is (= "user" (-> prepared :prepared-request/user-message :role)))
      (is (re-find #"lambda-compiler" (-> prepared :prepared-request/user-message :content first :text))))))

;; ── Temperature projection ──────────────────────────────────────────────────

(deftest session->request-options-temperature-absent-test
  (testing "temperature key absent from options when not set on session"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"} :thinking-level :off}
          opts (prompt-request/session->request-options {} sd {})]
      (is (not (contains? opts :temperature))))))

(deftest session->request-options-temperature-nil-test
  (testing "nil :temperature key present in session-data does not inject :temperature into request options"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"}
                :thinking-level :off
                :temperature nil}
          opts (prompt-request/session->request-options {} sd {})]
      (is (not (contains? opts :temperature))))))

(deftest session->request-options-temperature-present-test
  (testing "explicit temperature projected into request options"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"}
                :thinking-level :off
                :temperature 0.0}
          opts (prompt-request/session->request-options {} sd {})]
      (is (= 0.0 (:temperature opts)))))

  (testing "non-zero temperature projected into request options"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"}
                :thinking-level :off
                :temperature 1.5}
          opts (prompt-request/session->request-options {} sd {})]
      (is (= 1.5 (:temperature opts))))))

(deftest session-effort-override-propagates-to-request-options-test
  ;; session->request-options includes effort overrides only when present.
  (is (not (contains? (prompt-request/session->request-options
                       nil
                       {:model {:provider :anthropic}
                        :effort-override nil}
                       {})
                      :effort-override)))
  (is (= :xhigh
         (:effort-override (prompt-request/session->request-options
                            nil
                            {:model {:provider :anthropic}
                             :effort-override :xhigh}
                            {})))))

(deftest session-speed-mode-propagates-to-request-options-test
  ;; Speed mode is canonical session data and is only projected when an override is present.
  (testing "speed mode is omitted when session state is nil"
    (is (not (contains? (prompt-request/session->request-options
                         {}
                         {:model {:provider "openai" :id "gpt-4.1"}
                          :thinking-level :off
                          :speed-mode nil}
                         {})
                        :speed-mode))))

  (testing "speed mode propagates when set"
    (is (= :fast
           (:speed-mode (prompt-request/session->request-options
                         {}
                         {:model {:provider "openai" :id "gpt-4.1"}
                          :thinking-level :off
                          :speed-mode :fast}
                         {}))))))

;; ── Defensive projection de-dup (already-wedged journal recovery) ────────────

(defn- assistant-tool-use-entry [tool-call-id]
  (persist/message-entry
   {:role "assistant"
    :content [{:type :tool-call :id tool-call-id :name "bash" :arguments "{}"}]}))

(defn- tool-result-entry [tool-call-id text]
  (persist/message-entry
   {:role "toolResult"
    :tool-call-id tool-call-id
    :tool-name "bash"
    :content [{:type :text :text text}]
    :is-error false}))

(defn- user-entry [text]
  (persist/message-entry {:role "user" :content [{:type :text :text text}]}))

(defn- rebuilt-tool-result-count [messages tool-call-id]
  (->> (:messages
        (conversation/agent-messages->ai-conversation "sys" messages [] {}))
       (filter #(and (= :tool-result (:role %))
                     (= tool-call-id (:tool-call-id %))))
       count))

(deftest journal-duplicate-tool-results-project-to-one-test
  (testing "a journal with duplicate toolResult entries for one tool-call-id —
            both a non-contiguous duplicate (separated from its assistant
            tool-use by an intervening message, so repair would synthesize a
            second result) and a contiguous duplicate — projects to exactly one
            provider tool_result per id through the conversation rebuild,
            recovering an already-wedged session"
    (let [journal  [;; non-contiguous: real result separated from its tool-use
                    (assistant-tool-use-entry "id-noncontig")
                    (user-entry "interleaved")
                    (tool-result-entry "id-noncontig" "real-noncontig")
                    ;; contiguous: two adjacent results for one id
                    (assistant-tool-use-entry "id-contig")
                    (tool-result-entry "id-contig" "first-contig")
                    (tool-result-entry "id-contig" "dup-contig")]
          messages (prompt-request/journal->provider-messages journal)]
      (is (= 1 (rebuilt-tool-result-count messages "id-noncontig"))
          "non-contiguous duplicate de-duped after repair (would be two without
           de-dup-after-repair)")
      (is (= 1 (rebuilt-tool-result-count messages "id-contig"))
          "contiguous duplicate de-duped"))))
