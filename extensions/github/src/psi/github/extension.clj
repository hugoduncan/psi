(ns psi.github.extension
  "psi/github extension entry point.

   Registers deterministic operations via the extension API `:register-operation` key:
   - `github/find-issue`
   - `github/find-pr`
   - `github/edit-labels`"
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
    (reg {:id          "github/edit-labels"
          :description "Add and/or remove labels on a GitHub issue or PR in a single operation"
          :handler     label-ops/edit-labels})))
