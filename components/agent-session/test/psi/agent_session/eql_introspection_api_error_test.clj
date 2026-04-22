(ns psi.agent-session.eql-introspection-api-error-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.session-state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- inject-messages!
  [ctx session-id msgs]
  (let [agent-ctx (ss/agent-ctx-in ctx session-id)]
    (doseq [m msgs]
      (agent-core/append-message-in! agent-ctx m))))

(defn- make-user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- make-assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :stop-reason :stop :timestamp (java.time.Instant/now)})

(defn- make-tool-call-msg [text tool-id tool-name]
  {:role "assistant"
   :content [{:type :text :text text}
             {:type :tool-call :id tool-id :name tool-name :arguments "{}"}]
   :stop-reason :tool_use :timestamp (java.time.Instant/now)})

(defn- make-tool-result-msg [tool-id tool-name result]
  {:role "toolResult" :tool-call-id tool-id :tool-name tool-name
   :content [{:type :text :text result}] :is-error false
   :timestamp (java.time.Instant/now)})

(defn- make-error-msg [error-text http-status]
  {:role "assistant"
   :content [{:type :error :text error-text}]
   :stop-reason :error :http-status http-status
   :timestamp (java.time.Instant/now)})

(deftest api-error-list-test
  (testing "no errors → count 0, empty list"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-assistant-msg "hello")])
      (let [r (session/query-in ctx session-id [:psi.agent-session/api-error-count])]
        (is (zero? (:psi.agent-session/api-error-count r))))))

  (testing "provider reply error is exposed via api-errors"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)]
      (test-support/update-state! ctx :provider-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-1"
                                   :timestamp t0
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_123]"
                                           :http-status 400
                                           :headers {"request-id" "req_ant_123"}
                                           :body-text "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Error\"},\"request_id\":\"req_ant_123\"}"
                                           :body {:type "error"
                                                  :error {:type "invalid_request_error"
                                                          :message "Error"}
                                                  :request_id "req_ant_123"}}})
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/http-status
                                   :psi.api-error/request-id
                                   :psi.api-error/provider
                                   :psi.api-error/api
                                   :psi.api-error/url
                                   :psi.api-error/turn-id
                                   :psi.api-error/error-message-full
                                   :psi.api-error/provider-event]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= 1 (count (:psi.agent-session/api-errors r))))
        (is (= 400 (:psi.api-error/http-status err)))
        (is (= "req_ant_123" (:psi.api-error/request-id err)))
        (is (= :anthropic (:psi.api-error/provider err)))
        (is (= :anthropic-messages (:psi.api-error/api err)))
        (is (= "https://api.anthropic.com/v1/messages" (:psi.api-error/url err)))
        (is (= "turn-ant-1" (:psi.api-error/turn-id err)))
        (is (= "Error (status 400) [request-id req_ant_123]"
               (:psi.api-error/error-message-full err)))
        (is (= :error (get-in err [:psi.api-error/provider-event :type])))
        (is (= 400 (get-in err [:psi.api-error/provider-event :http-status])))
        (is (string? (get-in err [:psi.api-error/provider-event :body-text]))))))

  (testing "single 400 error → count 1 with correct fields"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-error-msg "clj-http: status 400 {}" 400)])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/message-index
                                   :psi.api-error/http-status
                                   :psi.api-error/error-message-brief]}])
            errors (:psi.agent-session/api-errors r)]
        (is (= 1 (count errors)))
        (is (= 1 (:psi.api-error/message-index (first errors))))
        (is (= 400 (:psi.api-error/http-status (first errors))))
        (is (string? (:psi.api-error/error-message-brief (first errors)))))))

  (testing "assistant error is enriched from matching provider reply by request-id"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-error-msg
                                         "Error (status 400) [request-id req_ant_live]"
                                         400)])
      (test-support/update-state! ctx :provider-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-live"
                                   :timestamp t0
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_live]"
                                           :http-status 400
                                           :headers {"request-id" "req_ant_live"}
                                           :body-text "{\"type\":\"error\",\"request_id\":\"req_ant_live\"}"
                                           :body {:type "error" :request_id "req_ant_live"}}})
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/message-index
                                   :psi.api-error/request-id
                                   :psi.api-error/provider
                                   :psi.api-error/api
                                   :psi.api-error/url
                                   :psi.api-error/turn-id
                                   :psi.api-error/provider-event]}])
            errors (:psi.agent-session/api-errors r)
            err    (first errors)]
        (is (= 1 (count errors)))
        (is (= 1 (:psi.api-error/message-index err)))
        (is (= "req_ant_live" (:psi.api-error/request-id err)))
        (is (= :anthropic (:psi.api-error/provider err)))
        (is (= :anthropic-messages (:psi.api-error/api err)))
        (is (= "https://api.anthropic.com/v1/messages" (:psi.api-error/url err)))
        (is (= "turn-ant-live" (:psi.api-error/turn-id err)))
        (is (= :error (get-in err [:psi.api-error/provider-event :type]))))))

  (testing "multiple errors → all captured"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-error-msg "error 1" 429)
                                        (make-user-msg "retry")
                                        (make-error-msg "error 2" 500)])
      (let [r (session/query-in ctx session-id [:psi.agent-session/api-error-count])]
        (is (= 2 (:psi.agent-session/api-error-count r)))))))

(deftest api-error-detail-test
  (testing "request-id parsed from error text"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-error-msg
                                         "clj-http: status 400 {\"request-id\" \"req_abc123\"}"
                                         400)])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/request-id]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= "req_abc123" (:psi.api-error/request-id err))))))

  (testing "request-id parsed from normalized provider error suffix"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hi")
                                        (make-error-msg
                                         "Error (status 400) [request-id req_011CZ8hy9y3kRrVsNfhmugS1]"
                                         400)])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/request-id]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= "req_011CZ8hy9y3kRrVsNfhmugS1" (:psi.api-error/request-id err))))))

  (testing "surrounding messages include context window"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "step 1")
                                        (make-assistant-msg "response 1")
                                        (make-user-msg "step 2")
                                        (make-error-msg "boom" 400)])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [{:psi.api-error/surrounding-messages
                                    [:psi.context-message/index
                                     :psi.context-message/role]}]}])
            surr (-> r :psi.agent-session/api-errors first
                     :psi.api-error/surrounding-messages)]
        (is (pos? (count surr)))
        (is (= "user" (:psi.context-message/role (first surr))))))))

(deftest api-error-request-shape-test
  (testing "request shape computed at point of error"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "go")
                                        (make-tool-call-msg "running" "tc1" "bash")
                                        (make-tool-result-msg "tc1" "bash" "done")
                                        (make-error-msg "status 400" 400)])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/api-errors
                                  [{:psi.api-error/request-shape
                                    [:psi.request-shape/message-count
                                     :psi.request-shape/tool-use-count
                                     :psi.request-shape/tool-result-count
                                     :psi.request-shape/missing-tool-results
                                     :psi.request-shape/alternation-valid?
                                     :psi.request-shape/headroom-tokens]}]}])
            shape (-> r :psi.agent-session/api-errors first
                      :psi.api-error/request-shape)]
        (is (= 3 (:psi.request-shape/message-count shape)))
        (is (= 1 (:psi.request-shape/tool-use-count shape)))
        (is (= 1 (:psi.request-shape/tool-result-count shape)))
        (is (zero? (:psi.request-shape/missing-tool-results shape)))
        (is (true? (:psi.request-shape/alternation-valid? shape)))
        (is (pos? (:psi.request-shape/headroom-tokens shape)))))))
