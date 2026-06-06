(ns psi.agent-session.mutations.canonical-workflows-test
  "Tests for canonical workflow Pathom mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-run-retention :as workflow-run-retention]
   [psi.session-state.state :as ss]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn make-test-ctx
  "Create a minimal ctx with a state atom for testing pure mutations."
  ([] (make-test-ctx {}))
  ([initial-state]
   (let [ctx (merge
              (session/create-context
               (test-support/safe-context-opts {:persist? false}))
              {:execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})
               :resume-and-execute-workflow-run-fn (fn [_ _ _] {:status :completed :terminal? true :blocked? false :steps-executed []})})]
     (swap! (:state* ctx) merge {:workflows (workflow-model/initial-workflow-state)})
     (swap! (:state* ctx) merge initial-state)
     ctx)))

(def sample-definition
  {:definition-id "test-workflow"
   :name "test-workflow"
   :summary "A test workflow"
   :description "For testing"
   :steps [{:name "step-1"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}]})

(defn- workflow-owned-session!
  [ctx parent-session-id session-name]
  (let [sd (session/new-session-in! ctx parent-session-id {:session-name session-name})
        session-id (:session-id sd)]
    (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :workflow-owned?] true)
    session-id))

(defn- install-terminal-run!
  [ctx {:keys [run-id parent-id status finished-at linked-session-ids]
        :or {linked-session-ids []}}]
  (swap! (:state* ctx)
         (fn [state]
           (-> state
               (assoc-in [:workflows :runs run-id]
                         {:run-id run-id
                          :parent-session-id parent-id
                          :status status
                          :finished-at finished-at
                          :step-runs (cond-> {}
                                       (seq linked-session-ids)
                                       (assoc "plan"
                                              {:attempts (mapv (fn [session-id]
                                                                 {:execution-session-id session-id})
                                                               linked-session-ids)}))})
               (update-in [:workflows :run-order] (fnil conj []) run-id)))))

(deftest register-workflow-definition-test
  (testing "registers a valid definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                                 :definition sample-definition})]
      (is (true? (:psi.workflow/registered? result)))
      (is (= "test-workflow" (:psi.workflow/definition-id result)))
      (is (nil? (:psi.workflow/error result)))
      (is (some? (workflow-registry/workflow-definition @(:state* ctx) "test-workflow")))))

  (testing "returns error for invalid definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                                 :definition {:bad "data"}})]
      (is (false? (:psi.workflow/registered? result)))
      (is (string? (:psi.workflow/error result))))))

(deftest create-workflow-run-test
  (testing "creates a run from a registered definition"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          result (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :session-id "delegating-session"
                                                        :definition-id "test-workflow"
                                                        :workflow-input {:input "hello" :original "hello"}
                                                        :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :pending (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))
      (is (= "delegating-session"
             (get-in @(:state* ctx) [:workflows :runs "run-1" :parent-session-id])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-1"])))))

  (testing "returns error for unknown definition"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :definition-id "nonexistent"
                                                        :workflow-input {}})]
      (is (nil? (:psi.workflow/run-id result)))
      (is (string? (:psi.workflow/error result)))))

  (testing "captures the inherited-defaults snapshot from the invoking session
            at invoke time (task 207 S4)"
    (let [ctx (make-test-ctx)
          sd (session/new-session-in! ctx nil {:session-name "delegator"})
          session-id (:session-id sd)
          _ (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
                   merge {:model {:provider "anthropic" :id "claude-test"}
                          :prompt-mode :concise
                          :speed-mode :fast
                          :effort-override :xhigh})
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :session-id session-id
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "go"}
                                                   :run-id "run-snap"})
          snapshot (get-in @(:state* ctx) [:workflows :runs "run-snap" :inherited-defaults])]
      (is (= {:provider "anthropic" :id "claude-test"} (:model snapshot)))
      (is (= :concise (:prompt-mode snapshot)))
      (is (= :fast (:speed-mode snapshot)))
      (is (= :xhigh (:effort-override snapshot)))))

  (testing "no session-id → no inherited-defaults snapshot captured"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "go"}
                                                   :run-id "run-no-snap"})]
      (is (not (contains? (get-in @(:state* ctx) [:workflows :runs "run-no-snap"])
                          :inherited-defaults))))))

(deftest execute-workflow-run-test
  (testing "executes a pending run to completion"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :step-runs "step-1" :accepted-result]
                   {:outcome :ok :outputs {:text "final reply"}})
          result (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                                         :session-id "parent-session"
                                                         :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :completed (:psi.workflow/status result)))
      (is (true? (:psi.workflow/terminal? result)))
      (is (= "final reply" (:psi.workflow/result result)))
      (is (nil? (:psi.workflow/error result)))))

  (testing "blank accepted-result text is treated as missing"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :step-runs "step-1" :accepted-result]
                   {:outcome :ok :outputs {:text "   "}})
          result (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                                         :session-id "parent-session"
                                                         :run-id "run-1"})]
      (is (= :completed (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/result result))))))

(deftest resume-workflow-run-test
  (testing "resume-workflow-run updates workflow input before resuming when provided"
    (let [captured-run (atom nil)
          ctx (assoc (make-test-ctx)
                     :resume-and-execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (reset! captured-run (get-in @(:state* ctx*) [:workflows :runs run-id]))
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "old" :original "old"}
                                                   :run-id "run-1"})
          _ (swap! (:state* ctx) assoc-in [:workflows :runs "run-1" :status] :blocked)
          result (cwf-mutations/resume-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :session-id "parent-session"
                                                        :run-id "run-1"
                                                        :workflow-input {:input "new" :original "new"}})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :completed (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))
      (is (= {:input "new" :original "new"}
             (:workflow-input @captured-run))))))

(deftest cancel-workflow-run-test
  (testing "cancels a pending run"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {:input "hello" :original "hello"}
                                                   :run-id "run-1"})
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"
                                                        :reason "test cancel"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (= :cancelled (:psi.workflow/status result)))
      (is (nil? (:psi.workflow/error result)))))

  (testing "returns error for nonexistent run"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "ghost"})]
      (is (string? (:psi.workflow/error result)))))

  (testing "returns error for already-terminal run"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          _ (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :run-id "run-1"})
          result (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"})]
      (is (string? (:psi.workflow/error result))))))

(deftest remove-workflow-run-test
  (testing "removes an existing run from canonical state"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          result (cwf-mutations/remove-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "run-1"})]
      (is (= "run-1" (:psi.workflow/run-id result)))
      (is (true? (:psi.workflow/removed? result)))
      (is (nil? (:psi.workflow/error result)))
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (= [] (get-in @(:state* ctx) [:workflows :run-order])))))

  (testing "returns error for nonexistent run"
    (let [ctx (make-test-ctx)
          result (cwf-mutations/remove-workflow-run {} {:psi/agent-session-ctx ctx
                                                        :run-id "ghost"})]
      (is (false? (:psi.workflow/removed? result)))
      (is (string? (:psi.workflow/error result))))))

(deftest list-workflow-definitions-test
  (testing "lists registered definitions"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          result (cwf-mutations/list-workflow-definitions {} {:psi/agent-session-ctx ctx})]
      (is (= 1 (:psi.workflow/definition-count result)))
      (is (= ["test-workflow"] (mapv :definition-id (:psi.workflow/definitions result)))))))

(deftest workflow-run-retention-helpers-test
  (testing "negative configured retention counts are rejected"
    (let [ctx (make-test-ctx)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid completed workflow run retention count"
                            (workflow-run-retention/completed-workflow-run-retention-count
                             (assoc ctx :config {:completed-workflow-run-retention-count -1}))))))

  (testing "linked-session-ids includes execution and judge ids once each"
    (let [workflow-run {:step-runs {"plan" {:attempts [{:execution-session-id "exec-1"
                                                        :judge-session-id "judge-1"}
                                                       {:execution-session-id "exec-1"
                                                        :judge-session-id "judge-2"}]}}}]
      (is (= ["exec-1" "judge-1" "judge-2"]
             (workflow-run-retention/linked-session-ids workflow-run)))))

  (testing "equal finished-at ordering uses later run-order creation as newer"
    (let [finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
          state {:workflows {:runs {"run-1" {:run-id "run-1" :parent-session-id "parent" :status :completed :finished-at finished-at}
                                    "run-2" {:run-id "run-2" :parent-session-id "parent" :status :completed :finished-at finished-at}}
                             :run-order ["run-1" "run-2"]}}
          result (workflow-run-retention/runs-to-retain-and-remove state "parent" 1)]
      (is (= ["run-2"] (mapv :run-id (:kept-runs result))))
      (is (= ["run-1"] (mapv :run-id (:removed-runs result)))))))

(deftest workflow-run-retention-mutation-seams-test
  (testing "execute-workflow-run terminalization triggers retention cleanup from the public mutation seam"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :finished-at]
                              (java.time.Instant/parse "2026-05-29T12:01:00Z"))
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "hello" :original "hello"}
                                             :run-id "run-2"})
      (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                              :session-id parent-id
                                              :run-id "run-2"})
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-2"])))
      (is (nil? (ss/get-session-data-in ctx old-child-id)))
      (is (some? (ss/get-session-data-in ctx parent-id)))))

  (testing "resume-workflow-run terminalization triggers retention cleanup from the public mutation seam"
    (let [ctx (assoc (make-test-ctx)
                     :resume-and-execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :failed)
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :finished-at]
                              (java.time.Instant/parse "2026-05-29T12:02:00Z"))
                       {:status :failed :terminal? true :blocked? false :steps-executed []}))
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-blocked-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "old" :original "old"}
                                             :run-id "run-2"})
      (swap! (:state* ctx) assoc-in [:workflows :runs "run-2" :status] :blocked)
      (cwf-mutations/resume-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :run-id "run-2"
                                             :workflow-input {:input "new" :original "new"}})
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-2"])))
      (is (= :failed (get-in @(:state* ctx) [:workflows :runs "run-2" :status])))
      (is (nil? (ss/get-session-data-in ctx old-child-id)))
      (is (some? (ss/get-session-data-in ctx parent-id)))))

  (testing "cancel-workflow-run terminalization triggers retention cleanup from the public mutation seam"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-cancelled-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "hello" :original "hello"}
                                             :run-id "run-2"})
      (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                             :run-id "run-2"
                                             :reason "test cancel"})
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-2"])))
      (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-2" :status])))
      (is (nil? (ss/get-session-data-in ctx old-child-id)))
      (is (some? (ss/get-session-data-in ctx parent-id))))))

(deftest workflow-run-retention-cleanup-test
  (testing "default retention keeps only newest retained terminal run for one originating session and tree-closes linked workflow-owned sessions"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          child-1 (session/new-session-in! ctx parent-id {:session-name "wf-1"})
          child-1-id (:session-id child-1)
          grandchild-1 (session/new-session-in! ctx child-1-id {:session-name "wf-1-child"})
          grandchild-1-id (:session-id grandchild-1)
          child-2 (session/new-session-in! ctx parent-id {:session-name "wf-2"})
          child-2-id (:session-id child-2)
          finished-1 (java.time.Instant/parse "2026-05-29T12:00:00Z")
          finished-2 (java.time.Instant/parse "2026-05-29T12:01:00Z")]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-1-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions grandchild-1-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions grandchild-1-id :data :parent-session-id] child-1-id)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-2-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-1" {:run-id "run-1"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at finished-1
                       :step-runs {"plan" {:attempts [{:execution-session-id child-1-id}]}}}
              "run-2" {:run-id "run-2"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at finished-2
                       :step-runs {"plan" {:attempts [{:execution-session-id child-2-id}]}}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-1" "run-2"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-2")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-2"])))
      (is (nil? (ss/get-session-data-in ctx child-1-id)))
      (is (nil? (ss/get-session-data-in ctx grandchild-1-id)))
      (is (some? (ss/get-session-data-in ctx child-2-id)))
      (is (some? (ss/get-session-data-in ctx parent-id)))))

  (testing "cleanup removes multiple linked execution and judge workflow-owned session roots recorded on one removed run"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-exec (session/new-session-in! ctx parent-id {:session-name "old-exec"})
          old-exec-id (:session-id old-exec)
          old-exec-child (session/new-session-in! ctx old-exec-id {:session-name "old-exec-child"})
          old-exec-child-id (:session-id old-exec-child)
          old-judge (session/new-session-in! ctx parent-id {:session-name "old-judge"})
          old-judge-id (:session-id old-judge)
          old-judge-child (session/new-session-in! ctx old-judge-id {:session-name "old-judge-child"})
          old-judge-child-id (:session-id old-judge-child)
          retained-exec (session/new-session-in! ctx parent-id {:session-name "retained-exec"})
          retained-exec-id (:session-id retained-exec)
          finished-1 (java.time.Instant/parse "2026-05-29T12:00:00Z")
          finished-2 (java.time.Instant/parse "2026-05-29T12:01:00Z")]
      (doseq [session-id [old-exec-id old-exec-child-id old-judge-id old-judge-child-id retained-exec-id]]
        (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :workflow-owned?] true))
      (swap! (:state* ctx) assoc-in [:agent-session :sessions old-exec-child-id :data :parent-session-id] old-exec-id)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions old-judge-child-id :data :parent-session-id] old-judge-id)
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-1" {:run-id "run-1"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at finished-1
                       :step-runs {"plan" {:attempts [{:execution-session-id old-exec-id
                                                       :judge-session-id old-judge-id}
                                                      {:execution-session-id old-exec-id
                                                       :judge-session-id old-judge-id}]}}}
              "run-2" {:run-id "run-2"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at finished-2
                       :step-runs {"plan" {:attempts [{:execution-session-id retained-exec-id}]}}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-1" "run-2"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-2")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (nil? (ss/get-session-data-in ctx old-exec-id)))
      (is (nil? (ss/get-session-data-in ctx old-exec-child-id)))
      (is (nil? (ss/get-session-data-in ctx old-judge-id)))
      (is (nil? (ss/get-session-data-in ctx old-judge-child-id)))
      (is (some? (ss/get-session-data-in ctx retained-exec-id)))
      (is (some? (ss/get-session-data-in ctx parent-id)))))

  (testing "retention 2 keeps the two newest retained terminal runs for one originating session"
    (let [ctx (assoc (make-test-ctx) :config {:completed-workflow-run-retention-count 2})
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          finished-1 (java.time.Instant/parse "2026-05-29T12:00:00Z")
          finished-2 (java.time.Instant/parse "2026-05-29T12:01:00Z")
          finished-3 (java.time.Instant/parse "2026-05-29T12:02:00Z")]
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-1" {:run-id "run-1" :parent-session-id parent-id :status :completed :finished-at finished-1 :step-runs {}}
              "run-2" {:run-id "run-2" :parent-session-id parent-id :status :completed :finished-at finished-2 :step-runs {}}
              "run-3" {:run-id "run-3" :parent-session-id parent-id :status :failed :finished-at finished-3 :step-runs {}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-1" "run-2" "run-3"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-3")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-2"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-3"])))))

  (testing "retention 0 removes newly terminal retained runs immediately"
    (let [ctx (assoc (make-test-ctx) :config {:completed-workflow-run-retention-count 0})
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          child (session/new-session-in! ctx parent-id {:session-name "wf"})
          child-id (:session-id child)]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-1" {:run-id "run-1"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                       :step-runs {"plan" {:attempts [{:execution-session-id child-id}]}}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-1"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-1")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-1"])))
      (is (nil? (ss/get-session-data-in ctx child-id)))))

  (testing "nested :delegate-step sub-runs do not count against the originating session's retention budget"
    ;; A single user delegation of a multi-step workflow with a :delegate step
    ;; produces a top-level run plus a nested sub-run that shares the same
    ;; originating :parent-session-id. The nested sub-run (tagged with
    ;; :delegating-run-id) must NOT count toward the per-session retention
    ;; count, so the user's just-delegated top-level run and its sessions are
    ;; retained, and the nested sub-run + its sessions are retained as part of
    ;; that delegation rather than evicted as if they were a second delegation.
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          top-child (session/new-session-in! ctx parent-id {:session-name "wf-top"})
          top-child-id (:session-id top-child)
          nested-child (session/new-session-in! ctx parent-id {:session-name "wf-nested"})
          nested-child-id (:session-id nested-child)
          finished-nested (java.time.Instant/parse "2026-05-29T12:00:00Z")
          finished-top (java.time.Instant/parse "2026-05-29T12:01:00Z")]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions top-child-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions nested-child-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-top" {:run-id "run-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at finished-top
                         :step-runs {"plan" {:attempts [{:execution-session-id top-child-id}]}}}
              "run-nested" {:run-id "run-nested"
                            :parent-session-id parent-id
                            :delegating-run-id "run-top"
                            :status :completed
                            :finished-at finished-nested
                            :step-runs {"work" {:attempts [{:execution-session-id nested-child-id}]}}}})
      ;; Nested run is created after the top-level run during execution.
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-top" "run-nested"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-top")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-top"]))
          "the user's top-level delegated run is retained")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-nested"]))
          "the nested sub-run is retained (not counted as a competing delegation)")
      (is (some? (ss/get-session-data-in ctx top-child-id))
          "the top-level run's session survives")
      (is (some? (ss/get-session-data-in ctx nested-child-id))
          "the nested sub-run's session survives")))

  (testing "removing a top-level run transitively removes its nested :delegate sub-runs and their sessions"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-top-child (session/new-session-in! ctx parent-id {:session-name "old-top"})
          old-top-child-id (:session-id old-top-child)
          old-nested-child (session/new-session-in! ctx parent-id {:session-name "old-nested"})
          old-nested-child-id (:session-id old-nested-child)
          new-top-child (session/new-session-in! ctx parent-id {:session-name "new-top"})
          new-top-child-id (:session-id new-top-child)
          t0 (java.time.Instant/parse "2026-05-29T12:00:00Z")
          t1 (java.time.Instant/parse "2026-05-29T12:01:00Z")
          t2 (java.time.Instant/parse "2026-05-29T12:02:00Z")]
      (doseq [sid [old-top-child-id old-nested-child-id new-top-child-id]]
        (swap! (:state* ctx) assoc-in [:agent-session :sessions sid :data :workflow-owned?] true))
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"old-top" {:run-id "old-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at t0
                         :step-runs {"plan" {:attempts [{:execution-session-id old-top-child-id}]}}}
              "old-nested" {:run-id "old-nested"
                            :parent-session-id parent-id
                            :delegating-run-id "old-top"
                            :status :completed
                            :finished-at t1
                            :step-runs {"work" {:attempts [{:execution-session-id old-nested-child-id}]}}}
              "new-top" {:run-id "new-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at t2
                         :step-runs {"plan" {:attempts [{:execution-session-id new-top-child-id}]}}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["old-top" "old-nested" "new-top"])
      (workflow-run-retention/apply-retention-cleanup! ctx "new-top")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "new-top"]))
          "the newest top-level run is retained")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "old-top"]))
          "the older top-level run is removed")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "old-nested"]))
          "the older run's nested sub-run is transitively removed")
      (is (nil? (ss/get-session-data-in ctx old-top-child-id))
          "the older top-level run's session is removed")
      (is (nil? (ss/get-session-data-in ctx old-nested-child-id))
          "the older run's nested sub-run session is removed")
      (is (some? (ss/get-session-data-in ctx new-top-child-id))
          "the newest top-level run's session survives")))

  (testing "cleanup is isolated per originating parent session and non-terminal runs remain"
    (let [ctx (make-test-ctx)
          parent-a (session/new-session-in! ctx nil {})
          parent-b (session/new-session-in! ctx nil {})
          parent-a-id (:session-id parent-a)
          parent-b-id (:session-id parent-b)]
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-a1" {:run-id "run-a1" :parent-session-id parent-a-id :status :completed :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z") :step-runs {}}
              "run-a2" {:run-id "run-a2" :parent-session-id parent-a-id :status :completed :finished-at (java.time.Instant/parse "2026-05-29T12:01:00Z") :step-runs {}}
              "run-a3" {:run-id "run-a3" :parent-session-id parent-a-id :status :running :step-runs {}}
              "run-b1" {:run-id "run-b1" :parent-session-id parent-b-id :status :completed :finished-at (java.time.Instant/parse "2026-05-29T12:00:30Z") :step-runs {}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-a1" "run-a2" "run-a3" "run-b1"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-a2")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "run-a1"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-a2"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-a3"])))
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-b1"]))))))

(deftest list-workflow-runs-test
  (testing "lists created runs"
    (let [ctx (make-test-ctx)
          _ (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                            :definition sample-definition})
          _ (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                                   :definition-id "test-workflow"
                                                   :workflow-input {}
                                                   :run-id "run-1"})
          result (cwf-mutations/list-workflow-runs {} {:psi/agent-session-ctx ctx})]
      (is (= 1 (:psi.workflow/run-count result)))
      (is (= ["run-1"] (mapv :run-id (:psi.workflow/runs result))))))

  (testing "list-workflow-runs reflects retention cleanup after execute terminalization"
    (let [ctx (assoc (make-test-ctx)
                     :execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :completed)
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :finished-at]
                              (java.time.Instant/parse "2026-05-29T12:01:00Z"))
                       {:status :completed :terminal? true :blocked? false :steps-executed []}))
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "hello" :original "hello"}
                                             :run-id "run-2"})
      (cwf-mutations/execute-workflow-run {} {:psi/agent-session-ctx ctx
                                              :session-id parent-id
                                              :run-id "run-2"})
      (let [result (cwf-mutations/list-workflow-runs {} {:psi/agent-session-ctx ctx})]
        (is (= 1 (:psi.workflow/run-count result)))
        (is (= ["run-2"] (mapv :run-id (:psi.workflow/runs result))))
        (is (= [:completed] (mapv :status (:psi.workflow/runs result)))))))

  (testing "list-workflow-runs reflects retention cleanup after resume terminalization"
    (let [ctx (assoc (make-test-ctx)
                     :resume-and-execute-workflow-run-fn
                     (fn [ctx* _session-id run-id]
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :status] :failed)
                       (swap! (:state* ctx*) assoc-in [:workflows :runs run-id :finished-at]
                              (java.time.Instant/parse "2026-05-29T12:02:00Z"))
                       {:status :failed :terminal? true :blocked? false :steps-executed []}))
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-blocked-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "old" :original "old"}
                                             :run-id "run-2"})
      (swap! (:state* ctx) assoc-in [:workflows :runs "run-2" :status] :blocked)
      (cwf-mutations/resume-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :run-id "run-2"
                                             :workflow-input {:input "new" :original "new"}})
      (let [result (cwf-mutations/list-workflow-runs {} {:psi/agent-session-ctx ctx})]
        (is (= 1 (:psi.workflow/run-count result)))
        (is (= ["run-2"] (mapv :run-id (:psi.workflow/runs result))))
        (is (= [:failed] (mapv :status (:psi.workflow/runs result)))))))

  (testing "list-workflow-runs reflects retention cleanup after cancel terminalization"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-child-id (workflow-owned-session! ctx parent-id "old-cancelled-exec")]
      (cwf-mutations/register-workflow-definition {} {:psi/agent-session-ctx ctx
                                                      :definition sample-definition})
      (install-terminal-run! ctx {:run-id "run-1"
                                  :parent-id parent-id
                                  :status :completed
                                  :finished-at (java.time.Instant/parse "2026-05-29T12:00:00Z")
                                  :linked-session-ids [old-child-id]})
      (cwf-mutations/create-workflow-run {} {:psi/agent-session-ctx ctx
                                             :session-id parent-id
                                             :definition-id "test-workflow"
                                             :workflow-input {:input "hello" :original "hello"}
                                             :run-id "run-2"})
      (cwf-mutations/cancel-workflow-run {} {:psi/agent-session-ctx ctx
                                             :run-id "run-2"
                                             :reason "test cancel"})
      (let [result (cwf-mutations/list-workflow-runs {} {:psi/agent-session-ctx ctx})]
        (is (= 1 (:psi.workflow/run-count result)))
        (is (= ["run-2"] (mapv :run-id (:psi.workflow/runs result))))
        (is (= [:cancelled] (mapv :status (:psi.workflow/runs result))))))))

(deftest terminal-outcome-error-message-test
  (testing "iteration-limit-reached produces actionable error with step, counts, signal, and last result"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "compare"
                   :iteration-count 10
                   :max-iterations 10
                   :last-judge-signal "CHANGED"
                   :last-result-text "λx.prefer(compose(transducers))"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Iteration limit reached" msg))
      (is (re-find #"compare" msg))
      (is (re-find #"10 of 10" msg))
      (is (re-find #"CHANGED" msg))
      (is (re-find #"Last result" msg))
      (is (re-find #"transducers" msg))))

  (testing "iteration-limit-reached without optional fields"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 5
                   :max-iterations 5}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Iteration limit reached" msg))
      (is (not (re-find #"signal" msg)))
      (is (not (re-find #"Last result" msg)))))

  (testing "judge-no-match produces actionable error"
    (let [outcome {:outcome :failed
                   :reason :judge-no-match
                   :step-id "review"
                   :judge-output "MAYBE"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (re-find #"did not match" msg))
      (is (re-find #"review" msg))
      (is (re-find #"MAYBE" msg))))

  (testing "unknown failure reason uses generic fallback"
    (let [outcome {:outcome :failed
                   :reason :some-other-reason
                   :step-id "build"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (re-find #"some-other-reason" msg))
      (is (re-find #"build" msg))))

  (testing "nil terminal-outcome returns nil"
    (is (nil? (#'cwf-mutations/terminal-outcome-error-message nil))))

  (testing "terminal-outcome with nil :reason uses defensive fallback without NPE"
    (let [outcome {:outcome :failed
                   :reason nil
                   :step-id "build"}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"build" msg))
      (is (not (re-find #"null" (str msg))))))

  (testing "run-failure-error falls through to terminal-outcome when no step errors"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error nil}]}
          final-run {:terminal-outcome {:outcome :failed
                                        :reason :iteration-limit-reached
                                        :step-id "a"
                                        :iteration-count 3
                                        :max-iterations 3}}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (re-find #"Iteration limit reached" msg))))

  (testing "empty-string last-result-text produces no Last result header"
    (let [outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 3
                   :max-iterations 3
                   :last-result-text ""}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (not (re-find #"Last result" msg))
          "Empty last-result-text should not produce a dangling 'Last result:' header")))

  (testing "long last-result-text is truncated with marker"
    (let [long-text (apply str (repeat 3000 "x"))
          outcome {:outcome :failed
                   :reason :iteration-limit-reached
                   :step-id "check"
                   :iteration-count 3
                   :max-iterations 3
                   :last-result-text long-text}
          msg (#'cwf-mutations/terminal-outcome-error-message outcome)]
      (is (string? msg))
      (is (re-find #"Last result" msg))
      (is (re-find #"\[truncated\]" msg)
          "Long text should be truncated with [truncated] marker")
      (is (<= (count msg) (+ 2200 100))
          "Total message length should be bounded (2000 chars of text + overhead)")))

  (testing "run-failure-error returns nil when no step errors and no terminal-outcome"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error nil}]}
          final-run {}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (nil? msg)
          "Documents current behaviour: :judge/no-match path produces no terminal-outcome, so run-failure-error returns nil")))

  (testing "run-failure-error prefers step errors over terminal-outcome"
    (let [exec-result {:status :failed :steps-executed [{:step-id "a" :error "step blew up"}]}
          final-run {:terminal-outcome {:outcome :failed
                                        :reason :iteration-limit-reached
                                        :step-id "a"
                                        :iteration-count 3
                                        :max-iterations 3}}
          msg (#'cwf-mutations/run-failure-error exec-result final-run)]
      (is (= "step blew up" msg)))))
