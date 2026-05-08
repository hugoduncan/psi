(ns psi.agent-session.tool-defs
  "Thin compatibility wrappers over the extracted psi.tool-registry.defs owner."
  (:require
   [psi.tool-registry.defs :as defs]))

(def normalize-tool-def defs/normalize-tool-def)
(def normalize-tool-defs defs/normalize-tool-defs)
(def agent-core-tool defs/agent-core-tool)
(def agent-core-tools defs/agent-core-tools)
(def provider-tool defs/provider-tool)
(def provider-tools defs/provider-tools)

