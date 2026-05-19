(ns psi.build-manifest-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [psi.build-manifest :as sut]))

(deftest build-lib-src-dirs-match-psi-alias-exactly
  (testing "library build source dirs are derived from the authoritative :psi alias"
    (let [deps-data (edn/read-string (slurp "deps.edn"))
          expected (get-in deps-data [:aliases :psi :extra-paths])]
      (is (= expected
             (sut/build-lib-src-dirs))))))

(deftest build-lib-src-dirs-include-state-kernel-and-runtime-extracted-components
  (testing "recent runtime component extractions stay packaged in the library jar"
    (let [src-dirs (set (sut/build-lib-src-dirs))]
      (doseq [path ["components/state-kernel/src"
                    "components/session-state/src"
                    "components/provider-auth/src"
                    "components/turn-statechart/src"
                    "components/turn-runtime/src"
                    "components/tool-runtime/src"
                    "components/session-journal/src"
                    "components/session-persistence/src"
                    "components/shared-config/src"
                    "components/project-nrepl/src"
                    "components/graph/src"
                    "components/history/src"
                    "components/introspection/src"
                    "components/memory/src"
                    "components/recursion/src"
                    "components/ui-state/src"
                    "components/system-bootstrap/src"
                    "components/prompt-assets/src"
                    "components/prompt-registry/src"
                    "components/skill-registry/src"
                    "components/command-registry/src"
                    "components/tool-registry/src"
                    "components/deterministic-operation-registry/src"
                    "components/deterministic-operation-runtime/src"
                    "components/workflow-registry/src"
                    "components/workflow-judge/src"
                    "components/workflow-runtime/src"
                    "components/workflow-loader/src"
                    "components/workflow-step-materialization/src"
                    "components/workflow-step-session-config/src"
                    "extensions/github/src"
                    "extensions/edit-clj/src"
                    "extensions/logprobs/src"
                    "extensions/metrics/src"]]
        (is (contains? src-dirs path) path)))))
