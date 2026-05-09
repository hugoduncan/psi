(ns psi.agent-session.workflow-file-authoring-routing
  "Compatibility workflow-file authoring-routing façade. Canonical loader ownership now
   lives in `psi.workflow-loader.authoring-routing`."
  (:require
   [psi.workflow-loader.authoring-routing :as workflow-loader.authoring-routing]))

(def routing-target->step-id-map workflow-loader.authoring-routing/routing-target->step-id-map)
(def resolve-routing-table workflow-loader.authoring-routing/resolve-routing-table)
