(ns psi.agent-session.reload-error-reporting-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.tools :as tools]))

(deftest reload-error-summary-adds-compile-location-diagnostic-test
  (testing "reload-code error summary includes compile location diagnostics"
    (let [tool   (tools/make-psi-tool (fn [_q] {}) {:cwd (System/getProperty "user.dir")})
          result (with-redefs [psi-tool/canonical-source-path-for-ns (fn [_] (str (System/getProperty "user.dir") "/src/in-worktree.clj"))
                               psi-tool/target-source-path-for-ns (fn [worktree-path ns-name]
                                                                    (str worktree-path "/src/" (str/replace ns-name "." "/") ".clj"))
                               clojure.core/load-file (fn [_]
                                                        (throw (ex-info "boom"
                                                                        {:clojure.error/source "x.clj"
                                                                         :clojure.error/line 12
                                                                         :clojure.error/column 7})))]
                   ((:execute tool) {"action" "reload-code"
                                     "namespaces" ["clojure.string"]}))
          parsed (read-string (:content result))
          diag   (get-in parsed [:psi-tool/code-reload :error :reload-diagnostic])]
      (is (true? (:is-error result)))
      (is (= :compile-error (:issue diag)))
      (is (= "x.clj" (:source diag)))
      (is (= 12 (:line diag)))
      (is (= 7 (:column diag))))))

(deftest reload-error-summary-adds-cyclic-load-hint-test
  (testing "reload-code error summary explains cyclic live reload failures"
    (let [tool   (tools/make-psi-tool (fn [_q] {}) {:cwd (System/getProperty "user.dir")})
          result (with-redefs [psi-tool/canonical-source-path-for-ns (fn [_] (str (System/getProperty "user.dir") "/src/in-worktree.clj"))
                               psi-tool/target-source-path-for-ns (fn [worktree-path ns-name]
                                                                    (str worktree-path "/src/" (str/replace ns-name "." "/") ".clj"))
                               clojure.core/load-file (fn [_]
                                                        (throw (clojure.lang.Compiler$CompilerException.
                                                                "psi_tool.clj"
                                                                366
                                                                3
                                                                (Exception. "Cyclic load dependency: [a]->b->c"))))]
                   ((:execute tool) {"action" "reload-code"
                                     "namespaces" ["clojure.string"]}))
          parsed (read-string (:content result))
          diag   (get-in parsed [:psi-tool/code-reload :error :reload-diagnostic])]
      (is (true? (:is-error result)))
      (is (= :cyclic-load-dependency (:issue diag)))
      (is (string? (:hint diag)))
      (is (re-find #"reload order matters" (:hint diag)))
      (is (re-find #"psi\.agent-session\.psi-tool" (:hint diag))))))
