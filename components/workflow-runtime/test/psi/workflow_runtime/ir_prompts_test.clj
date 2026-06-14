(ns psi.workflow-runtime.ir-prompts-test
  "Task 226 — `:prompts` multi-prompt grammar, IR normalization, and `:prompt`
   source-ref discriminator validation.

   Extracted from ir-test to keep each test namespace focused and within the
   file-length budget."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.ir :as workflow-ir]))

(def ^:private valid-invoke-step
  {:name "discover"
   :type :invoke
   :invoke {:operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}
                   :state "open"}}
   :outputs {:data {:source :invoke/data}
             :summary {:source :invoke/summary}
             :result {:source :invoke/result}}
   :yields {:type :data :data :data}})

;; ── Task 226 Slice 2 — `:prompts` grammar + IR normalization ────────────────

(def ^:private named-prompt-group-a
  {:name "architecture"
   :contributions [{:type :template :text "arch review" :vars {}}]})

(def ^:private named-prompt-group-b
  {:name "ambiguity"
   :contributions [{:type :template :text "ambiguity review" :vars {}}]})

(defn- multi-prompt-session-step
  [prompts]
  {:name "design-review"
   :type :session
   :session {:tools ["read"]
             :prompts prompts}
   :outputs {:final-llm-reply {:source :session/final-llm-reply}
             :transcript {:source :session/transcript}}
   :yields {:type :text :text :final-llm-reply}})

(defn- session-step-semantic-result
  [step]
  (workflow-ir/validate-workflow-ir {:version :workflow-ir/v1 :steps [step]}))

(deftest session-prompts-grammar-validation-test
  (testing "a multi-prompt session step with named groups is valid"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (session-step-semantic-result
            (multi-prompt-session-step [named-prompt-group-a named-prompt-group-b])))))

  (testing "a one-element :prompts queue is valid (AC-1 N>=1)"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (session-step-semantic-result
            (multi-prompt-session-step [named-prompt-group-a])))))

  (testing "an empty :prompts queue is rejected structurally"
    (let [result (session-step-semantic-result (multi-prompt-session-step []))]
      (is (false? (:valid? result)))
      (is (some? (:structural-errors result)))))

  (testing "step-level :contributions and :prompts together is a semantic error"
    (let [step (assoc-in (multi-prompt-session-step [named-prompt-group-a])
                         [:session :contributions] [])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :session-contributions-and-prompts
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "a session step with neither :contributions nor :prompts is a semantic error"
    (let [step {:name "design-review"
                :type :session
                :session {:tools ["read"]}
                :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                :yields {:type :text :text :final-llm-reply}}
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :session-without-prompt-source
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "duplicate prompt-group names within a step are a semantic error"
    (let [step (multi-prompt-session-step
                [named-prompt-group-a
                 (assoc named-prompt-group-b :name "architecture")])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :duplicate-prompt-group-name
               :step "design-review"
               :duplicate-names ["architecture"]}]
             (:semantic-errors result)))))

  (testing "an unnamed group inside :prompts is a semantic error"
    (let [step (multi-prompt-session-step
                [named-prompt-group-a (dissoc named-prompt-group-b :name)])
          result (session-step-semantic-result step)]
      (is (false? (:valid? result)))
      (is (= [{:type :unnamed-prompt-group
               :step "design-review"}]
             (:semantic-errors result)))))

  (testing "names may repeat across distinct steps"
    (let [step-1 (assoc (multi-prompt-session-step [named-prompt-group-a])
                        :name "review-1")
          step-2 (assoc (multi-prompt-session-step [named-prompt-group-a])
                        :name "review-2")
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step-1 step-2]})]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result)))))

;; ── Task 226 Slice 4 — `:prompt` source-ref discriminator validation ────────

(def ^:private prior-multi-prompt-step
  (assoc (multi-prompt-session-step [named-prompt-group-a named-prompt-group-b])
         :name "review"))

(defn- consumer-step
  "A later single-prompt session step whose contribution carries `source-ref`."
  [source-ref]
  {:name "consume"
   :type :session
   :session {:contributions [{:type :source :from source-ref}]}
   :outputs {:final-llm-reply {:source :session/final-llm-reply}}
   :yields {:type :text :text :final-llm-reply}})

(defn- two-step-result
  [source-ref]
  (workflow-ir/validate-workflow-ir
   {:version :workflow-ir/v1
    :steps [prior-multi-prompt-step (consumer-step source-ref)]}))

(deftest prompt-source-ref-validation-test
  (testing "a valid `:prompt` ref to a prior multi-prompt group is accepted"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :prompt "architecture"
                             :output :final-llm-reply})))
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :prompt "ambiguity"
                             :output :transcript}))))

  (testing "a no-`:prompt` ref to the step-level surface stays valid (back-compat)"
    (is (= {:valid? true :structural-errors nil :semantic-errors []}
           (two-step-result {:step "review" :output :final-llm-reply}))))

  (testing "a `:prompt` ref to an unknown group is rejected"
    (let [result (two-step-result {:step "review" :prompt "nope"
                                   :output :final-llm-reply})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-unknown-group
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a non-text surface is rejected"
    (let [result (two-step-result {:step "review" :prompt "architecture"
                                   :output :result})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-non-text-surface
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a single-prompt step is rejected"
    (let [single {:name "single"
                  :type :session
                  :session {:contributions [{:type :template :text "x" :vars {}}]}
                  :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                  :yields {:type :text :text :final-llm-reply}}
          consumer (consumer-step {:step "single" :prompt "x"
                                   :output :final-llm-reply})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [single consumer]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-single-prompt-step
             (-> result :semantic-errors first :type)))))

  (testing "a `:prompt` ref to a non-session step is rejected"
    (let [consumer (consumer-step {:step "discover" :prompt "x"
                                   :output :final-llm-reply})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [valid-invoke-step consumer]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-non-session-step
             (-> result :semantic-errors first :type)))))

  (testing "a same-step sibling-group `:prompt` ref in a contribution is rejected"
    (let [step (assoc-in prior-multi-prompt-step
                         [:session :prompts]
                         [named-prompt-group-a
                          (assoc named-prompt-group-b
                                 :contributions
                                 [{:type :source
                                   :from {:step "review" :prompt "architecture"
                                          :output :final-llm-reply}}])])
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step]})]
      (is (false? (:valid? result)))
      (is (= :prompt-ref-same-step
             (-> result :semantic-errors first :type)))))

  (testing "the step's own post-drain judge may reference its prompt-groups (carve-out)"
    (let [step (assoc prior-multi-prompt-step
                      :judge {:type :llm
                              :session {:contributions
                                        [{:type :source
                                          :from {:step "review" :prompt "architecture"
                                                 :output :final-llm-reply}}]}}
                      :on {:done {:goto :done}})
          result (workflow-ir/validate-workflow-ir
                  {:version :workflow-ir/v1 :steps [step]})]
      (is (= {:valid? true :structural-errors nil :semantic-errors []}
             result)))))
