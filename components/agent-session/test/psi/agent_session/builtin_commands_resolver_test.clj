(ns psi.agent-session.builtin-commands-resolver-test
  "Tests for the built-in slash-command EQL resolver (task 205): the backend is
   the single authoritative source of the built-in command surface, exposed via
   `:psi.agent-session/builtin-command-specs` / `-names` and consumed by UIs the
   same way as `:psi.extension/command-names`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.commands.builtin-specs :as bspec]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest builtin-command-specs-resolver-shape-test
  (let [[ctx session-id] (create-session-context)
        result (session/query-in ctx session-id
                                 [:psi.agent-session/builtin-command-specs
                                  :psi.agent-session/builtin-command-names])
        specs  (:psi.agent-session/builtin-command-specs result)
        names  (:psi.agent-session/builtin-command-names result)]
    (testing "resolver returns a vector of {:name :description} maps"
      (is (vector? specs))
      (is (seq specs))
      (is (every? #(= #{:name :description} (set (keys %))) specs)))
    (testing "names are bare (no leading slash), descriptions are non-blank"
      (is (every? #(not (re-find #"^/" (:name %))) specs))
      (is (every? #(seq (:description %)) specs)))
    (testing "internal fields are never exposed"
      (is (every? #(not (contains? % :usage)) specs))
      (is (every? #(not (contains? % :kinds)) specs))
      (is (every? #(not (contains? % :handler)) specs))
      (is (every? #(not (contains? % :hide-in-help?)) specs)))
    (testing "previously-missing built-ins are present"
      (let [name-set (set (map :name specs))]
        (is (contains? name-set "reload-models"))
        (is (contains? name-set "reload-prompts"))
        (is (contains? name-set "reload-extension-installs"))
        (is (contains? name-set "speed"))
        (is (contains? name-set "effort"))
        (is (contains? name-set "project-repl"))))
    (testing "routed-but-help-absent aliases are present (autocomplete)"
      (let [name-set (set (map :name specs))]
        (is (contains? name-set "?"))
        (is (contains? name-set "exit"))))
    (testing "specs appear in table order (quit < status < help)"
      (let [order (map :name specs)
            idx   (fn [n] (.indexOf ^java.util.List order n))]
        (is (< (idx "quit") (idx "status")))
        (is (< (idx "status") (idx "help")))))
    (testing "the bare-name vector mirrors the spec names in the same order"
      (is (= names (mapv :name specs))))))

(deftest builtin-commands-resolver-exposes-full-spec-table-membership-test
  ;; AC6 end-to-end lock: the resolver is the single surface both UIs consume.
  ;; Every built-in command name (the sole source = the spec-table keys, via
  ;; `builtin-command-names`) appears in the resolver output, so adding a
  ;; spec-table entry flows to TUI + Emacs autocomplete with NO UI-side list edit
  ;; (the UIs build candidates purely from this resolver surface — see
  ;; tui app-input-selector-test and emacs psi-capf-test).
  (let [[ctx session-id] (create-session-context)
        specs    (:psi.agent-session/builtin-command-specs
                  (session/query-in ctx session-id [:psi.agent-session/builtin-command-specs]))
        resolved (set (map :name specs))
        sourced  bspec/builtin-command-names]
    (is (= sourced resolved)
        "resolver bare-name set equals the single-sourced built-in name set")))

(deftest builtin-commands-resolver-graph-discovery-test
  (let [[ctx session-id] (create-session-context)]
    (testing "the resolver is registered in the graph resolver index"
      (let [syms (:psi.graph/resolver-syms
                  (session/query-in ctx session-id [:psi.graph/resolver-syms]))]
        (is (contains? syms 'psi.agent-session.resolvers.extensions/builtin-commands-resolver))))
    (testing "the built-in command attrs are resolvable for a session"
      (let [result (session/query-in ctx session-id
                                     [:psi.agent-session/builtin-command-specs
                                      :psi.agent-session/builtin-command-names])]
        (is (seq (:psi.agent-session/builtin-command-specs result)))
        (is (seq (:psi.agent-session/builtin-command-names result)))))))
