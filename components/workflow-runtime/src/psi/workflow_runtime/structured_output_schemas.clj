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

(def judge-routing-result-schema-id :psi.workflow/judge-routing-result)
(def judge-routing-result-schema-version 1)
(def judge-routing-result-schema [:enum "REPEAT" "DONE"])
(def judge-routing-result-json-schema {:type "string" :enum ["REPEAT" "DONE"]})

(def pass-status-result-schema-id :psi.workflow/pass-status-result)
(def pass-status-result-schema-version 1)
(def pass-status-result-schema
  [:map
   [:status [:enum "PASS" "FAIL"]]
   [:reason :string]])
(def pass-status-result-json-schema
  {:type "object"
   :properties {:status {:type "string" :enum ["PASS" "FAIL"]}
                :reason {:type "string"}}
   :required ["status" "reason"]})

(def schemas
  {[judge-review-result-schema-id judge-review-result-schema-version]
   judge-review-result-schema
   [judge-routing-result-schema-id judge-routing-result-schema-version]
   judge-routing-result-schema
   [pass-status-result-schema-id pass-status-result-schema-version]
   pass-status-result-schema})

(defn schema-for
  [schema-id schema-version]
  (get schemas [schema-id schema-version]))
