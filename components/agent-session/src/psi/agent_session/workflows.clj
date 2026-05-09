(ns psi.agent-session.workflows
  "Compatibility shim for the renamed extension workflow runtime.

   Prefer `psi.agent-session.extension-workflow-runtime` for authoritative
   extension workflow runtime ownership. This namespace remains only to keep
   transitional callers working during the post-extraction cleanup."
  (:require
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]))

(def create-registry extension-workflow-runtime/create-registry)
(def ensure-pump! extension-workflow-runtime/ensure-pump!)
(def shutdown-in! extension-workflow-runtime/shutdown-in!)
(def register-type-in! extension-workflow-runtime/register-type-in!)
(def type-names-in extension-workflow-runtime/type-names-in)
(def create-workflow-in! extension-workflow-runtime/create-workflow-in!)
(def workflow-in extension-workflow-runtime/workflow-in)
(def workflows-in extension-workflow-runtime/workflows-in)
(def workflow-count-in extension-workflow-runtime/workflow-count-in)
(def running-count-in extension-workflow-runtime/running-count-in)
(def send-event-in! extension-workflow-runtime/send-event-in!)
(def abort-workflow-in! extension-workflow-runtime/abort-workflow-in!)
(def remove-workflow-in! extension-workflow-runtime/remove-workflow-in!)
(def clear-all-in! extension-workflow-runtime/clear-all-in!)
