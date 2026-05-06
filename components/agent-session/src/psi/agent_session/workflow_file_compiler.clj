(ns psi.agent-session.workflow-file-compiler
  "Compile parsed workflow file data into canonical target-authored workflow definitions.

   Accepts the output of `workflow-file-parser/parse-workflow-file` and produces
   target-authored workflow-definition maps suitable for registration in the
   deterministic workflow runtime.

   Retirement status for task 090:
   - checked-in `.psi/workflows/*.md` files are now target-authored only
   - current-authored single-step and multi-step file compilation paths are removed"
  (:require
   [psi.agent-session.workflow-target-ir-compiler :as workflow-target-ir-compiler]))

;;; Top-level compilation

(defn- target-authored-config?
  [config]
  (and (map? config)
       (vector? (:steps config))
       (every? map? (:steps config))
       (every? #(contains? % :type) (:steps config))))

(defn- compile-target-authored-workflow-file
  [{:keys [name description config body]}]
  (let [workflow-definition (cond-> {:steps (:steps config)}
                              name (assoc :definition-id name
                                          :name name)
                              description (assoc :summary description
                                                 :description description)
                              (contains? config :terminal-contract) (assoc :terminal-contract (:terminal-contract config))
                              body (assoc :workflow-file-meta {:framing-prompt body}))]
    (when-not (workflow-target-ir-compiler/target-authored-workflow-definition? workflow-definition)
      (throw (ex-info "Target-authored workflow file must define `{:steps [...]}`"
                      {:workflow-definition workflow-definition})))
    workflow-definition))

(defn compile-workflow-file
  "Compile a parsed workflow file into a canonical target-authored workflow definition.

   Returns {:definition <map>} on success, {:error <string>} on failure."
  [{:keys [name config error] :as parsed}]
  (try
    (cond
      error
      {:error error}

      (nil? name)
      {:error "Cannot compile: missing workflow name"}

      (target-authored-config? config)
      {:definition (compile-target-authored-workflow-file parsed)}

      :else
      {:error "Workflow files must define target-authored `{:steps [...]}` config"})
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
  "Target-authored workflow files use explicit IR validation and runtime compilation.
   No separate file-loader-time workflow-name reference validation remains.
   Returns {:valid? true}."
  [_definitions]
  {:valid? true})

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
  "Target-authored workflow files are validated through the target compiler + IR path.
   No separate current-grammar routing validation remains.
   Returns {:valid? true}."
  [_definitions]
  {:valid? true})
