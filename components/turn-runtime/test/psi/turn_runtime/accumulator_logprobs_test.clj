(ns psi.turn-runtime.accumulator-logprobs-test
  "Unit tests for logprob accumulation in the turn-runtime accumulator."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.turn-runtime.accumulator :as accum]
   [psi.turn-statechart.core :as turn-sc]))

(def ^:private sample-tokens-a
  [{:token "Hello" :logprob -0.05 :top [{:token "Hello" :logprob -0.05}]}])

(def ^:private sample-tokens-b
  [{:token "was" :logprob -0.33 :top [{:token "was" :logprob -0.33}
                                      {:token "is"  :logprob -1.66}]}])

(defn- fresh-turn-data
  []
  (let [td (atom (turn-sc/create-turn-data))]
    (swap! td assoc :turn-id "test-turn-1")
    td))

(deftest handle-logprob-delta-accumulates-into-buffer-test
  (testing "handle-logprob-delta! appends token vectors to :logprob-buffer"
    (let [td (fresh-turn-data)]
      (#'accum/handle-logprob-delta! td {:tokens sample-tokens-a})
      (is (= [sample-tokens-a] (:logprob-buffer @td)))
      (#'accum/handle-logprob-delta! td {:tokens sample-tokens-b})
      (is (= [sample-tokens-a sample-tokens-b] (:logprob-buffer @td))))))

(deftest handle-done-flattens-logprob-buffer-test
  (testing "handle-done! flattens :logprob-buffer into :logprobs before delivering"
    (let [td    (fresh-turn-data)
          done-p (promise)]
      (swap! td assoc :logprob-buffer [sample-tokens-a sample-tokens-b])
      (#'accum/handle-done! td done-p nil {:reason :stop})
      (let [final @done-p]
        (is (some? final))
        (is (= (concat sample-tokens-a sample-tokens-b)
               (:logprobs @td)))))))

(deftest handle-done-no-logprob-buffer-test
  (testing "handle-done! produces nil :logprobs when no logprob-buffer"
    (let [td    (fresh-turn-data)
          done-p (promise)]
      (#'accum/handle-done! td done-p nil {:reason :stop})
      (is (nil? (:logprobs @td))))))

(deftest make-turn-actions-on-logprob-delta-dispatched-test
  (testing ":on-logprob-delta action dispatched via make-turn-actions"
    (let [td       (fresh-turn-data)
          done-p   (promise)
          ai-model {:provider "openai" :id "gpt-4.1"}
          thinking-buffers (atom {})
          ;; Minimal ctx/session-id stubs — logprob path does not need session writes
          ctx      {}
          session-id "sess-test"
          actions-fn (accum/make-turn-actions ctx session-id done-p nil ai-model thinking-buffers)]
      (actions-fn :on-logprob-delta {:turn-data td :tokens sample-tokens-a})
      (is (= [sample-tokens-a] (:logprob-buffer @td))))))
