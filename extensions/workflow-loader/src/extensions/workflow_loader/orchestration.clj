(ns extensions.workflow-loader.orchestration
  (:require
   [psi.agent-session.workflow.orchestration :as core]))

(def workflow-provenance-id core/workflow-provenance-id)
(def background-job-tool-call-id core/background-job-tool-call-id)
(def start-background-job! core/start-background-job!)
(def mark-background-job-terminal! core/mark-background-job-terminal!)
(def continue-workflow-input core/continue-workflow-input)
(def exception-summary core/exception-summary)
(def delegated-result-publication core/delegated-result-publication)
(def on-async-completion! core/on-async-completion!)
(def execute-async! core/execute-async!)
(def continue-terminal-run-async! core/continue-terminal-run-async!)
(def continue-blocked-run-async! core/continue-blocked-run-async!)
(def await-run-completion core/await-run-completion)
