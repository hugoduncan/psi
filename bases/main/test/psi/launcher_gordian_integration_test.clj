(ns psi.launcher-gordian-integration-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.launcher :as launcher]))

(def psi-root
  "/Users/duncan/projects/hugoduncan/psi/fix-extensions")

(def gordian-cwd
  "/Users/duncan/projects/hugoduncan/gordian/gordian-master")

(deftest ^:integration launch-plan-expands-gordian-psi-owned-empty-map-manifest-entries
  (testing "real Gordian manifest expands workflow-loader into launcher basis deps"
    (if-not (.exists (io/file gordian-cwd))
      (is true "Skipping local Gordian launcher proof because the external Gordian checkout is absent.")
      (let [plan          (launcher/launch-plan [] gordian-cwd psi-root :installed)
            workflow-dep  (get-in plan [:basis :deps 'psi/workflow-loader])
            mementum-dep  (get-in plan [:basis :deps 'psi/mementum])
            manifest-deps (get-in plan [:manifest-info :expanded-manifest :deps])]
        (is (= {:local/root (str psi-root "/extensions/workflow-loader")}
               workflow-dep))
        (is (= {:local/root (str psi-root "/extensions/mementum")}
               mementum-dep))
        (is (= 'extensions.workflow-loader/init
               (get-in manifest-deps ['psi/workflow-loader :psi/init])))
        (is (= 'extensions.mementum/init
               (get-in manifest-deps ['psi/mementum :psi/init])))
        (is (some #{'psi/workflow-loader} (:defaulted-libs (:manifest-info plan))))
        (is (some #{'psi/workflow-loader} (:inferred-init-libs (:manifest-info plan))))
        (is (= ["clojure" "-Sdeps"] (subvec (:command plan) 0 2)))
        (is (= ["-M" "-m" "psi.main"] (subvec (:command plan) 3 6)))))))
