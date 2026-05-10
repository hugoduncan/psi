(ns psi.workflow-loader.authoring-step-source
  "Shared workflow-file authoring helpers for validating and resolving
   prior-step source maps used by loader-time session/preload compilation."
  (:require
   [psi.workflow-loader.authoring-errors :as authoring-errors]))

(def ^:private source-map-keys
  #{:step :kind})

(defn resolve-prior-step-source
  [scope expected-source-form allowed-kinds source-map step-name->step-ref current-step-idx]
  (or (authoring-errors/unexpected-keys-error scope
                                              source-map-keys
                                              source-map)
      (let [{:keys [step kind]} source-map]
        (cond
          (not (string? step))
          (authoring-errors/invalid-in scope expected-source-form)

          (not (contains? allowed-kinds kind))
          (authoring-errors/invalid-in scope
                                       (str "unsupported step source kind `"
                                            kind "`"))

          :else
          (if-let [{:keys [step-id idx]} (get step-name->step-ref step)]
            (if (< idx current-step-idx)
              {:ok {:step-id step-id
                    :idx idx
                    :kind kind}}
              (authoring-errors/invalid (str "Forward step reference: `"
                                             step
                                             "` must refer to an earlier step")))
            (authoring-errors/invalid (str "Unknown step name: `" step "`")))))))
