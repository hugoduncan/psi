(ns psi.tool-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.tool-registry.registry :as tool-registry]))

(defn- create-test-registry []
  {:state (atom {:extensions {}
                 :registration-order []
                 :flag-values {}
                 :event-bus {}})})

(defn- register-extension-in!
  [reg ext-path]
  (swap! (:state reg)
         (fn [s]
           (let [existing     (get-in s [:extensions ext-path])
                 registered?  (some #(= ext-path %) (:registration-order s))
                 s'           (if existing
                                s
                                (assoc-in s [:extensions ext-path]
                                          {:path           ext-path
                                           :handlers       {}
                                           :tools          {}
                                           :commands       {}
                                           :flags          {}
                                           :shortcuts      {}
                                           :operations     {}
                                           :allowed-events #{}}))]
             (cond-> s'
               (not registered?)
               (update :registration-order conj ext-path)))))
  reg)

(deftest tool-registration-test
  (testing "tool names tracked"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "My Tool" :format-request (fn [_] "my-tool")})
      (is (contains? (tool-registry/tool-names-in reg) "my-tool"))))

  (testing "rejects unregistered extension paths"
    (let [reg (create-test-registry)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unregistered extension path"
           (tool-registry/register-tool-in! reg "/ext/missing" {:name "my-tool" :label "My Tool"})))
      (is (= [] (:registration-order @(:state reg))))
      (is (= {} (:extensions @(:state reg))))))

  (testing "rejects non-canonical tool names"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (tool-registry/register-tool-in! reg "/ext/a" {:name "my_tool" :label "My Tool"}))))
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid tool name"
           (tool-registry/register-tool-in! reg "/ext/a" {:name "MyTool" :label "My Tool"}))))))

(deftest all-tools-in-test
  (testing "all-tools-in returns tools with extension-path"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T" :description "d" :parameters {:type "object"} :format-request (fn [_] "my-tool")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "my-tool" (:name (first tools))))
        (is (= "/ext/a" (:extension-path (first tools))))
        (is (= :extension (:source (first tools))))
        (is (= {:type "object" :properties {}} (:parameters (first tools)))))))

  (testing "first registration per name wins"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (register-extension-in! reg "/ext/b")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "t" :label "A" :format-request (fn [_] "A")})
      (tool-registry/register-tool-in! reg "/ext/b" {:name "t" :label "B" :format-request (fn [_] "B")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "A" (:label (first tools))))))))

(deftest get-tool-in-test
  (testing "returns tool by name"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "T" :format-request (fn [_] "my-tool")})
      (let [tool (tool-registry/get-tool-in reg "my-tool")]
        (is (= "my-tool" (:name tool)))
        (is (= {:type "object" :properties {}} (:parameters tool))))))

  (testing "rejects missing format-request"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing required :format-request"
           (tool-registry/register-tool-in! reg "/ext/a" {:name "missing" :label "Missing"})))))

  (testing "returns nil for unknown tool"
    (let [reg (create-test-registry)]
      (is (nil? (tool-registry/get-tool-in reg "nope"))))))

;;; Built-in tool registration

(deftest register-built-in-tool-in-test
  (testing "registers tool under :built-in-tools keyed by provenance-id"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= "delegate"
             (get-in @(:state reg) [:built-in-tools "built-in:workflow" "delegate" :name])))))

  (testing "stored tool carries :source :built-in"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= :built-in
             (get-in @(:state reg) [:built-in-tools "built-in:workflow" "delegate" :source])))))

  (testing "does not require prior extension registration"
    (let [reg (create-test-registry)]
      (is (empty? (:extensions @(:state reg))))
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= "delegate"
             (get-in @(:state reg) [:built-in-tools "built-in:workflow" "delegate" :name])))))

  (testing "rejects non-canonical tool names"
    (doseq [invalid ["my_tool" "MyTool" "My Tool"]]
      (let [reg (create-test-registry)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid built-in tool name"
             (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                       {:name invalid :label "T" :format-request (fn [_] "t")}))))))

  (testing "rejects missing format-request"
    (let [reg (create-test-registry)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing required :format-request"
           (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                     {:name "delegate" :label "Delegate"}))))))

(deftest all-built-in-tools-in-test
  (testing "returns all built-in tools across provenance ids"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (tool-registry/register-built-in-tool-in! reg "built-in:other"
                                                {:name "other-tool" :label "Other" :format-request (fn [_] "other")})
      (let [tools (tool-registry/all-built-in-tools-in reg)]
        (is (= 2 (count tools)))
        (is (= #{"delegate" "other-tool"}
               (set (map :name tools)))))))

  (testing "returns empty vector when no built-ins registered"
    (let [reg (create-test-registry)]
      (is (= [] (tool-registry/all-built-in-tools-in reg))))))

(deftest built-in-tool-names-in-test
  (testing "returns set of built-in tool names"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= #{"delegate"} (tool-registry/built-in-tool-names-in reg)))))

  (testing "returns empty set when no built-ins registered"
    (let [reg (create-test-registry)]
      (is (= #{} (tool-registry/built-in-tool-names-in reg))))))

(deftest built-in-tools-merged-read-paths-test
  (testing "tool-names-in includes built-in names"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "ext-tool" :label "Ext" :format-request (fn [_] "ext")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (contains? (tool-registry/tool-names-in reg) "delegate"))
      (is (contains? (tool-registry/tool-names-in reg) "ext-tool"))))

  (testing "all-tools-in includes built-in tools, built-ins listed first"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "ext-tool" :label "Ext" :format-request (fn [_] "ext")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 2 (count tools)))
        (is (= "delegate" (:name (first tools))) "built-in listed first")
        (is (= "ext-tool" (:name (second tools)))))))

  (testing "all-tools-in built-in entry carries :source :built-in"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= :built-in (:source (first tools)))))))

  (testing "get-tool-in returns built-in tool by name"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= "delegate" (:name (tool-registry/get-tool-in reg "delegate"))))
      (is (= :built-in (:source (tool-registry/get-tool-in reg "delegate"))))))

  (testing "get-tool-in prefers built-in over extension when names collide"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "delegate" :label "From Ext" :format-request (fn [_] "ext")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "From Built-in" :format-request (fn [_] "built-in")})
      (is (= "From Built-in"
             (:label (tool-registry/get-tool-in reg "delegate"))))))

  (testing "get-tool-in returns nil for unknown name"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (nil? (tool-registry/get-tool-in reg "nope"))))))
