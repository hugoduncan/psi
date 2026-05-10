(ns psi.github.extension
  "psi/github extension entry point.

   Registers the `github/find-issue` deterministic operation via the
   extension API `:register-operation` key."
  (:require
   [psi.github.find-issue :as find-issue]))

(defn init
  [api]
  ((:register-operation api)
   {:id          "github/find-issue"
    :description "Find a GitHub issue matching labels and optional narrowing input"
    :handler     find-issue/invoke}))
