(ns extensions.workflow-loader.delivery
  (:require
   [psi.agent-session.workflow.delivery :as core]))

(def append-message-in-session! core/append-message-in-session!)
(def inject-result-into-context! core/inject-result-into-context!)
