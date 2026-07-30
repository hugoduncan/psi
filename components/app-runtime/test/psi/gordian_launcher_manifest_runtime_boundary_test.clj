(ns ^:integration psi.gordian-launcher-manifest-runtime-boundary-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.command-registry.registry :as command-registry]
   [psi.tool-registry.registry :as tool-registry]
   [psi.app-runtime.test-support :as app-test-support]))

(def gordian-cwd
  "/Users/duncan/projects/hugoduncan/gordian/gordian-master")

(deftest ^:integration gordian-built-in-workflow-runtime-boundary-test
  (testing "runtime bootstrap proves built-in workflow wiring when launcher-owned classpath is already present"
    (if-not (.exists (io/file gordian-cwd))
      (is true "Skipping local Gordian runtime-boundary proof because the external Gordian checkout is absent.")
      (let [{:keys [ctx summary]} (app-test-support/bootstrap-fresh-session!
                                   {:provider :anthropic
                                    :id "claude-sonnet-4-6"
                                    :supports-reasoning true}
                                   {:cwd gordian-cwd})
            reg (:extension-registry ctx)]
        ;; Built-in workflow is no longer seeded as an extension identity.
        ;; Prove built-in availability through command and tool lookup instead.
        (is (some? (command-registry/get-command-in reg "delegate"))
            "built-in delegate command is available through built-in-aware command lookup")
        (is (some? (command-registry/get-command-in reg "delegate-reload"))
            "built-in delegate-reload command is available through built-in-aware command lookup")
        (is (some? (tool-registry/get-tool-in reg "delegate"))
            "built-in delegate tool is available through built-in-aware tool lookup")
        (is (pos? (:extension-loaded-count summary)))))))
