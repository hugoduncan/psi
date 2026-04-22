(ns psi.ai.user-models-test
  (:require
   [clojure.test :refer [deftest testing is]]
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
