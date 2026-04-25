(ns psi.agent-session.workflow-judge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-judge :as workflow-judge]))

;;; Test message fixtures

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

;;; Projection tests

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
      ;; Last 2 turns: the build turn (user + assistant + tool + assistant + tool + assistant) and the review turn
      ;; Turn 1: user "Build" -> assistant (text+tool) -> tool -> assistant (text+tool) -> tool -> assistant "Build complete."
      ;; Turn 2: user "Review it" -> assistant "Looks good."
      ;; Actually turns are: user->assistant pairs. Let me trace the turn structure.
      ;; The messages are: user, assistant, tool, assistant, tool, assistant, user, assistant
      ;; Turn structure: [user, assistant] is turn 1, then [tool, assistant] continues...
      ;; Actually the collect-turns function starts a new turn on each user message.
      ;; So turn 1 = [user "Build", assistant (text+tool), tool, assistant (text+tool), tool, assistant "Build complete."]
      ;; Turn 2 = [user "Review it", assistant "Looks good."]
      ;; tail 2 = both turns = all messages
      (is (= (count messages-with-tools) (count result))))))

(deftest project-messages-tail-tool-output-false-test
  (testing "tool-output false strips tool blocks from messages"
    (let [result (workflow-judge/project-messages messages-with-tools
                                                  {:type :tail :turns 1 :tool-output false})]
      ;; Last turn: [user "Review it", assistant "Looks good."] — no tool blocks to strip
      (is (= 2 (count result)))
      (is (= "Review it" (:content (first result))))))

  (testing "tool-output false strips tool_use blocks from assistant messages"
    (let [result (workflow-judge/project-messages messages-with-tools
                                                  {:type :tail :turns 2 :tool-output false})]
      ;; All messages, but tool_use/tool_result blocks stripped from content
      ;; tool role messages are stripped entirely (strip-tool-blocks returns nil for tool messages)
      ;; assistant messages with only tool_use blocks are stripped
      (doseq [msg result]
        (when (= "assistant" (:role msg))
          (doseq [block (:content msg)]
            (is (= :text (:type block)) "No tool blocks should remain"))))))

  (testing "tool-output true preserves tool blocks"
    (let [result (workflow-judge/project-messages messages-with-tools
                                                  {:type :tail :turns 2 :tool-output true})]
      (is (= (count messages-with-tools) (count result))))))

;;; Routing — match-signal

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

;;; Routing — resolve-goto-target

(def step-order ["step-1-plan" "step-2-build" "step-3-review"])

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

;;; Routing — check-iteration-limit

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

;;; Routing — evaluate-routing (composed)

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
