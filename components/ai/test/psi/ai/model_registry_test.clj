(ns psi.ai.model-registry-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as registry]
   [psi.ai.models :as built-in]
   [psi.ai.structured-output :as structured-output]
   [psi.provider-auth.oauth.core :as oauth]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

;; Reset registry after each test to avoid cross-contamination
(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        ;; Re-init with no custom models
        (registry/init! {})))))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(def local-provider-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :auth     {:api-key "test-key" :auth-header? false}
                :models   [{:id   "test-model"
                            :name "Test Model"
                            :context-window 32768}]}}})

(def remote-provider-config
  {:version   1
   :providers {"remote"
               {:base-url "http://gpu.example.com/v1"
                :api      :openai-completions
                :auth     {:api-key "remote-key"}
                :models   [{:id "remote-model" :name "Remote Model"}]}}})

;; ── Init with no custom models ───────────────────────────────────────────────

(deftest init-built-ins-only-test
  (registry/init! {})

  (testing "catalog includes built-in models"
    (let [models (registry/all-models)]
      (is (pos? (count models)))
      ;; Check known built-ins exist
      (is (some? (registry/find-model :anthropic "claude-sonnet-4-6")))
      (is (some? (registry/find-model :anthropic "claude-opus-4-7")))
      (is (some? (registry/find-model :anthropic "claude-opus-4-8")))
      (is (some? (registry/find-model :anthropic "claude-fable-5")))
      (is (some? (registry/find-model :anthropic "claude-sonnet-5")))
      (is (contains? built-in/all-models :fable-5))
      (is (contains? built-in/all-models :sonnet-5))
      (is (some? (registry/find-model :openai "gpt-5.5")))
      (is (some? (registry/find-model :openai "gpt-5.6")))
      (is (some? (registry/find-model :openai "gpt-5.4-mini")))))

  (testing "no auth for built-in providers"
    (is (nil? (registry/get-auth :anthropic)))
    (is (nil? (registry/get-auth :openai))))

  (testing "no load error"
    (is (nil? (registry/get-load-error))))

  (testing "providers includes built-ins"
    (let [providers (registry/providers)]
      (is (contains? providers :anthropic))
      (is (contains? providers :openai)))))

(deftest resolve-runtime-model-openai-oauth-routing-test
  (registry/init! {})

  (testing "openai gpt-5.5 remains chat-completions without oauth context"
    (let [model (registry/resolve-runtime-model nil :openai "gpt-5.5")]
      (is (= :openai-completions (:api model)))
      (is (= "https://api.openai.com/v1" (:base-url model)))))

  (testing "openai gpt-5.6 remains chat-completions without oauth context"
    (let [model (registry/resolve-runtime-model nil :openai "gpt-5.6")]
      (is (= :openai-completions (:api model)))
      (is (= "https://api.openai.com/v1" (:base-url model)))))

  (testing "openai gpt-5.5 resolves to codex transport when oauth credential is present"
    (let [ctx {:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :oauth
                                                                             :access "tok"
                                                                             :refresh "ref"
                                                                             :expires (+ (System/currentTimeMillis) 60000)}}})}
          model (registry/resolve-runtime-model ctx :openai "gpt-5.5")]
      (is (= :openai-codex-responses (:api model)))
      (is (= "https://chatgpt.com/backend-api" (:base-url model)))
      (is (= "gpt-5.5" (:id model)))
      (is (= [:provider-native :prompted-json]
             (get-in model [:capabilities :structured-output :strategies])))
      (is (= :openai/responses-text-format-json-schema
             (get-in model [:capabilities :structured-output :native-mechanism])))))

  (testing "openai gpt-5.6 resolves to codex transport when oauth credential is present"
    (let [ctx {:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :oauth
                                                                             :access "tok"
                                                                             :refresh "ref"
                                                                             :expires (+ (System/currentTimeMillis) 60000)}}})}
          model (registry/resolve-runtime-model ctx :openai "gpt-5.6")]
      (is (= :openai-codex-responses (:api model)))
      (is (= "https://chatgpt.com/backend-api" (:base-url model)))
      (is (= "gpt-5.6" (:id model)))
      (is (= [:provider-native :prompted-json]
             (get-in model [:capabilities :structured-output :strategies])))
      (is (= :openai/responses-text-format-json-schema
             (get-in model [:capabilities :structured-output :native-mechanism])))))

  (testing "non-member chat-completions model stays chat-completions under oauth"
    ;; Genuine negative control: gpt-5.4-mini's catalog transport is
    ;; :openai-completions and it is NOT in openai-oauth-codex-model-ids, so the
    ;; OAuth override must not apply and the model must retain chat-completions.
    (let [ctx {:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :oauth
                                                                             :access "tok"
                                                                             :refresh "ref"
                                                                             :expires (+ (System/currentTimeMillis) 60000)}}})}
          model (registry/resolve-runtime-model ctx :openai "gpt-5.4-mini")]
      (is (= :openai-completions (:api model)))
      (is (= "https://api.openai.com/v1" (:base-url model))))))

(deftest built-in-structured-output-capabilities-test
  (registry/init! {})

  (testing "modern OpenAI chat-completions models declare native JSON Schema support"
    (let [capability (-> (registry/find-model :openai "gpt-5.5")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :openai/chat-completions-json-schema-response-format
             (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "gpt-5.6 declares native JSON Schema support (chat-completions transport)"
    (let [capability (-> (registry/find-model :openai "gpt-5.6")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :openai/chat-completions-json-schema-response-format
             (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "unverified OpenAI chat-completions models normalize to unsupported"
    (let [capability (-> (registry/find-model :openai "o1-preview")
                         structured-output/effective-capability)]
      (is (= false (:supported? capability)))
      (is (empty? (:strategies capability)))
      (is (true? (:defaulted? capability)))))

  (testing "OpenAI Codex Responses models declare native streaming text.format support"
    (let [capability (-> (registry/find-model :openai "gpt-5.4")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= [:provider-native :prompted-json] (:strategies capability)))
      (is (= :openai/responses-text-format-json-schema
             (:native-mechanism capability)))))

  (testing "older Anthropic Messages models declare forced-tool native support"
    (let [capability (-> (registry/find-model :anthropic "claude-sonnet-4-20250514")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :anthropic/forced-tool-use (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "documented Claude 4.5+ catalog models declare Anthropic JSON Schema output"
    (let [capability (-> (registry/find-model :anthropic "claude-sonnet-4-6")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :anthropic/json-schema-output (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "Claude Opus 4.8 is findable and declares native Anthropic JSON Schema output"
    (let [model      (registry/find-model :anthropic "claude-opus-4-8")
          capability (structured-output/effective-capability model)]
      (is (some? model))
      (is (= "Claude Opus 4.8" (:name model)))
      (is (= true (:adaptive-thinking model)))
      (is (= true (:supports-mid-conversation-system-messages model)))
      (is (= true (:supported? capability)))
      (is (= :anthropic/json-schema-output (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "Claude Fable 5 declares native Anthropic JSON Schema output"
    (let [capability (-> (registry/find-model :anthropic "claude-fable-5")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :anthropic/json-schema-output (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native))))

  (testing "Claude Sonnet 5 declares native Anthropic JSON Schema output"
    (let [capability (-> (registry/find-model :anthropic "claude-sonnet-5")
                         structured-output/effective-capability)]
      (is (= true (:supported? capability)))
      (is (= :anthropic/json-schema-output (:native-mechanism capability)))
      (is (contains? (set (:strategies capability)) :provider-native)))))

(deftest fable-5-catalog-entry-test
  (registry/init! {})

  (testing "Claude Fable 5 catalog entry carries the agreed metadata, capability, and pricing values"
    (let [model (registry/find-model :anthropic "claude-fable-5")]
      (is (some? model))
      (is (= "Claude Fable 5" (:name model)))
      (is (= true (:adaptive-thinking model)))
      (is (= true (:supports-mid-conversation-system-messages model)))
      (is (= true (:supports-reasoning model)))
      (is (= true (:supports-images model)))
      (is (= true (:supports-text model)))
      (is (= 1000000 (:context-window model)))
      (is (= 128000 (:max-tokens model)))
      (is (= 10.0 (:input-cost model)))
      (is (= 50.0 (:output-cost model)))
      (is (= 1.0 (:cache-read-cost model)))
      (is (= 12.5 (:cache-write-cost model))))))

(deftest sonnet-5-catalog-entry-test
  (registry/init! {})

  (testing "Claude Sonnet 5 catalog entry carries the agreed metadata, capability, and pricing values"
    (let [model (registry/find-model :anthropic "claude-sonnet-5")]
      (is (some? model))
      (is (= "Claude Sonnet 5" (:name model)))
      (is (= :anthropic (:provider model)))
      (is (= :anthropic-messages (:api model)))
      (is (= "https://api.anthropic.com" (:base-url model)))
      (is (= true (:adaptive-thinking model)))
      (is (= true (:supports-mid-conversation-system-messages model)))
      (is (= true (:supports-reasoning model)))
      (is (= true (:supports-images model)))
      (is (= true (:supports-text model)))
      (is (= 1000000 (:context-window model)))
      (is (= 128000 (:max-tokens model)))
      (is (= 3.0 (:input-cost model)))
      (is (= 15.0 (:output-cost model)))
      (is (= 0.3 (:cache-read-cost model)))
      (is (= 3.75 (:cache-write-cost model))))))

;; ── Init with user models ────────────────────────────────────────────────────

(deftest init-user-models-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})

      (testing "custom model added to catalog"
        (let [model (registry/find-model :local "test-model")]
          (is (some? model))
          (is (= "Test Model" (:name model)))
          (is (= "http://localhost:8080/v1" (:base-url model)))
          (is (= :openai-completions (:api model)))
          (is (= 32768 (:context-window model)))))

      (testing "built-in models still present"
        (is (some? (registry/find-model :anthropic "claude-sonnet-4-6"))))

      (testing "auth stored for custom provider"
        (let [auth (registry/get-auth :local)]
          (is (some? auth))
          (is (= "test-key" (:api-key auth)))
          (is (false? (:auth-header? auth)))))

      (testing "providers includes custom"
        (is (contains? (registry/providers) :local)))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

;; ── Project models override user models ──────────────────────────────────────

(deftest project-overrides-user-test
  (let [user-config    {:version   1
                        :providers {"staging"
                                    {:base-url "http://old.example.com/v1"
                                     :api      :openai-completions
                                     :auth     {:api-key "old-key"}
                                     :models   [{:id   "shared-model"
                                                 :name "Old Name"}]}}}
        project-config {:version   1
                        :providers {"staging"
                                    {:base-url "http://new.example.com/v1"
                                     :api      :openai-completions
                                     :auth     {:api-key "new-key"}
                                     :models   [{:id   "shared-model"
                                                 :name "New Name"}]}}}
        user-path    (write-temp-models! user-config)
        project-path (write-temp-models! project-config)]
    (try
      (registry/init! {:user-models-path    user-path
                       :project-models-path project-path})

      (testing "project model wins over user model"
        (let [model (registry/find-model :staging "shared-model")]
          (is (= "New Name" (:name model)))
          (is (= "http://new.example.com/v1" (:base-url model)))))

      (testing "project auth wins"
        (let [auth (registry/get-auth :staging)]
          (is (= "new-key" (:api-key auth)))))

      (finally
        (java.io.File/.delete (java.io.File. user-path))
        (java.io.File/.delete (java.io.File. project-path))))))

;; ── Custom models don't shadow built-ins ─────────────────────────────────────

(deftest no-shadow-built-in-test
  (let [;; Try to define a custom model with same provider+id as a built-in
        shadow-config {:version   1
                       :providers {"anthropic"
                                   {:base-url "http://evil.example.com/v1"
                                    :api      :openai-completions
                                    :models   [{:id "claude-sonnet-4-6"
                                                :name "Evil Sonnet"}]}}}
        path (write-temp-models! shadow-config)]
    (try
      (registry/init! {:user-models-path path})

      (testing "built-in model is preserved, not replaced"
        (let [model (registry/find-model :anthropic "claude-sonnet-4-6")]
          (is (some? model))
          ;; Should be the built-in, not the custom one
          (is (not= "Evil Sonnet" (:name model)))
          (is (= "https://api.anthropic.com" (:base-url model)))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

;; ── Invalid file doesn't block startup ───────────────────────────────────────

(deftest invalid-file-graceful-test
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (try
      (spit tmp "{{{bad edn")
      (registry/init! {:user-models-path (.getAbsolutePath tmp)})

      (testing "built-in models still present"
        (is (some? (registry/find-model :anthropic "claude-sonnet-4-6"))))

      (testing "load error recorded"
        (is (some? (registry/get-load-error))))

      (finally
        (.delete tmp)))))

;; ── Missing file is fine ─────────────────────────────────────────────────────

(deftest missing-file-test
  (registry/init! {:user-models-path "/tmp/psi-test-nonexistent.edn"})

  (testing "no error"
    (is (nil? (registry/get-load-error))))

  (testing "built-in models present"
    (is (pos? (count (registry/all-models))))))

;; ── all-models-by-key backward compat ────────────────────────────────────────

(deftest all-models-by-key-test
  (registry/init! {})

  (testing "all-models-by-key includes all built-in keys"
    (let [by-key (registry/all-models-by-key)]
      (doseq [[k _] built-in/all-models]
        (is (contains? by-key k)
            (str "Missing built-in key: " k))))))

(deftest all-models-by-key-custom-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})

      (testing "custom model gets synthesized keyword key"
        (let [by-key (registry/all-models-by-key)]
          (is (contains? by-key :local/test-model))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

;; ── models-for-provider ──────────────────────────────────────────────────────

(deftest models-for-provider-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})

      (testing "returns only models for specified provider"
        (let [local-models (registry/models-for-provider :local)]
          (is (= 1 (count local-models)))
          (is (= "test-model" (:id (first (vals local-models)))))))

      (testing "returns empty for unknown provider"
        (is (empty? (registry/models-for-provider :nonexistent))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))

;; ── refresh! ─────────────────────────────────────────────────────────────────

(deftest refresh-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (is (some? (registry/find-model :local "test-model")))

      ;; Now overwrite with a different model
      (spit path (pr-str {:version   1
                          :providers {"local"
                                      {:base-url "http://localhost:8080/v1"
                                       :api      :openai-completions
                                       :models   [{:id "new-model" :name "New"}]}}}))
      (registry/refresh! {:user-models-path path})

      (testing "old model gone"
        (is (nil? (registry/find-model :local "test-model"))))

      (testing "new model present"
        (is (some? (registry/find-model :local "new-model"))))

      (finally
        (java.io.File/.delete (java.io.File. path))))))
