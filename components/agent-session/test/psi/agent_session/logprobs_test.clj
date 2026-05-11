(ns psi.agent-session.logprobs-test
  "Tests for logprob feature: options projection, journal projection, and recording."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-recording :as recording]))

;; ── Options projection ────────────────────────────────────────────────────────

(deftest session->request-options-logprobs-disabled-test
  (testing "logprob keys absent when :logprobs-enabled not set"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"} :thinking-level :off}
          opts (prompt-request/session->request-options {} sd {})]
      (is (not (contains? opts :logprobs-enabled)))
      (is (not (contains? opts :top-logprobs))))))

(deftest session->request-options-logprobs-enabled-test
  (testing "logprob keys present when :logprobs-enabled true"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"}
                :thinking-level :off
                :logprobs-enabled true
                :top-logprobs 5}
          opts (prompt-request/session->request-options {} sd {})]
      (is (true? (:logprobs-enabled opts)))
      (is (= 5 (:top-logprobs opts))))))

(deftest session->request-options-logprobs-default-top-n-test
  (testing "top-logprobs defaults to 3 when :logprobs-enabled true and :top-logprobs absent"
    (let [sd   {:model {:provider "openai" :id "gpt-4.1"}
                :thinking-level :off
                :logprobs-enabled true}
          opts (prompt-request/session->request-options {} sd {})]
      (is (true? (:logprobs-enabled opts)))
      (is (= 3 (:top-logprobs opts))))))

;; ── Journal projection ────────────────────────────────────────────────────────

(def ^:private sample-logprob-tokens
  [{:token "Hello" :logprob (Math/log 0.95) :top [{:token "Hello" :logprob (Math/log 0.95)}]}
   {:token "was"   :logprob (Math/log 0.72) :top [{:token "was" :logprob (Math/log 0.72)}
                                                  {:token "is"  :logprob (Math/log 0.19)}]}])

(defn- message-entry [role text]
  {:id "e1" :parent-id nil :timestamp (java.time.Instant/now)
   :kind :message
   :data {:message {:role role :content text}}})

(defn- logprobs-entry [turn-id tokens]
  {:id "e2" :parent-id nil :timestamp (java.time.Instant/now)
   :kind :logprobs
   :data {:turn-id turn-id :tokens tokens}})

(deftest journal-logprobs-entry-projects-to-synthetic-user-message-test
  (testing ":logprobs entry after :message projects to synthetic user message"
    (let [journal [(message-entry "assistant" "Hello there")
                   (logprobs-entry "turn-1" sample-logprob-tokens)]
          msgs    (prompt-request/journal->provider-messages journal)]
      (is (= 2 (count msgs)))
      (is (= "assistant" (:role (first msgs))))
      (is (= "user" (:role (second msgs))))
      (is (string/includes? (:content (second msgs)) "[logprob context")))))

(deftest journal-logprobs-entry-orphaned-dropped-test
  (testing "orphaned :logprobs entry (no preceding :message) is silently dropped"
    (let [journal [(logprobs-entry "turn-1" sample-logprob-tokens)]
          msgs    (prompt-request/journal->provider-messages journal)]
      (is (empty? msgs)))))

(deftest journal-logprobs-entry-all-certain-test
  (testing "all-certain token message still emitted when all tokens p ≥ 0.90"
    (let [certain-tokens [{:token "Hello" :logprob (Math/log 0.99) :top []}]
          journal [(message-entry "assistant" "Hello")
                   (logprobs-entry "turn-1" certain-tokens)]
          msgs    (prompt-request/journal->provider-messages journal)]
      (is (= 2 (count msgs)))
      (is (string/includes? (:content (second msgs)) "All tokens p ≥ 0.90")))))

(deftest journal-logprobs-entry-uncertain-tokens-formatted-test
  (testing "uncertain token (p < 0.90) appears in logprob message"
    (let [uncertain-tokens [{:token "was" :logprob (Math/log 0.72)
                             :top [{:token "was" :logprob (Math/log 0.72)}
                                   {:token "is"  :logprob (Math/log 0.19)}]}]
          journal [(message-entry "assistant" "it was good")
                   (logprobs-entry "turn-1" uncertain-tokens)]
          msgs    (prompt-request/journal->provider-messages journal)]
      (is (= 2 (count msgs)))
      (is (string/includes? (:content (second msgs)) "Uncertain tokens"))
      (is (string/includes? (:content (second msgs)) "\"was\"")))))

(deftest journal-non-logprobs-entries-skipped-test
  (testing "non-message non-logprobs entries are skipped without disrupting projection"
    (let [journal [(message-entry "user" "hi")
                   {:id "x" :parent-id nil :timestamp (java.time.Instant/now)
                    :kind :thinking-level :data {:thinking-level :off}}
                   (message-entry "assistant" "hello")]
          msgs    (prompt-request/journal->provider-messages journal)]
      (is (= 2 (count msgs))))))

;; ── Recording: journal append + last-turn-logprobs ───────────────────────────

(deftest build-record-response-appends-logprobs-effect-test
  (testing "logprobs effect appended when :execution-result/logprobs is non-empty"
    (let [logprobs sample-logprob-tokens
          execution-result
          {:execution-result/turn-id      "turn-1"
           :execution-result/session-id   "sess-1"
           :execution-result/stop-reason  :stop
           :execution-result/logprobs     logprobs
           :execution-result/assistant-message
           {:role "assistant" :content [{:type :text :text "hi"}]
            :stop-reason :stop :timestamp (java.time.Instant/now)}
           :execution-result/turn-outcome :response
           :execution-result/tool-calls   []}
          result  (recording/build-record-response "sess-1" execution-result nil)
          effects (:effects result)]
      ;; Two effects: append-message + append-logprobs
      (is (= 2 (count effects)))
      (let [logprobs-effect (second effects)]
        (is (= :runtime/dispatch-event (:effect/type logprobs-effect)))
        (is (= :session/append-journal-entry (:event-type logprobs-effect)))
        (is (= :logprobs (get-in logprobs-effect [:event-data :entry :kind])))
        (is (= logprobs (get-in logprobs-effect [:event-data :entry :data :tokens])))))))

(deftest build-record-response-no-logprobs-effect-when-nil-test
  (testing "no logprobs effect when :execution-result/logprobs is nil"
    (let [execution-result
          {:execution-result/turn-id      "turn-1"
           :execution-result/session-id   "sess-1"
           :execution-result/stop-reason  :stop
           :execution-result/logprobs     nil
           :execution-result/assistant-message
           {:role "assistant" :content [{:type :text :text "hi"}]
            :stop-reason :stop :timestamp (java.time.Instant/now)}
           :execution-result/turn-outcome :response
           :execution-result/tool-calls   []}
          result (recording/build-record-response "sess-1" execution-result nil)
          effects (:effects result)]
      (is (= 1 (count effects))))))

(deftest build-record-response-writes-last-turn-logprobs-test
  (testing ":last-turn-logprobs written to session-data when logprobs present"
    (let [logprobs sample-logprob-tokens
          execution-result
          {:execution-result/turn-id      "turn-1"
           :execution-result/session-id   "sess-1"
           :execution-result/stop-reason  :stop
           :execution-result/logprobs     logprobs
           :execution-result/assistant-message
           {:role "assistant" :content [{:type :text :text "hi"}]
            :stop-reason :stop :timestamp (java.time.Instant/now)}
           :execution-result/turn-outcome :response
           :execution-result/tool-calls   []}
          result (recording/build-record-response "sess-1" execution-result nil)
          update-fn (:root-state-update result)
          ;; Apply the state update to a minimal root state
          initial-state {:agent-session {:sessions {"sess-1" {:data {:thinking-level :off}}}}}
          updated-state (update-fn initial-state)
          session-data  (get-in updated-state [:agent-session :sessions "sess-1" :data])]
      (is (= logprobs (:last-turn-logprobs session-data))))))
