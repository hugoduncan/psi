(ns psi.agent-session.workflow-loader-delivery-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.workflow-loader.delivery :as delivery]))

(deftest inject-result-into-context-does-not-emit-visible-bridge-filler-test
  (testing "workflow result injection appends only the user marker and assistant result"
    (let [calls (atom [])]
      (delivery/inject-result-into-context!
       {:mutate-session-fn (fn [session-id sym params]
                             (swap! calls conj {:session-id session-id
                                                :sym sym
                                                :params params})
                             {})}
       "origin-session"
       "run-1"
       "result text")
      (is (= [{:session-id "origin-session"
               :sym 'psi.extension/append-message
               :params {:role "user"
                        :content "Workflow run run-1 result:"}}
              {:session-id "origin-session"
               :sym 'psi.extension/append-message
               :params {:role "assistant"
                        :content "result text"}}]
             @calls)))))
