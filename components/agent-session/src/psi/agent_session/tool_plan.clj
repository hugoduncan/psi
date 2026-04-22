(ns psi.agent-session.tool-plan
  "Data-driven tool plan execution and runtime tool invocation boundary.

   Provides the concrete runtime tool executor (`execute-tool-runtime-in!`)
   and a sequential tool-plan runner (`run-tool-plan-in!`) that both delegate
   through a ctx-provided executor boundary for consistent tool dispatch."
  (:require
   [clojure.walk :as walk]
   [psi.agent-core.core :as agent]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.tools :as tools]
   [psi.query.core :as query]))

;;; Runtime tool invocation boundary

(defn- find-tool-def
  "Resolve a tool definition by name from agent-ctx tools or extension registry.

   agent-ctx may be nil (e.g. child/agent sessions that have no dedicated
   agent-core context). Falls back to extension registry in that case."
  [ctx session-id tool-name]
  (let [agent-ctx   (ss/agent-ctx-in ctx session-id)
        from-agent  (when agent-ctx
                      (some #(when (= tool-name (:name %)) %)
                            (:tools (agent/get-data-in agent-ctx))))
        from-ext    (when-let [reg (:extension-registry ctx)]
                      (some #(when (= tool-name (:name %)) %)
                            (ext/all-tools-in reg)))]
    ;; Agent runtime tool defs intentionally project provider-safe metadata and
    ;; may omit executable fns. Prefer an executable agent tool when present,
    ;; otherwise fall back to the extension registry's canonical tool def.
    (or (when (:execute from-agent) from-agent)
        from-ext
        from-agent)))

(defn- execute-psi-tool-in!
  "Execute a session-bound psi-tool instance.

   psi-tool cannot use the generic built-in dispatcher because it needs
   access to the live query graph scoped to the current session-id. Build a
   fresh query context at call time so the resolver/mutation graph matches the
   current runtime state, and bind resolver session targeting explicitly."
  [ctx session-id args opts]
  (let [register-resolvers! (:register-resolvers-fn ctx)
        register-mutations! (:register-mutations-fn ctx)
        qctx                (query/create-query-context)
        _                   (register-resolvers! qctx false)
        _                   (register-mutations! qctx (:all-mutations ctx) true)
        tool                (tools/make-psi-tool
                             (fn
                               ([eql-query]
                                (query/query-in qctx
                                                {:psi/agent-session-ctx        ctx
                                                 :psi.agent-session/session-id session-id}
                                                eql-query))
                               ([eql-query entity]
                                (query/query-in qctx
                                                (merge {:psi/agent-session-ctx ctx}
                                                       entity)
                                                eql-query)))
                             {:ctx          ctx
                              :session-id   session-id
                              :cwd          (:cwd opts)
                              :overrides    (:overrides opts)
                              :tool-call-id (:tool-call-id opts)})]
    ((:execute tool) args)))

(defn default-execute-runtime-tool-in!
  "Default runtime tool invocation implementation.

   Prefers a registered :execute fn in agent tools (or extension registry for
   child sessions without an agent-ctx) and falls back to built-in tool
   dispatch. Supports both 1-arity and 2-arity execute fn contracts.

   psi-tool is special: its schema is session-persistable, but its
   executable implementation must be synthesized per session because it closes
   over the live query graph and current session-id.

   This is the default concrete runtime invocation behind the higher-level
   ctx-provided runtime tool executor boundary."
  [ctx session-id tool-name args opts]
  (let [tool-def   (find-tool-def ctx session-id tool-name)
        execute-fn (:execute tool-def)]
    (cond
      (= tool-name "psi-tool")
      (execute-psi-tool-in! ctx session-id args opts)

      (fn? execute-fn)
      (try
        (execute-fn args opts)
        (catch clojure.lang.ArityException _
          (execute-fn args)))

      :else
      (tools/execute-tool tool-name args opts))))

(defn execute-tool-runtime-in!
  "Execute a tool through the ctx-provided runtime tool executor boundary.

   Falls back to `default-execute-runtime-tool-in!` when no custom runtime
   executor is installed on the session context."
  [ctx session-id tool-name args opts]
  (let [runtime-execute-fn (or (:runtime-tool-executor-fn ctx)
                               default-execute-runtime-tool-in!)]
    (try
      (runtime-execute-fn ctx session-id tool-name args opts)
      (catch clojure.lang.ArityException _
        (runtime-execute-fn ctx tool-name args opts)))))

;;; Tool plan helpers

(defn- step-field
  [step k]
  (or (get step k)
      (get step (name k))))

(defn- normalize-step-id
  [step idx]
  (or (step-field step :id)
      (keyword (str "step-" (inc idx)))))

(defn- normalize-step-tool-name
  [step]
  (let [raw (or (step-field step :tool)
                (step-field step :tool-name)
                (step-field step :name))]
    (cond
      (string? raw) raw
      (keyword? raw) (name raw)
      (symbol? raw) (name raw)
      :else nil)))

(defn- continue-on-error?
  [step]
  (boolean (step-field step :continue-on-error?)))

(defn- from-ref-form?
  [value]
  (and (vector? value)
       (>= (count value) 2)
       (= :from (first value))))

(defn- normalize-ref-path
  [path-parts]
  (cond
    (empty? path-parts)
    []

    (and (= 1 (count path-parts))
         (vector? (first path-parts)))
    (first path-parts)

    :else
    (vec path-parts)))

(defn- resolve-from-ref
  [results-by-id ref-form]
  (let [[_ step-id & raw-path] ref-form
        step-result           (get results-by-id step-id ::missing)]
    (when (= ::missing step-result)
      (throw (ex-info (str "Unknown step reference: " step-id)
                      {:reference          ref-form
                       :available-step-ids (vec (keys results-by-id))})))
    (if (seq raw-path)
      (get-in step-result (normalize-ref-path raw-path))
      step-result)))

(defn- resolve-step-args
  [args-template results-by-id]
  (let [resolved (walk/postwalk (fn [value]
                                  (if (from-ref-form? value)
                                    (resolve-from-ref results-by-id value)
                                    value))
                                (or args-template {}))]
    (when-not (map? resolved)
      (throw (ex-info "Step :args must resolve to a map"
                      {:args-template args-template
                       :resolved      resolved})))
    (walk/stringify-keys resolved)))

;;; Tool plan execution

(defn run-tool-plan-step-in!
  "Execute one tool-plan step through the same runtime tool executor boundary
   used by dispatch-owned tool execution.

   This keeps tool-plan execution aligned with `execute-tool-runtime-in!` so
   custom runtime tool executors apply consistently across both interactive
   tool-use and data-driven tool-plan paths."
  [ctx session-id step-id tool-name args]
  (let [reg          (:extension-registry ctx)
        tool-call-id (str "plan-" step-id "-" (java.util.UUID/randomUUID))
        blocked?     (ext/dispatch-tool-call-in reg tool-name tool-call-id args)]
    (if (:block blocked?)
      {:content  (or (:reason blocked?)
                     "Tool execution was blocked by an extension")
       :is-error true
       :details  {:blocked true}}
      (let [opts     {:cwd          (ss/session-worktree-path-in ctx session-id)
                      :overrides    (:tool-output-overrides (ss/get-session-data-in ctx session-id))
                      :tool-call-id tool-call-id}
            result   (try
                       (execute-tool-runtime-in! ctx session-id tool-name args opts)
                       (catch Exception e
                         {:content  (str "Error: " (ex-message e))
                          :is-error true}))
            modified (ext/dispatch-tool-result-in
                      reg tool-name tool-call-id args result (:is-error result))]
        (cond-> result
          (contains? modified :content)  (assoc :content (:content modified))
          (contains? modified :details)  (assoc :details (:details modified))
          (contains? modified :is-error) (assoc :is-error (:is-error modified)))))))

(defn run-tool-plan-in!
  "Execute a data-driven tool plan sequentially.

   Plan map keys:
   - :steps          vector of step maps
   - :stop-on-error? halt at first failing step (default true)

   Step map keys:
   - :id                 optional step id (defaults to :step-N)
   - :tool | :tool-name  tool identifier (string/keyword/symbol)
   - :args               tool arg map (values may include [:from <step-id> <path...>])
   - :continue-on-error? optional per-step override to continue despite error

   Returns summary map with :results and :result-by-id for downstream use."
  [ctx session-id {:keys [steps stop-on-error?]
                   :or   {steps [] stop-on-error? true}}]
  (if-not (vector? steps)
    {:succeeded?      false
     :step-count      0
     :completed-count 0
     :failed-step-id  nil
     :results         []
     :result-by-id    {}
     :error           "Tool plan :steps must be a vector"}
    (loop [idx       0
           remaining steps
           results   []
           by-id     {}]
      (if (empty? remaining)
        {:succeeded?      true
         :step-count      (count steps)
         :completed-count (count results)
         :failed-step-id  nil
         :results         results
         :result-by-id    by-id
         :error           nil}
        (let [step    (first remaining)
              step-id (normalize-step-id step idx)
              outcome (try
                        (when-not (map? step)
                          (throw (ex-info "Each step must be a map"
                                          {:step step :step-id step-id})))
                        (when (contains? by-id step-id)
                          (throw (ex-info (str "Duplicate step id: " step-id)
                                          {:step-id step-id})))
                        (let [tool-name      (normalize-step-tool-name step)
                              _              (when-not (seq tool-name)
                                               (throw (ex-info "Each step requires :tool"
                                                               {:step step :step-id step-id})))
                              args-template  (or (step-field step :args) {})
                              resolved-args  (resolve-step-args args-template by-id)
                              tool-result    (run-tool-plan-step-in! ctx session-id step-id tool-name resolved-args)
                              step-result    {:id        step-id
                                              :tool-name tool-name
                                              :args      resolved-args
                                              :result    tool-result
                                              :is-error  (boolean (:is-error tool-result))}
                              results'       (conj results step-result)
                              by-id'         (assoc by-id step-id tool-result)
                              step-failed?   (boolean (:is-error tool-result))
                              stop-now?      (and step-failed?
                                                  stop-on-error?
                                                  (not (continue-on-error? step)))]
                          (if stop-now?
                            {:status  :stop
                             :step-id step-id
                             :results results'
                             :by-id   by-id'
                             :error   (str "Tool step failed: " step-id)}
                            {:status  :continue
                             :results results'
                             :by-id   by-id'}))
                        (catch Exception e
                          {:status  :error
                           :step-id step-id
                           :results results
                           :by-id   by-id
                           :error   (ex-message e)}))]
          (case (:status outcome)
            :continue
            (recur (inc idx) (rest remaining) (:results outcome) (:by-id outcome))

            :stop
            {:succeeded?      false
             :step-count      (count steps)
             :completed-count (count (:results outcome))
             :failed-step-id  (:step-id outcome)
             :results         (:results outcome)
             :result-by-id    (:by-id outcome)
             :error           (:error outcome)}

            :error
            {:succeeded?      false
             :step-count      (count steps)
             :completed-count (count (:results outcome))
             :failed-step-id  (:step-id outcome)
             :results         (:results outcome)
             :result-by-id    (:by-id outcome)
             :error           (:error outcome)}))))))
