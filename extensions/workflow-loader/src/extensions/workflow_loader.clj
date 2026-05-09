(ns extensions.workflow-loader
  "Thin compatibility facade delegating legacy extension-owned workflow wiring
   to the built-in core workflow owner."
  (:require
   [extensions.workflow-loader.delivery]
   [extensions.workflow-loader.orchestration]
   [extensions.workflow-loader.text]
   [psi.agent-session.workflow.core :as core]))

(def state core/state)
(def inflight-runs core/inflight-runs)
(def prompt-contribution-id core/prompt-contribution-id)

(defn init [api]
  (core/init api))
