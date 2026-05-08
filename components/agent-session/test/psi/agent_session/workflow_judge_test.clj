(ns psi.agent-session.workflow-judge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.session-persistence.core]))

(def step-order ["step-1-plan" "step-2-build" "step-3-review"])

(deftest execute-judge-successful-match-test
  (testing "judge matches on first attempt"
    (let [created-sessions* (atom [])
          prompts* (atom [])
          judge-spec {:prompt "APPROVED or REVISE?"
                      :system-prompt "You are a judge."
                      :projection :full}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-1-plan"   {:step-id "step-1-plan" :attempts [] :iteration-count 1}
                     "step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}
          ctx {:create-workflow-child-session-fn
               (fn [_ctx _parent opts]
                 (swap! created-sessions* conj opts)
                 nil)}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid]
                      [{:role "user" :content "Build it"}
                       {:role "assistant" :content [{:type :text :text "Done building."}]}])
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx sid text]
                      (swap! prompts* conj {:session-id sid :text text})
                      {:execution-result/assistant-message
                       {:role "assistant" :content [{:type :text :text "APPROVED"}]}})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order step-order
                       :step-runs step-runs})]
          (is (string? (:judge-session-id result)))
          (is (= "APPROVED" (:judge-output result)))
          (is (= "APPROVED" (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= [] (:tool-defs (first @created-sessions*))))
          (is (= "You are a judge." (:system-prompt (first @created-sessions*))))
          (is (= 1 (count @prompts*)))
          (is (= "APPROVED or REVISE?" (:text (first @prompts*)))))))))

(deftest execute-judge-retry-then-match-test
  (testing "judge retries on no-match then matches"
    (let [prompt-count* (atom 0)
          ctx {:create-workflow-child-session-fn (fn [_ctx _parent _opts] nil)}
          judge-spec {:prompt "APPROVED or REVISE?"
                      :projection :none}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _sid _text]
                      (swap! prompt-count* inc)
                      {:execution-result/assistant-message
                       (if (<= @prompt-count* 1)
                         {:role "assistant" :content [{:type :text :text "I think it looks good"}]}
                         {:role "assistant" :content [{:type :text :text "APPROVED"}]})})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order ["step-2-build" "step-3-review"]
                       :step-runs step-runs})]
          (is (= "APPROVED" (:judge-output result)))
          (is (= "APPROVED" (:judge-event result)))
          (is (= {:action :complete} (:routing-result result)))
          (is (= 2 @prompt-count*)))))))

(deftest execute-judge-retry-exhaustion-test
  (testing "judge retries exhausted — returns no-match routing"
    (let [prompt-count* (atom 0)
          ctx {:create-workflow-child-session-fn (fn [_ctx _parent _opts] nil)}
          judge-spec {:prompt "APPROVED or REVISE?"
                      :projection :none}
          routing-table {"APPROVED" {:goto :next}
                         "REVISE"   {:goto "step-2-build" :max-iterations 3}}
          step-runs {"step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                     "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]
      (with-redefs [psi.session-persistence.core/messages-from-entries-in
                    (fn [_ctx _sid] [])
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _sid _text]
                      (swap! prompt-count* inc)
                      {:execution-result/assistant-message
                       {:role "assistant" :content [{:type :text :text "hmm not sure"}]}})]
        (let [result (workflow-judge/execute-judge!
                      ctx "parent-1" "actor-1" judge-spec routing-table
                      {:current-step-id "step-3-review"
                       :step-order ["step-2-build" "step-3-review"]
                       :step-runs step-runs})]
          (is (= "hmm not sure" (:judge-output result)))
          (is (nil? (:judge-event result)))
          (is (= {:action :no-match} (:routing-result result)))
          (is (= 3 @prompt-count*)))))))
