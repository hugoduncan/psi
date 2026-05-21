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
           (tool-registry/register-tool-in! reg "/ext/a" {:name "MyTool" :label "My Tool"})))))

  (testing "same-extension duplicate registration replaces the prior stored tool"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "First" :format-request (fn [_] "first")})
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "Second" :format-request (fn [_] "second")})
      (is (= "Second"
             (:label (tool-registry/get-tool-in reg "my-tool")))))))

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

  (testing "cross-extension duplicates are allowed and listing is first-registration-wins"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (register-extension-in! reg "/ext/b")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "t" :label "A" :format-request (fn [_] "A")})
      (tool-registry/register-tool-in! reg "/ext/b" {:name "t" :label "B" :format-request (fn [_] "B")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= 1 (count tools)))
        (is (= "A" (:label (first tools)))))))

  (testing "listing preserves first-encounter order by extension registration order"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/b")
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/b" {:name "beta" :label "Beta" :format-request (fn [_] "beta")})
      (tool-registry/register-tool-in! reg "/ext/a" {:name "alpha" :label "Alpha" :format-request (fn [_] "alpha")})
      (is (= ["beta" "alpha"]
             (mapv :name (tool-registry/all-tools-in reg)))))))

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
      (is (nil? (tool-registry/get-tool-in reg "nope")))))

  (testing "cross-extension lookup is first-registration-wins"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (register-extension-in! reg "/ext/b")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "my-tool" :label "From A" :format-request (fn [_] "a")})
      (tool-registry/register-tool-in! reg "/ext/b" {:name "my-tool" :label "From B" :format-request (fn [_] "b")})
      (is (= "From A"
             (:label (tool-registry/get-tool-in reg "my-tool")))))))

;;; Built-in tool registration

(deftest register-built-in-tool-in-test
  (testing "registers tool under root-registry built-in provenance entry"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (= "delegate"
             (get-in @(:state reg)
                     [:root-registries :tools :entries-by-id "built-in:workflow" :value :tools "delegate" :name])))))

  (testing "stored tool carries :source :built-in and :ext-path provenance-id"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (let [stored (get-in @(:state reg)
                           [:root-registries :tools :entries-by-id "built-in:workflow" :value :tools "delegate"])]
        (is (= :built-in (:source stored)))
        (is (= "built-in:workflow" (:ext-path stored))))))

  (testing "does not require prior extension registration"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (is (some? (tool-registry/get-tool-in reg "delegate"))
          "registered built-in tool is retrievable without prior extension registration")))

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
                                                     {:name "delegate" :label "Delegate"})))))

  (testing "repeated registration for same name replaces prior entry"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "First" :format-request (fn [_] "first")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Second" :format-request (fn [_] "second")})
      (is (= "Second"
             (:label (tool-registry/get-tool-in reg "delegate")))))))

(deftest all-built-in-tools-in-test
  (testing "returns all built-in tools across provenance ids"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate-reload" :label "Delegate Reload" :format-request (fn [_] "delegate-reload")})
      (tool-registry/register-built-in-tool-in! reg "built-in:other"
                                                {:name "other-tool" :label "Other" :format-request (fn [_] "other")})
      (let [tools (tool-registry/all-built-in-tools-in reg)]
        (is (= 3 (count tools)))
        (is (= #{"delegate" "delegate-reload" "other-tool"}
               (set (map :name tools)))))))

  (testing "returns empty vector when no built-ins registered"
    (let [reg (create-test-registry)]
      (is (= [] (tool-registry/all-built-in-tools-in reg))))))

(deftest built-in-tool-names-in-test
  (testing "returns set of built-in tool names"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate-reload" :label "Delegate Reload" :format-request (fn [_] "delegate-reload")})
      (is (= #{"delegate" "delegate-reload"}
             (tool-registry/built-in-tool-names-in reg)))))

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

  (testing "all-tools-in preserves built-in provenance registration order before extensions"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (tool-registry/register-tool-in! reg "/ext/a" {:name "ext-tool" :label "Ext" :format-request (fn [_] "ext")})
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (tool-registry/register-built-in-tool-in! reg "built-in:other"
                                                {:name "other-tool" :label "Other" :format-request (fn [_] "other")})
      (is (= ["delegate" "other-tool" "ext-tool"]
             (mapv :name (tool-registry/all-tools-in reg))))))

  (testing "all-tools-in built-in entry carries :source :built-in and :ext-path provenance id"
    (let [reg (create-test-registry)]
      (tool-registry/register-built-in-tool-in! reg "built-in:workflow"
                                                {:name "delegate" :label "Delegate" :format-request (fn [_] "delegate")})
      (let [tools (tool-registry/all-tools-in reg)]
        (is (= :built-in (:source (first tools))))
        (is (= "built-in:workflow" (:ext-path (first tools)))))))

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
