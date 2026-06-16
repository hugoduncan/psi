(ns psi.agent-session.submit-synthetic-prompt-mutation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]))

(defn- create-session-context []
  ;; Drive the downstream AI turn through the injectable ctx seam
  ;; (:execute-prepared-request-fn) so the synthetic prompt completes a turn
  ;; deterministically with no network/provider dependency.
  (let [ctx  (-> (session/create-context (test-support/safe-context-opts {:persist? false}))
                 (assoc :execute-prepared-request-fn
                        (fn [_ai-ctx _ctx sid prepared _pq]
                          (test-support/stub-execution-result
                           {:sid sid :prepared prepared :text "ack"}))))
        sd   (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- journal-messages
  [ctx session-id]
  (->> (ss/get-state-value-in ctx (ss/state-path :journal session-id))
       (filter #(= :message (:kind %)))
       (mapv #(get-in % [:data :message]))))

(defn- mutate-fn
  [ctx session-id]
  (let [qctx (query/create-query-context)]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (fn [op params]
      (get (query/query-in qctx
                           {:psi/agent-session-ctx ctx}
                           [(list op (assoc params
                                            :psi/agent-session-ctx ctx
                                            :session-id session-id))])
           op))))

(deftest submit-synthetic-prompt-injects-user-message-test
  ;; Verifies the psi.extension/submit-synthetic-prompt mutation wraps plain
  ;; text in a canonical user message record and drives it through the
  ;; submit-synthetic-user-prompt lifecycle, mirroring the scheduler path.
  (let [[ctx session-id] (create-session-context)
        mutate           (mutate-fn ctx session-id)]
    (kernel/clear-event-log!)
    (testing "reports submission and enters the canonical prompt lifecycle"
      (let [result (mutate 'psi.extension/submit-synthetic-prompt
                           {:user-msg "I choose option A"})]
        (is (true? (:psi.extension/prompt-submitted? result)))
        (let [entries (kernel/event-log-entries)]
          (is (some #(= :session/submit-synthetic-user-prompt (:event-type %)) entries))
          (is (some #(= :session/prompt-submit (:event-type %)) entries)))))
    (testing "injects exactly one mid-conversation user message with the text"
      (let [user-msgs (filter #(= "user" (:role %)) (journal-messages ctx session-id))]
        (is (= 1 (count user-msgs)))
        (is (= :extension (:source (first user-msgs))))
        (is (= "I choose option A"
               (get-in (first user-msgs) [:content 0 :text])))))
    (testing "drives the next turn to a downstream assistant message (AC-6)"
      ;; The injected user message must not merely sit in the journal — it must
      ;; drive the agent's next turn immediately. The :execute-prepared-request-fn
      ;; seam returns a stub assistant "ack"; assert that turn actually ran by
      ;; finding the resulting assistant message in the journal.
      (let [assistant-msgs (filter #(= "assistant" (:role %))
                                   (journal-messages ctx session-id))]
        (is (= 1 (count assistant-msgs)))
        (is (= "ack"
               (get-in (first assistant-msgs) [:content 0 :text])))))))
