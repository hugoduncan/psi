(ns psi.agent-session.workflow-source-resolution
  "Canonical shared workflow-runtime source reference and source-spec resolution.

   Owns normalized IR-shaped runtime semantics for:
   - source refs (`:workflow-input`, `:workflow-original`, step `:output`, step `:yield`)
   - source specs (`{:from ...}` with optional `:path` or `:projection`)
   - first-cut path traversal
   - first-cut projection application

   This substrate is runtime-owned. Authoring/compiler namespaces may translate
   authored syntax into canonical IR-compatible source specs, but they should
   delegate runtime value resolution semantics here rather than re-encode them."
  (:require
   [psi.agent-session.workflow-ir :as workflow-ir]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.agent-session.workflow-statechart :as workflow-statechart]))

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

(defn resolve-source-ref
  [workflow-run source-ref]
  (cond
    (= source-ref :workflow-input)
    (:workflow-input workflow-run)

    (= source-ref :workflow-original)
    (or (get-in (:workflow-input workflow-run) [:original])
        (:workflow-input workflow-run))

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

(defn resolve-binding-ref
  [workflow-run {:keys [source path]}]
  (case source
    :workflow-input
    (get-path* (:workflow-input workflow-run) path)

    :step-output
    (let [[step-id & more] path
          accepted-result (get-in workflow-run [:step-runs step-id :accepted-result])]
      (get-path* accepted-result more))

    :workflow-runtime
    (get-path* {:run-id (:run-id workflow-run)
                :current-step-id (:current-step-id workflow-run)
                :status (:status workflow-run)}
               path)

    nil))
