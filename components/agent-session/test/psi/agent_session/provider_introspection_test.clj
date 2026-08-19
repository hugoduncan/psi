(ns psi.agent-session.provider-introspection-test
  "Tests for EQL query-in introspection: provider request/reply captures,
  request shape, and the retry-compact resolver surface."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]))

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

(deftest provider-capture-eql-introspection-test
  (testing "query-in resolves provider request/reply captures"
    (let [[ctx session-id] (create-session-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 25)
          t2  (.plusMillis t0 50)]
      (test-support/update-state! ctx :provider-requests
                                  conj
                                  {:provider :openai
                                   :api :openai-codex-responses
                                   :url "https://chatgpt.com/backend-api/codex/responses"
                                   :turn-id "turn-123"
                                   :timestamp t0
                                   :request {:headers {"Authorization" "Bearer ***REDACTED*** (len=99)"}
                                             :body {:model "gpt-5.3-codex" :tool_choice "auto"}}})
      (test-support/update-state! ctx :provider-replies
                                  into
                                  [{:provider :openai
                                    :api :openai-codex-responses
                                    :url "https://chatgpt.com/backend-api/codex/responses"
                                    :turn-id "turn-123"
                                    :timestamp t1
                                    :event {:type "response.completed"
                                            :response {:status "completed"}}}
                                   {:provider :anthropic
                                    :api :anthropic-messages
                                    :url "https://api.anthropic.com/v1/messages"
                                    :turn-id "turn-ant-1"
                                    :timestamp t2
                                    :event {:type :error
                                            :error-message "Error (status 400) [request-id req_ant_1]"
                                            :http-status 400}}])
      (test-support/update-state! ctx :provider-error-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-1"
                                   :timestamp t2
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_1]"
                                           :http-status 400}})

      (let [r (session/query-in ctx session-id
                                [:psi.agent-session/provider-request-count
                                 :psi.agent-session/provider-reply-count
                                 {:psi.agent-session/provider-last-request
                                  [:psi.provider-request/provider
                                   :psi.provider-request/api
                                   :psi.provider-request/turn-id
                                   :psi.provider-request/body]}
                                 {:psi.agent-session/provider-last-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-last-error-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-error-replies
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}])]
        (is (= 1 (:psi.agent-session/provider-request-count r)))
        (is (= 2 (:psi.agent-session/provider-reply-count r)))

        (is (= :openai
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/provider])))
        (is (= :openai-codex-responses
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/api])))
        (is (= "turn-123"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/turn-id])))
        (is (= "gpt-5.3-codex"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/body
                          :model])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= 1 (count (:psi.agent-session/provider-error-replies r))))
        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-error-replies 0
                          :psi.provider-reply/provider])))))

    (testing "provider capture lookup by turn-id resolves exact request and reply"
      (let [[ctx session-id] (create-session-context)
            t0  (java.time.Instant/now)
            t1  (.plusMillis t0 25)]
        (test-support/update-state! ctx :provider-requests
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t0
                                     :request {:headers {"x-test" "1"}
                                               :body {:model "claude-sonnet-4-6"
                                                      :messages [{:role "user" :content [{:type "text" :text "hi"}]}]}}})
        (test-support/update-state! ctx :provider-replies
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t1
                                     :event {:type :error
                                             :error-message "Error (status 400) [request-id req_lookup]"
                                             :http-status 400}})

        (let [req ((resolve 'psi.agent-session.resolvers.telemetry/provider-request-by-turn-id)
                   {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                    :psi/agent-session-ctx ctx
                    :psi.agent-session/session-id session-id})
              reply ((resolve 'psi.agent-session.resolvers.telemetry/provider-reply-by-turn-id)
                     {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                      :psi/agent-session-ctx ctx
                      :psi.agent-session/session-id session-id})]
          (is (= :anthropic
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/provider])))
          (is (= :anthropic-messages
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/api])))
          (is (= "claude-sonnet-4-6"
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/body
                              :model])))
          (is (= :error
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :type])))
          (is (= 400
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :http-status]))))))))

(deftest current-request-shape-test
  (testing "current shape reflects all messages"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "hello")
                                        (make-assistant-msg "world")])
      (let [r (session/query-in ctx session-id
                                [{:psi.agent-session/request-shape
                                  [:psi.request-shape/message-count
                                   :psi.request-shape/estimated-tokens
                                   :psi.request-shape/context-window
                                   :psi.request-shape/alternation-valid?]}])
            shape (:psi.agent-session/request-shape r)]
        (is (= 2 (:psi.request-shape/message-count shape)))
        (is (pos? (:psi.request-shape/estimated-tokens shape)))
        (is (= 200000 (:psi.request-shape/context-window shape)))
        (is (true? (:psi.request-shape/alternation-valid? shape))))))

  (testing "headroom decreases as context grows"
    (let [[ctx session-id] (create-session-context)]
      (inject-messages! ctx session-id [(make-user-msg "a")])
      (let [r1 (session/query-in ctx session-id
                                 [{:psi.agent-session/request-shape
                                   [:psi.request-shape/headroom-tokens]}])
            h1 (-> r1 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
        ;; Add more messages
        (inject-messages! ctx session-id [(make-assistant-msg (apply str (repeat 1000 "x")))
                                          (make-user-msg "b")])
        (let [r2 (session/query-in ctx session-id
                                   [{:psi.agent-session/request-shape
                                     [:psi.request-shape/headroom-tokens]}])
              h2 (-> r2 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
          (is (< h2 h1)))))))

(deftest retry-compact-eql-introspection-test
  (testing "retry-compact resolver exposes retry-attempt / retry / retry-deadline-ms"
    (let [[ctx session-id] (create-session-context)
          now-ms           (System/currentTimeMillis)
          _                (ss/apply-root-state-update-in!
                            ctx
                            (ss/session-update session-id #(assoc %
                                                                  :retry-attempt 2
                                                                  :retry {:active? true
                                                                          :attempt 2
                                                                          :delay-ms 8000
                                                                          :delay-source :retry-after
                                                                          :resume-at (+ now-ms 8000)}
                                                                  :retry-deadline-ms (+ now-ms 600000))))
          r (session/query-in ctx session-id
                              [:psi.agent-session/retry-attempt
                               :psi.agent-session/retry-deadline-ms
                               :psi.agent-session/retry
                               :psi.agent-session/auto-retry-enabled])]
      (is (= 2 (:psi.agent-session/retry-attempt r)))
      (is (= (+ now-ms 600000) (:psi.agent-session/retry-deadline-ms r)))
      (is (= {:active? true
              :attempt 2
              :delay-ms 8000
              :delay-source :retry-after
              :resume-at (+ now-ms 8000)}
             (:psi.agent-session/retry r)))
      (is (true? (:psi.agent-session/auto-retry-enabled r)))))
  (testing "retry-deadline-ms resolves nil when no window is open"
    (let [[ctx session-id] (create-session-context)
          r (session/query-in ctx session-id [:psi.agent-session/retry-deadline-ms])]
      (is (nil? (:psi.agent-session/retry-deadline-ms r))))))
