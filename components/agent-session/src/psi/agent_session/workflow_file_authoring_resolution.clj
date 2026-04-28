(ns psi.agent-session.workflow-file-authoring-resolution
  "Compatibility authoring-resolution façade.

   Canonical authoring helpers now live in narrower namespaces:
   - `workflow-file-authoring-session` for `:session` source/projection/override compilation
   - `workflow-file-authoring-routing` for author-facing routing target resolution"
  (:require
   [psi.agent-session.workflow-file-authoring-preload :as preload]
   [psi.agent-session.workflow-file-authoring-routing :as routing]
   [psi.agent-session.workflow-file-authoring-session :as session]))

(def compile-step-input-bindings session/compile-step-input-bindings)
(def compile-step-session-overrides session/compile-step-session-overrides)
(def compile-step-session-preload preload/compile-step-session-preload)
(def step-source-reference-map session/step-source-reference-map)
(def routing-target->step-id-map routing/routing-target->step-id-map)
(def resolve-routing-table routing/resolve-routing-table)
