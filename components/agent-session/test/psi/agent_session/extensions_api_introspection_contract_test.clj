(ns psi.agent-session.extensions-api-introspection-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]))

(deftest extension-api-registrations-keep-extension-introspection-coherent-test
  (testing "extension API registrations keep extension introspection coherent"
    (let [reg    (ext/create-registry)
          _      (ext/register-extension-in! reg "/ext/test")
          api    (ext/create-extension-api reg "/ext/test"
                                           {:register-deterministic-operation-fn
                                            (fn [ext-path op]
                                              (ext/register-operation-in! reg ext-path op)
                                              {:id (:id op)})})
          _      ((:on api) "tool_call" (fn [_] nil))
          _      ((:register-tool api) {:name "ext-tool"
                                        :label "ET"
                                        :description "test"
                                        :format-request (fn [_] "ext-tool")})
          _      ((:register-command api) "greet" {:handler (fn [_] nil)
                                                   :description "Say hi"})
          _      ((:register-flag api) "debug" {:type :boolean :default false})
          _      ((:register-operation api) {:id "github/search-issues-by-label"
                                             :handler (fn [_] {:status :ok :data {}})})
          detail (ext/extension-detail-in reg "/ext/test")]
      (is (= #{"tool_call"} (:handler-names detail)))
      (is (= 1 (:handler-count detail)))
      (is (= #{"ext-tool"} (:tool-names detail)))
      (is (= 1 (:tool-count detail)))
      (is (= #{"greet"} (:command-names detail)))
      (is (= 1 (:command-count detail)))
      (is (= #{"debug"} (:flag-names detail)))
      (is (= 1 (:flag-count detail)))
      (is (= #{"github/search-issues-by-label"} (:operation-ids detail)))
      (is (= 1 (:operation-count detail))))))
