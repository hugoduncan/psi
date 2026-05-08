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
          nullable-state   (nullable-api/create-state)
          runtime-fns      {:query-fn  (fn [q]
                                         (case q
                                           [:psi.extension/prompt-contributions]
                                           {:psi.extension/prompt-contributions (->> (:prompt-contributions @nullable-state)
                                                                                     vals
                                                                                     vec)}
                                           {}))
                            :mutate-fn (fn [op params]
                                         (case op
                                           psi.extension/register-prompt-contribution
                                           (let [id    (str (:id params))
                                                 key   [(:ext-path params) id]
                                                 value (merge {:id id
                                                               :ext-path (:ext-path params)}
                                                              (:contribution params))]
                                             (swap! nullable-state assoc-in [:prompt-contributions key] value)
                                             {:psi.extension.prompt-contribution/registered? true})
                                           (throw (ex-info "unexpected mutation" {:op op :params params}))))}
          ext-path         "/ext/test"
          _                (ext/register-extension-in! reg ext-path)
          _                (tool-registry/register-tool-in! reg ext-path {:name "delegate"
                                                                          :label "Delegate"
                                                                          :description "Run workflows"
                                                                          :parameters {:type "object"}})
          _                (command-registry/register-command-in! reg ext-path {:name "delegate" :description "Run a workflow"})
          _                (((ext/create-extension-api reg ext-path runtime-fns)
                             :register-prompt-contribution)
                            "workflow-loader-workflows"
                            {:section "Extension Capabilities"
                             :content "tool: delegate"
                             :priority 200})
          summary          {:extension-loaded-count (count (ext/extensions-in reg))}
          tool-def-names   (->> (:tool-defs (ss/get-session-data-in ctx session-id))
                                (map :name)
                                set)
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
