(ns psi.agent-session.prompt-request-test
  "Tests for prompt-request auth injection with custom providers."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.turn-runtime.augmentation :as turn-augmentation]
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

(defn- anthropic-oauth-ctx [api-key]
  {:oauth-ctx
   (oauth/create-null-context
    {:credentials {:anthropic {:type :api-key :key api-key}}})})

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

(deftest built-in-session-never-inherits-custom-same-named-provider-auth-test
  ;; Review 42: `provider-auth/provider-api-key` + `provider-request-options`
  ;; resolved registry auth purely by provider NAME, and
  ;; `session->request-options`/`resolve-api-key` consumed them without the
  ;; session model's `:custom?` origin gate — so a custom models.edn provider
  ;; literally named "anthropic" made the BUILT-IN same-named model session
  ;; inherit the custom provider's auth config (headers / :no-auth-header /
  ;; api-key spec). Registry-auth options/key resolution is now gated on the
  ;; session model's `:custom?` origin: built-in models resolve only env/OAuth.
  (let [path-headers (write-temp-models!
                      {:version   1
                       :providers {"anthropic"
                                   {:base-url "https://third-party.example/anthropic"
                                    :api      :anthropic-messages
                                    :auth     {:headers {"x-api-key" "THIRD-PARTY-KEY"}}
                                    :models   [{:id "my-custom-model"}]}}})
        path-key (write-temp-models!
                  {:version   1
                   :providers {"anthropic"
                               {:base-url "https://third-party.example/anthropic"
                                :api      :anthropic-messages
                                :auth     {:api-key "env:MY_THIRD_PARTY_KEY"}
                                :models   [{:id "my-custom-model"}]}}})]
    (try
      (testing "headers/:no-auth-header variant — built-in claude session inherits nothing"
        (model-registry/init! {:user-models-path path-headers})
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :anthropic "claude-sonnet-4-6")
                    {})]
          (is (nil? (:api-key opts))
              "the custom provider's registry auth is never resolved for the built-in model")
          (is (nil? (:no-auth-header opts))
              "the custom provider's :no-auth-header hint is not inherited")
          (is (nil? (:headers opts))
              "the custom provider's x-api-key header is not merged onto the built-in request")))

      (testing "api-key variant — built-in claude session carries no custom provider key spec"
        (model-registry/init! {:user-models-path path-key})
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :anthropic "claude-sonnet-4-6")
                    {})]
          (is (nil? (:api-key opts))
              "the custom provider's env:MY_THIRD_PARTY_KEY spec is never injected into the built-in request")
          (is (nil? (:no-auth-header opts)))
          (is (nil? (:headers opts)))))

      (testing "the custom same-named model still resolves its own registry auth (positive control)"
        (model-registry/init! {:user-models-path path-headers})
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :anthropic "my-custom-model")
                    {})]
          (is (= {"x-api-key" "THIRD-PARTY-KEY"} (:headers opts))
              "the custom provider's own session still gets its registry headers")))

      (finally
        (java.io.File/.delete (java.io.File. path-headers))
        (java.io.File/.delete (java.io.File. path-key))))))

(deftest provider-switch-never-reuses-stale-runtime-api-key-test
  ;; Review 35: `:runtime-api-key` is stored per-session, unscoped, at prompt
  ;; prepare; prompt_request/resolve-api-key gave it priority 2 ABOVE the
  ;; current provider's own provider-auth/provider-api-key, and neither
  ;; :session/set-model nor :session/apply-session-profile cleared or scoped
  ;; it — so a mid-session /model provider switch (A → B) injected A's raw
  ;; spec/literal key (or OAuth token) into B's request options, sending A's
  ;; live credential to B's endpoint. The stored key is now recorded with
  ;; `:runtime-api-key-provider` and reused only when it still matches the
  ;; session's current model provider.
  ;;
  ;; Review 36: the reuse check additionally requires the built-in/custom
  ;; ORIGIN to match (`:runtime-api-key-custom?` recorded at prepare vs the
  ;; session model's registry `:custom?` tag) — a custom models.edn provider
  ;; literally named "anthropic"/"openai" can no longer reuse a key recorded
  ;; for the built-in same-named origin — and the stored key is reused only
  ;; when it still equals the current provider-auth resolution (a models.edn
  ;; `:auth` change wins over the stale stored spec; OAuth stability is
  ;; preserved because provider-auth re-resolves the same token).
  (let [path (write-temp-models!
              {:version   1
               :providers {"deepseek"
                           {:base-url "https://api.deepseek.com/anthropic"
                            :api      :anthropic-messages
                            :auth     {:api-key "deepseek-registry-key"}
                            :models   [{:id "deepseek-v4-flash"}]}
                           "minimax"
                           {:base-url "https://api.minimax.io/anthropic"
                            :api      :anthropic-messages
                            :auth     {:api-key "minimax-registry-key"}
                            :models   [{:id "MiniMax-M2.7"}]}
                           ;; review 36: a custom provider literally named
                           ;; "anthropic" — tagged :custom? true by
                           ;; expand-model (review 14), same session provider
                           ;; string as the built-in.
                           "anthropic"
                           {:base-url "https://third-party.example/anthropic"
                            :api      :anthropic-messages
                            :auth     {:api-key "custom-anthropic-key"}
                            :models   [{:id "my-custom-model"}]}}})]
    (try
      (model-registry/init! {:user-models-path path})

      (testing "cross-provider stale key is never reused — deepseek registry auth wins"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model               {:provider "deepseek" :id "deepseek-v4-flash"}
                     :thinking-level      :off
                     ;; stale from a prior minimax turn (recorded provider)
                     :runtime-api-key        "env:MINIMAX_API_KEY"
                     :runtime-api-key-provider "minimax"}
                    {})]
          (is (= "deepseek-registry-key" (:api-key opts))
              "the new provider's own registry auth resolves, not the prior provider's stale key spec")))

      (testing "unscoped legacy stored key (no recorded provider) is never reused"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model          {:provider "deepseek" :id "deepseek-v4-flash"}
                     :thinking-level :off
                     ;; legacy session data predating :runtime-api-key-provider
                     :runtime-api-key "env:MINIMAX_API_KEY"}
                    {})]
          (is (= "deepseek-registry-key" (:api-key opts))
              "without a recorded provider we cannot prove ownership — fall through to provider auth")))

      (testing "same-provider stored key is still reused when it equals the current OAuth resolution (OAuth stability intent)"
        (let [opts (prompt-request/session->request-options
                    (anthropic-oauth-ctx "sk-ant-oat-runtime-token")
                    {:model                    {:provider "anthropic" :id "claude-sonnet-4-6"}
                     :thinking-level           :off
                     :runtime-api-key          "sk-ant-oat-runtime-token"
                     :runtime-api-key-provider "anthropic"
                     :runtime-api-key-custom?  false}
                    {})]
          (is (= "sk-ant-oat-runtime-token" (:api-key opts))
              "a key recorded for the CURRENT provider+origin that still equals the current OAuth resolution keeps working across turns")))

      (testing "custom provider named anthropic never reuses a stored built-in origin OAuth token"
        (let [opts (prompt-request/session->request-options
                    {}
                    {:model                    {:provider "anthropic" :id "my-custom-model"}
                     :thinking-level           :off
                     ;; recorded on the BUILT-IN anthropic origin (a prior
                     ;; OAuth turn): provider string matches, origin does not
                     :runtime-api-key          "sk-ant-oat-builtin-oauth-token"
                     :runtime-api-key-provider "anthropic"
                     :runtime-api-key-custom?  false}
                    {})]
          (is (= "custom-anthropic-key" (:api-key opts))
              "the built-in origin OAuth token is NOT reused for the custom origin — the custom provider's own registry auth resolves")))

      (testing "custom provider named anthropic with NO resolvable auth never reuses a stored built-in origin OAuth token"
        ;; The discriminating origin-gate case: reload the same-named custom
        ;; provider as explicitly keyless, so the real provider-auth path
        ;; resolves nil. Without the origin check, the stored built-in token
        ;; would fill that gap and leak to the third-party endpoint.
        (let [keyless-path (write-temp-models!
                            {:version 1
                             :providers {"anthropic"
                                         {:base-url "https://third-party.example/anthropic"
                                          :api :anthropic-messages
                                          :auth {:auth-header? false}
                                          :models [{:id "my-custom-model"}]}}})]
          (try
            (model-registry/init! {:user-models-path keyless-path})
            (let [opts (prompt-request/session->request-options
                        (anthropic-oauth-ctx "sk-ant-oat-builtin-oauth-token")
                        {:model                    {:provider "anthropic" :id "my-custom-model"}
                         :thinking-level           :off
                         :runtime-api-key          "sk-ant-oat-builtin-oauth-token"
                         :runtime-api-key-provider "anthropic"
                         :runtime-api-key-custom?  false}
                        {})]
              (is (nil? (:api-key opts))
                  "the built-in origin OAuth token is NOT reused for the keyless custom origin"))
            (finally
              (java.io.File/.delete (java.io.File. keyless-path))
              (model-registry/init! {:user-models-path path})))))

      (testing "built-in anthropic never reuses a stored custom-origin raw spec"
        (let [opts (prompt-request/session->request-options
                    (anthropic-oauth-ctx "sk-ant-oat-builtin-token")
                    {:model                    {:provider "anthropic" :id "claude-sonnet-4-6"}
                     :thinking-level           :off
                     ;; recorded on the CUSTOM "anthropic" origin (a prior
                     ;; turn against the third-party provider)
                     :runtime-api-key          "env:CUSTOM_ANTHROPIC_KEY"
                     :runtime-api-key-provider "anthropic"
                     :runtime-api-key-custom?  true}
                    {})]
          (is (= "sk-ant-oat-builtin-token" (:api-key opts))
              "the custom-origin raw spec is NOT reused for the built-in origin — the built-in's own current resolution (OAuth token) wins")))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest registry-auth-change-wins-over-stale-stored-key-test
  ;; Review 36: the same-provider stored `:runtime-api-key` is a
  ;; self-perpetuating fixed point — priority-2 reuse re-recorded the stored
  ;; RAW spec each prepare, so a models.edn `:auth` change + /reload-models
  ;; never reached an existing session (old env: var name pinned). The stored
  ;; key is now reused only when it still equals the current provider-auth
  ;; resolution, so a registry `:auth` change wins over the stale stored spec.
  (let [path-old (write-temp-models!
                  {:version   1
                   :providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :auth     {:api-key "env:DEEPSEEK_OLD_VAR"}
                                :models   [{:id "deepseek-v4-flash"}]}}})
        path-new (write-temp-models!
                  {:version   1
                   :providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :auth     {:api-key "env:DEEPSEEK_NEW_VAR"}
                                :models   [{:id "deepseek-v4-flash"}]}}})
        session-data {:model                    {:provider "deepseek" :id "deepseek-v4-flash"}
                      :thinking-level           :off
                      ;; stored spec from a prior turn while the registry
                      ;; still pointed at OLD_VAR
                      :runtime-api-key          "env:DEEPSEEK_OLD_VAR"
                      :runtime-api-key-provider "deepseek"
                      :runtime-api-key-custom?  true}]
    (try
      (model-registry/init! {:user-models-path path-old})

      (testing "stored key equal to the current registry spec is still reused"
        (let [opts (prompt-request/session->request-options {} session-data {})]
          (is (= "env:DEEPSEEK_OLD_VAR" (:api-key opts)))))

      ;; simulate a models.edn :auth edit + /reload-models between turns
      (model-registry/init! {:user-models-path path-new})

      (testing "registry :auth change wins over the stale stored spec"
        (let [opts (prompt-request/session->request-options {} session-data {})]
          (is (= "env:DEEPSEEK_NEW_VAR" (:api-key opts))
              "the current registry auth resolves, not the stale stored env: spec")))

      (finally
        (java.io.File/.delete (java.io.File. path-old))
        (java.io.File/.delete (java.io.File. path-new))))))

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

(defn- rebuilt-tool-results [messages tool-call-id]
  (->> (:messages
        (conversation/agent-messages->ai-conversation "sys" messages [] {}))
       (filter #(and (= :tool-result (:role %))
                     (= tool-call-id (:tool-call-id %))))))

(defn- rebuilt-tool-result-count [messages tool-call-id]
  (count (rebuilt-tool-results messages tool-call-id)))

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
          "contiguous duplicate de-duped")
      (is (= "first-contig"
             (-> (rebuilt-tool-results messages "id-contig")
                 first :content :text))
          "first occurrence wins (kept first-contig, not dup-contig)"))))

;; ── Turn augmentation request rendering ─────────────────────────────────────

(defn- augmentation-record
  [session-id turn-id operations]
  {:session-id session-id
   :turn-id turn-id
   :status (if (seq operations) :success :no-op)
   :replay? false
   :accepted-operation-count (count operations)
   :operations operations
   :providers [{:extension-id "manifest:psi/context-manager"
                :augmenter-id "project-context"
                :status (if (seq operations) :success :no-op)
                :operation-count (count operations)
                :accepted-operation-count (count operations)
                :rejected-operation-count 0
                :child-session-ids []
                :reasons []}]})

(defn- context-operation
  [id title content]
  {:op :append-context-block
   :id id
   :title title
   :content content
   :source {:type :extension
            :extension-id "manifest:psi/context-manager"
            :augmenter-id "project-context"
            :child-session-ids []}})

(deftest turn-augmentation-record-well-formedness-test
  ;; Tests the canonical record predicate shared by live prepare and replay.
  (testing "accepts failed and no-op terminal records when counts and shapes are coherent"
    (is (true? (turn-augmentation/well-formed-record?
                "s" "t"
                (assoc (augmentation-record "s" "t" []) :status :failed)))))

  (testing "rejects open, wrong-turn, and malformed operation records"
    (is (false? (turn-augmentation/well-formed-record?
                 "s" "t"
                 (assoc (augmentation-record "s" "t" []) :accepting? true))))
    (is (false? (turn-augmentation/well-formed-record?
                 "s" "t"
                 (augmentation-record "s" "other" []))))
    (is (false? (turn-augmentation/well-formed-record?
                 "s" "t"
                 (augmentation-record "s" "t" [(dissoc (context-operation "ctx" "Ctx" "Body") :source)]))))))

(deftest build-prepared-request-inserts-turn-augmentation-context-test
  ;; Accepted append-context-block operations render as a user-role turn context
  ;; message before the submitted user message, with prompt-layer and summary
  ;; introspection.
  (let [session-id "sid-aug"
        turn-id    "turn-aug"
        op         (context-operation "project-context" "Project context" "Working directory: /repo")
        record     (augmentation-record session-id turn-id [op])
        journal    [(persist/message-entry {:role "assistant"
                                            :content [{:type :text :text "previous"}]})
                    (persist/message-entry {:role "user"
                                            :content [{:type :text :text "raw current"}]})]
        state      {:agent-session {:sessions {session-id {:data {:session-id session-id
                                                                  :model {:provider :openai :id "gpt-4.1"}
                                                                  :thinking-level :off
                                                                  :turn-augmentations {turn-id record}}
                                                           :persistence {:journal journal}}}}}
        ctx        {:state* (atom state)}
        prepared   (prompt-request/build-prepared-request
                    ctx
                    session-id
                    {:turn-id turn-id
                     :user-message {:role "user"
                                    :content [{:type :text :text "current"}]}
                     :runtime-opts {}})
        messages   (:prepared-request/messages prepared)
        layer      (some #(when (= :turn/augmentation-context (:id %)) %)
                         (:prepared-request/prompt-layers prepared))]
    (is (= [:assistant :user :user]
           (mapv :role messages)))
    (is (= "[Project context]\nWorking directory: /repo"
           (get-in messages [1 :content :text])))
    (is (= "current" (get-in messages [2 :content :text])))
    (is (= {:id :turn/augmentation-context
            :kind :turn-context
            :role "user"
            :stable? false
            :turn-id turn-id
            :position :after-history-and-repairs-before-current-user
            :status :success
            :operation-count 1
            :provider-count 1
            :operation-ids ["project-context"]
            :content "[Project context]\nWorking directory: /repo"}
           layer))
    (is (= {:turn-id turn-id
            :workflow-run-id nil
            :status :success
            :accepted-operation-count 1
            :message-inserted? true}
           (:prepared-request/augmentation prepared)))))

(deftest build-prepared-request-fails-closed-for-missing-augmentation-record-test
  ;; Live request preparation refuses to silently omit the pre-turn phase.
  (let [session-id "sid-missing"
        turn-id "turn-missing"
        ctx {:state* (atom {:agent-session {:sessions {session-id {:data {:session-id session-id
                                                                          :model {:provider :openai :id "gpt-4.1"}
                                                                          :thinking-level :off
                                                                          :turn-augmentations {}}
                                                                   :persistence {:journal []}}}}})}]
    (try
      (prompt-request/build-prepared-request
       ctx
       session-id
       {:turn-id turn-id
        :user-message {:role "user" :content [{:type :text :text "hello"}]}
        :runtime-opts {}})
      (is false "request preparation must fail closed")
      (catch clojure.lang.ExceptionInfo e
        (is (= :missing-turn-augmentation-record (:reason (ex-data e))))))))
