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

(def ^:private supported-session-keys
  #{:input :reference})

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

(defn- default-binding
  [binding-key previous-step-id]
  (case binding-key
    :input
    (if previous-step-id
      {:source :step-output
       :path [previous-step-id :outputs :text]}
      {:source :workflow-input
       :path [:input]})

    :reference
    {:source :workflow-input
     :path [:original]}))

(defn- workflow-input-binding
  [binding-key]
  {:source :workflow-input
   :path [(case binding-key
            :input :input
            :reference :original)]})

(defn- source->binding
  [binding-key source step-name->step-ref current-step-idx]
  (cond
    (= source :workflow-input)
    {:ok (workflow-input-binding binding-key)}

    (= source :workflow-original)
    {:ok {:source :workflow-input
          :path [:original]}}

    (map? source)
    (let [{:keys [step kind] :as source-map} source
          unknown-keys (seq (remove #{:step :kind} (keys source-map)))]
      (cond
        unknown-keys
        {:error (str "Malformed `:session " (name binding-key)
                     "` source form: unexpected keys "
                     (pr-str (vec unknown-keys)))}

        (not (string? step))
        {:error (str "Malformed `:session " (name binding-key)
                     "` source form: expected `{:step \"...\" :kind :accepted-result}`")}

        (not= kind :accepted-result)
        {:error (str "Malformed `:session " (name binding-key)
                     "` source form: unsupported step source kind `"
                     kind "`")}

        :else
        (if-let [{:keys [step-id idx]} (get step-name->step-ref step)]
          (if (< idx current-step-idx)
            {:ok {:source :step-output
                  :path [step-id :outputs :text]}}
            {:error (str "Forward step reference in `:session " (name binding-key)
                         "`: `" step "` must refer to an earlier step")})
          {:error (str "Unknown step name in `:session " (name binding-key)
                       "`: `" step "`")})))

    :else
    {:error (str "Malformed `:session " (name binding-key)
                 "` source form: unsupported `:from` value "
                 (pr-str source))}))

(defn- compile-session-binding
  [binding-key previous-step-id session step-name->step-ref current-step-idx]
  (let [entry (get session binding-key ::missing)]
    (cond
      (= entry ::missing)
      {:ok (default-binding binding-key previous-step-id)}

      (not (map? entry))
      {:error (str "Malformed `:session " (name binding-key) "`: expected map")}

      (empty? entry)
      {:error (str "Malformed `:session " (name binding-key)
                   "`: expected non-empty map with `:from`")}

      (contains? entry :projection)
      {:error (str "Unsupported `:session " (name binding-key)
                   " :projection` before task 061")}

      (contains? entry :project)
      {:error (str "Unsupported `:session " (name binding-key)
                   " :project` before task 061")}

      (not= #{:from} (set (keys entry)))
      {:error (str "Malformed `:session " (name binding-key)
                   "`: expected only `:from`")}

      :else
      (source->binding binding-key (:from entry) step-name->step-ref current-step-idx))))

(defn- compile-step-input-bindings
  [step previous-step-id step-name->step-ref current-step-idx]
  (let [session (:session step)
        defaults {:input (default-binding :input previous-step-id)
                  :original (default-binding :reference previous-step-id)}]
    (cond
      (nil? session)
      {:ok defaults}

      (not (map? session))
      {:error "Malformed `:session`: expected map"}

      (empty? session)
      {:ok defaults}

      :else
      (let [unsupported-keys (seq (remove supported-session-keys (keys session)))]
        (if unsupported-keys
          {:error (str "Unsupported `:session` keys for task 060: "
                       (pr-str (vec unsupported-keys)))}
          (let [{input-binding :ok input-error :error}
                (compile-session-binding :input previous-step-id session step-name->step-ref current-step-idx)
                {reference-binding :ok reference-error :error}
                (compile-session-binding :reference previous-step-id session step-name->step-ref current-step-idx)]
            (cond
              input-error
              {:error input-error}

              reference-error
              {:error reference-error}

              :else
              {:ok {:input input-binding
                    :original reference-binding}})))))))

(defn- multi-step-reference-map
  ;; Source-selection references are intentionally stricter than routing refs
  ;; in task 060: `:session` step sources resolve only explicit author-facing
  ;; step `:name` values. Legacy compatibility fallback to unambiguous
  ;; delegated `:workflow` names is preserved only for `:goto` routing.
  [steps step-order]
  (into {}
        (keep-indexed (fn [idx step]
                        (when-let [step-name (:name step)]
                          [step-name
                           {:step-id (nth step-order idx)
                            :idx idx}])))
        steps))

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

(defn- workflow-name->step-id-map
  "Build a map for goto resolution.
   Explicit `:name` values are authoritative. For backward compatibility,
   unique delegated workflow names are also accepted when unambiguous."
  [steps step-order]
  (let [workflow-name-freqs (frequencies (keep :workflow steps))]
    (into {}
          (keep-indexed
           (fn [idx step]
             (cond
               (:name step)
               [(:name step) (nth step-order idx)]

               (and (:workflow step)
                    (= 1 (get workflow-name-freqs (:workflow step))))
               [(:workflow step) (nth step-order idx)]

               :else
               nil))
           steps))))

(defn- resolve-routing-table
  "Resolve :goto step names in a routing table to compiled step-ids.
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

(defn- compile-multi-step-entry
  [workflow-name step-order name->step-id step-ref-map idx step]
  (let [step-id (nth step-order idx)
        previous-step-id (when (pos? idx)
                           (nth step-order (dec idx)))
        delegated-workflow-name (:workflow step)
        step-label (step-label step)
        resolved-on (resolve-routing-table (:on step) name->step-id)
        {input-bindings :ok binding-error :error}
        (compile-step-input-bindings step previous-step-id step-ref-map idx)]
    (when binding-error
      (throw (ex-info (str "Workflow `" workflow-name "` step `" step-label "`: " binding-error)
                      {:workflow workflow-name
                       :step step-label
                       :error binding-error})))
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
       (assoc :on resolved-on))]))

(defn compile-multi-step
  "Compile a parsed workflow file with `:steps` into an N-step canonical definition."
  [{:keys [name description config body]}]
  (let [steps (:steps config)
        duplicate-names (duplicate-step-names steps)]
    (when (seq duplicate-names)
      (throw (ex-info (str "Duplicate workflow step names: " (pr-str duplicate-names))
                      {:duplicate-step-names duplicate-names})))
    (let [step-order (mapv multi-step-id (range) steps)
          name->step-id (workflow-name->step-id-map steps step-order)
          step-ref-map (multi-step-reference-map steps step-order)
          step-map (into {}
                         (map-indexed (partial compile-multi-step-entry
                                               name
                                               step-order
                                               name->step-id
                                               step-ref-map)
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
