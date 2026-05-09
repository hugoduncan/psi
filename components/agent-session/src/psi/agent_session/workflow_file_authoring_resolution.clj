(ns psi.agent-session.workflow-file-authoring-resolution
  "Compatibility authoring-resolution façade.

   Canonical authoring helpers now live in `psi.workflow-loader.authoring-*`.
   This namespace remains only as a temporary compatibility seam."
  (:require
   [psi.workflow-loader.authoring-preload :as preload]
   [psi.workflow-loader.authoring-routing :as routing]
   [psi.workflow-loader.authoring-session :as session]))

(def compile-step-input-bindings session/compile-step-input-bindings)
(def compile-step-session-overrides session/compile-step-session-overrides)
(def compile-step-session-preload preload/compile-step-session-preload)
(def step-source-reference-map session/step-source-reference-map)
(def routing-target->step-id-map routing/routing-target->step-id-map)
(def resolve-routing-table routing/resolve-routing-table)
