(ns psi.agent-session.workflow-file-compiler
  "Compatibility workflow-file compiler façade. Canonical loader ownership now
   lives in `psi.workflow-loader.compiler`."
  (:require
   [psi.workflow-loader.compiler :as workflow-loader.compiler]))

(def compile-workflow-file workflow-loader.compiler/compile-workflow-file)
(def compile-workflow-files workflow-loader.compiler/compile-workflow-files)
(def validate-step-references workflow-loader.compiler/validate-step-references)
(def validate-no-name-collisions workflow-loader.compiler/validate-no-name-collisions)
(def validate-judge-routing workflow-loader.compiler/validate-judge-routing)
