(ns psi.agent-session.workflow-file-authoring-errors
  "Shared error-shaping helpers for workflow-file authoring compilation.")

(defn invalid
  [message]
  {:error message})

(defn invalid-in
  [scope message]
  (invalid (str message " in `" scope "`")))

(defn unexpected-keys-error
  [scope allowed-keys actual-map]
  (when-let [unknown-keys (seq (remove allowed-keys (keys actual-map)))]
    (invalid-in scope
                (str "unexpected keys "
                     (pr-str (vec unknown-keys))))))
