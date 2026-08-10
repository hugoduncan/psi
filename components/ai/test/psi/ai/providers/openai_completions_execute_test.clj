(ns psi.ai.providers.openai-completions-execute-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.providers.openai :as openai]))

(deftest execute-openai-honors-http-boundary-test
  ;; Non-streaming requests cross the same injectable HTTP seam as streams.
  (let [model (models/get-model :gpt-4o)
        convo (-> (conv/create "sys") (conv/add-user-message "hello"))
        response {:status 200
                  :body (json/generate-string
                         {:choices [{:finish_reason "stop"
                                     :message {:role "assistant" :content "hello back"}}]
                          :usage {:prompt_tokens 2 :completion_tokens 3 :total_tokens 5}})}
        http (http-boundary/nullable [response])
        result ((:execute openai/provider)
                convo model {:api-key "sk-test" :http-boundary http})
        recorded (first (http-boundary/requests http))]
    (is (= "hello back" (get-in result [:assistant-message :content 0 :text])))
    (is (= "https://api.openai.com/v1/chat/completions" (:url recorded)))
    (is (= :text (get-in recorded [:request :as])))
    (is (false? (get-in recorded [:request :throw-exceptions])))))

(deftest local-openai-non-streaming-response-preserves-usage-test
  (testing "non-streaming local OpenAI-compatible responses keep usage totals"
    (let [model {:id "qwen-3.6-27b"
                 :provider :local3
                 :custom? true
                 :api :openai-completions
                 :base-url "http://localhost:8082/v1"
                 :supports-text true}
          convo (-> (conv/create "sys")
                    (conv/add-user-message "Reply with exactly: hi"))
          body {:choices [{:finish_reason "stop"
                           :index 0
                           :message {:role "assistant"
                                     :content "hi"
                                     :reasoning_content "internal reasoning"}}]
                :usage {:prompt_tokens 15
                        :completion_tokens 152
                        :total_tokens 167}}
          response-fn (fn [_]
                        {:status 200
                         :body (json/generate-string body)})
          http-client (http-boundary/nullable [response-fn response-fn])
          result ((:execute openai/provider) convo model {:http-boundary http-client
                                                          :no-auth-header true})]
      (is (= "hi" (get-in result [:assistant-message :content 0 :text])))
      (is (= :stop (get-in result [:assistant-message :stop-reason])))
      (is (= {:input-tokens 15
              :output-tokens 152
              :cache-read-tokens 0
              :cache-write-tokens 0
              :total-tokens 167
              :cost {:input 0.0
                     :output 0.0
                     :cache-read 0.0
                     :cache-write 0.0
                     :total 0.0}}
             (get-in result [:assistant-message :usage]))))))
