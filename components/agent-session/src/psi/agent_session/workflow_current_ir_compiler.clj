(ns psi.agent-session.workflow-current-ir-compiler
  "Compile current authored workflow definitions into normalized workflow IR.

   This compiler is a compatibility layer from the current implemented
   `workflow-model` step grammar into the canonical normalized workflow IR.
   It preserves current authored semantics by:
   - compiling all current steps to IR `:type :session`
   - translating current binding refs into shared IR source-specs
   - translating prompt templates into template contributions
   - preserving current result-schema / executor / capability-policy breadcrumbs
     under narrow step-level `:compat` metadata
   - preserving current accepted-result envelope reads through canonical `:result`
     outputs plus narrow source-spec `:compat` breadcrumbs"
  (:require
   [clojure.string :as str]
   [psi.agent-session.workflow-ir :as workflow-ir]))

(def ^:private current-session-output-specs
  {:text {:source :session/final-llm-reply}
   :transcript {:source :session/transcript}
   :result {:source :session/result}})

(def ^:private current-session-yields
  {:type :text
   :text :text})

(defn- non-blank-map
  [m]
  (when (seq m)
    m))

(defn- compile-step-output-ref
  [path]
  (let [[step-id & more] path]
    (cond
      (not (string? step-id))
      (throw (ex-info "Current `:step-output` refs must start with a string step id"
                      {:path path}))

      (empty? more)
      {:from {:step step-id :output :result}
       :compat {:current-binding-ref {:source :step-output
                                      :path path
                                      :accepted-result-envelope true
                                      :surface :whole-envelope}}}

      (= :outputs (first more))
      (let [[_ output-key & output-path] more]
        (when-not (keyword? output-key)
          (throw (ex-info "Current `:step-output` `:outputs` refs must name a keyword output key"
                          {:path path})))
        (cond-> {:from {:step step-id :output output-key}}
          (seq output-path) (assoc :path (vec output-path))))

      (#{:diagnostics :blocked} (first more))
      {:from {:step step-id :output :result}
       :path (vec more)
       :compat {:current-binding-ref {:source :step-output
                                      :path path
                                      :accepted-result-envelope true
                                      :surface (first more)}}}

      :else
      (throw (ex-info "Unsupported current `:step-output` accepted-result envelope surface"
                      {:path path
                       :supported-surfaces #{:outputs :diagnostics :blocked :whole-envelope}})))))

(defn- compile-binding-ref
  [{:keys [source path] :as ref}]
  (case source
    :workflow-input
    (if (and (seq path) (= :original (first path)))
      (cond-> {:from :workflow-original}
        (seq (rest path)) (assoc :path (vec (rest path))))
      (cond-> {:from :workflow-input}
        (seq path) (assoc :path (vec path))))

    :workflow-runtime
    (cond-> {:from :workflow-runtime
             :compat {:current-binding-ref ref}}
      (seq path) (assoc :path (vec path)))

    :step-output
    (compile-step-output-ref path)

    (throw (ex-info "Unsupported current workflow binding ref source"
                    {:ref ref}))))

(defn- placeholder-token
  [binding-k]
  (str "$"
       (-> binding-k
           name
           (str/replace "-" "_")
           str/upper-case)))

(defn- template-var-name
  [binding-k]
  (name binding-k))

(defn- render-template-text
  [prompt-template input-bindings]
  (reduce-kv (fn [text binding-k _]
               (str/replace text
                            (placeholder-token binding-k)
                            (str "{{" (template-var-name binding-k) "}}")))
             (or prompt-template "$INPUT")
             input-bindings))

(defn- compile-template-contribution
  [{:keys [prompt-template input-bindings]}]
  {:type :template
   :text (render-template-text prompt-template input-bindings)
   :vars (into {}
               (map (fn [[binding-k binding-ref]]
                      [(template-var-name binding-k)
                       (compile-binding-ref binding-ref)]))
               input-bindings)
   :compat {:current-template-syntax :dollar-bindings
            :current-prompt-template (or prompt-template "$INPUT")}})

(defn- compile-preload-entry
  [entry]
  (case (:kind entry)
    :value
    (let [compiled-binding (compile-binding-ref (:binding entry))]
      (cond-> (assoc compiled-binding :type :source)
        (:role entry) (update :compat #(merge {:current-preload {:kind :value
                                                                 :role (:role entry)}}
                                              (or % {})))))

    :session-transcript
    {:type :source
     :from {:step (:step-id entry) :output :transcript}
     :projection (:projection entry)
     :compat {:current-preload {:kind :session-transcript
                                :step-id (:step-id entry)}}}

    (throw (ex-info "Unsupported current session preload kind"
                    {:entry entry}))))

(defn- compile-session-overrides
  [{:keys [session-overrides capability-policy executor]}]
  (let [skill (:skill executor)
        explicit-skills (:skills session-overrides)
        combined-skills (cond
                          (and skill (seq explicit-skills))
                          (vec (distinct (cons skill explicit-skills)))

                          skill
                          [skill]

                          (seq explicit-skills)
                          (vec explicit-skills)

                          :else nil)
        tools (if (contains? session-overrides :tools)
                (:tools session-overrides)
                (some-> capability-policy :tools vec))]
    (cond-> {}
      (contains? session-overrides :system-prompt)
      (assoc :system-prompt (:system-prompt session-overrides))

      (some? tools)
      (assoc :tools tools)

      (some? combined-skills)
      (assoc :skills combined-skills)

      (contains? session-overrides :model)
      (assoc :model (:model session-overrides))

      (contains? session-overrides :thinking-level)
      (assoc :thinking-level (:thinking-level session-overrides))

      (contains? session-overrides :prompt-component-selection)
      (assoc :prompt-component-selection (:prompt-component-selection session-overrides)))))

(defn- compile-judge
  [{:keys [judge]}]
  (when judge
    (cond-> {:type :llm
             :session {:contributions [{:type :template
                                        :text (:prompt judge)
                                        :vars {}}]}}
      (contains? judge :system-prompt)
      (assoc-in [:session :system-prompt] (:system-prompt judge))

      (contains? judge :projection)
      (assoc :projection (:projection judge)))))

(defn- step-compat
  [step-id {:keys [result-schema executor capability-policy]}]
  (non-blank-map
   {:current-step-id step-id
    :result-schema result-schema
    :executor executor
    :capability-policy capability-policy}))

(defn- compile-step
  [step-id step-def]
  (let [session-contributions (vec (concat (map compile-preload-entry (:session-preload step-def))
                                           [(compile-template-contribution step-def)]))
        session-config (compile-session-overrides step-def)]
    (cond-> {:name step-id
             :type :session
             :session (assoc session-config :contributions session-contributions)
             :outputs current-session-output-specs
             :yields current-session-yields}
      (:judge step-def)
      (assoc :judge (compile-judge step-def))

      (:on step-def)
      (assoc :on (:on step-def))

      (some? (step-compat step-id step-def))
      (assoc :compat (step-compat step-id step-def)))))

(defn compile-workflow-definition
  "Compile a current authored workflow definition into normalized workflow IR.

   Throws `ExceptionInfo` on malformed current-authored inputs that cannot be
   translated into normalized IR."
  [workflow-definition]
  {:version :workflow-ir/v1
   :steps (mapv (fn [step-id]
                  (compile-step step-id
                                (get-in workflow-definition [:steps step-id])))
                (:step-order workflow-definition))})

(defn compile-and-validate-workflow-definition
  "Compile current authored workflow definition and validate the resulting IR.

   Returns:
   {:valid? boolean
    :ir workflow-ir?
    :structural-errors explain-data?
    :semantic-errors [error*]
    :compile-error string?}"
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
       :compile-error (.getMessage e)})))
