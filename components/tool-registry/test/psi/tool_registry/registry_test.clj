(ns psi.tool-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.tool-registry.registry :as tool-registry]))

(deftest tool-registration-test
  (testing "tool names tracked"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "My Tool"})
      (is (contains? (tool-registry/tool-names-in reg) "my-tool"))))

  (testing "rejects non-canonical tool names"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (tool-registry/register-tool-in! reg "/ext/a" {:name "my_tool" :label "My Tool"}))))
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (tool-registry/register-tool-in! reg "/ext/a" {:name "MyTool" :label "My Tool"}))))))

(deftest all-tools-in-test
  (testing "all-tools-in returns tools with extension-path"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T" :description "d" :parameters {:type "object"}})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "my-tool" (:name (first tools))))
        (is (= "/ext/a" (:extension-path (first tools))))
        (is (= :extension (:source (first tools))))
        (is (= {:type "object" :properties {}} (:parameters (first tools)))))))

  (testing "first registration per name wins"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-extension-in! reg "/ext/b")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "t" :label "A"})
      (tool-registry/register-tool-in! reg "/ext/b" {:name "t" :label "B"})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "A" (:label (first tools))))))))

(deftest get-tool-in-test
  (testing "returns tool by name"
    (let [reg (ext/create-registry)]
      (ext/register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T"})
      (let [tool (tool-registry/get-tool-in reg "my-tool")]
        (is (= "my-tool" (:name tool)))
        (is (= {:type "object" :properties {}} (:parameters tool))))))

  (testing "returns nil for unknown tool"
    (let [reg (ext/create-registry)]
      (is (nil? (tool-registry/get-tool-in reg "nope"))))))
