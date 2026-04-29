(ns psi.agent-session.prompt-request-test
  "Tests for prompt-request auth injection with custom providers."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.prompt-request :as prompt-request]))

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

      (testing "auth-header? false sets :no-auth-header and omits api-key"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :ollama "llama")
                    {})]
          (is (true? (:no-auth-header opts)))
          ;; api-key should NOT be injected because auth-header? is false
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

      (testing "custom headers merged into options"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :custom "model-a")
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

      (testing "custom anthropic-compatible provider injects provider-scoped api-key"
        (let [opts (prompt-request/session->request-options
                    {}
                    (session-data-for :minimax "MiniMax-M2.7")
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
