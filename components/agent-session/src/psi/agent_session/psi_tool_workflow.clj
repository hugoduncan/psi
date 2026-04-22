(ns psi.agent-session.psi-tool-workflow
  "Workflow action handler for psi-tool: parse, summarise, and execute workflow ops."
  (:require
   [clojure.edn :as edn]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

;; ── Helpers (local copies of private psi_tool utilities) ────────────────────

(defn- parse-edn-string
  [s]
  (binding [*read-eval* false]
    (edn/read-string s)))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   {:message (or (ex-message e) (str e))
    :class   (.getName (class e))
    :phase   (or (:phase (ex-data e)) default-phase :execute)
    :data    (ex-data e)}))

;; ── Parsing ──────────────────────────────────────────────────────────────────

(defn parse-workflow-definition-string
  [definition]
  (let [parsed (parse-edn-string definition)]
    (when-not (map? parsed)
      (throw (ex-info "psi-tool workflow definition must be an EDN map"
                      {:phase :validate :action "workflow" :op "create-run"})))
    parsed))

(defn parse-workflow-input-string
  [workflow-input]
  (when (some? workflow-input)
    (let [parsed (parse-edn-string workflow-input)]
      (when-not (map? parsed)
        (throw (ex-info "psi-tool workflow-input must be an EDN map"
                        {:phase :validate :action "workflow" :op "create-run"})))
      parsed)))

;; ── Summary projection ───────────────────────────────────────────────────────

(defn workflow-run-summary
  [workflow-run]
  {:run-id               (:run-id workflow-run)
   :status               (:status workflow-run)
   :source-definition-id (:source-definition-id workflow-run)
   :workflow-input       (:workflow-input workflow-run)
   :current-step-id      (:current-step-id workflow-run)
   :created-at           (:created-at workflow-run)
   :updated-at           (:updated-at workflow-run)
   :finished-at          (:finished-at workflow-run)
   :blocked              (:blocked workflow-run)
   :terminal-outcome     (:terminal-outcome workflow-run)})

(defn- find-required-fn
  [ns-name var-name]
  (or (some-> (find-var (symbol ns-name var-name)) var-get)
      (throw (ex-info "Required workflow runtime function is not loaded"
                      {:phase :workflow
                       :ns ns-name
                       :var var-name}))))

(defn- require-session-id!
  [session-id op]
  (or session-id
      (throw (ex-info "psi-tool workflow action requires invoking or explicit `session-id`"
                      {:phase :validate :action "workflow" :op op}))))

(defn- ensure-workflow-callbacks
  "Patch older live ctx maps on demand so workflow execution controls can run
   without requiring a full runtime/context rebuild."
  [ctx]
  (cond-> ctx
    (nil? (:create-workflow-child-session-fn ctx))
    (assoc :create-workflow-child-session-fn
           (find-required-fn "psi.agent-session.context" "create-workflow-child-session!"))

    (nil? (:execute-workflow-run-fn ctx))
    (assoc :execute-workflow-run-fn
           (find-required-fn "psi.agent-session.workflow-execution" "execute-run!"))

    (nil? (:resume-and-execute-workflow-run-fn ctx))
    (assoc :resume-and-execute-workflow-run-fn
           (find-required-fn "psi.agent-session.workflow-execution" "resume-and-execute-run!"))))

;; ── Workflow op handler ──────────────────────────────────────────────────────

(defn execute-psi-tool-workflow-report
  [{:keys [ctx session-id]} {:keys [op definition-id definition workflow-input run-id reason]}]
  (let [started-at (System/nanoTime)]
    (try
      (when-not ctx
        (throw (ex-info "psi-tool workflow action requires live runtime ctx"
                        {:phase :validate :action "workflow" :op op})))
      (let [ctx (ensure-workflow-callbacks ctx)
            session-id (require-session-id! session-id op)
            result
            (case op
              "list-definitions"
              (let [definitions (->> (get-in @(:state* ctx) [:workflows :definitions])
                                     vals
                                     (sort-by :definition-id)
                                     vec)]
                {:psi-tool/action         :workflow
                 :psi-tool/workflow-op    :list-definitions
                 :psi-tool/overall-status :ok
                 :psi-tool/workflow       {:definition-count (count definitions)
                                           :definition-ids   (mapv :definition-id definitions)
                                           :definitions      (mapv (fn [d]
                                                                     {:definition-id (:definition-id d)
                                                                      :name          (:name d)
                                                                      :summary       (:summary d)
                                                                      :step-order    (:step-order d)})
                                                                   definitions)}})

              "create-run"
              (let [create-opts (cond-> {}
                                  definition-id (assoc :definition-id definition-id)
                                  definition    (assoc :definition (parse-workflow-definition-string definition))
                                  true          (assoc :workflow-input (or (parse-workflow-input-string workflow-input) {})))
                    [new-state created-run-id workflow-run]
                    (workflow-runtime/create-run @(:state* ctx) create-opts)]
                ((:apply-root-state-update-fn ctx) ctx (constantly new-state))
                {:psi-tool/action         :workflow
                 :psi-tool/workflow-op    :create-run
                 :psi-tool/overall-status :ok
                 :psi-tool/workflow       {:run-id created-run-id
                                           :run    (workflow-run-summary workflow-run)}})

              "execute-run"
              (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                (when-not workflow-run
                  (throw (ex-info "Workflow run not found"
                                  {:phase :validate :action "workflow" :op op :run-id run-id})))
                (when (contains? #{:completed :failed :cancelled} (:status workflow-run))
                  (throw (ex-info "Workflow run is already terminal"
                                  {:phase :validate :action "workflow" :op op :run-id run-id
                                   :status (:status workflow-run)})))
                (let [exec-result ((:execute-workflow-run-fn ctx) ctx session-id run-id)
                      final-run   (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                  {:psi-tool/action         :workflow
                   :psi-tool/workflow-op    :execute-run
                   :psi-tool/overall-status (if (:terminal? exec-result) :ok :blocked)
                   :psi-tool/workflow       {:run-id         run-id
                                             :status         (:status exec-result)
                                             :steps-executed (:steps-executed exec-result)
                                             :terminal?      (:terminal? exec-result)
                                             :blocked?       (:blocked? exec-result)
                                             :run            (workflow-run-summary final-run)}}))

              "read-run"
              (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                (when-not workflow-run
                  (throw (ex-info "Workflow run not found"
                                  {:phase :validate :action "workflow" :op op :run-id run-id})))
                {:psi-tool/action         :workflow
                 :psi-tool/workflow-op    :read-run
                 :psi-tool/overall-status :ok
                 :psi-tool/workflow       {:run-id run-id
                                           :run    (workflow-run-summary workflow-run)}})

              "list-runs"
              (let [runs (workflow-runtime/list-workflow-runs @(:state* ctx))]
                {:psi-tool/action         :workflow
                 :psi-tool/workflow-op    :list-runs
                 :psi-tool/overall-status :ok
                 :psi-tool/workflow       {:run-count (count runs)
                                           :run-ids   (mapv :run-id runs)
                                           :runs      (mapv workflow-run-summary runs)}})

              "resume-run"
              (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                (when-not workflow-run
                  (throw (ex-info "Workflow run not found"
                                  {:phase :validate :action "workflow" :op op :run-id run-id})))
                (when-not (= :blocked (:status workflow-run))
                  (throw (ex-info "Workflow run is not blocked"
                                  {:phase :validate :action "workflow" :op op :run-id run-id
                                   :status (:status workflow-run)})))
                (let [exec-result ((:resume-and-execute-workflow-run-fn ctx) ctx session-id run-id)
                      final-run   (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                  {:psi-tool/action         :workflow
                   :psi-tool/workflow-op    :resume-run
                   :psi-tool/overall-status (if (:terminal? exec-result) :ok :blocked)
                   :psi-tool/workflow       {:run-id         run-id
                                             :status         (:status exec-result)
                                             :steps-executed (:steps-executed exec-result)
                                             :terminal?      (:terminal? exec-result)
                                             :blocked?       (:blocked? exec-result)
                                             :run            (workflow-run-summary final-run)}}))

              "cancel-run"
              (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                (when-not workflow-run
                  (throw (ex-info "Workflow run not found"
                                  {:phase :validate :action "workflow" :op op :run-id run-id})))
                (when (contains? #{:completed :failed :cancelled} (:status workflow-run))
                  (throw (ex-info "Workflow run is already terminal"
                                  {:phase :validate :action "workflow" :op op :run-id run-id
                                   :status (:status workflow-run)})))
                (let [new-state     (workflow-progression/cancel-run @(:state* ctx) run-id
                                                                     (or reason "cancelled by psi-tool"))
                      cancelled-run (workflow-runtime/workflow-run-in new-state run-id)]
                  ((:apply-root-state-update-fn ctx) ctx (constantly new-state))
                  {:psi-tool/action         :workflow
                   :psi-tool/workflow-op    :cancel-run
                   :psi-tool/overall-status :ok
                   :psi-tool/workflow       {:run-id run-id
                                             :run    (workflow-run-summary cancelled-run)}})))]
        (assoc result :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))))
      (catch Exception e
        {:psi-tool/action         :workflow
         :psi-tool/workflow-op    (some-> op keyword)
         :psi-tool/duration-ms    (long (/ (- (System/nanoTime) started-at) 1000000))
         :psi-tool/overall-status :error
         :psi-tool/error          (psi-tool-error-summary :workflow e)}))))
