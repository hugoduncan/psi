(ns psi.agent-session.workflow-manifest-activation-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.test-support :as test-support])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-workflow-loader-manifest-activation-test-"
                                      (into-array FileAttribute []))))

(defn- manifest-file
  [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(deftest built-in-workflow-minimal-manifest-entry-no-longer-expands-to-installed-extension
  (let [cwd  (test-support/temp-cwd)
        home (tmp-dir)]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/workflow-loader {}
                            'psi/work-on {}}}))
      (let [install-state (installs/compute-install-state cwd)]
        (is (= false
               (get-in install-state
                       [:psi.extensions/effective :entries-by-lib 'psi/workflow-loader :extension?])))
        (is (= :not-applicable
               (get-in install-state
                       [:psi.extensions/effective :entries-by-lib 'psi/workflow-loader :status])))))))
