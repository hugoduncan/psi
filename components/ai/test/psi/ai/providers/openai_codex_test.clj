(ns psi.ai.providers.openai-codex-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai]
   [psi.ai.providers.openai.transport :as transport])
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
(deftest codex-requires-chatgpt-token-test
  (testing "non-ChatGPT token emits an error event (missing chatgpt_account_id)"
    (let [model  (models/get-model :gpt-5.3-codex)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      ((:stream openai/provider)
       convo model {:api-key "not-a-jwt-token"}
       (fn [ev] (swap! events conj ev)))
      ;; Review 52: emit-codex-error! emits :start first when the stream
      ;; never produced output, so the sequence is [:start :error].
      (is (= [:start :error] (mapv :type @events)))
      (is (re-find #"chatgpt_account_id"
                   (:error-message (second @events)))))))
(deftest codex-reasoning-text-delta-maps-to-thinking-delta-test
  (testing "response.reasoning_text.delta is bridged as :thinking-delta"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "You are a helpful assistant")
                       (conv/add-user-message "Think then answer"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.reasoning_text.delta"
                               :delta "Plan step"}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan step" (:delta %)))
                @events))
      (is (some #(= :done (:type %)) @events)))))
(deftest codex-reasoning-map-delta-normalized-to-string-test
  (testing "non-string reasoning delta payloads are normalized to text"
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "sys") (conv/add-user-message "think"))
          events   (atom [])
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_item.added"
                               :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.reasoning_summary.delta"
                               :delta {:text "Plan chunk"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.completed"
                               :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(and (= :thinking-delta (:type %))
                      (= "Plan chunk" (:delta %)))
                @events)))))
(deftest codex-reasoning-output-item-done-emits-thinking-boundary-test
  (testing "response.output_item.done reasoning emits thinking start/end even without reasoning delta events"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "think"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 0
                             :item {:type "reasoning" :id "rs_1"}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_item.done"
                             :output_index 0
                             :item {:type "reasoning"
                                    :id "rs_1"
                                    :encrypted_content "enc"}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.completed"
                             :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (let [types (mapv :type @events)]
        (is (some #{:thinking-start} types))
        (is (some #{:thinking-end} types))))))
(deftest codex-thinking-level-maps-to-reasoning-effort-test
  (let [model (models/get-model :gpt-5.3-codex)]
    (is (= {"effort" "high" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :high})))
    (is (= {"effort" "minimal" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :minimal})))
    (is (= {"effort" "high" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :medium
                                            :effort-override :xhigh})))
    (is (= {"effort" "medium" "summary" "auto"}
           (#'openai/codex-reasoning model {:thinking-level :high
                                            :effort-override :medium})))
    (is (nil? (#'openai/codex-reasoning model {:thinking-level :off
                                               :effort-override :xhigh})))
    (is (= {"effort" "medium" "summary" "auto"}
           (#'openai/codex-reasoning model {})))))
(deftest codex-tool-call-id-roundtrip-test
  (testing "tool call ids split into call_id + item id (not single-char prefixes)"
    (let [call-id "call_abc123"
          item-id "fc_456def"
          full-id (str call-id "|" item-id)
          convo   (-> (conv/create "sys")
                      (conv/add-user-message "ls")
                      (conv/add-assistant-message
                       {:content
                        {:kind :structured
                         :blocks [{:kind  :tool-call
                                   :id    full-id
                                   :name  "bash"
                                   :input {"command" "ls"}}]}})
                      (conv/add-tool-result full-id "bash" {:kind :text :text "ok"} false))
          input   ((deref #'openai/codex-input-messages) convo)
          call    (second input)
          result  (nth input 2)]
      (is (= "function_call" (get call "type")))
      (is (= call-id (get call "call_id")))
      (is (= item-id (get call "id")))
      (is (= "function_call_output" (get result "type")))
      (is (= call-id (get result "call_id"))))))
(deftest codex-function-call-done-includes-final-arguments-test
  (testing "response.output_item.done can carry final function arguments"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "run pwd"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 0
                             :item {:type "function_call"
                                    :id "fc_1"
                                    :call_id "call_1"
                                    :name "bash"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_item.done"
                             :output_index 0
                             :item {:type "function_call"
                                    :id "fc_1"
                                    :call_id "call_1"
                                    :name "bash"
                                    :arguments "{\"command\":\"pwd\"}"}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.completed"
                             :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (some #(and (= :toolcall-start (:type %))
                      (= "call_1|fc_1" (:id %))
                      (= "bash" (:name %)))
                @events))
      (is (some #(and (= :toolcall-delta (:type %))
                      (= "{\"command\":\"pwd\"}" (:delta %)))
                @events))
      (is (some #(= :toolcall-end (:type %)) @events))
      (is (some #(= :done (:type %)) @events)))))
(deftest codex-non-2xx-response-map-surfaces-body-message-test
  (let [model  (models/get-model :gpt-5.3-codex)
        token  (jwt-with-account-id "acc_test")
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "hello"))
        events (atom [])]
    (with-redefs [http/post (fn [_url _req]
                              {:status 429
                               :headers {"x-request-id" "req_oai_429"}
                               :body (stream-body
                                      (json/generate-string
                                       {:error {:message "rate limit exceeded"}}))})]
      ((:stream openai/provider)
       convo model {:api-key token}
       (fn [ev] (swap! events conj ev))))
    ;; Review 52: emit-codex-error! emits :start first when the stream never
    ;; produced output, so the HTTP-error sequence is [:start :error].
    (is (= [:start :error] (mapv :type @events)))
    (is (= :error (:type (second @events))))
    (is (= "rate limit exceeded (status 429) [request-id req_oai_429]"
           (:error-message (second @events))))
    (is (= 429 (:http-status (second @events))))))

(deftest codex-http-error-surfaces-response-headers-test
  (testing "a codex HTTP-error response keeps its headers on the :error event"
    ;; Review 51: stream-openai-codex's HTTP-error branch destructured away
    ;; :headers/:body-text/:body even though emit-codex-error!'s 4-arity
    ;; accepts headers (used by the SSE response.failed / error branches) —
    ;; the only transport whose HTTP-error path lost request-id-style headers
    ;; for diagnostics (the anthropic and openai chat-completions paths
    ;; surface the full error map via response->error). A codex HTTP error
    ;; (401/429/500 from the ChatGPT backend or a custom codex endpoint) now
    ;; keeps its headers on the :error event, mirroring the sibling
    ;; transports.
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                {:status 429
                                 :headers {"x-request-id" "req_oai_429"
                                           "retry-after"  "5"}
                                 :body (stream-body
                                        (json/generate-string
                                         {:error {:message "rate limit exceeded"}}))})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :error] (mapv :type @events))
          "a stream that never produced output emits :start then the :error terminal (review 52)")
      (is (= :error (:type (second @events))))
      (is (= "rate limit exceeded (status 429) [request-id req_oai_429]"
             (:error-message (second @events)))
          "error message still surfaces (with the request-id header now)")
      (is (= 429 (:http-status (second @events))))
      (is (= "req_oai_429" (get-in (second @events) [:headers "x-request-id"]))
          "x-request-id header is kept on the :error event for diagnostics")
      (is (= "5" (get-in (second @events) [:headers "retry-after"]))
          "retry-after header is kept on the :error event"))))

(deftest codex-error-first-stream-emits-start-then-error-test
  (testing "an error-FIRST codex stream (response.failed before any output event) emits :start then :error"
    ;; Review 52: emit-codex-error! emitted [:error] with no preceding :start
    ;; when the stream errored before producing any output event — the
    ;; review-50 :start-before-terminal fix covered the anthropic "error"
    ;; branch and the terminal :done emitters but not the codex error
    ;; emitter, and the existing codex error test never caught it because it
    ;; starts with response.output_text.delta (which triggers :start via the
    ;; non-error path). The error emitter now emits :start first (mirroring
    ;; emit-codex-start!'s role in the codex EOF flush and the review-50
    ;; anthropic error branch), so an error-first stream yields
    ;; [:start :error] — the last three-transport asymmetry in the review-50
    ;; class.
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.failed"
                             :response {:error {:message "Overloaded"}}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :error] (mapv :type @events))
          "error-first stream emits :start then the :error terminal")
      (is (= "Overloaded" (:error-message (second @events)))
          "error message extracted from the response.failed body")
      (is (not-any? #(= :done (:type %)) @events)
          "no synthetic :done — the :error event is terminal"))))

(deftest codex-mid-stream-error-captured-once-test
  (testing "a codex mid-stream SSE error captures the constructed :error once, not the raw event twice"
    ;; Review 52: handle-codex-event! captured the raw response.failed/error
    ;; event at its top AND emit-codex-error! captured the CONSTRUCTED
    ;; :error event again — two :on-provider-response callbacks per codex
    ;; mid-stream error, while the anthropic "error" branch and openai
    ;; emit-chat-error! capture the raw SSE line only (the constructed
    ;; :error is never in their capture payload). The raw capture is now
    ;; skipped for the error event types, so the error line is captured
    ;; exactly once (as the CONSTRUCTED :error, matching the codex
    ;; HTTP-error path); non-error lines are still captured raw (matching
    ;; the sibling transports' raw-line capture). The trailing
    ;; response.output_text.delta after the error is a full no-op (done?
    ;; short-circuit — review 46), so it is not captured either.
    (let [model    (models/get-model :gpt-5.3-codex)
          token    (jwt-with-account-id "acc_test")
          convo    (-> (conv/create "sys") (conv/add-user-message "hi"))
          captures (atom [])
          sse      (str
                    "data: " (json/generate-string
                              {:type "response.output_text.delta"
                               :delta "Hello"}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.failed"
                               :response {:error {:message "Overloaded"}
                                          :status "failed"}}) "\n\n"
                    "data: " (json/generate-string
                              {:type "response.output_text.delta"
                               :delta "trailing"}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:api-key token
                      :on-provider-response #(swap! captures conj %)}
         (fn [_ev] nil)))
      (is (= 2 (count @captures))
          "exactly two captures: the raw non-error line + the constructed error (the raw response.failed line is NOT double-captured)")
      (is (= "response.output_text.delta" (get-in (first @captures) [:event :type]))
          "the first capture is the raw non-error SSE line (unchanged)")
      (is (not-any? #(= "response.failed" (get-in % [:event :type])) @captures)
          "the raw response.failed line is never captured (error lines capture only via the constructed :error)")
      (let [event (get-in (second @captures) [:event])]
        (is (= :error (:type event))
            "the error capture is the CONSTRUCTED :error (normalized), not the raw response.failed line")
        (is (= "Overloaded" (:error-message event))
            "the constructed error's message is captured")
        (is (= :openai-codex-responses (get-in (second @captures) [:api]))
            "capture carries the codex api tag")))))

(deftest codex-catch-block-surfaces-exception-headers-test
  (testing "a stream-read exception with response headers in ex-data keeps them on the :error event"
    ;; Review 52: stream-openai-codex's catch block destructured away
    ;; :headers/:body-text/:body from transport/exception->error and called
    ;; the 3-arity (headers nil) — the exact class review 51 just fixed on
    ;; the HTTP-error branch. Reachability is lower (non-HTTP stream
    ;; exceptions rarely carry response headers), but the fix is the same
    ;; one-line destructure: the catch now passes headers through, so an
    ;; exception whose ex-data carries headers surfaces them on the :error
    ;; event for diagnostics, consistent with the review-51-fixed branch and
    ;; the sibling transports.
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_text.delta"
                             :delta "Hello"}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})
                    transport/parse-sse-line
                    (fn [_line]
                      (throw (ex-info "simulated stream read failure"
                                      {:status 429
                                       :headers {"x-request-id" "req_catch_429"}})))]
        ((:stream openai/provider)
         convo model {:api-key token}
         (fn [ev] (swap! events conj ev))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (some? err) "the exception surfaces as an :error event")
        (is (= "req_catch_429" (get-in err [:headers "x-request-id"]))
            "exception ex-data headers are kept on the :error event (review-52 catch-block fix)")
        (is (= "simulated stream read failure (status 429) [request-id req_catch_429]"
               (:error-message err))
            "error message includes the status and request-id from the exception ex-data")
        (is (= 429 (:http-status err))
            "exception ex-data status is carried through")))))

(deftest codex-chatgpt-account-id-capture-masked-test
  ;; Review 21: mask-chatgpt-account-id (first 6 chars + "...",
  ;; request_support.clj) is wired into openai/transport.clj
  ;; redact-request-headers, but no capture-path test asserts the masked
  ;; output — codex-request-and-reply-capture-callbacks-test asserts only
  ;; Authorization redaction, and custom-header-auth-redacted-in-captures-test
  ;; covers X-API-Key/authorization only. Locks the mask on the
  ;; :on-provider-request payload for a wire chatgpt-account-id header
  ;; (keyless codex request, custom header passes through per review 18) and a
  ;; mixed-case duplicate (review 19 dual-casing semantics: EVERY
  ;; case-insensitive match is masked).
  (testing "wire chatgpt-account-id headers are masked to first-6-chars in :on-provider-request captures"
    (let [model           {:id                 "local-codex"
                           :name               "Local Codex"
                           :provider           :local
                           :custom?            true
                           :api                :openai-codex-responses
                           :base-url           "http://localhost:8080/v1"
                           :supports-reasoning true
                           :supports-images    false
                           :supports-text      true
                           :context-window     128000
                           :max-tokens         16384
                           :input-cost         0.0
                           :output-cost        0.0
                           :cache-read-cost    0.0
                           :cache-write-cost   0.0}
          convo           (-> (conv/create "sys")
                              (conv/add-user-message "hello"))
          request-capture (atom nil)
          sse             (str
                           "data: " (json/generate-string
                                     {:type "response.output_item.added"
                                      :item {:type "message"
                                             :id "msg_1"
                                             :role "assistant"
                                             :status "in_progress"
                                             :content []}}) "\n\n"
                           "data: " (json/generate-string
                                     {:type "response.completed"
                                      :response {:status "completed"}}) "\n\n")]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        ((:stream openai/provider)
         convo model {:no-auth-header true
                      :headers {"chatgpt-account-id" "acc_1234567890"
                                "ChatGPT-Account-Id" "acc_0987654321"}
                      :on-provider-request #(reset! request-capture %)}
         (fn [_ev] nil)))
      (is (= "acc_12..." (get-in @request-capture [:request :headers "chatgpt-account-id"]))
          "lowercase chatgpt-account-id must be masked to first 6 chars + '...'")
      (is (= "acc_09..." (get-in @request-capture [:request :headers "ChatGPT-Account-Id"]))
          "mixed-case duplicate chatgpt-account-id must also be masked (review 19 dual-casing)")
      (is (nil? (get-in @request-capture [:request :headers "Authorization"]))
          "keyless request sends no Authorization header"))))

(deftest codex-done-balances-multiple-open-tool-calls-in-index-order-test
  ;; Terminal balancing must be independent of insertion and set traversal order.
  (testing "response.completed closes multiple open tools in content-index order"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "run tools"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 2
                             :item {:type "function_call"
                                    :id "fc_2"
                                    :call_id "call_2"
                                    :name "second"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 100
                             :item {:type "function_call"
                                    :id "fc_100"
                                    :call_id "call_100"
                                    :name "hundredth"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.completed"
                             :response {:status "completed"}}) "\n\n")
          http    (http-boundary/nullable [{:body (stream-body sse)}])]
      ((:stream openai/provider)
       convo model {:api-key token :http-boundary http}
       (fn [ev] (swap! events conj ev)))
      (is (= 1 (count (http-boundary/requests http))))
      (is (= [[:start nil]
              [:toolcall-start 2]
              [:toolcall-start 100]
              [:toolcall-end 2]
              [:toolcall-end 100]
              [:done nil]]
             (mapv (juxt :type :content-index) @events))))))

(deftest codex-error-balances-multiple-open-tool-calls-in-index-order-test
  ;; The shared error terminal must preserve the same deterministic order as done.
  (testing "response.failed closes multiple open tools in content-index order"
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "run tools"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 2
                             :item {:type "function_call"
                                    :id "fc_2"
                                    :call_id "call_2"
                                    :name "second"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 100
                             :item {:type "function_call"
                                    :id "fc_100"
                                    :call_id "call_100"
                                    :name "hundredth"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.failed"
                             :response {:error {:message "Overloaded"}
                                        :status "failed"}}) "\n\n")
          http    (http-boundary/nullable [{:body (stream-body sse)}])]
      ((:stream openai/provider)
       convo model {:api-key token :http-boundary http}
       (fn [ev] (swap! events conj ev)))
      (is (= 1 (count (http-boundary/requests http))))
      (is (= [[:start nil]
              [:toolcall-start 2]
              [:toolcall-start 100]
              [:toolcall-end 2]
              [:toolcall-end 100]
              [:error nil]]
             (mapv (juxt :type :content-index) @events))))))

(deftest codex-error-after-tool-start-balances-open-tool-call-test
  (testing "a response.failed after a function_call output item closes the open tool call before :error"
    ;; Review 56: review-55's open-tool balancing covered only the :done
    ;; path (emit-codex-done!'s open-tool-indexes doseq) — emit-codex-error!
    ;; (shared by every codex error path: response.failed/error SSE events,
    ;; the HTTP-error branch, the catch block) never balanced open tool
    ;; indexes, so a function_call output item followed by response.failed
    ;; finalized the turn accumulator with an OPEN tool index
    ;; ([:start :toolcall-start :error]). The error emitter now doseqs
    ;; :toolcall-end over the open indexes (mirroring emit-codex-done!)
    ;; before the :error; a no-op on the error-first / HTTP-error paths
    ;; (no output item was added before they fire).
    (let [model  (models/get-model :gpt-5.3-codex)
          token  (jwt-with-account-id "acc_test")
          convo  (-> (conv/create "sys") (conv/add-user-message "run pwd"))
          events (atom [])
          sse    (str
                  "data: " (json/generate-string
                            {:type "response.output_item.added"
                             :output_index 0
                             :item {:type "function_call"
                                    :id "fc_1"
                                    :call_id "call_1"
                                    :name "bash"
                                    :arguments ""}}) "\n\n"
                  "data: " (json/generate-string
                            {:type "response.failed"
                             :response {:error {:message "Overloaded"}
                                        :status "failed"}}) "\n\n")
          http    (http-boundary/nullable [{:body (stream-body sse)}])]
      ((:stream openai/provider)
       convo model {:api-key token :http-boundary http}
       (fn [ev] (swap! events conj ev)))
      (is (= [:start :toolcall-start :toolcall-end :error]
             (mapv :type @events))
          "the open function_call tool is balanced with :toolcall-end before the :error")
      (is (= "Overloaded" (:error-message (last @events)))
          "the response.failed error still surfaces with its message")
      (is (not-any? #(= :done (:type %)) @events)
          "no synthetic :done — the :error event is terminal"))))
