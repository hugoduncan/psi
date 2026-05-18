(ns psi.build-jar-smoke-test
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.build-smoke-support :as support]))

(defn- jar-entries
  [jar-path]
  (let [{:keys [exit out err]} (shell/sh "jar" "tf" jar-path)]
    (when-not (zero? exit)
      (throw (ex-info "jar tf failed"
                      {:jar-path jar-path
                       :exit exit
                       :out out
                       :err err})))
    (->> (str/split-lines out)
         set)))

(defn- build-lib!
  []
  (let [{:keys [exit out err]} (shell/sh "clojure" "-T:build" "lib")]
    (when-not (zero? exit)
      (throw (ex-info "clojure -T:build lib failed"
                      {:exit exit
                       :out out
                       :err err})))
    {:out out :err err}))

(deftest ^:integration build-lib-jar-includes-runtime-packaging-regression-entries
  (testing "the built library jar contains runtime namespaces required by installed psi"
    (support/with-build-lock
      (build-lib!)
      (let [entries (jar-entries "target/psi-unreleased.jar")]
        (is (contains? entries "psi/main.clj"))
        (is (contains? entries "hugoduncan/psi.clj"))
        (is (contains? entries "psi/agent_session/dispatch.clj"))
        (is (contains? entries "psi/state_kernel/dispatch.clj"))
        (is (contains? entries "psi/edit_clj/extension.clj"))
        (is (contains? entries "psi/github/extension.clj"))
        (is (contains? entries "extensions/logprobs.clj"))))))
