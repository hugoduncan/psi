(ns psi.gordian-launcher-manifest-runtime-boundary-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.extensions :as ext]
   [psi.app-runtime :as app-runtime]))

(def gordian-cwd
  "/Users/duncan/projects/hugoduncan/gordian/gordian-master")

(defn- registry-extensions
  [ctx]
  (set (map str (ext/extensions-in (:extension-registry ctx)))))

(deftest ^:integration gordian-built-in-workflow-runtime-boundary-test
  (testing "runtime bootstrap proves built-in workflow wiring when launcher-owned classpath is already present"
    (if-not (.exists (io/file gordian-cwd))
      (is true "Skipping local Gordian runtime-boundary proof because the external Gordian checkout is absent.")
      (let [{:keys [ctx summary]} (app-runtime/bootstrap-runtime-session!
                                   {:provider :anthropic
                                    :id "claude-sonnet-4-6"
                                    :supports-reasoning true}
                                   {:cwd gordian-cwd})]
        (is (contains? (registry-extensions ctx) "built-in:workflow"))
        (is (some #(re-find #"workflow" %)
                  (map str (registry-extensions ctx))))
        (is (pos? (:extension-loaded-count summary)))))))
