(ns psi.agent-session.tool-defs-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.tool-defs :as compat]
   [psi.tool-registry.defs :as defs]))

(deftest compatibility-wrapper-delegates-to-tool-registry-defs-test
  (is (identical? defs/normalize-tool-def compat/normalize-tool-def))
  (is (identical? defs/normalize-tool-defs compat/normalize-tool-defs))
  (is (identical? defs/agent-core-tool compat/agent-core-tool))
  (is (identical? defs/agent-core-tools compat/agent-core-tools))
  (is (identical? defs/provider-tool compat/provider-tool))
  (is (identical? defs/provider-tools compat/provider-tools)))
