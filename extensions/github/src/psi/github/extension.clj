(ns psi.github.extension
  "psi/github extension entry point.

   Registers deterministic operations via the extension API `:register-operation` key:
   - `github/find-issue`
   - `github/find-pr`
   - `github/add-label`
   - `github/remove-label`"
  (:require
   [psi.github.find-issue :as find-issue]
   [psi.github.find-pr :as find-pr]
   [psi.github.label-ops :as label-ops]))

(defn init
  [api]
  (let [reg (:register-operation api)]
    (reg {:id          "github/find-issue"
          :description "Find a GitHub issue matching labels and optional narrowing input"
          :handler     find-issue/invoke})
    (reg {:id          "github/find-pr"
          :description "Find a GitHub PR matching labels and optional narrowing input"
          :handler     find-pr/invoke})
    (reg {:id          "github/add-label"
          :description "Add one or more labels to a GitHub issue or PR"
          :handler     label-ops/add-label})
    (reg {:id          "github/remove-label"
          :description "Remove one or more labels from a GitHub issue or PR"
          :handler     label-ops/remove-label})))
