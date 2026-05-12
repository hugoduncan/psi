(ns extensions.logprobs-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [extensions.logprobs :as logprobs]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

(use-fixtures :each
  (fn [f]
    ;; Reset store between tests
    (reset! @#'logprobs/store {})
    (f)
    (reset! @#'logprobs/store {})))

;; ── Perplexity calculation ───────────────────────────────────────────────────

(deftest calculate-perplexity-test
  (testing "perplexity of uniform distribution tokens"
    ;; All tokens with logprob ln(0.5) ≈ -0.693
    ;; perplexity = exp(-1/2 * (ln(0.5) + ln(0.5))) = exp(-ln(0.5)) = 1/0.5 = 2.0
    (let [tokens [{:token "a" :logprob (Math/log 0.5)}
                  {:token "b" :logprob (Math/log 0.5)}]]
      (is (< (abs (- 2.0 (logprobs/calculate-perplexity tokens))) 0.001))))

  (testing "perplexity of highly confident tokens"
    ;; All tokens with logprob ln(0.99) ≈ -0.01
    ;; perplexity ≈ exp(0.01) ≈ 1.01
    (let [tokens [{:token "the" :logprob (Math/log 0.99)}
                  {:token "cat" :logprob (Math/log 0.99)}]]
      (is (< (logprobs/calculate-perplexity tokens) 1.02))))

  (testing "nil for empty tokens"
    (is (nil? (logprobs/calculate-perplexity []))))

  (testing "nil for nil tokens"
    (is (nil? (logprobs/calculate-perplexity nil))))

  (testing "tokens with nil logprob are excluded from count"
    (let [tokens [{:token "a" :logprob (Math/log 0.5)}
                  {:token "b" :logprob nil}]]
      ;; Only one token with logprob, perplexity = exp(-(-0.693)/1) = 2.0
      (is (< (abs (- 2.0 (logprobs/calculate-perplexity tokens))) 0.001)))))

;; ── Event-driven storage ─────────────────────────────────────────────────────

(deftest store-logprobs-on-event-test
  (let [registered-handlers (atom {})
        api {:on (fn [event-name handler]
                   (swap! registered-handlers assoc event-name handler))
             :register-operation (fn [_op] nil)}]
    (logprobs/init api)

    (testing "event handler registered for session_turn_finished"
      (is (contains? @registered-handlers "session_turn_finished")))

    (let [handler (get @registered-handlers "session_turn_finished")]
      (testing "stores logprobs on turn finished event"
        (handler {:session-id "s1"
                  :turn-id "t1"
                  :logprobs [{:token "hi" :logprob -0.5}]
                  :assistant-message {:role "assistant" :content [{:type :text :text "hi"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (some? (:perplexity result)))
          (is (= 1 (:token-count result)))
          (is (= "t1" (:turn-id result)))
          (is (= "hi" (:reply-text result)))))

      (testing "turn without logprobs does not clear stored data"
        (handler {:session-id "s1"
                  :turn-id "t2"
                  :assistant-message {:role "assistant" :content [{:type :text :text "bye"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "t1" (:turn-id result)))
          (is (= "hi" (:reply-text result)))))

      (testing "turn with empty logprobs does not clear stored data"
        (handler {:session-id "s1"
                  :turn-id "t3"
                  :logprobs []
                  :assistant-message {:role "assistant" :content [{:type :text :text "hey"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "t1" (:turn-id result)))))

      (testing "new turn with logprobs replaces stored data"
        (handler {:session-id "s1"
                  :turn-id "t4"
                  :logprobs [{:token "new" :logprob -0.1}]
                  :assistant-message {:role "assistant" :content [{:type :text :text "new"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "t4" (:turn-id result)))
          (is (= "new" (:reply-text result))))))))

;; ── Perplexity result ────────────────────────────────────────────────────────

(deftest perplexity-result-no-data-test
  (testing "returns nil perplexity when no data stored"
    (let [result (logprobs/perplexity-result "unknown-session")]
      (is (nil? (:perplexity result)))
      (is (= 0 (:token-count result)))
      (is (nil? (:turn-id result)))
      (is (nil? (:reply-text result))))))

;; ── Operation handler ────────────────────────────────────────────────────────

(deftest invoke-perplexity-test
  (testing "returns error when session-id missing"
    (let [result (logprobs/invoke-perplexity {:args {}})]
      (is (= :error (:status result)))
      (is (= :missing-session-id (:reason result)))))

  (testing "returns ok with nil perplexity for unknown session"
    (let [result (logprobs/invoke-perplexity {:args {:session-id "unknown"}})]
      (is (= :ok (:status result)))
      (is (nil? (get-in result [:data :perplexity])))))

  (testing "returns ok with computed perplexity for session with data"
    ;; Manually store some data
    (reset! @#'logprobs/store
            {"s1" {:logprobs [{:token "x" :logprob (Math/log 0.5)}
                              {:token "y" :logprob (Math/log 0.5)}]
                   :assistant-message {:role "assistant" :content [{:type :text :text "x y"}]}
                   :turn-id "t1"}})
    (let [result (logprobs/invoke-perplexity {:args {:session-id "s1"}})]
      (is (= :ok (:status result)))
      (is (< (abs (- 2.0 (get-in result [:data :perplexity]))) 0.001))
      (is (= 2 (get-in result [:data :token-count])))
      (is (= "t1" (get-in result [:data :turn-id])))
      (is (= "x y" (get-in result [:data :reply-text]))))))

;; ── Operation registration ───────────────────────────────────────────────────

(deftest init-registers-operation-test
  (let [registered-ops (atom [])
        api {:on (fn [_ _] nil)
             :register-operation (fn [op] (swap! registered-ops conj op))}]
    (logprobs/init api)
    (testing "registers logprobs/perplexity operation"
      (is (= 1 (count @registered-ops)))
      (is (= "logprobs/perplexity" (:id (first @registered-ops)))))))
