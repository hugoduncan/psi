(ns psi.agent-session.deterministic-operation-registry
  "Compatibility wrapper over the extracted deterministic-operation-registry component."
  (:require
   [psi.agent-session.deterministic-operations :as ops]
   [psi.deterministic-operation-registry.registry :as registry]))

(def create-registry registry/create-registry)
(def register-operation-in! registry/register-operation-in!)
(def unregister-operations-by-extension-in! registry/unregister-operations-by-extension-in!)
(def operation-ids-in registry/operation-ids-in)
(def operation-count-in registry/operation-count-in)
(def get-operation-in registry/get-operation-in)
(def all-operations-in registry/all-operations-in)

(defn invoke-operation-in
  [reg operation-id invocation]
  (registry/invoke-operation-in reg operation-id invocation ops/invoke-operation))
