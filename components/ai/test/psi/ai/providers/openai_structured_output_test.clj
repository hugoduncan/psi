(ns psi.ai.providers.openai-structured-output-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai]
   [psi.ai.providers.openai.chat-completions]
   [psi.ai.providers.openai.codex-structured-output]
   [psi.ai.structured-output :as structured-output])
  (:import [java.io ByteArrayInputStream]
           [java.util Base64]))

(defn- jwt-with-account-id
  [account-id]
  (let [payload-json (json/generate-string
                      {"https://api.openai.com/auth"
                       {"chatgpt_account_id" account-id}})
        payload      (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                      (.getBytes payload-json "UTF-8"))]
    (str "aaa." payload ".bbb")))

(defn- stream-body
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

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

(deftest openai-chat-completions-structured-output-request-shaping-test
  ;; Tests OpenAI Chat Completions native JSON Schema request construction and
  ;; explicit schema-only rejection without fallback prompt mutation.
  (let [convo (-> (conv/create "sys")
                  (conv/add-user-message "Review this"))]
    (testing "native-capable chat completions request includes response_format"
      (let [model (models/get-model :gpt-5)
            req   (#'openai/build-request convo model {:api-key "sk-test"
                                                       :structured-output judge-structured-output-request})
            body  (json/parse-string (:body req) true)]
        (is (= {:type "json_schema"
                :json_schema {:name "judge_review_result"
                              :strict true
                              :schema judge-json-schema}}
               (:response_format body)))
        (is (not (re-find #"Structured output required"
                          (get-in body [:messages 1 :content]))))))

    (testing "schema-only request is unsupported and does not mutate request shape"
      (let [model    (models/get-model :gpt-5)
            request  (dissoc judge-structured-output-request :json-schema)
            strategy (structured-output/select-strategy model request)
            req      (#'openai/build-request convo model {:api-key "sk-test"
                                                          :structured-output request})
            body     (json/parse-string (:body req) true)]
        (is (= :unsupported (:strategy strategy)))
        (is (= :missing-json-schema (:reason strategy)))
        (is (nil? (:response_format body)))
        (is (not (re-find #"Structured output required"
                          (get-in body [:messages 1 :content]))))))))

(deftest openai-codex-structured-output-native-request-shaping-test
  ;; Tests ChatGPT/Codex native streaming structured-output request construction
  ;; uses Responses-style text.format and not Chat Completions response_format.
  (let [model (models/get-model :gpt-5.3-codex)
        token (jwt-with-account-id "acc_test")
        convo (-> (conv/create "sys")
                  (conv/add-user-message "Review this"))]
    (testing "native-capable codex request includes text.format and omits response_format"
      (let [req  (#'openai/build-codex-request convo model {:api-key token
                                                            :structured-output judge-structured-output-request})
            body (json/parse-string (:body req) true)
            text (get-in body [:input 0 :content 0 :text])]
        (is (= {:type "json_schema"
                :name "judge_review_result"
                :schema judge-json-schema
                :strict true}
               (get-in body [:text :format])))
        (is (not (contains? body :response_format)))
        (is (re-find #"Review this" text))
        (is (not (re-find #"Structured output required" text)))))

    (testing "fallback disallowed becomes unsupported and does not inject fallback instructions"
      (let [fallback-model (structured-output/with-openai-codex-fallback-capability model)
            request        (assoc judge-structured-output-request :fallback-allowed? false)
            req            (#'openai/build-codex-request convo fallback-model {:api-key token
                                                                               :structured-output request})
            body           (json/parse-string (:body req) true)
            text           (get-in body [:input 0 :content 0 :text])]
        (is (not (re-find #"Structured output required" text)))
        (is (nil? (get-in body [:text :format])))))

    (testing "fallback-only capability still avoids native fields and uses prompt injection"
      (let [fallback-model (structured-output/with-openai-codex-fallback-capability model)
            req            (#'openai/build-codex-request convo fallback-model {:api-key token
                                                                               :structured-output judge-structured-output-request})
            body           (json/parse-string (:body req) true)
            text           (get-in body [:input 0 :content 0 :text])]
        (is (nil? (get-in body [:text :format])))
        (is (not (contains? body :response_format)))
        (is (re-find #"Structured output required" text))))))

(defn- openai-chat-result-payload
  [strategy raw]
  (#'psi.ai.providers.openai.chat-completions/structured-output-result
   strategy
   (if (= :provider-native (:strategy strategy))
     :openai/message-json
     :prompted-json/text)
   raw))

(deftest openai-chat-completions-structured-output-json-value-payloads-test
  ;; Tests the shared Chat Completions result helper used by both streaming and
  ;; non-streaming result paths preserves every JSON value for provider-native
  ;; and prompted-JSON strategies with raw text payload.
  (let [cases [{:label "string" :raw "\"DONE\"" :expected "DONE"}
               {:label "number" :raw "42" :expected 42}
               {:label "boolean" :raw "true" :expected true}
               {:label "array" :raw "[true]" :expected [true]}
               {:label "object" :raw "{\"ok\":true}" :expected {:ok true}}
               {:label "null" :raw "null" :expected nil}]]
    (doseq [{:keys [label raw expected]} cases
            strategy [{:strategy :provider-native}
                      {:strategy :prompted-json :fallback-used? true}]]
      (let [result (openai-chat-result-payload strategy raw)]
        (is (= expected (:payload result)) label)
        (is (contains? result :payload) label)
        (is (= raw (:raw-payload result)) label)
        (is (not (:parse-error? result)) label)
        (is (= (if (= :provider-native (:strategy strategy))
                 :openai/message-json
                 :prompted-json/text)
               (:source result)) label)))))

(deftest openai-codex-structured-output-null-payload-test
  ;; Tests Codex structured-output result preserves JSON null as present payload.
  (let [result (#'psi.ai.providers.openai.codex-structured-output/structured-output-result
                {:strategy :provider-native
                 :native-mechanism :openai/responses-text-format-json-schema}
                :openai/codex-text-format
                "null")]
    (is (contains? result :payload))
    (is (nil? (:payload result)))
    (is (= "null" (:raw-payload result)))
    (is (not (:parse-error? result)))))

(deftest prompted-json-instruction-requests-json-value-test
  ;; Tests prompted JSON fallback permits non-object JSON Schema outputs.
  (let [text (structured-output/json-only-instruction judge-structured-output-request)]
    (is (re-find #"Return exactly one JSON value" text))
    (is (not (re-find #"Return exactly one JSON object" text)))
    (is (re-find #"Do not wrap the JSON in Markdown fences" text))
    (is (re-find #"do not add prose" text))
    (is (re-find #"do not emit extra top-level text" text))))

(deftest openai-non-streaming-structured-output-result-metadata-test
  ;; Tests top-level structured-output metadata and extracted payload handoff.
  (let [model (models/get-model :gpt-5)
        convo (-> (conv/create "sys")
                  (conv/add-user-message "Review this"))
        body  {:choices [{:finish_reason "stop"
                          :message {:role "assistant"
                                    :content "{\"ok\":true}"}}]}]
    (with-redefs [http/post (fn [_url _req]
                              {:status 200
                               :body (json/generate-string body)})]
      (let [result ((:execute openai/provider) convo model {:api-key "sk-test"
                                                            :structured-output judge-structured-output-request})]
        (is (= :provider-native (get-in result [:structured-output :strategy])))
        (is (= :openai/message-json (get-in result [:structured-output :source])))
        (is (= {:ok true} (get-in result [:structured-output :payload])))
        (is (= "{\"ok\":true}"
               (get-in result [:assistant-message :content 0 :text])))))))

(deftest openai-streaming-structured-output-events-test
  ;; Tests streaming strategy and result metadata are emitted as first-class AI events.
  (let [model    (models/get-model :gpt-5)
        convo    (-> (conv/create "sys")
                     (conv/add-user-message "Review this"))
        events   (atom [])
        sse      (str "data: " (json/generate-string
                                {:choices [{:delta {:role "assistant"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:delta {:content "{\"ok\":true}"}}]}) "\n\n"
                      "data: " (json/generate-string
                                {:choices [{:finish_reason "stop"}]
                                 :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "\n\n")]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream openai/provider)
       convo model {:api-key "sk-test"
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (some #(and (= :structured-output-strategy (:type %))
                    (= :provider-native (get-in % [:structured-output :strategy])))
              @events))
    (is (some #(and (= :structured-output-result (:type %))
                    (= {:ok true} (get-in % [:structured-output :payload]))
                    (= :openai/message-json (get-in % [:structured-output :source])))
              @events))))

(deftest openai-codex-streaming-native-structured-output-events-test
  ;; Tests native ChatGPT/Codex streaming emits first-class provider-native
  ;; strategy and structured result events from ordinary output text.
  (let [model  (models/get-model :gpt-5.3-codex)
        token  (jwt-with-account-id "acc_test")
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "Review this"))
        events (atom [])
        sse    (str "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "message"
                                      :id "msg_1"
                                      :role "assistant"
                                      :status "in_progress"
                                      :content []}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.output_text.delta"
                               :delta "{\"ok\":true}"}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"
                                          :usage {:input_tokens 1
                                                  :output_tokens 1
                                                  :total_tokens 2
                                                  :input_tokens_details {:cached_tokens 0}}}}) "\n\n")]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream openai/provider)
       convo model {:api-key token
                    :structured-output judge-structured-output-request}
       (fn [ev] (swap! events conj ev))))
    (is (some #(and (= :structured-output-strategy (:type %))
                    (= :provider-native (get-in % [:structured-output :strategy]))
                    (= :openai/responses-text-format-json-schema
                       (get-in % [:structured-output :native-mechanism])))
              @events))
    (is (some #(and (= :structured-output-result (:type %))
                    (= :provider-native (get-in % [:structured-output :strategy]))
                    (= {:ok true} (get-in % [:structured-output :payload]))
                    (= :openai/codex-text-format (get-in % [:structured-output :source])))
              @events))))

(deftest openai-codex-streaming-native-scalar-structured-output-events-test
  ;; Tests native ChatGPT/Codex streaming preserves valid non-object JSON payloads
  ;; such as workflow loop-control string enums.
  (let [model   (models/get-model :gpt-5.3-codex)
        token   (jwt-with-account-id "acc_test")
        convo   (-> (conv/create "sys")
                    (conv/add-user-message "Review this"))
        request (assoc judge-structured-output-request
                       :name "loop_control"
                       :json-schema {:type "string"
                                     :enum ["REPEAT" "DONE"]})
        events  (atom [])
        sse     (str "data: " (json/generate-string
                               {:type "response.output_item.added"
                                :item {:type "message"
                                       :id "msg_1"
                                       :role "assistant"
                                       :status "in_progress"
                                       :content []}}) "\n\n"
                     "data: " (json/generate-string
                               {:type "response.output_text.delta"
                                :delta "\"DONE\""}) "\n\n"
                     "data: " (json/generate-string
                               {:type "response.completed"
                                :response {:status "completed"
                                           :usage {:input_tokens 1
                                                   :output_tokens 1
                                                   :total_tokens 2
                                                   :input_tokens_details {:cached_tokens 0}}}}) "\n\n")]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream openai/provider)
       convo model {:api-key token
                    :structured-output request}
       (fn [ev] (swap! events conj ev))))
    (let [result (some #(when (= :structured-output-result (:type %)) %) @events)
          structured (:structured-output result)]
      (is (some? result))
      (is (= :provider-native (:strategy structured)))
      (is (= :openai/responses-text-format-json-schema (:native-mechanism structured)))
      (is (= :openai/codex-text-format (:source structured)))
      (is (= "DONE" (:payload structured)))
      (is (= "\"DONE\"" (:raw-payload structured)))
      (is (not (:parse-error? structured))))))
