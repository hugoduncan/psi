(ns psi.agent-session.ui-capabilities-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.ui-capabilities :as ui-capabilities]))

(def ^:private ui-query
  [:psi.ui/type
   :psi.ui/available?
   :psi.ui/capabilities
   :psi.ui/actions
   :psi.ui/make-visible-action
   :psi.ui/diagnostic])

(defn- create-ctx
  [opts]
  (session/create-context (test-support/safe-context-opts (assoc opts :persist? false))))

(deftest missing-provider-query-behaviour-test
  ;; Tests that headless/missing-provider UI queries return explicit unavailable data.
  (let [ctx (create-ctx {})
        _ (ui-capabilities/clear-provider! ctx)
        result (session/query-in ctx ui-query)]
    (is (= nil (:psi.ui/type result)))
    (is (= false (:psi.ui/available? result)))
    (is (= [] (:psi.ui/capabilities result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/no-provider
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest emacs-provider-query-behaviour-test
  ;; Tests that the Emacs provider advertises a supported make-visible action.
  (let [ctx (create-ctx {:ui-type :emacs})
        result (session/query-in ctx ui-query)
        action (:psi.ui/make-visible-action result)]
    (is (= :emacs (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [:psi.ui.capability/make-visible]
           (:psi.ui/capabilities result)))
    (is (= [action] (:psi.ui/actions result)))
    (is (= {:psi.ui.invocation/kind :emacs-command
            :psi.ui.invocation/command "psi-emacs-show-active"}
           (:psi.ui.action/invocation action)))
    (is (true? (:psi.ui.action/available? action)))
    (is (nil? (:psi.ui/diagnostic result)))))

(deftest console-provider-query-behaviour-test
  ;; Tests that attached console UI is available but unsupported for make-visible.
  (let [ctx (create-ctx {:ui-type :console})
        result (session/query-in ctx ui-query)]
    (is (= :console (:psi.ui/type result)))
    (is (= true (:psi.ui/available? result)))
    (is (= [] (:psi.ui/capabilities result)))
    (is (= [] (:psi.ui/actions result)))
    (is (= :psi.ui.unavailable.reason/unsupported-capability
           (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))))

(deftest provider-normalization-invalid-data-test
  ;; Tests that invalid provider output maps to provider-error unavailable data.
  (let [ctx (create-ctx {})]
    (ui-capabilities/install-provider!
     ctx
     (fn [_]
       {:psi.ui/type :broken
        :psi.ui/available? true
        :psi.ui/capabilities [:psi.ui.capability/make-visible]
        :psi.ui/actions [{:psi.ui.action/id :psi.ui.action/make-visible
                          :psi.ui.action/capability :psi.ui.capability/make-visible
                          :psi.ui.action/label "Show Psi UI"
                          :psi.ui.action/description "Bring the active Psi UI to the foreground."
                          :psi.ui.action/available? true
                          :psi.ui.action/invocation {:psi.ui.invocation/kind :unknown}}]}))
    (let [result (session/query-in ctx ui-query)]
      (is (= false (:psi.ui/available? result)))
      (is (= [] (:psi.ui/capabilities result)))
      (is (= [] (:psi.ui/actions result)))
      (is (= :psi.ui.unavailable.reason/provider-error
             (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason])))
      (is (string? (:psi.ui/diagnostic result))))))

(deftest provider-exception-query-behaviour-test
  ;; Tests that provider exceptions do not drop requested UI attrs.
  (let [ctx (create-ctx {})]
    (ui-capabilities/install-provider! ctx (fn [_] (throw (ex-info "boom" {}))))
    (let [result (session/query-in ctx ui-query)]
      (is (contains? result :psi.ui/type))
      (is (contains? result :psi.ui/available?))
      (is (contains? result :psi.ui/capabilities))
      (is (contains? result :psi.ui/actions))
      (is (contains? result :psi.ui/make-visible-action))
      (is (= :psi.ui.unavailable.reason/provider-error
             (get-in result [:psi.ui/make-visible-action :psi.ui.action/unavailable-reason]))))))
