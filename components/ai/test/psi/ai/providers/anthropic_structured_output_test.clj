(ns psi.ai.providers.anthropic-structured-output-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.ai.providers.anthropic.structured-output]
   [psi.ai.structured-output :as structured-output])
  (:import [java.io ByteArrayInputStream]))

(defn- stream-body
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- sse-line
  [event payload]
  (str "event: " event "
"
       "data: " (json/generate-string payload) "

"))

(def ^:private judge-json-schema
  {:type "object"
   :properties {:ok {:type "boolean"}}
   :required ["ok"]
   :additionalProperties false})

(def ^:private judge-structured-output-request
  {:schema-id :psi.workflow/judge-review-result
   :schema-version 1
   :name "judge_review_result"
   :json-schema judge-json-schema
   :strict? true
   :fallback-allowed? true})

(deftest anthropic-json-schema-output-request-shaping-test
  ;; Tests Anthropic JSON Schema output request shape is selected for supported
  ;; Claude catalog entries without synthetic forced-tool fields.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys")
                  (conv/add-tool {:name "ordinary_tool"
                                  :description "existing"
                                  :parameters {:type "object"}})
                  (conv/add-user-message "Review this"))
        req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                      :structured-output (dissoc judge-structured-output-request
                                                                                 :strict?)})
        body  (json/parse-string (:body req) true)
        beta  (get-in req [:headers "anthropic-beta"])]
    (is (= {:type "json_schema"
            :schema judge-json-schema}
           (:output_format body)))
    (is (re-find #"structured-outputs-2025-11-13" beta))
    (is (= "ordinary_tool" (get-in body [:tools 0 :name])))
    (is (nil? (:tool_choice body)))
    (is (= :provider-native
           (:strategy (structured-output/select-strategy model judge-structured-output-request))))
    (is (= :anthropic/json-schema-output
           (:native-mechanism (structured-output/select-strategy model judge-structured-output-request))))))

(deftest anthropic-json-schema-output-request-omits-unsupported-strict-field-test
  ;; Live Anthropic verification rejects output_format.strict for the beta JSON
  ;; Schema output API; strictness remains Psi request metadata, not an outbound
  ;; Anthropic JSON Schema output_format field.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys")
                  (conv/add-user-message "Review this"))
        req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                      :structured-output (assoc judge-structured-output-request
                                                                                :strict? false)})
        body  (json/parse-string (:body req) true)]
    (is (not (contains? (:output_format body) :strict)))))

(deftest anthropic-json-schema-output-beta-header-composition-test
  ;; Tests JSON Schema native output appends its beta token without dropping or
  ;; duplicating OAuth, prompt-caching, or thinking beta tokens.
  (let [model       (models/get-model :sonnet-4.6)
        convo       (-> (conv/create {:system-prompt "sys"
                                      :system-prompt-blocks [{:kind :text
                                                              :text "sys"
                                                              :cache-control {:type :ephemeral}}]})
                        (conv/add-tool {:name "read"
                                        :description "Read a file"
                                        :parameters {:type "object"}
                                        :cache-control {:type :ephemeral}})
                        (conv/add-user-message "Review this"))
        req         (#'anthropic/build-request convo model {:api-key "sk-ant-oat-test-token"
                                                            :thinking-level :medium
                                                            :structured-output judge-structured-output-request})
        beta        (get-in req [:headers "anthropic-beta"])
        beta-tokens (str/split beta #",")]
    (is (= "Bearer sk-ant-oat-test-token"
           (get-in req [:headers "Authorization"])))
    (is (nil? (get-in req [:headers "x-api-key"])))
    (is (= (count beta-tokens)
           (count (distinct beta-tokens)))
        "beta header should not duplicate tokens")
    (doseq [token ["claude-code-20250219"
                   "oauth-2025-04-20"
                   "context-management-2025-06-27"
                   "prompt-caching-scope-2026-01-05"
                   "prompt-caching-2024-07-31"
                   "interleaved-thinking-2025-05-14"
                   "structured-outputs-2025-11-13"]]
      (is (some #{token} beta-tokens)
          (str "missing beta token " token)))))

(deftest anthropic-json-schema-output-400-fallback-removes-output-format-with-beta-test
  ;; Tests 400 compatibility fallback never retries an Anthropic JSON Schema
  ;; native request with :output_format present after the structured-output beta
  ;; header has been stripped.
  (let [model  (models/get-model :sonnet-4.6)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        calls  (atom [])
        events (atom [])
        sse    (str (sse-line "message_start" {:type "message_start"})
                    (sse-line "message_stop" {:type "message_stop"}))]
    (with-redefs [http/post (fn [_url req]
                              (swap! calls conj req)
                              (if (= 1 (count @calls))
                                {:status 400
                                 :headers {"request-id" "req_ant_first"}
                                 :body nil}
                                {:status 200
                                 :headers {}
                                 :body (stream-body sse)}))]
      ((:stream anthropic/provider)
       convo model {:api-key "test-key"
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (= 2 (count @calls)))
    (let [first-beta  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
          second-beta (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
          first-body  (json/parse-string (:body (first @calls)) true)
          second-body (json/parse-string (:body (second @calls)) true)]
      (is (re-find #"structured-outputs-2025-11-13" first-beta))
      (is (contains? first-body :output_format))
      (is (not (re-find #"structured-outputs-2025-11-13" second-beta)))
      (is (not (contains? second-body :output_format))
          "retry body must not keep output_format when the beta header is absent"))
    (is (not-any? #(= :error (:type %)) @events))))

(deftest anthropic-structured-output-forced-tool-request-shaping-test
  ;; Tests Anthropic provider-native structured output as a synthetic forced tool
  ;; composed alongside ordinary user tools.
  (let [model (models/get-model :sonnet-4)
        convo (-> (conv/create "sys")
                  (conv/add-tool {:name "psi_structured_output__judge_review_result"
                                  :description "existing"
                                  :parameters {:type "object"}})
                  (conv/add-user-message "Review this"))
        req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                      :structured-output judge-structured-output-request})
        body  (json/parse-string (:body req) true)
        tools (:tools body)
        structured-tool (some #(when (= "psi_structured_output__judge_review_result_2" (:name %)) %)
                              tools)]
    (is (= 2 (count tools)))
    (is (= judge-json-schema (:input_schema structured-tool)))
    (is (= {:type "tool" :name "psi_structured_output__judge_review_result_2"}
           (:tool_choice body)))
    (is (= :provider-native
           (:strategy (structured-output/select-strategy model judge-structured-output-request))))))

(deftest anthropic-structured-output-missing-json-schema-test
  ;; Tests schema-only structured-output requests report unsupported and do not
  ;; add a synthetic forced tool.
  (let [model   (models/get-model :sonnet-4)
        request (dissoc judge-structured-output-request :json-schema)
        convo   (-> (conv/create "sys")
                    (conv/add-user-message "Review this"))
        req     (#'anthropic/build-request convo model {:api-key "test-key"
                                                        :structured-output request})
        body    (json/parse-string (:body req) true)
        strategy (structured-output/select-strategy model request)]
    (is (= :unsupported (:strategy strategy)))
    (is (= :missing-json-schema (:reason strategy)))
    (is (nil? (:tools body)))
    (is (nil? (:tool_choice body)))))

(deftest anthropic-structured-output-prompted-json-fallback-request-shaping-test
  ;; Tests Anthropic fallback-only structured-output behavior without synthetic
  ;; forced-tool/native fields.
  (let [model (-> (models/get-model :sonnet-4.6)
                  (structured-output/with-structured-output-capability
                    {:supported? true
                     :strategies [:prompted-json]
                     :native-mechanism nil}))
        convo (-> (conv/create "sys")
                  (conv/add-tool {:name "ordinary_tool"
                                  :description "ordinary"
                                  :parameters {:type "object"}})
                  (conv/add-user-message "Review this"))
        req   (#'anthropic/build-request convo model {:api-key "test-key"
                                                      :structured-output judge-structured-output-request})
        body  (json/parse-string (:body req) true)
        text  (get-in body [:messages 0 :content 0 :text])
        strategy (structured-output/select-strategy model judge-structured-output-request)]
    (is (= :prompted-json (:strategy strategy)))
    (is (true? (:fallback-used? strategy)))
    (is (re-find #"Review this" text))
    (is (re-find #"Structured output required" text))
    (is (re-find #"JSON Schema" text))
    (is (= 1 (count (:tools body))))
    (is (= "ordinary_tool" (get-in body [:tools 0 :name])))
    (is (= "object" (get-in body [:tools 0 :input_schema :type])))
    (is (nil? (:tool_choice body)))))

(deftest anthropic-streaming-structured-output-events-test
  ;; Tests forced-tool streaming strategy and result metadata are emitted as first-class AI events.
  (let [model  (models/get-model :sonnet-4)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        events (atom [])
        sse    (str (sse-line "message_start"
                              {:type "message_start"
                               :message {:usage {:input_tokens 1}}})
                    (sse-line "content_block_start"
                              {:type "content_block_start"
                               :index 0
                               :content_block {:type "tool_use"
                                               :id "toolu_1"
                                               :name "psi_structured_output__judge_review_result"}})
                    (sse-line "content_block_delta"
                              {:type "content_block_delta"
                               :index 0
                               :delta {:partial_json "{\"ok\":true}"}})
                    (sse-line "content_block_stop"
                              {:type "content_block_stop"
                               :index 0})
                    (sse-line "message_delta"
                              {:type "message_delta"
                               :delta {:stop_reason "end_turn"}
                               :usage {:output_tokens 1}}))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream anthropic/provider)
       convo model {:api-key "test-key"
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (some #(and (= :structured-output-strategy (:type %))
                    (= :provider-native (get-in % [:structured-output :strategy])))
              @events))
    (is (some #(and (= :structured-output-result (:type %))
                    (= {:ok true} (get-in % [:structured-output :payload]))
                    (= :anthropic/tool-use (get-in % [:structured-output :source])))
              @events))
    (is (not-any? #(contains? #{:toolcall-start :toolcall-delta :toolcall-end} (:type %))
                  @events))))

(deftest anthropic-streaming-prompted-json-fallback-structured-output-events-test
  ;; Tests fallback-only Anthropic streaming preserves ordinary text deltas while
  ;; also emitting a first-class parsed structured-output result.
  (let [model  (-> (models/get-model :sonnet-4.6)
                   (structured-output/with-structured-output-capability
                     {:supported? true
                      :strategies [:prompted-json]
                      :native-mechanism nil}))
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        events (atom [])
        sse    (str (sse-line "message_start"
                              {:type "message_start"
                               :message {:usage {:input_tokens 1}}})
                    (sse-line "content_block_start"
                              {:type "content_block_start"
                               :index 0
                               :content_block {:type "text"}})
                    (sse-line "content_block_delta"
                              {:type "content_block_delta"
                               :index 0
                               :delta {:text "{\"ok\":true}"}})
                    (sse-line "content_block_stop"
                              {:type "content_block_stop"
                               :index 0})
                    (sse-line "message_delta"
                              {:type "message_delta"
                               :delta {:stop_reason "end_turn"}
                               :usage {:output_tokens 1}}))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream anthropic/provider)
       convo model {:api-key "test-key"
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (some #(and (= :structured-output-strategy (:type %))
                    (= :prompted-json (get-in % [:structured-output :strategy]))
                    (true? (get-in % [:structured-output :fallback-used?])))
              @events))
    (is (some #(and (= :text-delta (:type %))
                    (= "{\"ok\":true}" (:delta %)))
              @events))
    (is (some #(and (= :structured-output-result (:type %))
                    (= :prompted-json (get-in % [:structured-output :strategy]))
                    (= :prompted-json/text (get-in % [:structured-output :source]))
                    (= {:ok true} (get-in % [:structured-output :payload]))
                    (= "{\"ok\":true}" (get-in % [:structured-output :raw-payload])))
              @events))))

(deftest anthropic-json-schema-output-non-streaming-execute-test
  ;; Tests Anthropic non-streaming execute returns the top-level structured-output
  ;; surface for JSON Schema native responses and sends a non-streaming request
  ;; body instead of reusing the streaming request shape.
  (let [model            (models/get-model :sonnet-4.6)
        convo            (-> (conv/create "sys")
                             (conv/add-user-message "Review this"))
        captured-request (atom nil)
        body             {:content [{:type "text" :text "{\"ok\":true}"}]
                          :stop_reason "end_turn"
                          :usage {:input_tokens 1 :output_tokens 1}}
        result           (with-redefs [http/post (fn [_url req]
                                                   (reset! captured-request req)
                                                   {:status 200
                                                    :body (json/generate-string body)})]
                           ((:execute anthropic/provider)
                            convo model {:api-key "test-key"
                                         :structured-output judge-structured-output-request}))
        outbound-body    (json/parse-string (:body @captured-request) true)]
    (is (false? (contains? outbound-body :stream))
        "non-streaming execute should omit :stream from the Anthropic request body")
    (is (= {:ok true} (get-in result [:structured-output :payload])))
    (is (= :anthropic/json-schema-output
           (get-in result [:structured-output :source])))
    (is (= :anthropic/json-schema-output
           (get-in result [:structured-output :native-mechanism])))
    (is (= "{\"ok\":true}" (get-in result [:structured-output :raw-payload])))))

(deftest anthropic-json-schema-output-non-streaming-parse-failure-test
  ;; Tests Anthropic JSON Schema native non-streaming invalid output preserves
  ;; raw text and marks parse failure without a trusted payload.
  (let [model  (models/get-model :sonnet-4.6)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        cases  [{:label "invalid-json"
                 :text "not json"}]]
    (doseq [{:keys [label text]} cases]
      (let [body   {:content [{:type "text" :text text}]
                    :stop_reason "end_turn"
                    :usage {:input_tokens 1 :output_tokens 1}}
            result (with-redefs [http/post (fn [_url _req]
                                             {:status 200
                                              :body (json/generate-string body)})]
                     ((:execute anthropic/provider)
                      convo model {:api-key "test-key"
                                   :structured-output judge-structured-output-request}))]
        (is (= :anthropic/json-schema-output
               (get-in result [:structured-output :source]))
            label)
        (is (= :anthropic/json-schema-output
               (get-in result [:structured-output :native-mechanism]))
            label)
        (is (= text (get-in result [:structured-output :raw-payload])) label)
        (is (true? (get-in result [:structured-output :parse-error?])) label)
        (is (not (contains? (:structured-output result) :payload)) label)))))

(deftest anthropic-streaming-json-schema-output-parse-failure-test
  ;; Tests Anthropic JSON Schema native streaming invalid output emits a
  ;; parse-failure result with raw text and no trusted payload.
  (let [model (models/get-model :sonnet-4.6)
        convo (-> (conv/create "sys")
                  (conv/add-user-message "Review this"))
        cases [{:label "invalid-json"
                :text "not json"}]]
    (doseq [{:keys [label text]} cases]
      (let [events (atom [])
            sse    (str (sse-line "message_start"
                                  {:type "message_start"
                                   :message {:usage {:input_tokens 1}}})
                        (sse-line "content_block_start"
                                  {:type "content_block_start"
                                   :index 0
                                   :content_block {:type "text"}})
                        (sse-line "content_block_delta"
                                  {:type "content_block_delta"
                                   :index 0
                                   :delta {:text text}})
                        (sse-line "content_block_stop"
                                  {:type "content_block_stop"
                                   :index 0})
                        (sse-line "message_delta"
                                  {:type "message_delta"
                                   :delta {:stop_reason "end_turn"}
                                   :usage {:output_tokens 1}}))]
        (with-redefs [http/post (fn [_url _req]
                                  {:body (stream-body sse)})]
          ((:stream anthropic/provider)
           convo model {:api-key "test-key"
                        :structured-output judge-structured-output-request}
           (fn [ev] (swap! events conj ev))))
        (let [result (some #(when (= :structured-output-result (:type %)) %) @events)
              structured (:structured-output result)]
          (is (some? result) label)
          (is (= :anthropic/json-schema-output (:source structured)) label)
          (is (= :anthropic/json-schema-output (:native-mechanism structured)) label)
          (is (= text (:raw-payload structured)) label)
          (is (true? (:parse-error? structured)) label)
          (is (not (contains? structured :payload)) label))))))

(deftest anthropic-structured-output-result-json-value-payloads-test
  ;; Tests the shared Anthropic structured-output result helper preserves every
  ;; valid JSON value, including present nil for JSON null.
  (let [strategy {:strategy :provider-native
                  :native-mechanism :anthropic/json-schema-output}
        cases    [{:label "string" :raw "\"DONE\"" :expected "DONE"}
                  {:label "number" :raw "42" :expected 42}
                  {:label "boolean" :raw "true" :expected true}
                  {:label "array" :raw "[true]" :expected [true]}
                  {:label "object" :raw "{\"ok\":true}" :expected {:ok true}}
                  {:label "null" :raw "null" :expected nil}]]
    (doseq [{:keys [label raw expected]} cases
            source [:anthropic/json-schema-output :prompted-json/text]]
      (let [result (#'psi.ai.providers.anthropic.structured-output/structured-output-result
                    (if (= :prompted-json/text source)
                      {:strategy :prompted-json :fallback-used? true}
                      strategy)
                    source
                    raw)]
        (is (= expected (:payload result)) label)
        (is (contains? result :payload) label)
        (is (not (:parse-error? result)) label)
        (is (= raw (:raw-payload result)) label)
        (is (= source (:source result)) label)))))

(deftest anthropic-streaming-json-schema-output-events-test
  ;; Tests JSON Schema native streaming preserves text events and emits a parsed
  ;; structured-output result sourced from ordinary assistant text.
  (let [model  (models/get-model :sonnet-4.6)
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        events (atom [])
        sse    (str (sse-line "message_start"
                              {:type "message_start"
                               :message {:usage {:input_tokens 1}}})
                    (sse-line "content_block_start"
                              {:type "content_block_start"
                               :index 0
                               :content_block {:type "text"}})
                    (sse-line "content_block_delta"
                              {:type "content_block_delta"
                               :index 0
                               :delta {:text "{\"ok\":true}"}})
                    (sse-line "content_block_stop"
                              {:type "content_block_stop"
                               :index 0})
                    (sse-line "message_delta"
                              {:type "message_delta"
                               :delta {:stop_reason "end_turn"}
                               :usage {:output_tokens 1}}))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream anthropic/provider)
       convo model {:api-key "test-key"
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (some #(and (= :text-delta (:type %))
                    (= "{\"ok\":true}" (:delta %)))
              @events))
    (is (some #(and (= :structured-output-result (:type %))
                    (= {:ok true} (get-in % [:structured-output :payload]))
                    (= :anthropic/json-schema-output
                       (get-in % [:structured-output :source])))
              @events))))
