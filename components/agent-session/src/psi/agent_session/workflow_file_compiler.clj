(ns psi.agent-session.workflow-file-compiler
  "Compile parsed workflow file data into canonical workflow definitions.

   Accepts the output of `workflow-file-parser/parse-workflow-file` and produces
   canonical `workflow-definition` maps suitable for registration in the
   deterministic workflow runtime.

   Single-step files (no `:steps` in config) compile to 1-step definitions.
   Multi-step files (`:steps` present) compile to N-step definitions where
   each step references another workflow by name."
  (:require
   [clojure.string :as str]))

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
     ;; Carry source metadata for execution bridge
     :workflow-file-meta (cond-> {:system-prompt body}
                           (:tools config) (assoc :tools (:tools config))
                           (:skills config) (assoc :skills (:skills config))
                           (:thinking-level config) (assoc :thinking-level (:thinking-level config))
                           (:model config) (assoc :model (:model config)))}))

;;; Multi-step compilation

(defn- workflow-name->step-id-map
  "Build a map from workflow names to compiled step-ids for goto resolution."
  [steps step-order]
  (into {}
        (map-indexed (fn [idx step]
                       [(:workflow step) (nth step-order idx)]))
        steps))

(defn- resolve-routing-table
  "Resolve :goto workflow names in a routing table to compiled step-ids.
   Keywords (:next, :previous, :done) pass through without resolution."
  [on-table name->step-id]
  (when on-table
    (into {}
          (map (fn [[signal directive]]
                 [signal
                  (if (and (string? (:goto directive))
                           (contains? name->step-id (:goto directive)))
                    (assoc directive :goto (get name->step-id (:goto directive)))
                    directive)]))
          on-table)))

(defn compile-multi-step
  "Compile a parsed workflow file with `:steps` into an N-step canonical definition."
  [{:keys [name description config body]}]
  (let [steps (:steps config)
        step-order (mapv multi-step-id (range) steps)
        name->step-id (workflow-name->step-id-map steps step-order)
        step-map (into {}
                       (map-indexed
                        (fn [idx step]
                          (let [step-id (nth step-order idx)
                                previous-step-id (when (pos? idx)
                                                   (nth step-order (dec idx)))
                                workflow-name (:workflow step)
                                resolved-on (resolve-routing-table (:on step) name->step-id)]
                            [step-id
                             (cond-> {:label (or workflow-name step-id)
                                      :description (str "Delegate to workflow `" workflow-name "`.")
                                      :executor {:type :agent
                                                 :profile workflow-name}
                                      :prompt-template (:prompt step)
                                      :input-bindings
                                      (cond-> {:original {:source :workflow-input
                                                          :path [:original]}}
                                        previous-step-id
                                        (assoc :input {:source :step-output
                                                       :path [previous-step-id :outputs :text]})
                                        (nil? previous-step-id)
                                        (assoc :input {:source :workflow-input
                                                       :path [:input]}))
                                      :result-schema default-result-schema
                                      :retry-policy default-retry-policy}
                               (:judge step)
                               (assoc :judge (:judge step))
                               resolved-on
                               (assoc :on resolved-on))])))
                       steps)]
    {:definition-id name
     :name name
     :summary description
     :description description
     :step-order step-order
     :steps step-map
     ;; Carry source metadata
     :workflow-file-meta (cond-> {:framing-prompt body}
                           (:tools config) (assoc :tools (:tools config))
                           (:skills config) (assoc :skills (:skills config))
                           (:thinking-level config) (assoc :thinking-level (:thinking-level config))
                           (:model config) (assoc :model (:model config)))}))

;;; Top-level compilation

(defn compile-workflow-file
  "Compile a parsed workflow file into a canonical workflow definition.

   Dispatches to single-step or multi-step compilation based on presence
   of `:steps` in the config block.

   Returns {:definition <map>} on success, {:error <string>} on failure."
  [{:keys [name config error] :as parsed}]
  (cond
    error
    {:error error}

    (nil? name)
    {:error "Cannot compile: missing workflow name"}

    (seq (:steps config))
    {:definition (compile-multi-step parsed)}

    :else
    {:definition (compile-single-step parsed)}))

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
   Returns {:valid? true} or {:valid? false :errors [...]}."
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
                                ;; :on without :judge
                                (when (and has-on? (not has-judge?))
                                  [{:definition (:name definition)
                                    :step step-id
                                    :error :on-without-judge}])
                                ;; :goto string targets must be known step-ids
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
