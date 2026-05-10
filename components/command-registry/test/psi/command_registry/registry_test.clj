(ns psi.command-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.command-registry.registry :as command-registry]))

(defn- create-test-registry []
  {:state (atom {:extensions {}
                 :registration-order []
                 :flag-values {}
                 :event-bus {}})})

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
             (:description (get-in @(:state reg) [:extensions "/ext/a" :commands "hello"]))))))

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
