(ns psi.ai.providers.openai-completions-logprobs-test
  "Tests for logprob support in OpenAI chat-completions request building and SSE extraction."
  (:require
   [clojure.test :refer [deftest testing is]]
   [cheshire.core :as json]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai.chat-completions :as cc]))

(def ^:private stub-model
  (models/get-model :gpt-4.1))

(defn- parse-body [request]
  (json/parse-string (:body request) keyword))

;; ── Request building ─────────────────────────────────────────────────────────

(deftest build-request-without-logprobs-test
  (testing "logprob fields absent when :logprobs-enabled not set"
    (let [convo   (-> (conv/create "sys") (conv/add-user-message "hi"))
          request (cc/build-request convo stub-model {})
          body    (parse-body request)]
      (is (not (contains? body :logprobs)))
      (is (not (contains? body :top_logprobs))))))

(deftest build-request-with-logprobs-enabled-test
  (testing "logprob fields present when :logprobs-enabled is true"
    (let [convo   (-> (conv/create "sys") (conv/add-user-message "hi"))
          request (cc/build-request convo stub-model {:logprobs-enabled true :top-logprobs 5})
          body    (parse-body request)]
      (is (true? (:logprobs body)))
      (is (= 5 (:top_logprobs body))))))

(deftest build-request-logprobs-default-top-n-test
  (testing "top_logprobs defaults to 3 when :logprobs-enabled true and :top-logprobs absent"
    (let [convo   (-> (conv/create "sys") (conv/add-user-message "hi"))
          request (cc/build-request convo stub-model {:logprobs-enabled true})
          body    (parse-body request)]
      (is (true? (:logprobs body)))
      (is (= 3 (:top_logprobs body))))))

;; ── SSE extraction — OpenAI per-chunk ────────────────────────────────────────

(deftest extract-openai-logprob-delta-present-test
  (testing "non-nil when choices[0].logprobs.content is present"
    (let [choice {:logprobs {:content [{:token "Hello"
                                        :logprob -0.5
                                        :top_logprobs [{:token "Hello" :logprob -0.5}
                                                       {:token "Hi"    :logprob -1.2}]}]}}
          result (#'cc/extract-openai-logprob-delta choice)]
      (is (= 1 (count result)))
      (is (= "Hello" (:token (first result))))
      (is (= -0.5 (:logprob (first result))))
      (is (= 2 (count (:top (first result))))))))

(deftest extract-openai-logprob-delta-absent-test
  (testing "nil when choices[0].logprobs is absent"
    (let [choice {:delta {:content "hello"}}]
      (is (nil? (#'cc/extract-openai-logprob-delta choice))))))

;; ── SSE extraction — llama.cpp final chunk ───────────────────────────────────

(deftest extract-llama-logprob-delta-present-test
  (testing "non-nil when completion_probabilities is present"
    (let [chunk {:completion_probabilities
                 [{:content "Hello"
                   :probs [{:tok_str "Hello" :prob 0.72}
                           {:tok_str "Hi"    :prob 0.19}]}]}
          result (#'cc/extract-llama-logprob-delta chunk)]
      (is (= 1 (count result)))
      (is (= "Hello" (:token (first result))))
      ;; logprob = ln(0.72) ≈ -0.329
      (is (< (:logprob (first result)) 0))
      (is (= 2 (count (:top (first result))))))))

(deftest extract-llama-logprob-delta-absent-test
  (testing "nil when completion_probabilities is absent"
    (is (nil? (#'cc/extract-llama-logprob-delta {})))))

;; ── Normalization round-trip ──────────────────────────────────────────────────

(deftest normalize-openai-logprob-token-test
  (testing "OpenAI token normalized with :token :logprob :top"
    (let [item   {:token "was" :logprob -0.329 :top_logprobs [{:token "was" :logprob -0.329}
                                                              {:token "is"  :logprob -1.5}]}
          result (#'cc/normalize-openai-logprob-token item)]
      (is (= "was" (:token result)))
      (is (= -0.329 (:logprob result)))
      (is (= 2 (count (:top result)))))))

(deftest normalize-llama-logprob-token-test
  (testing "llama.cpp token normalized to common shape with log-converted probabilities"
    (let [item   {:content "was" :probs [{:tok_str "was" :prob 0.72}
                                         {:tok_str "is"  :prob 0.19}]}
          result (#'cc/normalize-llama-logprob-token item)]
      (is (= "was" (:token result)))
      (is (some? (:logprob result)))
      (is (< (:logprob result) 0))
      (is (= 2 (count (:top result)))))))
