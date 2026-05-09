(ns psi.agent-session.workflow-file-authoring-preload
  "Compatibility workflow-file authoring-preload façade. Canonical loader ownership now
   lives in `psi.workflow-loader.authoring-preload`."
  (:require
   [psi.workflow-loader.authoring-preload :as workflow-loader.authoring-preload]))

(def compile-step-session-preload workflow-loader.authoring-preload/compile-step-session-preload)
