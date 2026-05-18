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
      (is (contains? src-dirs "components/state-kernel/src"))
      (is (contains? src-dirs "components/provider-auth/src"))
      (is (contains? src-dirs "components/turn-statechart/src"))
      (is (contains? src-dirs "components/turn-runtime/src"))
      (is (contains? src-dirs "components/tool-runtime/src"))
      (is (contains? src-dirs "components/session-journal/src"))
      (is (contains? src-dirs "components/session-persistence/src"))
      (is (contains? src-dirs "components/shared-config/src"))
      (is (contains? src-dirs "components/project-nrepl/src"))
      (is (contains? src-dirs "components/workflow-loader/src"))
      (is (contains? src-dirs "components/workflow-step-materialization/src"))
      (is (contains? src-dirs "components/workflow-step-session-config/src"))
      (is (contains? src-dirs "extensions/github/src"))
      (is (contains? src-dirs "extensions/edit-clj/src"))
      (is (contains? src-dirs "extensions/logprobs/src"))
      (is (contains? src-dirs "extensions/metrics/src")))))
