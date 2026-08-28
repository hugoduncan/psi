(ns psi.agent-session.workflow.authored-token
  "Shared grammar for authored workflow route tokens and field labels.")

(def ^:private route-token-pattern #"^[A-Z_]+$")

(defn valid-route-token?
  "True when `value` is an unambiguous authored workflow route token or field label."
  [value]
  (and (string? value)
       (boolean (re-matches route-token-pattern value))))
