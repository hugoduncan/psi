(ns extensions.logprobs-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [extensions.logprobs :as logprobs]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

(use-fixtures :each
  (fn [f]
    ;; Reset store between tests
    (reset! @#'logprobs/store nil)
    (f)
    (reset! @#'logprobs/store nil)))

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
      (is (< (abs (- 2.0 (logprobs/calculate-perplexity tokens))) 0.001))))

  (testing "all-nil-logprob tokens returns nil"
    ;; Tokens present but every :logprob is nil — no usable data
    (let [tokens [{:token "a" :logprob nil}
                  {:token "b" :logprob nil}]]
      (is (nil? (logprobs/calculate-perplexity tokens)))))

  (testing "single token returns exp(-logprob)"
    ;; N=1 boundary: perplexity = exp(-logprob)
    (let [lp (Math/log 0.25)
          tokens [{:token "x" :logprob lp}]]
      ;; exp(-ln(0.25)) = exp(ln(4)) = 4.0
      (is (< (abs (- 4.0 (logprobs/calculate-perplexity tokens))) 0.001)))))

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
          (is (= "s1" (:session-id result)))
          (is (some? (:perplexity result)))
          (is (= 1 (:token-count result)))
          (is (= "t1" (:turn-id result)))
          (is (= "hi" (:reply-text result)))))

      (testing "turn without logprobs does not clear stored data"
        (handler {:session-id "s1"
                  :turn-id "t2"
                  :assistant-message {:role "assistant" :content [{:type :text :text "bye"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "s1" (:session-id result)))
          (is (= "t1" (:turn-id result)))
          (is (= "hi" (:reply-text result)))))

      (testing "turn with empty logprobs does not clear stored data"
        (handler {:session-id "s1"
                  :turn-id "t3"
                  :logprobs []
                  :assistant-message {:role "assistant" :content [{:type :text :text "hey"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "s1" (:session-id result)))
          (is (= "t1" (:turn-id result)))))

      (testing "new turn with logprobs replaces stored data"
        (handler {:session-id "s1"
                  :turn-id "t4"
                  :logprobs [{:token "new" :logprob -0.1}]
                  :assistant-message {:role "assistant" :content [{:type :text :text "new"}]}})
        (let [result (logprobs/perplexity-result "s1")]
          (is (= "s1" (:session-id result)))
          (is (= "t4" (:turn-id result)))
          (is (= "new" (:reply-text result))))))))

(deftest mismatched-session-id-returns-empty-result-test
  (let [registered-handlers (atom {})
        api {:on (fn [event-name handler]
                   (swap! registered-handlers assoc event-name handler))
             :register-operation (fn [_op] nil)}]
    (logprobs/init api)
    ((get @registered-handlers "session_turn_finished")
     {:session-id "s1"
      :turn-id "t1"
      :logprobs [{:token "x" :logprob (Math/log 0.5)}]
      :assistant-message {:role "assistant" :content [{:type :text :text "x"}]}})
    (testing "requesting a different session returns the empty result"
      (is (= {:session-id nil
              :perplexity nil
              :token-count 0
              :turn-id nil
              :reply-text nil}
             (logprobs/perplexity-result "s2"))))))

;; ── Perplexity result ────────────────────────────────────────────────────────

(deftest perplexity-result-no-data-test
  (testing "returns nil perplexity when no data stored"
    (let [result (logprobs/perplexity-result "unknown-session")]
      (is (nil? (:session-id result)))
      (is (nil? (:perplexity result)))
      (is (= 0 (:token-count result)))
      (is (nil? (:turn-id result)))
      (is (nil? (:reply-text result))))))

(deftest perplexity-result-token-count-matches-effective-n-test
  (testing "token-count reflects only tokens with non-nil logprob"
    (reset! @#'logprobs/store
            {:session-id "s1"
             :logprobs [{:token "a" :logprob (Math/log 0.5)}
                        {:token "b" :logprob nil}
                        {:token "c" :logprob (Math/log 0.5)}]
             :assistant-message {:role "assistant" :content [{:type :text :text "a b c"}]}
             :turn-id "t1"})
    (let [result (logprobs/perplexity-result "s1")]
      (is (= "s1" (:session-id result)))
      (is (= 2 (:token-count result))
          "token-count should be 2 (only tokens with non-nil :logprob)")
      (is (< (abs (- 2.0 (:perplexity result))) 0.001)
          "perplexity should match the 2 effective tokens"))))

;; ── Operation handler ────────────────────────────────────────────────────────

(deftest invoke-perplexity-test
  (testing "returns error when session-id missing"
    (let [result (logprobs/invoke-perplexity {:args {}})]
      (is (= :error (:status result)))
      (is (= :missing-session-id (:reason result)))))

  (testing "returns ok with nil perplexity for unknown session"
    (let [result (logprobs/invoke-perplexity {:args {:session-id "unknown"}})]
      (is (= :ok (:status result)))
      (is (nil? (get-in result [:data :session-id])))
      (is (nil? (get-in result [:data :perplexity])))))

  (testing "returns ok with computed perplexity for session with matching stored data"
    ;; Manually store some data
    (reset! @#'logprobs/store
            {:session-id "s1"
             :logprobs [{:token "x" :logprob (Math/log 0.5)}
                        {:token "y" :logprob (Math/log 0.5)}]
             :assistant-message {:role "assistant" :content [{:type :text :text "x y"}]}
             :turn-id "t1"})
    (let [result (logprobs/invoke-perplexity {:args {:session-id "s1"}})]
      (is (= :ok (:status result)))
      (is (= "s1" (get-in result [:data :session-id])))
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
