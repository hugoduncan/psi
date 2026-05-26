(ns psi.command-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.command-registry.registry :as command-registry]))

(defn- create-test-registry []
  {:state (atom {:extensions {}
                 :registration-order []
                 :flag-values {}
                 :event-bus {}
                 :built-in-commands {}})})

(defn- register-extension-in!
  [reg ext-path]
  (swap! (:state reg)
         (fn [s]
           (let [existing    (get-in s [:extensions ext-path])
                 registered? (some #(= ext-path %) (:registration-order s))
                 s'          (if existing
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

(deftest command-registration-test
  (testing "command names tracked"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "/do-thing"})
      (is (contains? (command-registry/command-names-in reg) "/do-thing"))))

  (testing "rejects unregistered extension paths"
    (let [reg (create-test-registry)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unregistered extension path"
           (command-registry/register-command-in! reg "/ext/missing" {:name "hello"})))
      (is (= [] (:registration-order @(:state reg))))
      (is (= {} (:extensions @(:state reg))))))

  (testing "rejects missing or blank command names"
    (doseq [invalid [nil "" "   "]]
      (let [reg (create-test-registry)]
        (register-extension-in! reg "/ext/a")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid command name"
             (command-registry/register-command-in! reg "/ext/a" {:name invalid}))))))

  (testing "same-extension duplicate registration replaces the prior stored command"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "first"})
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "second"})
      (is (= "second"
             (:description (command-registry/get-command-in reg "hello"))))))

  (testing "command identity is exact and no slash normalization is applied"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "plain"})
      (command-registry/register-command-in! reg "/ext/a" {:name "/hello" :description "slash"})
      (is (= #{"hello" "/hello"}
             (command-registry/command-names-in reg))))))

(deftest all-commands-in-test
  (testing "all-commands-in returns commands with extension-path"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "say hi"})
      (let [cmds (command-registry/all-commands-in reg)]
        (is (= 1 (count cmds)))
        (is (= "hello" (:name (first cmds))))
        (is (= "/ext/a" (:extension-path (first cmds)))))))

  (testing "cross-extension duplicates are allowed and listing is first-registration-wins"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (register-extension-in! reg "/ext/b")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "from a"})
      (command-registry/register-command-in! reg "/ext/b" {:name "hello" :description "from b"})
      (let [cmds (command-registry/all-commands-in reg)]
        (is (= 1 (count cmds)))
        (is (= "from a" (:description (first cmds)))))))

  (testing "listing preserves first-encounter order by extension registration order"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/b")
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/b" {:name "beta"})
      (command-registry/register-command-in! reg "/ext/a" {:name "alpha"})
      (is (= ["beta" "alpha"]
             (mapv :name (command-registry/all-commands-in reg)))))))

(deftest get-command-in-test
  (testing "returns command by name"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :handler identity})
      (is (= "hello" (:name (command-registry/get-command-in reg "hello"))))))

  (testing "returns nil for unknown command"
    (let [reg (create-test-registry)]
      (is (nil? (command-registry/get-command-in reg "nope")))))

  (testing "cross-extension lookup is first-registration-wins"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (register-extension-in! reg "/ext/b")
      (command-registry/register-command-in! reg "/ext/a" {:name "hello" :description "from a"})
      (command-registry/register-command-in! reg "/ext/b" {:name "hello" :description "from b"})
      (is (= "from a"
             (:description (command-registry/get-command-in reg "hello")))))))

;;; Built-in command registration

(deftest register-built-in-command-in-test
  (testing "registers command under root-registry built-in provenance entry"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "run workflow"})
      (is (= "delegate"
             (get-in @(:state reg)
                     [:root-registries :commands :entries-by-id "built-in:workflow" :value :commands "delegate" :name])))))

  (testing "stored command carries :source :built-in and :ext-path provenance-id"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "run workflow"})
      (let [stored (get-in @(:state reg)
                           [:root-registries :commands :entries-by-id "built-in:workflow" :value :commands "delegate"])]
        (is (= :built-in (:source stored)))
        (is (= "built-in:workflow" (:ext-path stored))))))

  (testing "does not require prior extension registration"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (is (some? (command-registry/get-command-in reg "delegate"))
          "registered built-in command is retrievable without prior extension registration")))

  (testing "rejects blank or nil command names"
    (doseq [invalid [nil "" "   "]]
      (let [reg (create-test-registry)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid built-in command name"
             (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name invalid}))))))

  (testing "repeated registration for same name replaces prior entry"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "first"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "second"})
      (is (= "second"
             (:description (command-registry/get-command-in reg "delegate")))))))

(deftest all-built-in-commands-in-test
  (testing "returns all built-in commands across provenance ids"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate-reload"})
      (command-registry/register-built-in-command-in! reg "built-in:other" {:name "other-cmd"})
      (let [cmds (command-registry/all-built-in-commands-in reg)]
        (is (= 3 (count cmds)))
        (is (= #{"delegate" "delegate-reload" "other-cmd"}
               (set (map :name cmds)))))))

  (testing "returns empty vector when no built-ins registered"
    (let [reg (create-test-registry)]
      (is (= [] (command-registry/all-built-in-commands-in reg))))))

(deftest built-in-command-names-in-test
  (testing "returns set of built-in command names"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate-reload"})
      (is (= #{"delegate" "delegate-reload"}
             (command-registry/built-in-command-names-in reg)))))

  (testing "returns empty set when no built-ins registered"
    (let [reg (create-test-registry)]
      (is (= #{} (command-registry/built-in-command-names-in reg))))))

(deftest built-in-commands-merged-read-paths-test
  (testing "command-names-in includes built-in names"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "ext-cmd"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (is (contains? (command-registry/command-names-in reg) "delegate"))
      (is (contains? (command-registry/command-names-in reg) "ext-cmd"))))

  (testing "all-commands-in includes built-in commands, built-ins listed first"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "ext-cmd" :description "from ext"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "built-in"})
      (let [cmds (command-registry/all-commands-in reg)]
        (is (= 2 (count cmds)))
        (is (= "delegate" (:name (first cmds))) "built-in listed first")
        (is (= "ext-cmd" (:name (second cmds)))))))

  (testing "all-commands-in preserves built-in provenance registration order before extensions"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "ext-cmd" :description "from ext"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (command-registry/register-built-in-command-in! reg "built-in:other" {:name "other-cmd"})
      (is (= ["delegate" "other-cmd" "ext-cmd"]
             (mapv :name (command-registry/all-commands-in reg))))))

  (testing "get-command-in returns built-in command by name"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "built-in"})
      (is (= "delegate" (:name (command-registry/get-command-in reg "delegate"))))
      (is (= "built-in" (:description (command-registry/get-command-in reg "delegate"))))))

  (testing "get-command-in prefers built-in over extension when names collide"
    (let [reg (create-test-registry)]
      (register-extension-in! reg "/ext/a")
      (command-registry/register-command-in! reg "/ext/a" {:name "delegate" :description "from ext"})
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate" :description "from built-in"})
      (is (= "from built-in"
             (:description (command-registry/get-command-in reg "delegate"))))))

  (testing "get-command-in returns nil for unknown name"
    (let [reg (create-test-registry)]
      (command-registry/register-built-in-command-in! reg "built-in:workflow" {:name "delegate"})
      (is (nil? (command-registry/get-command-in reg "nope"))))))
