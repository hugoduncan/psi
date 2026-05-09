(ns psi.agent-session.workflow-file-authoring-session
  "Compatibility workflow-file authoring-session façade. Canonical loader ownership now
   lives in `psi.workflow-loader.authoring-session`."
  (:require
   [psi.workflow-loader.authoring-session :as workflow-loader.authoring-session]))

(def source+projection->binding workflow-loader.authoring-session/source+projection->binding)
(def compile-step-input-bindings workflow-loader.authoring-session/compile-step-input-bindings)
(def compile-step-session-overrides workflow-loader.authoring-session/compile-step-session-overrides)
(def step-source-reference-map workflow-loader.authoring-session/step-source-reference-map)
