(ns psi.agent-session.workflow-mutations
  "Compatibility shim for the renamed extension workflow mutations.

   Prefer `psi.agent-session.extension-workflow-mutations` for authoritative
   extension workflow mutation ownership. This namespace remains only to keep
   transitional callers working during the rename transition."
  (:require
   [psi.agent-session.extension-workflow-mutations :as extension-workflow-mutations]))

(def register-workflow-type extension-workflow-mutations/register-workflow-type)
(def create-workflow extension-workflow-mutations/create-workflow)
(def send-workflow-event extension-workflow-mutations/send-workflow-event)
(def abort-workflow extension-workflow-mutations/abort-workflow)
(def remove-workflow extension-workflow-mutations/remove-workflow)
(def all-mutations extension-workflow-mutations/all-mutations)
