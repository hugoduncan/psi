(ns psi.agent-session.workflow-tools-test
  "psi-tool workflow action tests, split from tools-test to stay under file-length limit."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime]))

(defn- execute-run-nullable
  [state*]
  (fn [_ctx _session-id run-id]
    (loop [steps-executed []]
      (let [run (psi.agent-session.workflow-runtime/workflow-run-in @state* run-id)
            status (:status run)]
        (cond
          (contains? #{:completed :failed :cancelled} status)
          {:run-id run-id
           :status status
           :steps-executed steps-executed
           :terminal? true
           :blocked? false}

          (= :blocked status)
          {:run-id run-id
           :status status
           :steps-executed steps-executed
           :terminal? false
           :blocked? true}

          :else
          (let [step-id (:current-step-id run)
                next-step-id (second (drop-while #(not= step-id %) (get-in run [:effective-definition :step-order])))
                envelope {:outcome :ok :outputs {:text (str step-id " output")}}]
            (swap! state* workflow-progression/submit-result-envelope run-id step-id envelope)
            (recur (conj steps-executed {:step-id step-id
                                         :attempt-id (str step-id "-attempt")
                                         :execution-session-id (str step-id "-session")
                                         :status (get-in @state* [:workflows :runs run-id :status])
                                         :next-step-id next-step-id}))))))))

(defn- resume-and-execute-run-nullable
  [state*]
  (fn [ctx session-id run-id]
    (swap! state* workflow-progression/resume-blocked-run run-id)
    ((execute-run-nullable state*) ctx session-id run-id)))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context
              (test-support/safe-context-opts
               (merge {:execute-workflow-run-fn (execute-run-nullable nil)
                       :resume-and-execute-workflow-run-fn (resume-and-execute-run-nullable nil)}
                      opts)))
         _   (swap! (:state* ctx)
                    (fn [state]
                      state))
         ctx (assoc ctx
                    :execute-workflow-run-fn (execute-run-nullable (:state* ctx))
                    :resume-and-execute-workflow-run-fn (resume-and-execute-run-nullable (:state* ctx)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest make-psi-tool-workflow-test
  (testing "workflow list-definitions reports registered definitions"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (first
                      (let [[state' _ _]
                            (psi.agent-session.workflow-runtime/register-definition
                             state
                             {:definition-id "plan-build-review"
                              :name "Plan Build Review"
                              :step-order ["plan"]
                              :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                              :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                              :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}})]
                        [state']))))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "list-definitions"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :workflow (:psi-tool/action parsed)))
      (is (= :list-definitions (:psi-tool/workflow-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= 1 (get-in parsed [:psi-tool/workflow :definition-count])))
      (is (= ["plan-build-review"] (get-in parsed [:psi-tool/workflow :definition-ids])))))

  (testing "workflow create-run creates a run from inline definition"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow"
                                   "op" "create-run"
                                   "definition" "{:name \"Inline\" :step-order [\"plan\"] :steps {\"plan\" {:executor {:type :agent :profile \"planner\" :mode :sync} :result-schema [:map [:outcome [:= :ok]] [:outputs :map]] :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}"
                                   "workflow-input" "{:task \"ship it\"}"})
          parsed (read-string (:content result))
          run-id (get-in parsed [:psi-tool/workflow :run-id])]
      (is (false? (:is-error result)))
      (is (= :create-run (:psi-tool/workflow-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (string? run-id))
      (is (= :pending (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= {:task "ship it"} (get-in parsed [:psi-tool/workflow :run :workflow-input])))
      (is (= run-id (get-in @(:state* ctx) [:workflows :run-order 0])))))

  (testing "workflow create-run plus execute-run completes an ad-hoc inline workflow"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool          (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          create-result ((:execute tool) {"action" "workflow"
                                          "op" "create-run"
                                          "definition" "{:name \"Inline Lambda Build\" :step-order [\"step-1-lambda-compiler\" \"step-2-lambda-decompiler\" \"step-3-lambda-compiler\"] :steps {\"step-1-lambda-compiler\" {:label \"lambda-compiler\" :executor {:type :agent :profile \"lambda-compiler\"} :prompt-template \"compile a lambda for: $INPUT\" :input-bindings {:input {:source :workflow-input :path [:input]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}} \"step-2-lambda-decompiler\" {:label \"lambda-decompiler\" :executor {:type :agent :profile \"lambda-decompiler\"} :prompt-template \"decompile the lambda expression: $INPUT\" :input-bindings {:input {:source :step-output :path [\"step-1-lambda-compiler\" :outputs :text]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}} \"step-3-lambda-compiler\" {:label \"lambda-compiler\" :executor {:type :agent :profile \"lambda-compiler\"} :prompt-template \"compile a lambda for: $INPUT\" :input-bindings {:input {:source :step-output :path [\"step-2-lambda-decompiler\" :outputs :text]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}}}"
                                          "workflow-input" "{:input \"refine the scope clearly and collaboratively\"}"})
          create-parsed (read-string (:content create-result))
          run-id        (get-in create-parsed [:psi-tool/workflow :run-id])
          exec-result   ((:execute tool) {"action" "workflow" "op" "execute-run" "run-id" run-id})
          exec-parsed   (read-string (:content exec-result))]
      (is (false? (:is-error create-result)))
      (is (= :create-run (:psi-tool/workflow-op create-parsed)))
      (is (string? run-id))
      (is (false? (:is-error exec-result)))
      (is (= :execute-run (:psi-tool/workflow-op exec-parsed)))
      (is (= :completed (get-in exec-parsed [:psi-tool/workflow :status])))
      (is (true? (get-in exec-parsed [:psi-tool/workflow :terminal?])))
      (is (= :completed (get-in exec-parsed [:psi-tool/workflow :run :status])))
      (is (= 3 (count (get-in exec-parsed [:psi-tool/workflow :steps-executed]))))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs run-id :status])))))

  (testing "workflow list-runs and read-run return run summaries"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                      :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                      :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool        (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          list-result ((:execute tool) {"action" "workflow" "op" "list-runs"})
          list-parsed (read-string (:content list-result))
          read-result ((:execute tool) {"action" "workflow" "op" "read-run" "run-id" "run-1"})
          read-parsed (read-string (:content read-result))]
      (is (false? (:is-error list-result)))
      (is (= :list-runs (:psi-tool/workflow-op list-parsed)))
      (is (= 1 (get-in list-parsed [:psi-tool/workflow :run-count])))
      (is (= ["run-1"] (get-in list-parsed [:psi-tool/workflow :run-ids])))
      (is (false? (:is-error read-result)))
      (is (= :read-run (:psi-tool/workflow-op read-parsed)))
      (is (= "run-1" (get-in read-parsed [:psi-tool/workflow :run-id])))
      (is (= :pending (get-in read-parsed [:psi-tool/workflow :run :status])))))

  (testing "workflow resume-run resumes blocked runs and continues execution"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                      :prompt-template "Return exactly {:outcome :ok :outputs {}}"
                                      :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                      :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 run-id _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       (-> state2
                           (assoc-in [:workflows :runs run-id :status] :blocked)
                           (assoc-in [:workflows :runs run-id :blocked] {:reason "needs resume"})))))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "resume-run" "run-id" "run-1"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :resume-run (:psi-tool/workflow-op parsed)))
      (is (= :completed (get-in parsed [:psi-tool/workflow :run :status])))
      (is (true? (get-in parsed [:psi-tool/workflow :terminal?])))
      (is (false? (get-in parsed [:psi-tool/workflow :blocked?])))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))))

  (testing "workflow execute-run executes pending runs to completion"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                      :prompt-template "Return exactly {:outcome :ok :outputs {}}"
                                      :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                      :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "execute-run" "run-id" "run-1"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :execute-run (:psi-tool/workflow-op parsed)))
      (is (= :completed (get-in parsed [:psi-tool/workflow :status])))
      (is (true? (get-in parsed [:psi-tool/workflow :terminal?])))
      (is (= :completed (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))))

  (testing "workflow cancel-run cancels non-terminal runs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                      :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                      :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "cancel-run" "run-id" "run-1" "reason" "operator request"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :cancel-run (:psi-tool/workflow-op parsed)))
      (is (= :cancelled (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= "operator request" (get-in parsed [:psi-tool/workflow :run :terminal-outcome :reason])))
      (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-1" :status]))))))
