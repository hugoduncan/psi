(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence]
   [psi.agent-session.prompt-control]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime :as workflow-statechart-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def single-step-definition-with-meta
  {:definition-id "planner"
   :name "planner"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}
                                      :original {:source :workflow-input :path [:original]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :capability-policy {:tools #{"read" "bash"}}}}
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :medium}})

(def builder-definition-with-meta
  {:definition-id "builder"
   :name "builder"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "builder"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :capability-policy {:tools #{"read" "bash" "edit" "write"}}}}
   :workflow-file-meta {:system-prompt "You are a builder."
                        :tools ["read" "bash" "edit" "write"]
                        :thinking-level :off}})

(def multi-step-definition-with-meta
  {:definition-id "plan-build"
   :name "plan-build"
   :step-order ["step-1-planner" "step-2-builder"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder"}
                             :prompt-template "Execute: $INPUT"
                             :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}
   :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}})

(def judged-definition
  {:definition-id "plan-build-review-judged"
   :name "plan-build-review-judged"
   :step-order ["step-1-planner" "step-2-builder" "step-3-reviewer"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder"}
                             :prompt-template "Execute: $INPUT\nOriginal: $ORIGINAL"
                             :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-3-reviewer" {:executor {:type :agent :profile "reviewer"}
                              :prompt-template "Review: $INPUT\nOriginal: $ORIGINAL"
                              :input-bindings {:input {:source :step-output :path ["step-2-builder" :outputs :text]}
                                               :original {:source :workflow-input :path [:original]}}
                              :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                              :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                              :judge {:prompt "APPROVED or REVISE?"
                                      :system-prompt "You are a routing judge."
                                      :projection {:type :tail :turns 1}}
                              :on {"APPROVED" {:goto :next}
                                   "REVISE" {:goto "step-2-builder" :max-iterations 3}}}}})

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (create-session-context {:persist? false})
          single-step-with-model (assoc-in single-step-definition-with-meta [:workflow-file-meta :model]
                                           {:provider :anthropic :id "claude-test"})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-with-model)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-1"
                                                                   :workflow-input {:input "plan it"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")
          config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1")]
      (is (= "You are a planner." (:system-prompt config)))
      (is (= :medium (:thinking-level config)))
      (is (= {:provider :anthropic :id "claude-test"} (:model config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs config))))
      (is (= ["clojure-coding-standards"] (mapv :name (:skills config)))))))

(deftest resolve-step-session-config-multi-step-test
  (testing "multi-step workflow composes referenced workflow prompt with framing prompt"
    (let [[ctx _] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s builder-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-2"
                                                                   :workflow-input {:input "build it"
                                                                                    :original "build this"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")
          planner-config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1-planner")
          builder-config (workflow-execution/resolve-step-session-config ctx workflow-run "step-2-builder")]
      (is (= "You are a planner.\n\nCoordinate a plan-build cycle." (:system-prompt planner-config)))
      (is (= "You are a builder.\n\nCoordinate a plan-build cycle." (:system-prompt builder-config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs planner-config))))
      (is (= ["read" "bash" "edit" "write"] (mapv :name (:tool-defs builder-config)))))))

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                           multi-step-definition-with-meta)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-prompt"
                                                               :workflow-input {:input "ship it"
                                                                                :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-execution/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:text "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-execution/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute: plan text" (:prompt prompt1)))))

(deftest execute-run-linear-test
  (testing "execute-run! drives a linear workflow to completion through the statechart runtime"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-linear"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build this feature"}})]
                       s)))
          prompts* (atom [])
          responses* (atom ["planner output" "builder output"])]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx child-session-id prompt]
                                                                  (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                                                                  nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content (let [resp (first @responses*)]
                                                                                             (swap! responses* subvec 1)
                                                                                             resp)})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-linear")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-linear")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (false? (:blocked? result)))
          (is (= 2 (count (:steps-executed result))))
          (is (= {:outcome :ok :outputs {:text "builder output"}}
                 (get-in run [:step-runs "step-2-builder" :accepted-result])))
          (is (= ["ship it"
                  "Execute: planner output"]
                 (mapv :prompt @prompts*))))))))

(deftest execute-run-preserves-parent-extension-prompt-contributions-test
  (testing "workflow child sessions inherit parent extension prompt contributions by default"
    (let [[ctx session-id] (create-session-context {:persist? false})
          planner-def (assoc single-step-definition-with-meta :workflow-file-meta
                             {:system-prompt "You are a planner."
                              :tools ["read"]
                              :thinking-level :medium})
          contribution {:id "work-on"
                        :ext-path "/extensions/work-on"
                        :section "Extension Capabilities"
                        :content "command: /work-on"
                        :enabled true
                        :created-at (java.time.Instant/parse "2026-04-22T12:00:00Z")
                        :updated-at (java.time.Instant/parse "2026-04-22T12:00:00Z")}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state planner-def)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-ext-1"
                                                                   :workflow-input {:input "plan it"}})
                           s (assoc-in s [:agent-session :sessions session-id :data :tool-defs]
                                       [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}])
                           s (assoc-in s [:agent-session :sessions session-id :data :prompt-contributions]
                                       [contribution])]
                       s)))]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _child-session-id _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content "planner output"})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-ext-1")
              child-id (-> result :steps-executed first :execution-session-id)
              child-sd (ss/get-session-data-in ctx child-id)]
          (is (= :completed (:status result)))
          (is (= [contribution]
                 (mapv #(select-keys % [:id :ext-path :section :content :enabled :created-at :updated-at])
                       (:prompt-contributions child-sd))))
          (is (str/includes? (prompt-request/effective-system-prompt child-sd)
                             "command: /work-on")))))))

(deftest execute-run-with-judge-loop-test
  (testing "execute-run! handles a judge loop via the statechart runtime"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state judged-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build-review-judged"
                                                                   :run-id "run-loop"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build feature"}})]
                       s)))
          step-executions* (atom [])
          judge-call-count* (atom 0)
          ctx' (assoc ctx :create-workflow-child-session-fn (fn [_ctx _parent _opts] nil))]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _text] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in
                    (fn [_ctx sid]
                      (cond
                        (str/includes? sid "planner")
                        (do (swap! step-executions* conj "step-1-planner")
                            {:content "plan output"})

                        (str/includes? sid "builder")
                        (do (swap! step-executions* conj "step-2-builder")
                            {:content "build output"})

                        (str/includes? sid "reviewer")
                        (do (swap! step-executions* conj "step-3-reviewer")
                            {:content "review output"})

                        :else
                        (let [n (swap! judge-call-count* inc)]
                          {:role "assistant"
                           :content [{:type :text :text (if (= 1 n) "REVISE" "APPROVED")}]})))
                    psi.agent-session.persistence/messages-from-entries-in
                    (fn [_ctx _sid]
                      [{:role "user" :content "Review prompt"}
                       {:role "assistant" :content [{:type :text :text "review output"}]}])]
        (let [result (workflow-execution/execute-run! ctx' session-id "run-loop")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-loop")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= 5 (count (:steps-executed result))))
          (is (= ["step-1-planner" "step-2-builder" "step-3-reviewer" "step-2-builder" "step-3-reviewer"]
                 @step-executions*))
          (is (= 2 (get-in run [:step-runs "step-2-builder" :iteration-count])))
          (is (= 2 (get-in run [:step-runs "step-3-reviewer" :iteration-count]))))))))

(deftest resume-and-execute-run-test
  (testing "resume-and-execute-run! resumes a blocked run with a fresh attempt"
    (let [[ctx session-id] (create-session-context {:persist? false})
          blocked-definition {:definition-id "blocked-review"
                              :name "Blocked Review"
                              :step-order ["step-1-review"]
                              :steps {"step-1-review" {:executor {:type :agent :profile "reviewer"}
                                                       :prompt-template "$INPUT"
                                                       :input-bindings {:input {:source :workflow-input :path [:input]}}
                                                       :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                                       :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state blocked-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "blocked-review"
                                                                   :run-id "run-resume"
                                                                   :workflow-input {:input "need approval"}})]
                       s)))
          first-call* (atom true)]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _sid _text] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in
                    (fn [_ctx _sid]
                      (if (compare-and-set! first-call* true false)
                        {:content "pause"}
                        {:content "approved"}))]
        ;; force blocked state by directly queuing actor/blocked after first start
        (with-redefs [psi.agent-session.workflow-statechart-runtime/make-workflow-actions
                      (let [orig psi.agent-session.workflow-statechart-runtime/make-workflow-actions]
                        (fn [ctx* parent run-id wm* q*]
                          (let [af (orig ctx* parent run-id wm* q*)
                                blocked-once* (atom false)]
                            (fn [action-key data]
                              (if (and (= action-key :step/enter) (compare-and-set! blocked-once* false true))
                                (do
                                  (af action-key data)
                                  (swap! wm* assoc :pending-actor-result {:kind :blocked
                                                                          :payload {:outcome :blocked :blocked {:question "approve?"}}
                                                                          :step-id (:step-id data)
                                                                          :attempt-id (get-in @wm* [:attempt-ids (:step-id data)])
                                                                          :updated-at (java.time.Instant/now)})
                                  (reset! q* [{:event :actor/blocked :data {}}])
                                  nil)
                                (af action-key data))))))]
          (let [blocked-result (workflow-execution/execute-run! ctx session-id "run-resume")
                resumed-result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")
                run (workflow-runtime/workflow-run-in @(:state* ctx) "run-resume")]
            (is (= :blocked (:status blocked-result)))
            (is (true? (:blocked? blocked-result)))
            (is (= :completed (:status resumed-result)))
            (is (true? (:terminal? resumed-result)))
            (is (= 2 (count (get-in run [:step-runs "step-1-review" :attempts]))))))))))
