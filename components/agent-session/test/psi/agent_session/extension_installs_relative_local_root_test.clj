(ns psi.agent-session.extension-installs-relative-local-root-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.test-support :as test-support]))

(defn- manifest-file
  [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(deftest compute-install-state-absolutizes-relative-project-local-root-test
  (testing "project manifest relative :local/root is resolved against the project cwd"
    (let [cwd  (test-support/temp-cwd)
          home (test-support/temp-cwd)]
      (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                    installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
        (spit (installs/project-manifest-file cwd)
              (pr-str {:deps {'psi/test-ext {:local/root "extensions/test-ext"
                                             :psi/init 'psi.test-ext/init}}}))
        (let [state (installs/compute-install-state cwd)]
          (is (= (str (io/file cwd "extensions/test-ext"))
                 (get-in state [:psi.extensions/effective :entries-by-lib 'psi/test-ext :dep :local/root])))))))

  (testing "user manifest relative :local/root is resolved against ~/.psi/agent"
    (let [cwd  (test-support/temp-cwd)
          home (test-support/temp-cwd)]
      (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                    installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
        (spit (installs/user-manifest-file)
              (pr-str {:deps {'psi/test-ext {:local/root "extensions/test-ext"
                                             :psi/init 'psi.test-ext/init}}}))
        (let [state (installs/compute-install-state cwd)]
          (is (= (str (io/file home ".psi/agent/extensions/test-ext"))
                 (get-in state [:psi.extensions/effective :entries-by-lib 'psi/test-ext :dep :local/root]))))))))
