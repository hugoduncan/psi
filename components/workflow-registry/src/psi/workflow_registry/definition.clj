(ns psi.workflow-registry.definition
  "Lower authored workflow-definition shape predicates shared by registry and
   higher workflow compilation/runtime layers.")

(defn target-authored-workflow-definition?
  "Return true when `workflow-definition` matches the first-cut target-authored
   registration/compilation input shape.

   Current preserved contract:
   - root value is a map
   - `:steps` is a vector
   - every step is a map"
  [workflow-definition]
  (and (map? workflow-definition)
       (vector? (:steps workflow-definition))
       (every? map? (:steps workflow-definition))))
