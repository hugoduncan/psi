(ns psi.agent-session.workflow-file-compiler
  "Compile parsed workflow file data into canonical workflow definitions.

   Accepts the output of `workflow-file-parser/parse-workflow-file` and produces
   canonical `workflow-definition` maps suitable for registration in the
   deterministic workflow runtime.

   Single-step files (no `:steps` in config) compile to 1-step definitions.
   Multi-step files (`:steps` present) compile to N-step definitions where
   each step references another workflow by name."
  (:require
   [clojure.string :as str]
   [psi.agent-session.workflow-file-authoring-resolution :as authoring-resolution]))

;;; Shared constants

(def ^:private default-result-schema
  [:map
   [:outcome [:= :ok]]
   [:outputs [:map [:text :string]]]])

(def ^:private default-retry-policy
  {:max-attempts 1
   :retry-on #{:execution-failed :validation-failed}})

;;; Step ID generation

(defn- kebab-fragment
  [x]
  (-> (str x)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      not-empty
      (or "step")))

(defn- multi-step-id
  "Generate a canonical step-id for multi-step workflows."
  [idx {:keys [workflow]}]
  (str "step-" (inc idx) "-" (kebab-fragment (or workflow "agent"))))

(defn- step-label
  [step]
  (or (:name step)
      (:workflow step)))

(defn- duplicate-step-names
  [steps]
  (->> steps
       (keep :name)
       frequencies
       (keep (fn [[step-name n]]
               (when (> n 1)
                 step-name)))
       vec))

(defn- missing-step-names
  [steps]
  (->> steps
       (keep-indexed (fn [idx step]
                       (when-not (string? (:name step))
                         (inc idx))))
       vec))

;;; Single-step compilation

(defn compile-single-step
  "Compile a parsed workflow file (no `:steps`) into a 1-step canonical definition."
  [{:keys [name description config body]}]
  (let [step-id "step-1"
        executor (cond-> {:type :agent
                          :profile name}
                   (:model config) (assoc :model (:model config))
                   (:thinking-level config) (assoc :thinking-level (:thinking-level config)))
        step-def (cond-> {:label name
                          :description (or description (str "Run workflow `" name "`."))
                          :executor executor
                          :prompt-template "$INPUT"
                          :input-bindings {:input {:source :workflow-input
                                                   :path [:input]}
                                           :original {:source :workflow-input
                                                      :path [:original]}}
                          :result-schema default-result-schema
                          :retry-policy default-retry-policy}
                   (:tools config)
                   (assoc :capability-policy
                          {:tools (set (:tools config))}))]
    {:definition-id name
     :name name
     :summary description
     :description description
     :step-order [step-id]
     :steps {step-id step-def}
     :workflow-file-meta (cond-> {:system-prompt body}
                           (:tools config) (assoc :tools (:tools config))
                           (:skills config) (assoc :skills (:skills config))
                           (:thinking-level config) (assoc :thinking-level (:thinking-level config))
                           (:model config) (assoc :model (:model config)))}))

;;; Multi-step compilation

(defn- compile-multi-step-entry
  [workflow-name step-order routing-target->step-id step-source-ref-map idx step]
  (let [step-id (nth step-order idx)
        previous-step-id (when (pos? idx)
                           (nth step-order (dec idx)))
        delegated-workflow-name (:workflow step)
        step-label (step-label step)
        resolved-on (authoring-resolution/resolve-routing-table (:on step) routing-target->step-id)
        {input-bindings :ok binding-error :error}
        (authoring-resolution/compile-step-input-bindings step previous-step-id step-source-ref-map idx)
        {session-overrides :ok override-error :error}
        (authoring-resolution/compile-step-session-overrides step)
        {session-preload :ok preload-error :error}
        (authoring-resolution/compile-step-session-preload step step-source-ref-map idx)]
    (when binding-error
      (throw (ex-info (str "Workflow `" workflow-name "` step `" step-label "`: " binding-error)
                      {:workflow workflow-name
                       :step step-label
                       :error binding-error})))
    (when override-error
      (throw (ex-info (str "Workflow `" workflow-name "` step `" step-label "`: " override-error)
                      {:workflow workflow-name
                       :step step-label
                       :error override-error})))
    (when preload-error
      (throw (ex-info (str "Workflow `" workflow-name "` step `" step-label "`: " preload-error)
                      {:workflow workflow-name
                       :step step-label
                       :error preload-error})))
    [step-id
     (cond-> {:label (or step-label delegated-workflow-name step-id)
              :description (str "Delegate to workflow `" delegated-workflow-name "`.")
              :executor {:type :agent
                         :profile delegated-workflow-name}
              :prompt-template (:prompt step)
              :input-bindings input-bindings
              :result-schema default-result-schema
              :retry-policy default-retry-policy}
       (:judge step)
       (assoc :judge (:judge step))

       resolved-on
       (assoc :on resolved-on)

       (seq session-overrides)
       (assoc :session-overrides session-overrides)

       (seq session-preload)
       (assoc :session-preload session-preload))]))

(defn compile-multi-step
  "Compile a parsed workflow file with `:steps` into an N-step canonical definition."
  [{:keys [name description config body]}]
  (let [steps (:steps config)
        missing-names (missing-step-names steps)
        duplicate-names (duplicate-step-names steps)]
    (when (seq missing-names)
      (throw (ex-info (str "Multi-step workflow steps must have unique string `:name`; missing or invalid at positions "
                           (pr-str missing-names))
                      {:missing-step-names missing-names})))
    (when (seq duplicate-names)
      (throw (ex-info (str "Duplicate workflow step names: " (pr-str duplicate-names))
                      {:duplicate-step-names duplicate-names})))
    (let [step-order (mapv multi-step-id (range) steps)
          routing-target->step-id (authoring-resolution/routing-target->step-id-map steps step-order)
          step-source-ref-map (authoring-resolution/step-source-reference-map steps step-order)
          step-map (into {}
                         (map-indexed (partial compile-multi-step-entry
                                               name
                                               step-order
                                               routing-target->step-id
                                               step-source-ref-map)
                                      steps))]
      {:definition-id name
       :name name
       :summary description
       :description description
       :step-order step-order
       :steps step-map
       :workflow-file-meta (cond-> {:framing-prompt body}
                             (:tools config) (assoc :tools (:tools config))
                             (:skills config) (assoc :skills (:skills config))
                             (:thinking-level config) (assoc :thinking-level (:thinking-level config))
                             (:model config) (assoc :model (:model config)))})))

;;; Top-level compilation

(defn compile-workflow-file
  "Compile a parsed workflow file into a canonical workflow definition.

   Dispatches to single-step or multi-step compilation based on presence
   of `:steps` in the config block.

   Returns {:definition <map>} on success, {:error <string>} on failure."
  [{:keys [name config error] :as parsed}]
  (try
    (cond
      error
      {:error error}

      (nil? name)
      {:error "Cannot compile: missing workflow name"}

      (seq (:steps config))
      {:definition (compile-multi-step parsed)}

      :else
      {:definition (compile-single-step parsed)})
    (catch clojure.lang.ExceptionInfo e
      {:error (.getMessage e)})))

(defn compile-workflow-files
  "Compile a seq of parsed workflow files into canonical definitions.
   Returns {:definitions [<def> ...] :errors [{:name ... :error ...} ...]}."
  [parsed-files]
  (reduce (fn [acc parsed]
            (let [{:keys [definition error]} (compile-workflow-file parsed)]
              (if error
                (update acc :errors conj {:name (:name parsed) :error error})
                (update acc :definitions conj definition))))
          {:definitions [] :errors []}
          parsed-files))

(defn validate-step-references
  "Validate that all multi-step workflow definitions reference known workflow names.
   Returns {:valid? true} or {:valid? false :errors [{:definition ... :step ... :missing ...} ...]}."
  [definitions]
  (let [known-names (set (map :name definitions))
        errors (into []
                     (mapcat
                      (fn [definition]
                        (when (> (count (:step-order definition)) 1)
                          (keep (fn [[step-id step-def]]
                                  (let [profile (get-in step-def [:executor :profile])]
                                    (when (and profile (not (contains? known-names profile)))
                                      {:definition (:name definition)
                                       :step step-id
                                       :missing profile})))
                                (:steps definition)))))
                     definitions)]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn validate-no-name-collisions
  "Check that no two definitions share the same name.
   Returns {:valid? true} or {:valid? false :duplicates [<name> ...]}."
  [definitions]
  (let [freqs (frequencies (map :name definitions))
        dups (into [] (comp (filter #(> (val %) 1)) (map key)) freqs)]
    (if (seq dups)
      {:valid? false :duplicates dups}
      {:valid? true})))

(defn validate-judge-routing
  "Validate judge/routing constraints across definitions.
   Checks:
   - :on without :judge is an error
   - :goto string targets reference known step-ids within the definition
   Returns {:valid? true} or {:valid? false :errors [...]}"
  [definitions]
  (let [errors (into []
                     (mapcat
                      (fn [definition]
                        (let [step-ids (set (:step-order definition))]
                          (mapcat
                           (fn [[step-id step-def]]
                             (let [has-on? (some? (:on step-def))
                                   has-judge? (some? (:judge step-def))
                                   on-table (:on step-def)]
                               (concat
                                (when (and has-on? (not has-judge?))
                                  [{:definition (:name definition)
                                    :step step-id
                                    :error :on-without-judge}])
                                (when on-table
                                  (keep (fn [[signal directive]]
                                          (when (and (string? (:goto directive))
                                                     (not (contains? step-ids (:goto directive))))
                                            {:definition (:name definition)
                                             :step step-id
                                             :signal signal
                                             :error :unknown-goto-target
                                             :target (:goto directive)}))
                                        on-table)))))
                           (:steps definition)))))
                     definitions)]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))
