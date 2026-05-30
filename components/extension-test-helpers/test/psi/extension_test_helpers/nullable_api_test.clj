(ns psi.extension-test-helpers.nullable-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest prompt-contributions-use-root-registry-backed-authority-test
  ;; Proves the nullable extension API no longer treats any local prompt vector/map as authoritative.
  (testing "list/query surfaces read canonical prompt contributions from root-registry-backed state"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/ext/a"})]
      ((:register-prompt-contribution api) "c1" {:content "A" :priority 10 :enabled true})
      (is (= [{:id "c1"
               :ext-path "/ext/a"
               :content "A"
               :priority 10
               :enabled true}]
             (mapv #(select-keys % [:id :ext-path :content :priority :enabled])
                   ((:list-prompt-contributions api)))))
      (is (= ["c1"]
             (mapv :id
                   (:psi.extension/prompt-contributions
                    ((:query api) [:psi.extension/prompt-contributions]))))))))

(deftest prompt-contribution-mutations-preserve-root-backed-result-contracts-test
  ;; Proves register/update/unregister continue to expose the caller-facing result maps.
  (testing "prompt contribution mutations preserve count and id contracts"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/ext/a"})
          registered ((:register-prompt-contribution api) "c1" {:content "A" :enabled true})
          updated ((:update-prompt-contribution api) "c1" {:content "B"})
          removed ((:unregister-prompt-contribution api) "c1")]
      (is (= {:psi.extension.prompt-contribution/registered? true
              :psi.extension.prompt-contribution/id "c1"
              :psi.extension.prompt-contribution/count 1}
             registered))
      (is (= {:psi.extension.prompt-contribution/updated? true
              :psi.extension.prompt-contribution/id "c1"
              :psi.extension.prompt-contribution/count 1}
             updated))
      (is (= {:psi.extension.prompt-contribution/removed? true
              :psi.extension.prompt-contribution/id "c1"
              :psi.extension.prompt-contribution/count 0}
             removed)))))

(deftest documented-ui-capability-query-including-diagnostic-test
  (testing "nullable extension API supports the documented UI capability query shape"
    (let [{:keys [api]} (nullable/create-nullable-extension-api {:path "/ext/a"})]
      (is (= {:psi.ui/type :console
              :psi.ui/available? true
              :psi.ui/capabilities []
              :psi.ui/actions []
              :psi.ui/make-visible-action
              {:psi.ui.action/id :psi.ui.action/make-visible
               :psi.ui.action/capability :psi.ui.capability/make-visible
               :psi.ui.action/label "Show Psi UI"
               :psi.ui.action/description "Bring the active Psi UI to the foreground."
               :psi.ui.action/available? false
               :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/unsupported-capability
               :psi.ui.action/unavailable-message "The attached UI does not support making itself visible."}
              :psi.ui/diagnostic nil}
             ((:query api) [:psi.ui/type
                            :psi.ui/available?
                            :psi.ui/capabilities
                            :psi.ui/actions
                            :psi.ui/make-visible-action
                            :psi.ui/diagnostic]))))))
