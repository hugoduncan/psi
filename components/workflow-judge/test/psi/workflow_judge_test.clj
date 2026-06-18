(ns psi.workflow-judge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-judge :as workflow-judge]))

(def simple-messages
  [{:role "user" :content "Plan the feature"}
   {:role "assistant" :content [{:type :text :text "Here is the plan..."}]}
   {:role "user" :content "Build it"}
   {:role "assistant" :content [{:type :text :text "Done building."}]}])

(def messages-with-tools
  [{:role "user" :content "Build the feature"}
   {:role "assistant" :content [{:type :text :text "I'll read the file first."}
                                {:type :tool_use :id "t1" :name "read" :input {:path "src/core.clj"}}]}
   {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "file contents"}]}
   {:role "assistant" :content [{:type :text :text "Now I'll edit it."}
                                {:type :tool_use :id "t2" :name "edit" :input {:path "src/core.clj"}}]}
   {:role "tool" :content [{:type :tool_result :tool-use-id "t2" :content "edited"}]}
   {:role "assistant" :content [{:type :text :text "Build complete."}]}
   {:role "user" :content "Review it"}
   {:role "assistant" :content [{:type :text :text "Looks good."}]}])

(def three-turn-messages
  [{:role "user" :content "Turn 1 user"}
   {:role "assistant" :content [{:type :text :text "Turn 1 assistant"}]}
   {:role "user" :content "Turn 2 user"}
   {:role "assistant" :content [{:type :text :text "Turn 2 assistant"}]}
   {:role "user" :content "Turn 3 user"}
   {:role "assistant" :content [{:type :text :text "Turn 3 assistant"}]}])

(def messages-with-tool-only-turn
  [{:role "user" :content "Build the feature"}
   {:role "assistant" :content [{:type :tool_use :id "t1" :name "read" :input {:path "src/core.clj"}}]}
   {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "file contents"}]}
   {:role "assistant" :content [{:type :text :text "Build complete."}]}])

(def step-order ["step-1-plan" "step-2-build" "step-3-review"])

(deftest project-messages-none-test
  (testing ":none projection returns empty"
    (is (= [] (workflow-judge/project-messages simple-messages :none)))
    (is (= [] (workflow-judge/project-messages [] :none)))))

(deftest project-messages-full-test
  (testing ":full projection returns all messages"
    (is (= simple-messages (workflow-judge/project-messages simple-messages :full))))

  (testing "nil projection defaults to full"
    (is (= simple-messages (workflow-judge/project-messages simple-messages nil)))))

(deftest project-messages-tail-test
  (testing "tail 1 returns last turn"
    (let [result (workflow-judge/project-messages simple-messages {:type :tail :turns 1})]
      (is (= 2 (count result)))
      (is (= "Build it" (:content (first result))))
      (is (= "Done building." (get-in (second result) [:content 0 :text])))))

  (testing "tail 2 returns last 2 turns"
    (let [result (workflow-judge/project-messages simple-messages {:type :tail :turns 2})]
      (is (= 4 (count result)))
      (is (= simple-messages result))))

  (testing "tail exceeding message count returns all"
    (let [result (workflow-judge/project-messages simple-messages {:type :tail :turns 10})]
      (is (= simple-messages result))))

  (testing "tail on empty messages"
    (is (= [] (workflow-judge/project-messages [] {:type :tail :turns 3})))))

(deftest project-messages-tail-three-turns-test
  (testing "tail 2 of 3 turns returns last 2"
    (let [result (workflow-judge/project-messages three-turn-messages {:type :tail :turns 2})]
      (is (= 4 (count result)))
      (is (= "Turn 2 user" (:content (first result))))
      (is (= "Turn 3 assistant" (get-in (nth result 3) [:content 0 :text]))))))

(deftest project-messages-tail-with-tools-test
  (testing "tail includes tool messages as part of the turn"
    (let [result (workflow-judge/project-messages messages-with-tools {:type :tail :turns 2})]
      (is (= (count messages-with-tools) (count result))))))

(deftest project-messages-tail-tool-output-false-preserves-non-tool-text-test
  (let [result (workflow-judge/project-messages messages-with-tools
                                                {:type :tail :turns 1 :tool-output false})]
    (is (= 2 (count result)))
    (is (= "Review it" (:content (first result))))))

(deftest project-messages-tail-tool-output-false-strips-tool-blocks-from-assistant-messages-test
  (let [result (workflow-judge/project-messages messages-with-tools
                                                {:type :tail :turns 2 :tool-output false})]
    (doseq [msg result]
      (when (= "assistant" (:role msg))
        (doseq [block (:content msg)]
          (is (= :text (:type block)) "No tool blocks should remain"))))))

(deftest project-messages-tail-tool-output-false-drops-emptied-messages-test
  (let [result (workflow-judge/project-messages messages-with-tool-only-turn
                                                {:type :tail :turns 1 :tool-output false})]
    (is (= [{:role "user" :content "Build the feature"}
            {:role "assistant" :content [{:type :text :text "Build complete."}]}]
           result))))

(deftest project-messages-tail-tool-output-true-preserves-tool-blocks-test
  (let [result (workflow-judge/project-messages messages-with-tools
                                                {:type :tail :turns 2 :tool-output true})]
    (is (= (count messages-with-tools) (count result)))))

(deftest match-signal-test
  (let [table {"APPROVED" {:goto :next}
               "REVISE"   {:goto "step-2-builder" :max-iterations 3}}]
    (testing "exact match"
      (is (= {:goto :next} (workflow-judge/match-signal "APPROVED" table)))
      (is (= {:goto "step-2-builder" :max-iterations 3} (workflow-judge/match-signal "REVISE" table))))

    (testing "match after trim"
      (is (= {:goto :next} (workflow-judge/match-signal "  APPROVED  " table))))

    (testing "no match"
      (is (nil? (workflow-judge/match-signal "REJECT" table))))

    (testing "nil signal"
      (is (nil? (workflow-judge/match-signal nil table))))

    (testing "nil table"
      (is (nil? (workflow-judge/match-signal "APPROVED" nil))))))

(deftest resolve-goto-target-test
  (testing ":next advances to next step"
    (is (= {:action :goto :target "step-2-build"}
           (workflow-judge/resolve-goto-target :next "step-1-plan" step-order))))

  (testing ":next from last step completes"
    (is (= {:action :complete}
           (workflow-judge/resolve-goto-target :next "step-3-review" step-order))))

  (testing ":previous goes to previous step"
    (is (= {:action :goto :target "step-1-plan"}
           (workflow-judge/resolve-goto-target :previous "step-2-build" step-order))))

  (testing ":previous from first step fails"
    (is (= {:action :fail :reason :no-previous-step}
           (workflow-judge/resolve-goto-target :previous "step-1-plan" step-order))))

  (testing ":done completes"
    (is (= {:action :complete}
           (workflow-judge/resolve-goto-target :done "step-2-build" step-order))))

  (testing "string step-id goto"
    (is (= {:action :goto :target "step-2-build"}
           (workflow-judge/resolve-goto-target "step-2-build" "step-3-review" step-order))))

  (testing "unknown string step-id fails"
    (is (= {:action :fail :reason :unknown-step :step-id "nonexistent"}
           (workflow-judge/resolve-goto-target "nonexistent" "step-1-plan" step-order)))))

(deftest check-iteration-limit-test
  (testing "within limit"
    (is (= :within-limit (workflow-judge/check-iteration-limit 1 3)))
    (is (= :within-limit (workflow-judge/check-iteration-limit 0 3))))

  (testing "at limit — exhausted"
    (is (= :exhausted (workflow-judge/check-iteration-limit 3 3))))

  (testing "over limit — exhausted"
    (is (= :exhausted (workflow-judge/check-iteration-limit 5 3))))

  (testing "nil max-iterations — always within limit"
    (is (= :within-limit (workflow-judge/check-iteration-limit 100 nil))))

  (testing "nil iteration-count treated as 0"
    (is (= :within-limit (workflow-judge/check-iteration-limit nil 3)))))

(deftest evaluate-routing-test
  (let [table {"APPROVED" {:goto :next}
               "REVISE"   {:goto "step-2-build" :max-iterations 3}}
        step-runs {"step-1-plan"   {:step-id "step-1-plan" :attempts [] :iteration-count 1}
                   "step-2-build"  {:step-id "step-2-build" :attempts [] :iteration-count 1}
                   "step-3-review" {:step-id "step-3-review" :attempts [] :iteration-count 1}}]

    (testing "APPROVED from review → advance (complete, since review is last)"
      (is (= {:action :complete}
             (workflow-judge/evaluate-routing "APPROVED" table "step-3-review" step-order step-runs))))

    (testing "REVISE from review → goto build"
      (is (= {:action :goto :target "step-2-build"}
             (workflow-judge/evaluate-routing "REVISE" table "step-3-review" step-order step-runs))))

    (testing "REVISE with build iteration exhausted → fail"
      (let [exhausted-runs (assoc-in step-runs ["step-2-build" :iteration-count] 3)]
        (is (= {:action :fail :reason :iteration-exhausted :step-id "step-2-build" :iteration-count 3}
               (workflow-judge/evaluate-routing "REVISE" table "step-3-review" step-order exhausted-runs)))))

    (testing "exhausted with :on-max-iterations → route to author target, not fail (DI-6)"
      (let [on-max-table {"REVISE" {:goto "step-2-build" :max-iterations 3
                                    :on-max-iterations "step-1-plan"}}
            exhausted-runs (assoc-in step-runs ["step-2-build" :iteration-count] 3)]
        (is (= {:action :goto :target "step-1-plan"}
               (workflow-judge/evaluate-routing
                "REVISE" on-max-table "step-3-review" step-order exhausted-runs)))))

    (testing "exhausted with :on-max-iterations :done → complete (DI-6)"
      (let [on-max-done-table {"REVISE" {:goto "step-2-build" :max-iterations 3
                                         :on-max-iterations :done}}
            exhausted-runs (assoc-in step-runs ["step-2-build" :iteration-count] 3)]
        (is (= {:action :complete}
               (workflow-judge/evaluate-routing
                "REVISE" on-max-done-table "step-3-review" step-order exhausted-runs)))))

    (testing "within-limit with :on-max-iterations still routes the success goto (DI-6)"
      (let [on-max-table {"REVISE" {:goto "step-2-build" :max-iterations 3
                                    :on-max-iterations "step-1-plan"}}]
        (is (= {:action :goto :target "step-2-build"}
               (workflow-judge/evaluate-routing
                "REVISE" on-max-table "step-3-review" step-order step-runs)))))

    (testing "no match"
      (is (= {:action :no-match}
             (workflow-judge/evaluate-routing "REJECT" table "step-3-review" step-order step-runs))))

    (testing ":next from last step = complete"
      (let [next-table {"OK" {:goto :next}}]
        (is (= {:action :complete}
               (workflow-judge/evaluate-routing "OK" next-table "step-3-review" step-order step-runs)))))

    (testing ":previous from first step = fail"
      (let [prev-table {"BACK" {:goto :previous}}]
        (is (= {:action :fail :reason :no-previous-step}
               (workflow-judge/evaluate-routing "BACK" prev-table "step-1-plan" step-order step-runs)))))))
