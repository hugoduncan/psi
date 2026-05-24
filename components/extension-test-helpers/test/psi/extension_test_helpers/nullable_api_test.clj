(ns psi.extension-test-helpers.nullable-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest prompt-contributions-use-root-registry-backed-authority-test
  ;; Proves the nullable extension API no longer treats any local prompt vector/map as authoritative.
  (testing "list/query surfaces read canonical prompt contributions from root-registry-backed state"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/ext/a"})]
      ((:register-prompt-contribution api) "c1" {:content "A" :priority 10 :enabled true})
      (swap! state assoc-in [:root-state :agent-session :sessions "nullable-session" :data :prompt-contributions]
             [{:id "stale" :ext-path "/ext/stale" :content "stale" :priority 0 :enabled true}])
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