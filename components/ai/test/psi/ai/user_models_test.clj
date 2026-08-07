(ns psi.ai.user-models-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.ai.structured-output :as structured-output]
   [psi.ai.user-models :as user-models]))

;; ── API key resolution ───────────────────────────────────────────────────────

(deftest resolve-api-key-spec-test
  (testing "nil returns nil"
    (is (nil? (user-models/resolve-api-key-spec nil))))

  (testing "blank string returns nil"
    (is (nil? (user-models/resolve-api-key-spec "")))
    (is (nil? (user-models/resolve-api-key-spec "  "))))

  (testing "env: prefix reads environment variable"
    ;; PATH is always set
    (is (string? (user-models/resolve-api-key-spec "env:PATH")))
    (is (= (System/getenv "PATH")
           (user-models/resolve-api-key-spec "env:PATH"))))

  (testing "env: with nonexistent var returns nil"
    (is (nil? (user-models/resolve-api-key-spec "env:PSI_TEST_NONEXISTENT_VAR_XYZ"))))

  (testing "literal string returned as-is"
    (is (= "my-secret-key" (user-models/resolve-api-key-spec "my-secret-key")))
    (is (= "none" (user-models/resolve-api-key-spec "none")))))

;; ── Valid config parsing ─────────────────────────────────────────────────────

(def minimal-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :models   [{:id "my-model"}]}}})

(def full-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :auth     {:api-key      "test-key"
                           :auth-header? false
                           :headers      {"X-Custom" "value"}}
                :models   [{:id               "llama-70b"
                            :name             "Llama 70B"
                            :supports-reasoning true
                            :supports-images  false
                            :context-window   65536
                            :max-tokens       8192
                            :parallel-tool-calls false
                            :input-cost       1.0
                            :output-cost      2.0
                            :cache-read-cost  0.5
                            :cache-write-cost 0.0}]}}})

(def multi-provider-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :models   [{:id "model-a"}]}
               "remote"
               {:base-url "http://gpu.example.com:8000/v1"
                :api      :openai-completions
                :auth     {:api-key "env:REMOTE_KEY"}
                :models   [{:id "model-b" :name "Remote Model B"}
                           {:id "model-c"}]}}})

(deftest parse-minimal-config-test
  (let [result (user-models/parse-models-config minimal-config)]
    (testing "no error"
      (is (nil? (:error result))))

    (testing "produces one model"
      (is (= 1 (count (:models result)))))

    (let [model (first (:models result))]
      (testing "model fields"
        (is (= "my-model" (:id model)))
        (is (= "my-model" (:name model)))    ;; defaults to id
        (is (= :local (:provider model)))
        (is (= :openai-completions (:api model)))
        (is (= "http://localhost:8080/v1" (:base-url model))))

      (testing "defaults applied"
        (is (false? (:supports-reasoning model)))
        (is (false? (:supports-images model)))
        (is (true? (:supports-text model)))
        (is (= 128000 (:context-window model)))
        (is (= 16384 (:max-tokens model)))
        (is (= 0.0 (:input-cost model)))
        (is (= 0.0 (:output-cost model)))))

    (testing "auth defaults"
      (let [auth (get-in result [:auth :local])]
        (is (= :local (:provider auth)))
        (is (nil? (:api-key auth)))
        (is (true? (:auth-header? auth)))))))

(deftest parse-full-config-test
  (let [result (user-models/parse-models-config full-config)]
    (testing "no error"
      (is (nil? (:error result))))

    (let [model (first (:models result))]
      (testing "explicit fields override defaults"
        (is (= "llama-70b" (:id model)))
        (is (= "Llama 70B" (:name model)))
        (is (true? (:supports-reasoning model)))
        (is (false? (:supports-images model)))
        (is (= 65536 (:context-window model)))
        (is (= 8192 (:max-tokens model)))
        (is (false? (:parallel-tool-calls model)))
        (is (= 1.0 (:input-cost model)))
        (is (= 2.0 (:output-cost model)))))

    (testing "auth config"
      (let [auth (get-in result [:auth :local])]
        (is (= "test-key" (:api-key auth)))
        (is (false? (:auth-header? auth)))
        (is (= {"X-Custom" "value"} (:headers auth)))))))

(deftest parse-multi-provider-config-test
  (let [result (user-models/parse-models-config multi-provider-config)]
    (testing "no error"
      (is (nil? (:error result))))

    (testing "models from all providers"
      (is (= 3 (count (:models result))))
      (is (= #{:local :remote}
             (set (map :provider (:models result))))))

    (testing "each provider has auth entry"
      (is (contains? (:auth result) :local))
      (is (contains? (:auth result) :remote)))))

(deftest parse-structured-output-capabilities-test
  (testing "omitted structured-output capability remains valid and normalizes to unsupported"
    (let [result (user-models/parse-models-config minimal-config)
          capability (-> result :models first structured-output/effective-capability)]
      (is (nil? (:error result)))
      (is (= false (:supported? capability)))
      (is (empty? (:strategies capability)))
      (is (true? (:defaulted? capability)))))

  (testing "explicit fallback-only structured-output capability is accepted"
    (let [result (user-models/parse-models-config
                  {:providers {"local"
                               {:base-url "http://localhost:8080/v1"
                                :api      :openai-completions
                                :models   [{:id "json-model"
                                            :capabilities
                                            {:structured-output
                                             {:supported? true
                                              :strategies [:prompted-json]
                                              :native-mechanism nil}}}]}}})
          capability (-> result :models first structured-output/effective-capability)]
      (is (nil? (:error result)))
      (is (= true (:supported? capability)))
      (is (= [:prompted-json] (:strategies capability)))
      (is (nil? (:native-mechanism capability))))))

;; ── Invalid configs ──────────────────────────────────────────────────────────

(deftest parse-invalid-config-test
  (testing "missing base-url"
    (let [result (user-models/parse-models-config
                  {:providers {"bad" {:api :openai-completions
                                      :models [{:id "m"}]}}})]
      (is (some? (:error result)))
      (is (empty? (:models result)))))

  (testing "missing api"
    (let [result (user-models/parse-models-config
                  {:providers {"bad" {:base-url "http://localhost/v1"
                                      :models [{:id "m"}]}}})]
      (is (some? (:error result)))))

  (testing "empty models list"
    (let [result (user-models/parse-models-config
                  {:providers {"bad" {:base-url "http://localhost/v1"
                                      :api :openai-completions
                                      :models []}}})]
      (is (some? (:error result)))))

  (testing "invalid api protocol"
    (let [result (user-models/parse-models-config
                  {:providers {"bad" {:base-url "http://localhost/v1"
                                      :api :invalid-api
                                      :models [{:id "m"}]}}})]
      (is (some? (:error result)))))

  (testing "not a map"
    (let [result (user-models/parse-models-config "not a map")]
      (is (some? (:error result))))))

;; ── File loading ─────────────────────────────────────────────────────────────

(deftest load-models-file-missing-test
  (testing "missing file returns empty with no error"
    (let [result (user-models/load-models-file "/tmp/psi-test-nonexistent-models.edn")]
      (is (empty? (:models result)))
      (is (empty? (:auth result)))
      (is (nil? (:error result))))))

(deftest load-models-file-valid-test
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (try
      (spit tmp (pr-str minimal-config))
      (let [result (user-models/load-models-file (.getAbsolutePath tmp))]
        (testing "loads valid file"
          (is (nil? (:error result)))
          (is (= 1 (count (:models result))))))
      (finally
        (.delete tmp)))))

(deftest load-models-file-invalid-edn-test
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (try
      (spit tmp "{{{invalid edn")
      (let [result (user-models/load-models-file (.getAbsolutePath tmp))]
        (testing "returns error for invalid EDN"
          (is (some? (:error result)))
          (is (empty? (:models result)))))
      (finally
        (.delete tmp)))))

(deftest load-models-file-non-map-test
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (try
      (spit tmp "[1 2 3]")
      (let [result (user-models/load-models-file (.getAbsolutePath tmp))]
        (testing "returns error for non-map content"
          (is (some? (:error result)))
          (is (empty? (:models result)))))
      (finally
        (.delete tmp)))))

;; ── Version field ────────────────────────────────────────────────────────────

(deftest version-optional-test
  (testing "version field is optional"
    (let [result (user-models/parse-models-config
                  {:providers {"local"
                               {:base-url "http://localhost:8080/v1"
                                :api      :openai-completions
                                :models   [{:id "m"}]}}})]
      (is (nil? (:error result)))
      (is (= 1 (count (:models result)))))))

;; ── Anthropic API ────────────────────────────────────────────────────────────

(deftest anthropic-api-test
  (testing "anthropic-messages api is valid"
    (let [result (user-models/parse-models-config
                  {:providers {"my-anthropic"
                               {:base-url "http://localhost:9090"
                                :api      :anthropic-messages
                                :models   [{:id "claude-local"}]}}})]
      (is (nil? (:error result)))
      (is (= :anthropic-messages (:api (first (:models result))))))))

;; ── Adaptive thinking (custom providers) ─────────────────────────────────────

(deftest adaptive-thinking-field-test
  (testing "explicit :adaptive-thinking true is accepted and flows through"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :supports-reasoning true
                                            :adaptive-thinking true}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (true? (:adaptive-thinking model)))))

  (testing "explicit :adaptive-thinking false is accepted and flows through"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :adaptive-thinking false}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (false? (:adaptive-thinking model)))))

  (testing "omitted :adaptive-thinking remains valid and stays absent/falsy"
    (let [result (user-models/parse-models-config minimal-config)
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (not (contains? model :adaptive-thinking)))
      (is (false? (boolean (:adaptive-thinking model)))))))

(deftest parse-documented-deepseek-example-test
  ;; Parse-lock: the EXACT models.edn example documented in
  ;; doc/custom-providers.md ("DeepSeek-compatible example"). Guards the closed
  ;; ModelDef/AuthConfig schemas against docs/code drift — e.g. a future field
  ;; typo silently making the documented example invalid.
  (testing "the exact documented DeepSeek example parses and carries through every resolved field"
    (let [result (user-models/parse-models-config
                  {:version 1
                   :providers
                   {"deepseek"
                    {:base-url "https://api.deepseek.com/anthropic"
                     :api      :anthropic-messages
                     :auth     {:api-key "env:DEEPSEEK_API_KEY"}
                     :models   [{:id                 "deepseek-v4-flash"
                                 :name               "DeepSeek V4 Flash"
                                 :supports-reasoning true
                                 :adaptive-thinking  true
                                 :supports-images    false
                                 :supports-text      true
                                 :context-window     1000000
                                 :max-tokens         384000
                                 :input-cost         0.14
                                 :output-cost        0.28
                                 :cache-read-cost    0.0028
                                 :cache-write-cost   0.14}]}}})
          model (first (:models result))]
      (is (nil? (:error result)))
      (is (= 1 (count (:models result))))
      (is (= "deepseek-v4-flash" (:id model)))
      (is (= "DeepSeek V4 Flash" (:name model)))
      (is (= :deepseek (:provider model)))
      (is (= :anthropic-messages (:api model)))
      (is (= "https://api.deepseek.com/anthropic" (:base-url model)))
      (is (true? (:supports-reasoning model)))
      (is (true? (:adaptive-thinking model)))
      (is (false? (:supports-images model)))
      (is (true? (:supports-text model)))
      (is (= 1000000 (:context-window model)))
      (is (= 384000 (:max-tokens model)))
      (is (= 0.14 (:input-cost model)))
      (is (= 0.28 (:output-cost model)))
      (is (= 0.0028 (:cache-read-cost model)))
      (is (= 0.14 (:cache-write-cost model))))

    (testing "auth resolves provider-scoped from env:DEEPSEEK_API_KEY"
      (let [auth (get-in (user-models/parse-models-config
                          {:version 1
                           :providers
                           {"deepseek"
                            {:base-url "https://api.deepseek.com/anthropic"
                             :api      :anthropic-messages
                             :auth     {:api-key "env:DEEPSEEK_API_KEY"}
                             :models   [{:id "deepseek-v4-flash"}]}}})
                         [:auth :deepseek])]
        (is (= :deepseek (:provider auth)))
        (is (= (user-models/resolve-api-key-spec "env:DEEPSEEK_API_KEY")
               (:api-key auth))
            "env:DEEPSEEK_API_KEY resolved via resolve-api-key-spec (env-dependent)")
        (is (true? (:auth-header? auth)))))))
