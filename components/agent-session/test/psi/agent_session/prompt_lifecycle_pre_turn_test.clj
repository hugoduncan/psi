(ns psi.agent-session.prompt-lifecycle-pre-turn-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]
   [psi.turn-runtime.core]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest pre-turn-augmentation-opens-closes-and-schedules-prepare-test
  ;; Pre-turn augmentation is an explicit lifecycle barrier before request preparation.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (kernel/clear-event-log!)
    (kernel/clear-dispatch-trace!)
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-barrier"
                             :user-msg user-msg}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-barrier"
                             :user-msg user-msg}
                            {:origin :core}))
    (let [session-data (ss/get-session-data-in ctx session-id)
          entries (kernel/dispatch-trace-entries)]
      (is (= :turn/augmentation-closed
             (get-in session-data [:prompt-turns "turn-barrier" :state])))
      (is (= :no-op
             (get-in session-data [:turn-augmentations "turn-barrier" :status])))
      (is (= [:session/prompt-submit
              :session/pre-turn-augment
              :session/close-pre-turn-augmentation
              :session/prompt-prepare-request]
             (->> entries
                  (filter #(= :dispatch/received (:trace/kind %)))
                  (keep :event-type)
                  (filter #{:session/prompt-submit
                            :session/pre-turn-augment
                            :session/close-pre-turn-augmentation
                            :session/prompt-prepare-request})
                  vec))))))

(deftest prompt-prepare-request-rejects-before-augmentation-closed-test
  ;; Direct prepare attempts cannot bypass the augmentation lifecycle barrier.
  (let [[ctx session-id] (create-session-context {:persist? false})
        user-msg {:role "user"
                  :content [{:type :text :text "hello"}]
                  :timestamp (java.time.Instant/now)}]
    (kernel/clear-dispatch-trace!)
    (session/dispatch-in! ctx :session/prompt-submit
                          {:session-id session-id
                           :turn-id "turn-direct"
                           :user-msg user-msg}
                          {:origin :core})
    (let [result (session/dispatch-in! ctx :session/prompt-prepare-request
                                       {:session-id session-id
                                        :turn-id "turn-direct"
                                        :user-msg user-msg}
                                       {:origin :core})
          session-data (ss/get-session-data-in ctx session-id)]
      (is (nil? result))
      (is (nil? (:last-prepared-request-summary session-data)))
      (is (= :turn/submitted
             (get-in session-data [:prompt-turns "turn-direct" :state]))))))

(deftest prompt-prepare-request-consumes-queued-steering-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (session/dispatch-in! ctx :session/enqueue-steering-message
                          {:session-id session-id
                           :text "Please be brief."}
                          {:origin :core})
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "ok"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :session/prompt-submit
                            {:session-id session-id
                             :turn-id "turn-steer"
                             :user-msg nil}
                            {:origin :core})
      (session/dispatch-in! ctx :session/pre-turn-augment
                            {:session-id session-id
                             :turn-id "turn-steer"
                             :user-msg nil}
                            {:origin :core}))
    (is (= [] (:steering-messages (ss/get-session-data-in ctx session-id))))))
