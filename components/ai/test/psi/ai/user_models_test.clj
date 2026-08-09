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

(defn- doc-clojure-blocks
  "Parse every ```clojure code block in doc/custom-providers.md as EDN,
   returning [{:heading <section-title> :edn <parsed>} ...] in document
   order. :heading is the nearest preceding '## ' section title (review 35:
   generalized doc extraction so every documented models.edn example can be
   parse-locked, not just the DeepSeek one)."
  []
  (let [doc-lines (vec (str/split-lines (slurp (io/file (repo-root) "doc" "custom-providers.md"))))]
    (->> (keep-indexed (fn [i l] (when (str/starts-with? l "```clojure") i)) doc-lines)
         (keep (fn [start]
                 (let [end (first (keep-indexed (fn [i l]
                                                  (when (and (> i start) (str/starts-with? l "```")) i))
                                                doc-lines))]
                   (when end
                     {:start start
                      :lines (subvec doc-lines (inc start) end)}))))
         (mapv (fn [{:keys [start lines]}]
                 {:heading (some (fn [i]
                                   (when (str/starts-with? (nth doc-lines i) "## ")
                                     (subs (nth doc-lines i) 3)))
                                 (range (dec start) -1 -1))
                  :edn     (edn/read-string (str/join "\n" lines))})))))

(defn- models-edn-example-blocks
  "Every full models.edn example block in doc/custom-providers.md: a
   ```clojure block whose content is the models.edn root map
   (`{:version ... :providers {...}}`). Returns the parsed EDN maps in
   document order (MiniMax, Anthropic-compatible proxy-sonnet, DeepSeek)."
  []
  (->> (doc-clojure-blocks)
       (keep (fn [{:keys [edn]}]
               (when (and (map? edn)
                          (number? (:version edn))
                          (map? (:providers edn)))
                 edn)))
       vec))

(defn- deepseek-example-edn
  "Parse the DeepSeek models.edn example block from doc/custom-providers.md
  (the full models.edn block whose provider map carries the
  `deepseek-v4-flash` model). Picks by model id rather than section heading so
  the lock survives section reordering; a doc edit that breaks the example (or
  a schema change that rejects it) fails the parse-lock test."
  []
  (or (first (filter (fn [edn]
                       (some (fn [[_ p]]
                               (some #(= "deepseek-v4-flash" (:id %)) (:models p)))
                             (:providers edn)))
                     (models-edn-example-blocks)))
      (throw (ex-info "doc/custom-providers.md: DeepSeek models.edn example block not found" {}))))

(defn- local-servers-auth-snippet
  "Parse the {:auth ...} snippet under the '## Local servers and custom
   headers' heading in doc/custom-providers.md — the flagship keyless local
   pattern. Locked against the closed AuthConfig schema (review 35) by
   wrapping the doc's exact snippet in a minimal provider definition."
  []
  (or (some (fn [{:keys [heading edn]}]
              (when (and (= "Local servers and custom headers" heading)
                         (map? edn)
                         (contains? edn :auth))
                edn))
            (doc-clojure-blocks))
      (throw (ex-info "doc/custom-providers.md: 'Local servers and custom headers' :auth snippet not found" {}))))

;; ── API key resolution ───────────────────────────────────────────────────────

(deftest resolve-key-spec-test
  ;; Review 26: env: spec resolution happens per request through the shared
  ;; `request-support/resolve-key-spec` (custom models.edn `env:` keys are
  ;; stored RAW in the registry and re-resolved at request time). Review 28:
  ;; the `user_models/resolve-api-key-spec` delegation wrapper is deleted
  ;; (production-dead since review 26) — this test now targets the shared
  ;; helper directly; request_support_test.clj's resolve-key-spec-test is the
  ;; canonical coverage.
  (testing "nil returns nil"
    (is (nil? (request-support/resolve-key-spec nil))))

  (testing "blank string returns nil"
    (is (nil? (request-support/resolve-key-spec "")))
    (is (nil? (request-support/resolve-key-spec "  "))))

  (testing "env: prefix reads environment variable"
    ;; PATH is always set
    (is (string? (request-support/resolve-key-spec "env:PATH")))
    (is (= (System/getenv "PATH")
           (request-support/resolve-key-spec "env:PATH"))))

  (testing "env: with nonexistent var returns nil"
    (is (nil? (request-support/resolve-key-spec "env:PSI_TEST_NONEXISTENT_VAR_XYZ"))))

  (testing "literal string returned as-is"
    (is (= "my-secret-key" (request-support/resolve-key-spec "my-secret-key")))
    (is (= "none" (request-support/resolve-key-spec "none")))))

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

(deftest custom-model-cannot-supply-reserved-custom-tag-test
  ;; Review 33: the reserved-tag guarantee is a security property — `:custom?`
  ;; is set by expand-model (origin tag gating built-in classification: env-key
  ;; fallback, OAuth headers, mid-system inference), and the closed ModelDef
  ;; schema rejects a user-supplied `:custom?` key, so a user cannot spoof
  ;; built-in classification from models.edn. The docs' "Note on `:custom?`"
  ;; claims this rejection; lock it in both directions.
  (testing "user-supplied :custom? true is rejected"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :custom? true}]}}})]
      (is (re-find #"Invalid models.edn schema" (str (:error result))))
      (is (empty? (:models result)))))

  (testing "user-supplied :custom? false is rejected"
    (let [result (user-models/parse-models-config
                  {:providers {"deepseek"
                               {:base-url "https://api.deepseek.com/anthropic"
                                :api      :anthropic-messages
                                :models   [{:id "deepseek-v4-flash"
                                            :custom? false}]}}})]
      (is (re-find #"Invalid models.edn schema" (str (:error result))))
      (is (empty? (:models result))))))

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
      ;; Review 25: pin the review-14 origin tag on the exact shipped
      ;; example — the doc lock is the natural home to catch an expand-model
      ;; change that stops tagging custom models (e.g. a merge-order
      ;; regression moving `:custom? true` before the model-def merge).
      (is (true? (:custom? model))
          "the documented example must carry the :custom? origin tag")
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

    (testing "auth stores the raw env: spec; resolution happens per request"
      ;; Review 26: the registry snapshots the RAW spec (not the resolved
      ;; value) — extract-provider-auth stores "env:DEEPSEEK_API_KEY"
      ;; verbatim, and the transports' shared
      ;; request-support/resolve-key-spec re-resolves env: keys through
      ;; getenv per request (matching the built-in env fallback's live
      ;; semantics). The redef exercises the genuine resolution path —
      ;; env:VAR → getenv → concrete key — instead of a tautological
      ;; raw-spec-vs-itself comparison.
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
        (is (= "env:DEEPSEEK_API_KEY" (:api-key auth))
            "registry stores the raw env: spec — NOT snapshotted at parse time")
        (is (true? (:auth-header? auth)))
        (with-redefs [psi.ai.providers.request-support/getenv (fn [_] "sk-deepseek-sentinel")]
          (is (= "sk-deepseek-sentinel"
                 (psi.ai.providers.request-support/resolve-key-spec "env:DEEPSEEK_API_KEY"))
              "env:DEEPSEEK_API_KEY resolves through the shared request-time env lookup"))))))

(deftest all-documented-models-edn-examples-parse-test
  ;; Review 35: only the DeepSeek doc example was parse-locked; reviews 33/34
  ;; found REAL defects in the other documented models.edn examples (MiniMax
  ;; :locality, proxy-sonnet :locality/tiers) by MANUAL review, each fixed
  ;; docs-only with "no parse-lock impact" — so the closed
  ;; ModelDef/AuthConfig schemas could silently reject or mis-parse the doc's
  ;; other copy-paste examples with no test catching it (the same docs/code
  ;; drift class review 6 built the DeepSeek parse-lock for). Parse EVERY
  ;; full models.edn example block in doc/custom-providers.md through
  ;; parse-models-config and assert zero errors, so future doc edits cannot
  ;; break the shipped examples.
  (testing "every documented models.edn example block parses without error"
    (let [blocks (models-edn-example-blocks)]
      (is (>= (count blocks) 3)
          "at least the MiniMax, Anthropic-compatible (proxy-sonnet) and DeepSeek examples must be locked")
      (doseq [edn blocks]
        (let [result (user-models/parse-models-config edn)]
          (is (nil? (:error result))
              (str "documented models.edn example must parse cleanly: " (pr-str edn)))
          (is (seq (:models result))
              (str "documented models.edn example must resolve at least one model: " (pr-str edn))))))))

(deftest committed-project-models-edn-matches-documented-deepseek-example-test
  ;; Review 38: the committed .psi/models.edn (added d1b28eb93, used by the
  ;; committed .psi/project.edn deepseek workflow session-profiles) is
  ;; covered by no test — the doc parse-locks read doc/custom-providers.md
  ;; only, so the committed file can silently drift from the shipped example
  ;; (the review-38 recurrence: the committed deepseek model map omitted the
  ;; :locality/:latency-tier/:cost-tier fields the documented example
  ;; mandates, so expand-model applied the custom-model defaults
  ;; (:locality :local) — the exact "cloud model with defaulted locality"
  ;; misconfiguration reviews 21/33/34 fixed in the docs). Parse the
  ;; committed file and assert its deepseek model equals the documented
  ;; example's resolved model, so committed-file ↔ doc drift fails here in
  ;; both directions.
  (testing "the committed .psi/models.edn deepseek model matches the documented example"
    (let [committed-file (io/file (repo-root) ".psi" "models.edn")
          _              (is (.exists committed-file)
                             "committed .psi/models.edn must exist (the delegate-review live test resolves session profiles against it)")
          committed      (user-models/parse-models-config
                          (edn/read-string (slurp committed-file)))
          committed-model (first (filter #(= "deepseek-v4-flash" (:id %))
                                         (:models committed)))
          documented-model (first (filter #(= "deepseek-v4-flash" (:id %))
                                          (:models (user-models/parse-models-config
                                                    (deepseek-example-edn)))))]
      (is (nil? (:error committed))
          "committed .psi/models.edn must be schema-valid")
      (is (some? committed-model)
          "committed .psi/models.edn must carry the deepseek-v4-flash model")
      ;; Review 21/38: the committed file must NOT fall through to the
      ;; custom-model defaults — a cloud model with defaulted locality can
      ;; be selected for (and charged as) a "local" helper.
      (is (= :cloud (:locality committed-model))
          "committed deepseek model must classify as cloud, not default to :local")
      (is (= :low (:latency-tier committed-model)))
      (is (= :low (:cost-tier committed-model)))
      (is (= documented-model committed-model)
          "committed deepseek model must equal the documented example's resolved model (no drift)"))))

(deftest local-servers-auth-snippet-parses-test
  ;; Review 35: the 'Local servers and custom headers' :auth snippet (the
  ;; flagship keyless local-provider pattern, `{:auth {:auth-header? false
  ;; :headers {"X-Client" "psi"}}}`) is part of the documented
  ;; custom-provider surface and was never schema-locked. Wrap the doc's
  ;; exact {:auth ...} snippet in a minimal provider definition and parse it
  ;; through the closed AuthConfig schema.
  (testing "the documented keyless :auth snippet is accepted by AuthConfig"
    (let [auth   (:auth (local-servers-auth-snippet))
          result (user-models/parse-models-config
                  {:version 1
                   :providers {"local-keyless"
                               {:base-url "http://localhost:8080/v1"
                                :api      :openai-completions
                                :auth     auth
                                :models   [{:id "test-model"}]}}})
          stored (get-in result [:auth :local-keyless])]
      (is (nil? (:error result))
          (str "documented :auth snippet must be schema-valid: " (pr-str auth)))
      (is (false? (:auth-header? stored))
          "auth-header? false from the documented snippet is carried through")
      (is (= {"X-Client" "psi"} (:headers stored))
          "custom headers from the documented snippet are carried through")
      (is (nil? (:api-key stored))
          "the documented keyless snippet configures no api-key"))))
