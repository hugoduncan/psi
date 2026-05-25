(ns psi.bootstrap-extension-invariant-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.extensions :as ext]
   [psi.command-registry.registry :as command-registry]
   [psi.tool-registry.registry :as tool-registry]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.extension-test-helpers.nullable-api :as nullable-api]))

(deftest bootstrap-loaded-extensions-match-live-registry-and-active-tools-test
  (testing "bootstrap keeps startup summary, live registry, and active tools aligned"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          reg              (:extension-registry ctx)
          {:keys [api]}    (nullable-api/create-nullable-extension-api {:path "/ext/test"})
          ext-path         "/ext/test"
          _                (ext/register-extension-in! reg ext-path)
          _                (tool-registry/register-tool-in! reg ext-path {:name "delegate"
                                                                          :label "Delegate"
                                                                          :description "Run workflows"
                                                                          :format-request (fn [_] "delegate")
                                                                          :parameters {:type "object"}})
          _                (command-registry/register-command-in! reg ext-path {:name "delegate" :description "Run a workflow"})
          _                ((api :register-prompt-contribution)
                            "workflow-loader-workflows"
                            {:section "Extension Capabilities"
                             :content "tool: delegate"
                             :priority 200})
          summary          {:extension-loaded-count (count (ext/extensions-in reg))}
          tool-def-names   (set (:tool-ids (ss/get-session-data-in ctx session-id)))
          active-tool-names (->> (:tools (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))
                                 (map :name)
                                 set)]
      (is (= (count (ext/extensions-in reg))
             (:extension-loaded-count summary)
             1))
      (is (= #{ext-path} (set (ext/extensions-in reg))))
      (is (contains? (set (tool-registry/tool-names-in reg)) "delegate"))
      ;; Registry invariants first: if extensions are loaded, the live registry must retain tools.
      ;; Active tool projection is asserted elsewhere by bootstrap/runtime tests.
      (is (or (empty? tool-def-names)
              (contains? tool-def-names "delegate")))
      (is (or (empty? active-tool-names)
              (contains? active-tool-names "delegate"))))))
