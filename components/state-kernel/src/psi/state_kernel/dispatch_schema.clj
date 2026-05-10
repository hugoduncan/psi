(ns psi.state-kernel.dispatch-schema
  (:require
   [malli.core :as m]))

(def effect-schema
  "Schema for a single dispatch effect description.
   The kernel knows only that effects dispatch on :effect/type; concrete effect
   kinds remain application-owned above the kernel boundary."
  [:map {:closed false}
   [:effect/type keyword?]])

(def pure-result-schema
  "Schema for the unified pure handler result shape.
   At least one recognized key must be present."
  [:and
   [:map
    [:root-state-update {:optional true} fn?]
    [:effects {:optional true} [:vector [:map [:effect/type keyword?]]]]
    [:return {:optional true} :any]
    [:return-key {:optional true} [:or :keyword [:vector :any]]]
    [:return-effect-result? {:optional true} :boolean]]
   [:fn {:error/message "must contain at least one of :root-state-update, :effects, :return, :return-key, :return-effect-result?"}
    (fn [m]
      (or (contains? m :root-state-update)
          (contains? m :effects)
          (contains? m :return)
          (contains? m :return-key)
          (contains? m :return-effect-result?)))]])

(def valid-effect?
  "Compiled malli validator for effect descriptions."
  (m/validator effect-schema))

(def explain-effect
  "Compiled malli explainer for effect descriptions."
  (m/explainer effect-schema))

(def valid-pure-result?*
  "Compiled malli validator for pure handler results."
  (m/validator pure-result-schema))

(def explain-pure-result
  "Compiled malli explainer for pure handler results."
  (m/explainer pure-result-schema))

(def validate-dispatch-schemas
  "Kernel-owned validator for pure dispatch results.
   Checks pure-result shape and nested effects against compiled schemas.
   Compiled out when *assert* is false."
  (when *assert*
    (fn [_env ictx]
      (if-let [pr (:pure-result ictx)]
        (if (valid-pure-result?* pr)
          true
          {:valid? false
           :reason {:type :schema-validation-failed
                    :explanation (explain-pure-result pr)}})
        true))))
