(ns psi.ai.providers.openai-codex-retry-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [psi.ai.conversation :as conv]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
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

(deftest codex-stream-failed-event-preserves-status-and-headers-test
  ;; OpenAI Codex can report a terminal provider failure inside a 2xx SSE stream;
  ;; status and retry headers must survive so session retry can classify it.
  ;; Review 52: emit-codex-error! emits :start first when the stream never
  ;; produced output, so an error-first stream yields [:start :error].
  (let [model  (models/get-model :gpt-5.3-codex)
        token  (jwt-with-account-id "acc_test")
        convo  (-> (conv/create "sys")
                   (conv/add-user-message "hello"))
        events (atom [])
        sse    (str "data: "
                    (json/generate-string
                     {:type "response.failed"
                      :response {:error {:message "The usage limit has been reached (status 429) [request-id req_123]"
                                         :status 429
                                         :headers {"Retry-After" "8"}}}})
                    "\n\n")
        http   (http-boundary/nullable
                [{:status 200 :body (stream-body sse)}])]
    ((:stream openai/provider)
     convo model {:api-key token :http-boundary http}
     (fn [ev] (swap! events conj ev)))
    (is (= 1 (count (http-boundary/requests http))))
    (is (= [:start :error] (mapv :type @events)))
    (is (= "The usage limit has been reached (status 429) [request-id req_123]"
           (:error-message (second @events))))
    (is (= 429 (:http-status (second @events))))
    (is (= {"Retry-After" "8"}
           (:headers (second @events))))))
