(ns psi.agent-session.workflow-file-loader
  "Compatibility workflow-file loader façade. Canonical loader ownership now
   lives in `psi.workflow-loader.core`."
  (:require
   [psi.workflow-loader.core :as workflow-loader]))

(def global-workflow-dirs workflow-loader/global-workflow-dirs)
(def project-workflow-dir workflow-loader/project-workflow-dir)
(def scan-directory workflow-loader/scan-directory)
(def scan-all-directories workflow-loader/scan-all-directories)
(def load-workflow-definitions workflow-loader/load-workflow-definitions)
