(ns psi.workflow-runtime.structured-output-schemas
  "Reusable workflow structured-output schema ids and versioned Malli schemas.")

(def judge-review-result-schema-id :psi.workflow/judge-review-result)
(def judge-review-result-schema-version 1)
(def judge-review-result-schema
  [:map
   [:decision [:enum :clear :needs-work :unclear]]
   [:issues
    [:vector
     [:map
      [:severity [:enum :blocking :minor]]
      [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
      [:description :string]
      [:evidence :string]
      [:suggested-change :string]]]]
   [:confidence [:double {:min 0.0 :max 1.0}]]])

(def schemas
  {[judge-review-result-schema-id judge-review-result-schema-version]
   judge-review-result-schema})

(defn schema-for
  [schema-id schema-version]
  (get schemas [schema-id schema-version]))
