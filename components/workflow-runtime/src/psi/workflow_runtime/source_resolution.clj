(ns psi.workflow-runtime.source-resolution
  "Canonical shared workflow-runtime source reference and source-spec resolution.

   Owns normalized IR-shaped runtime semantics for:
   - source refs (`:workflow-input`, `:workflow-original`, step `:output`, step `:yield`)
   - source specs (`{:from ...}` with optional `:path` or `:projection`)
   - first-cut path traversal
   - first-cut projection application
   - invoke arg materialization
   - template var resolution / rendering
   - delegate context / prompt-string materialization

   This substrate is runtime-owned. Authoring/compiler namespaces may translate
   authored syntax into canonical IR-compatible source specs, but they should
   delegate runtime value resolution semantics here rather than re-encode them."
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.statechart :as workflow-statechart]
   [psi.workflow-judge :as workflow-judge]))

(defn- effective-step-def
  [workflow-run step-id]
  (get (workflow-statechart/effective-steps (:effective-definition workflow-run)) step-id))

(defn get-path*
  [m path]
  (reduce (fn [acc k]
            (when (some? acc)
              (get acc k)))
          m
          path))

(defn source-spec?
  [x]
  (and (map? x)
       (contains? x :from)))

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

    (and (map? source-ref) (:output source-ref))
    (let [step-id (:step source-ref)
          output-key (:output source-ref)
          accepted (get-in workflow-run [:step-runs step-id :accepted-result])
          step-def (effective-step-def workflow-run step-id)]
      (workflow-ir/step-output-value step-def accepted output-key))

    (and (map? source-ref) (:yield source-ref))
    (let [step-id (:step source-ref)
          accepted (get-in workflow-run [:step-runs step-id :accepted-result])
          step-def (effective-step-def workflow-run step-id)]
      (workflow-ir/step-yield-field-value step-def accepted (:yield source-ref)))

    :else nil))

(defn apply-source-spec
  [workflow-run {:keys [from path projection] :as source-spec}]
  (let [base (resolve-source-ref workflow-run from)]
    (cond
      (and (contains? source-spec :path)
           (contains? source-spec :projection))
      (throw (ex-info "Workflow source-spec cannot contain both `:path` and `:projection`"
                      {:source-spec source-spec}))

      (seq path)
      (get-path* base path)

      (some? projection)
      (if (= :full projection)
        base
        (workflow-judge/project-messages base projection))

      :else
      base)))

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
  [workflow-run args]
  (into {}
        (map (fn [[arg-k arg-v]]
               [arg-k (if (source-spec? arg-v)
                        (apply-source-spec workflow-run arg-v)
                        arg-v)]))
        args))

(defn resolve-delegate-context
  [workflow-run context]
  (mapv #(materialize-contribution workflow-run %) context))

(defn render-delegate-prompt-string
  [workflow-run prompt-string]
  (if (and (map? prompt-string)
           (= :template (:type prompt-string)))
    (render-template-contribution workflow-run prompt-string)
    prompt-string))

(defn- resolve-accepted-result-path
  [workflow-run step-id path]
  (let [accepted-result (get-in workflow-run [:step-runs step-id :accepted-result])
        step-def (effective-step-def workflow-run step-id)
        [k1 k2 & more] path]
    (cond
      (empty? path)
      accepted-result

      (= :outputs k1)
      (if (keyword? k2)
        (let [value (cond
                      (contains? (set (keys (:outputs step-def))) k2)
                      (workflow-ir/step-output-value step-def accepted-result k2)

                      (= k2 :text)
                      (workflow-ir/step-yield-field-value step-def accepted-result :text)

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