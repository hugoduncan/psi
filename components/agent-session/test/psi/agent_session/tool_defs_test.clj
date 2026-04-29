(ns psi.agent-session.tool-defs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.tool-defs :as tool-defs]))

(deftest normalize-tool-def-test
  (testing "structured parameters are preserved canonically"
    (let [tool {:name "x"
                :description "desc"
                :parameters {:type "object"
                             :properties {"p" {:type "string"}}}}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= "x" (:name normalized)))
      (is (= "x" (:label normalized)))
      (is (= {:type "object"
              :properties {"p" {:type "string"}}}
             (:parameters normalized)))))

  (testing "runtime execute fns are preserved canonically"
    (let [exec-fn    (fn [_args _opts] {:content "ok" :is-error false})
          normalized (tool-defs/normalize-tool-def {:name "x" :execute exec-fn})]
      (is (identical? exec-fn (:execute normalized)))))

  (testing "EDN string parameters are parsed into canonical data when possible"
    (let [tool {:name "x"
                :label "X"
                :description "desc"
                :parameters "{:type \"object\" :required [\"p\"]}"}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= {:type "object" :properties {} :required ["p"]}
             (:parameters normalized)))))

  (testing "JSON string parameters are parsed into canonical data when possible"
    (let [tool {:name "x"
                :label "X"
                :description "desc"
                :parameters "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}},\"required\":[\"p\"]}"}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= {:type "object"
              :properties {:p {:type "string"}}
              :required ["p"]}
             (:parameters normalized)))))

  (testing "invalid parameter strings degrade to empty object schema"
    (let [tool {:name "x"
                :parameters "not-edn"}
          normalized (tool-defs/normalize-tool-def tool)]
      (is (= {:type "object"
              :properties {}}
             (:parameters normalized)))))

  (testing "object schemas always include properties for provider compatibility"
    (let [normalized (tool-defs/normalize-tool-def {:name "x"
                                                    :parameters {:type "object"
                                                                 :required ["p"]}})]
      (is (= {:type "object"
              :properties {}
              :required ["p"]}
             (:parameters normalized))))))

(deftest agent-core-tool-projection-test
  (let [tool {:name "x"
              :description "desc"
              :parameters {:type "object"
                           :properties {"p" {:type "string"}}}}
        projected (tool-defs/agent-core-tool tool)]
    (is (= "x" (:name projected)))
    (is (= "x" (:label projected)))
    (is (= "desc" (:description projected)))
    (is (= {:type "object"
            :properties {"p" {:type "string"}}}
           (:parameters projected)))))

(deftest provider-tool-projection-test
  (let [tool {:name "x"
              :description "desc"
              :parameters {:type "object"
                           :required ["p"]}}
        projected (tool-defs/provider-tool tool)]
    (is (= {:name "x"
            :description "desc"
            :parameters {:type "object"
                         :properties {}
                         :required ["p"]}}
           projected))))
