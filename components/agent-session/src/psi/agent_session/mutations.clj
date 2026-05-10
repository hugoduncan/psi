(ns psi.agent-session.mutations
  "EQL mutation surface for the agent-session extension API.
   Aggregates the focused mutation namespaces."
  (:require
   [psi.agent-session.mutations.canonical-workflows :as canonical-workflows]
   [psi.agent-session.mutations.extensions :as extensions]
   [psi.agent-session.mutations.prompts :as prompts]
   [psi.agent-session.mutations.services :as services]
   [psi.agent-session.mutations.session :as session]
   [psi.agent-session.mutations.tools :as tools]
   [psi.agent-session.mutations.ui :as ui]
   [psi.agent-session.extension-workflow-mutations :as extension-workflow-mutations]))

(def all-mutations
  "All agent-session mutations defined in this namespace family."
  (into []
        (concat session/all-mutations
                prompts/all-mutations
                tools/all-mutations
                extensions/all-mutations
                services/all-mutations
                ui/all-mutations
                extension-workflow-mutations/all-mutations
                canonical-workflows/all-mutations)))
