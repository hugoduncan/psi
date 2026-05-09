(ns psi.agent-session.workflow-file-authoring-errors
  "Compatibility workflow-file authoring-errors façade. Canonical loader ownership now
   lives in `psi.workflow-loader.authoring-errors`."
  (:require
   [psi.workflow-loader.authoring-errors :as workflow-loader.authoring-errors]))

(def invalid workflow-loader.authoring-errors/invalid)
(def invalid-in workflow-loader.authoring-errors/invalid-in)
(def unexpected-keys-error workflow-loader.authoring-errors/unexpected-keys-error)
