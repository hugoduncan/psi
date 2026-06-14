(ns psi.workflow-runtime.target-ir-compiler
  "Compile target-authored workflow definitions into normalized workflow IR.

   This compiler owns the forward compilation path from the converged authored
   workflow grammar documented in `doc/workflow-grammar.md` into canonical
   runtime workflow IR. Unlike the current-grammar compatibility compiler, this
   compiler emits no migration `:compat` metadata."
  (:require
   [psi.workflow-registry.definition :as workflow-definition]
   [psi.session-profile.names :as profile-names]
   [psi.workflow-runtime.ir :as workflow-ir]))

(def ^:private default-invoke-outputs
  {:data {:source :invoke/data}
   :summary {:source :invoke/summary}
   :result {:source :invoke/result}})

(def ^:private default-session-outputs
  {:final-llm-reply {:source :session/final-llm-reply}
   :transcript {:source :session/transcript}
   :result {:source :session/result}})

(def ^:private default-invoke-yields
  {:type :data
   :data :data})

(def ^:private default-session-yields
  {:type :text
   :text :final-llm-reply})

(def ^:private default-delegate-outputs
  {:handoff {:source :delegate/handoff}})

(def ^:private default-delegate-yields
  {:type :delegated})

;; Compatibility alias during workflow-registration extraction follow-on.
;; New lower ownership lives in `psi.workflow-registry.definition`; keep this
;; public name stable for existing higher workflow compiler/runtime callers.
(def target-authored-workflow-definition?
  workflow-definition/target-authored-workflow-definition?)

(defn- compile-source-spec
  [{:keys [from path projection] :as source-spec}]
  (when-not (map? source-spec)
    (throw (ex-info "Target workflow source-spec must be a map"
                    {:source-spec source-spec})))
  (when-not (contains? source-spec :from)
    (throw (ex-info "Target workflow source-spec requires `:from`"
                    {:source-spec source-spec})))
  (when (and (contains? source-spec :path)
             (contains? source-spec :projection))
    (throw (ex-info "Target workflow source-spec cannot contain both `:path` and `:projection`"
                    {:source-spec source-spec})))
  (cond-> {:from from}
    (contains? source-spec :path) (assoc :path path)
    (contains? source-spec :projection) (assoc :projection projection)))

(defn- compile-delegate-target
  [target]
  (cond
    (string? target)
    target

    (and (map? target) (contains? target :from))
    (compile-source-spec target)

    :else
    (throw (ex-info "Delegate target must be a workflow name string or workflow source-spec"
                    {:target target}))))

(defn- compile-contribution
  [{:keys [type] :as contribution}]
  (case type
    :source
    (let [{:keys [from path projection]} (compile-source-spec contribution)]
      (cond-> {:type :source
               :from from}
        (some? path) (assoc :path path)
        (some? projection) (assoc :projection projection)))

    :template
    (let [{:keys [text vars]} contribution]
      {:type :template
       :text text
       :vars (into {}
                   (map (fn [[var-name source-spec]]
                          [var-name (compile-source-spec source-spec)]))
                   vars)})

    (throw (ex-info "Unsupported target workflow contribution type"
                    {:contribution contribution
                     :supported-types #{:source :template}}))))

(defn- compile-invoke-args
  [args]
  (into {}
        (map (fn [[arg-k arg-v]]
               [arg-k (if (and (map? arg-v) (contains? arg-v :from))
                        (compile-source-spec arg-v)
                        arg-v)]))
        args))

(defn- compile-judge
  [{:keys [type] :as judge}]
  (when judge
    (case type
      :llm
      (let [session-config (select-keys judge [:model :tools :skills :system-prompt :thinking-level :prompt-component-selection :response-mode :temperature :logprobs :top-logprobs])]
        (cond-> {:type :llm
                 :session (assoc session-config
                                 :contributions (mapv compile-contribution (:contributions judge)))}
          (contains? judge :projection)
          (assoc :projection (:projection judge))
          (contains? judge :outputs)
          (assoc :outputs (:outputs judge))))

      :invoke
      {:type :invoke
       :invoke {:operation (:operation judge)
                :args (compile-invoke-args (:args judge))}}

      (throw (ex-info "Unsupported target workflow judge type"
                      {:judge judge
                       :supported-types #{:llm :invoke}})))))

(defn- compile-routing-table
  [routing-table]
  (when routing-table
    (into {}
          (map (fn [[outcome transition]]
                 [outcome (cond-> {:goto (:goto transition)}
                            (contains? transition :max-iterations)
                            (assoc :max-iterations (:max-iterations transition)))]))
          routing-table)))

(defn- compile-session-profile-name
  [profile-name]
  (when-not (profile-names/valid-profile-name? profile-name)
    (throw (ex-info "Session profile names must be selectable unqualified non-reserved keywords matching /session-profile token grammar"
                    {:session-profile profile-name})))
  profile-name)

(defn- compile-session-config
  [step allowed-keys]
  (cond-> (select-keys step allowed-keys)
    (contains? step :session-profile)
    (update :session-profile compile-session-profile-name)))

(defn- step-default-outputs
  [step-type]
  (case step-type
    :invoke default-invoke-outputs
    :session default-session-outputs
    :delegate default-delegate-outputs
    nil))

(defn- step-default-yields
  [step-type]
  (case step-type
    :invoke default-invoke-yields
    :session default-session-yields
    :delegate default-delegate-yields
    nil))

(defn- compile-common-step-fields
  [{:keys [name type outputs yields judge on] :as step}]
  (cond-> {:name name
           :type type}
    (some? (or outputs (step-default-outputs type)))
    (assoc :outputs (or outputs (step-default-outputs type)))

    true
    (assoc :yields (or yields (step-default-yields type)))

    (some? judge)
    (assoc :judge (compile-judge judge))

    (some? on)
    (assoc :on (compile-routing-table on))

    (contains? step :max-iterations)
    (assoc :max-iterations (:max-iterations step))))

(defn- compile-prompt-group
  "Compile an authored named prompt-group into a canonical IR prompt-group
   (task 226). Each group carries its `:name` and compiled `:contributions`;
   per-prompt session config is shared at the step level, not per group."
  [{:keys [name contributions]}]
  {:name name
   :contributions (mapv compile-contribution contributions)})

(defn- compile-step
  [{:keys [type] :as step}]
  (case type
    :invoke
    (assoc (compile-common-step-fields step)
           :invoke {:operation (:operation step)
                    :args (compile-invoke-args (:args step))})

    :session
    (assoc (compile-common-step-fields step)
           :session (let [session-config (compile-session-config step [:model :session-profile :tools :skills :system-prompt :thinking-level :prompt-component-selection :response-mode :temperature :logprobs :top-logprobs])]
                      (if (contains? step :prompts)
                        (assoc session-config :prompts (mapv compile-prompt-group (:prompts step)))
                        (assoc session-config :contributions (mapv compile-contribution (:contributions step))))))

    :delegate
    (assoc (compile-common-step-fields step)
           :delegate (cond-> {:target (compile-delegate-target (:target step))
                              :prompt-string (let [prompt-string (:prompt-string step)]
                                               (if (and (map? prompt-string)
                                                        (= :template (:type prompt-string)))
                                                 (compile-contribution prompt-string)
                                                 prompt-string))}
                       (some (partial contains? step) [:model :session-profile :thinking-level])
                       (assoc :session (compile-session-config step [:model :session-profile :thinking-level]))
                       (contains? step :context)
                       (assoc :context (mapv compile-contribution (:context step)))))

    (throw (ex-info "Unsupported target workflow step type"
                    {:step step
                     :supported-types #{:invoke :session :delegate}}))))

(defn- compile-step-with-context
  "Compile a single step, enriching any compile exception with step name and index."
  [step idx]
  (try
    (compile-step step)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e)
                      (merge (ex-data e)
                             {:step-name  (:name step)
                              :step-index idx}))))
    (catch Exception e
      (throw (ex-info (str "Unexpected error compiling step: " (ex-message e))
                      {:step-name  (:name step)
                       :step-index idx})))))

(defn compile-workflow-definition
  "Compile a target-authored workflow definition into normalized workflow IR.

   Throws `ExceptionInfo` on malformed authored inputs that cannot be
   translated into canonical IR."
  [workflow-definition]
  (when-not (target-authored-workflow-definition? workflow-definition)
    (throw (ex-info "Target workflow definition must be of the form `{:steps [...]}`"
                    {:workflow-definition workflow-definition})))
  (cond-> {:version :workflow-ir/v1
           :steps (vec (map-indexed (fn [idx step]
                                      (compile-step-with-context step idx))
                                    (:steps workflow-definition)))}
    (contains? workflow-definition :terminal-contract)
    (assoc :terminal-contract (:terminal-contract workflow-definition))))

(defn compile-and-validate-workflow-definition
  "Compile target-authored workflow definition and validate the resulting IR.

   Returns:
   {:valid? boolean
    :ir workflow-ir?
    :structural-errors explain-data?
    :semantic-errors [error*]
    :compile-error {:message string :data map}?}

   `:compile-error` is nil when compilation succeeds; a map with `:message` and
   `:data` when an ExceptionInfo is thrown during compilation."
  [workflow-definition]
  (try
    (let [ir (compile-workflow-definition workflow-definition)
          validation (workflow-ir/validate-workflow-ir ir)]
      (assoc validation :ir ir :compile-error nil))
    (catch clojure.lang.ExceptionInfo e
      {:valid? false
       :ir nil
       :structural-errors nil
       :semantic-errors []
       :compile-error {:message (ex-message e)
                       :data    (ex-data e)}})))
