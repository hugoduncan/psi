(ns psi.agent-session.workflow-file-parser
  "Compatibility workflow-file parser façade. Canonical loader ownership now
   lives in `psi.workflow-loader.parser`."
  (:require
   [psi.workflow-loader.parser :as workflow-loader.parser]))

(def parse-workflow-file workflow-loader.parser/parse-workflow-file)
