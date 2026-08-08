(ns psi.ai.user-models-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [psi.ai.providers.request-support :as request-support]
   [psi.ai.structured-output :as structured-output]
   [psi.ai.user-models :as user-models]))

(defn- repo-root
  "Repo root: walk up from the process cwd until doc/custom-providers.md
  exists. Tests run from the repo root via bb, but this also tolerates a
  component-local cwd."
  []
  (loop [dir (.getCanonicalFile (io/file "."))]
    (if (or (.exists (io/file dir "doc" "custom-providers.md"))
            (= dir (.getParentFile dir)))
      dir
      (recur (.getParentFile dir)))))

(defn- deepseek-example-edn
  "Parse the models.edn EDN block under the '## DeepSeek-compatible example'
  heading in doc/custom-providers.md. Picks the first ```clojure block whose
  content starts with the models.edn root map (`{:version ...`) so an
  incidental code block (curl / request-shape sample) added to the section
  prose before the example cannot silently move the parse-lock target
  (review 11)."
  []
  (let [lines    (str/split-lines (slurp (io/file (repo-root) "doc" "custom-providers.md")))
        heading  (first (keep-indexed (fn [i l]
                                        (when (str/starts-with? l "## DeepSeek-compatible example") i))
                                      lines))]
    (when (nil? heading)
      (throw (ex-info "doc/custom-providers.md: '## DeepSeek-compatible example' heading not found" {})))
    (let [blocks    (keep (fn [start]
                            (let [end (first (keep-indexed (fn [i l]
                                                             (when (and (> i start) (str/starts-with? l "```")) i))
                                                           lines))]
                              (when end
                                {:start start
                                 :lines (subvec (vec lines) (inc start) end)})))
                          (keep-indexed (fn [i l]
                                          (when (and (> i heading) (str/starts-with? l "```clojure")) i))
                                        lines))
          edn-block (first (filter (fn [{:keys [lines]}]
                                     (str/starts-with? (str/trim (first lines)) "{:version"))
                                   blocks))]
      (when (nil? edn-block)
        (throw (ex-info "doc/custom-providers.md: no ```clojure EDN block starting with {:version ...} found after the DeepSeek example heading" {})))
      (edn/read-string (str/join "\n" (:lines edn-block))))))

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

(deftest custom-provider-models-tagged-custom-test
  ;; Review 14: built-in detection must not key off the provider NAME alone —
  ;; a custom models.edn provider literally named "anthropic"/"openai" is
  ;; classified built-in by provider name, defeating the provider-scoped
  ;; guarantees (env-var key fallback, Claude Code OAuth headers). Every
  ;; custom models.edn model is tagged `:custom? true` at expand time so the
  ;; transports' `builtin?` / `builtin-anthropic?` helpers can distinguish
  ;; custom models from catalog built-ins.
  (testing "every custom models.edn model carries :custom? true"
    (let [result (user-models/parse-models-config minimal-config)
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (true? (:custom? model)))))

  (testing "a custom provider literally named \"anthropic\" is tagged :custom? true"
    (let [result (user-models/parse-models-config
                  {:providers {"anthropic"
                               {:base-url "https://third-party.example"
                                :api      :anthropic-messages
                                :models   [{:id "not-a-builtin"}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (= :anthropic (:provider model)))
      (is (true? (:custom? model))
          "the provider name alone must not make a custom model look built-in")))

  (testing "a custom provider literally named \"openai\" is tagged :custom? true"
    (let [result (user-models/parse-models-config
                  {:providers {"openai"
                               {:base-url "https://third-party.example"
                                :api      :openai-completions
                                :models   [{:id "not-a-builtin"}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (= :openai (:provider model)))
      (is (true? (:custom? model))))))

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

;; ── Mid-conversation system messages (custom providers) ─────────────────────

(deftest supports-mid-conversation-system-messages-field-test
  ;; Review 22: the canonical Model schema already carries
  ;; :supports-mid-conversation-system-messages (gates the agent-session
  ;; :session/inject-mid-system-message capability; OpenAI chat-completions
  ;; is inferred, :anthropic-messages custom providers are not), but the
  ;; closed ModelDef schema did not accept it — a models.edn custom provider
  ;; could not declare the capability at all. Schema gate only; the field
  ;; flows through expand-model's verbatim model-def merge like
  ;; :adaptive-thinking (slice 1).
  (testing "explicit true is accepted and flows through"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :supports-mid-conversation-system-messages true}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (true? (:supports-mid-conversation-system-messages model)))))

  (testing "explicit false is accepted and flows through"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :supports-mid-conversation-system-messages false}]}}})
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (false? (:supports-mid-conversation-system-messages model)))))

  (testing "omitted remains valid and stays absent (unchanged behaviour)"
    (let [result (user-models/parse-models-config minimal-config)
          model  (first (:models result))]
      (is (nil? (:error result)))
      (is (not (contains? model :supports-mid-conversation-system-messages))))))

(deftest parse-documented-deepseek-example-test
  ;; Parse-lock: parses the EXACT models.edn example documented in
  ;; doc/custom-providers.md ("DeepSeek-compatible example") directly from the
  ;; doc file, so a change to the documented example (typo, new field, pricing
  ;; edit, removed field) fails this test — guarding the closed
  ;; ModelDef/AuthConfig schemas against docs/code drift in both directions
  ;; (a doc edit that breaks the example, or a schema change that rejects the
  ;; documented example).
  (testing "the exact documented DeepSeek example parses and carries through every resolved field"
    (let [result (user-models/parse-models-config (deepseek-example-edn))
          model  (first (:models result))]
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
      (is (= 0.14 (:cache-write-cost model)))
      ;; Review 21: the example must NOT fall through to the custom-model
      ;; defaults (:locality :local / :latency-tier :low / :cost-tier :zero)
      ;; — a cloud model with defaulted locality can be selected for (and
      ;; charged as) a "local" helper on psi's local-only helper paths.
      (is (= :cloud (:locality model))
          "example must classify deepseek-v4-flash as a cloud model")
      (is (= :low (:latency-tier model)))
      (is (= :low (:cost-tier model))))

    (testing "auth resolves provider-scoped from env:DEEPSEEK_API_KEY"
      ;; Redefs the env lookup (getenv) to a sentinel so the resolution path
      ;; is genuinely exercised — env:VAR → getenv → :api-key — instead of a
      ;; tautological resolve-api-key-spec-vs-itself comparison.
      (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-deepseek-sentinel")]
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
          (is (= "sk-deepseek-sentinel" (:api-key auth))
              "env:DEEPSEEK_API_KEY resolved through the env lookup")
          (is (true? (:auth-header? auth))))))))
