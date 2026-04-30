(ns psi.launcher-gordian-integration-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.launcher :as launcher]))

(defn- temp-dir
  [prefix]
  (let [dir (-> (java.nio.file.Files/createTempDirectory
                 prefix
                 (make-array java.nio.file.attribute.FileAttribute 0))
                .toFile)]
    (.deleteOnExit dir)
    dir))

(defn- write-project-manifest!
  [project-dir manifest-edn]
  (let [psi-dir (doto (io/file project-dir ".psi")
                  (.mkdirs))
        manifest-file (doto (io/file psi-dir "extensions.edn")
                        (.deleteOnExit))]
    (spit manifest-file manifest-edn)
    manifest-file))

(deftest ^:integration launch-plan-expands-psi-owned-empty-map-manifest-entries
  (testing "launcher plan expands psi-owned empty-map manifest entries without absolute-path fixtures"
    (let [launcher-root (-> (io/file ".") .getCanonicalPath)
          project-dir   (temp-dir "psi-launcher-manifest")
          _             (write-project-manifest!
                         project-dir
                         "{:deps {psi/workflow-loader {}\n        psi/mementum {}}}")
          plan          (launcher/launch-plan [] (.getAbsolutePath project-dir) launcher-root :installed)
          workflow-dep  (get-in plan [:basis :deps 'psi/workflow-loader])
          mementum-dep  (get-in plan [:basis :deps 'psi/mementum])
          manifest-deps (get-in plan [:manifest-info :expanded-manifest :deps])]
      (is (= {:local/root (str launcher-root "/extensions/workflow-loader")}
             workflow-dep))
      (is (= {:local/root (str launcher-root "/extensions/mementum")}
             mementum-dep))
      ;; launcher/startup-basis strips :psi/init before forwarding manifest deps
      ;; into the startup basis / manifest-info projection consumed by launch-plan.
      (is (nil? (get-in manifest-deps ['psi/workflow-loader :psi/init])))
      (is (nil? (get-in manifest-deps ['psi/mementum :psi/init])))
      (is (some #{'psi/workflow-loader} (:defaulted-libs (:manifest-info plan))))
      (is (some #{'psi/workflow-loader} (:inferred-init-libs (:manifest-info plan))))
      (is (string? (:basis-edn plan)))
      (is (map? (read-string (:basis-edn plan)))))))
