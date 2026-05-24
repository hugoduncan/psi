(ns psi.ai.providers.anthropic-structured-output-test
  (:require
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
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

(deftest anthropic-structured-output-forced-tool-request-shaping-test
  ;; Tests Anthropic provider-native structured output as a synthetic forced tool
  ;; composed alongside ordinary user tools.
  (let [model (models/get-model :sonnet-4.6)
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
  (let [model   (models/get-model :sonnet-4.6)
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
  ;; Tests streaming strategy and result metadata are emitted as first-class AI events.
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
