(ns psi.workflow-step-materialization.source-resolution
  "Canonical workflow step materialization source resolution semantics.

   Owns normalized workflow-domain value derivation for:
   - source refs (`:workflow-input`, `:workflow-original`, step `:output`, step `:yield`)
   - source specs (`{:from ...}` with optional `:path` or `:projection`)
   - first-cut path traversal
   - first-cut projection application
   - invoke arg materialization
   - template var resolution / rendering
   - delegate context / prompt-string materialization
   - binding-ref resolution used by step materialization consumers."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-step-materialization.semantics :as semantics]
   [psi.workflow-step-materialization.structured-output :as structured-output]))

(def ^:private missing-path ::missing-path)

(defn get-path*
  [m path]
  (reduce (fn [acc k]
            (when (some? acc)
              (get acc k)))
          m
          path))

(defn- get-path-sentinel
  [m path]
  (reduce (fn [acc k]
            (cond
              (= missing-path acc) missing-path
              (map? acc) (get acc k missing-path)
              (vector? acc) (get acc k missing-path)
              :else missing-path))
          m
          path))

(defn- path-output-spec
  [workflow-run source-spec]
  (let [from (:from source-spec)]
    (when (and (map? from) (:output from))
      (get-in (semantics/effective-step-def workflow-run (:step from))
              [:outputs (:output from)]))))

(defn source-spec?
  [x]
  (and (map? x)
       (or (contains? x :from)
           (contains? x :value))))

(defn resolve-source-ref
  [workflow-run source-ref]
  (cond
    (= source-ref :workflow-input)
    (:workflow-input workflow-run)

    (= source-ref :workflow-original)
    (if (contains? workflow-run :workflow-original)
      (:workflow-original workflow-run)
      (or (get-in (:workflow-input workflow-run) [:original])
          (:workflow-input workflow-run)))

    ;; Per-prompt turn-local surface (task 226 Slice 4): `:prompt` selects a named
    ;; prompt-group's turn record from the multi-prompt step's accepted result,
    ;; then resolves the (text) output key against that record's turn-local outputs.
    (and (map? source-ref) (:prompt source-ref) (:output source-ref))
    (let [step-id (:step source-ref)
          group-name (:prompt source-ref)
          output-key (:output source-ref)
          accepted (get-in workflow-run [:step-runs step-id :accepted-result])
          group-record (some #(when (= group-name (:name %)) %)
                             (get-in accepted [:outputs :prompt-group-outputs]))]
      (semantics/step-output-value nil {:outputs (:outputs group-record)} output-key))

    (and (map? source-ref) (:output source-ref))
    (let [step-id (:step source-ref)
          output-key (:output source-ref)
          accepted (get-in workflow-run [:step-runs step-id :accepted-result])]
      (semantics/step-output-value (semantics/effective-step-def workflow-run step-id) accepted output-key))

    (and (map? source-ref) (:yield source-ref))
    (let [step-id (:step source-ref)
          accepted (get-in workflow-run [:step-runs step-id :accepted-result])
          step-def (semantics/effective-step-def workflow-run step-id)]
      (semantics/step-yield-field-value step-def accepted (:yield source-ref)))

    :else nil))

(defn apply-source-spec
  [workflow-run {:keys [from path projection] :as source-spec}]
  (if (contains? source-spec :value)
    (:value source-spec)
    (let [base (resolve-source-ref workflow-run from)]
      (cond
        (and (contains? source-spec :path)
             (contains? source-spec :projection))
        (throw (ex-info "Workflow source-spec cannot contain both `:path` and `:projection`"
                        {:source-spec source-spec}))

        (seq path)
        (let [output-spec (path-output-spec workflow-run source-spec)
              path-value (get-path-sentinel base path)]
          (if (= missing-path path-value)
            (cond
              (structured-output/structured-output-spec? output-spec)
              (throw (ex-info "Workflow structured output path is missing"
                              {:type :missing-structured-output-path
                               :source-spec source-spec
                               :path path}))

              (and output-spec (some? base) (not (coll? base)))
              (throw (ex-info "Workflow source output is not structured"
                              {:type :non-structured-output-path
                               :source-spec source-spec
                               :path path
                               :output-spec output-spec}))

              :else nil)
            path-value))

        (some? projection)
        (semantics/project-source-value base projection)

        :else
        base))))

(defn workflow-ref?
  [x]
  (m/validate workflow-model/workflow-ref-schema x))

(defn resolve-workflow-ref-source-spec
  [workflow-run source-spec]
  (let [resolved (apply-source-spec workflow-run source-spec)]
    (when-not (workflow-ref? resolved)
      (throw (ex-info "Dynamic delegate target must resolve to a workflow reference"
                      {:source-spec source-spec
                       :resolved-value resolved
                       :expected {:type :workflow-ref :name "workflow-name"}})))
    resolved))

(defn materialize-template-vars
  [workflow-run vars]
  (into {}
        (map (fn [[var-name source-spec]]
               [var-name (apply-source-spec workflow-run source-spec)]))
        vars))

(defn render-template-contribution
  [workflow-run contribution]
  (let [values (materialize-template-vars workflow-run (:vars contribution))]
    (reduce-kv (fn [text var-name value]
                 (str/replace text
                              (str "{{" var-name "}}")
                              (str (or value ""))))
               (:text contribution)
               values)))

(defn materialize-contribution
  [workflow-run contribution]
  (case (:type contribution)
    :source (apply-source-spec workflow-run contribution)
    :template (render-template-contribution workflow-run contribution)
    contribution))

(defn materialize-contributions
  [workflow-run contributions]
  (mapv #(materialize-contribution workflow-run %) contributions))

(defn resolve-invoke-args
  ([workflow-run args]
   (resolve-invoke-args workflow-run nil args))
  ([workflow-run step-id args]
   (into {}
         (map (fn [[arg-k arg-v]]
                [arg-k (if (source-spec? arg-v)
                         (apply-source-spec workflow-run
                                            (cond-> arg-v
                                              (and step-id
                                                   (map? (:from arg-v))
                                                   (= step-id (get-in arg-v [:from :step]))
                                                   (contains? (get-in arg-v [:from]) :output))
                                              (-> (assoc :value (resolve-source-ref workflow-run (:from arg-v)))
                                                  (dissoc :from))))
                         arg-v)]))
         args)))

(defn resolve-delegate-context
  [workflow-run context]
  (mapv #(materialize-contribution workflow-run %) context))

(defn render-delegate-prompt-string
  [workflow-run prompt-string]
  (cond
    (and (map? prompt-string) (= :template (:type prompt-string)))
    (render-template-contribution workflow-run prompt-string)

    (and (map? prompt-string) (= :map (:type prompt-string)))
    (into {}
          (map (fn [[field-k source-spec]]
                 [field-k (apply-source-spec workflow-run source-spec)]))
          (:fields prompt-string))

    :else
    prompt-string))

(defn- resolve-accepted-result-path
  [workflow-run step-id path]
  (let [accepted-result (get-in workflow-run [:step-runs step-id :accepted-result])
        step-def (semantics/effective-step-def workflow-run step-id)
        [k1 k2 & more] path]
    (cond
      (empty? path)
      accepted-result

      (= :outputs k1)
      (if (keyword? k2)
        (let [value (cond
                      (contains? (set (keys (:outputs step-def))) k2)
                      (semantics/step-output-value step-def accepted-result k2)

                      :else
                      (get-path* accepted-result path))]
          (get-path* value more))
        (get-path* accepted-result path))

      :else
      (get-path* accepted-result path))))

(defn resolve-binding-ref
  [workflow-run {:keys [source path]}]
  (case source
    :workflow-input
    (get-path* (:workflow-input workflow-run) path)

    :step-output
    (let [[step-id & more] path]
      (resolve-accepted-result-path workflow-run step-id more))

    :workflow-runtime
    (get-path* {:run-id (:run-id workflow-run)
                :current-step-id (:current-step-id workflow-run)
                :status (:status workflow-run)}
               path)

    nil))
