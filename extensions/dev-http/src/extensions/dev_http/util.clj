(ns extensions.dev-http.util
  "Small shared helpers for the dev-http extension.")

(defn kget
  "Read a value from `m` under any of `ks`, returning the first present key's
   value. Lets a single accessor serve keyword-keyed (REPL) and string-keyed
   (JSON tool) data."
  [m & ks]
  (some (fn [k] (when (contains? m k) (get m k))) ks))
