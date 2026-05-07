(ns psi.tool-runtime.args
  "Generic tool-call argument parsing helpers shared by lower tool/runtime code
   and higher turn/session orchestration code."
  (:require
   [cheshire.core :as json]))

(defn parse-args-strict
  "Parse tool args strictly, preserving parse validity.
   Returns {:ok? true :value <map>} when JSON parses to a map,
   otherwise {:ok? false :value nil}."
  [arguments]
  (try
    (let [parsed (json/parse-string arguments)]
      (if (map? parsed)
        {:ok? true :value parsed}
        {:ok? false :value nil}))
    (catch Exception _
      {:ok? false :value nil})))

(defn parse-args
  "Parse JSON tool arguments string into a map.
   Always returns a map — returns {} on non-map or parse failure."
  [arguments]
  (let [{:keys [ok? value]} (parse-args-strict arguments)]
    (if ok? value {})))
