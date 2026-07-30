(ns extensions.context-manager-rendering-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]))

(deftest render-mapping-content-test
  (testing "renders three fields, confidence dropped"
    (is (= "the resolver → components/pathom/resolver.clj (exact path)"
           (context-manager/render-mapping-content
            [{:surface "the resolver"
              :canonical "components/pathom/resolver.clj"
              :evidence "exact path"
              :confidence "high"}]))))

  (testing "multiple confident mappings render newline-joined, in input order"
    ;; design.md: rendered :content is a surface → canonical (evidence) *list*,
    ;; one line per confident mapping. Exercises the str/join "\n" multi-mapping
    ;; path (only single-mapping was previously covered): all mappings present,
    ;; newline-separated, and in the order parse-mapping-lines produced them.
    (is (= (str "the resolver → components/pathom/resolver.clj (exact path)\n"
                "that task → munera/open/238-x/ (git ls-files)\n"
                "the fn → foo/bar (grep)")
           (context-manager/render-mapping-content
            [{:surface "the resolver"
              :canonical "components/pathom/resolver.clj"
              :evidence "exact path"
              :confidence "high"}
             {:surface "that task"
              :canonical "munera/open/238-x/"
              :evidence "git ls-files"
              :confidence "medium"}
             {:surface "the fn"
              :canonical "foo/bar"
              :evidence "grep"
              :confidence "low"}])))))
